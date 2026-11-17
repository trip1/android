package com.zaneschepke.wireguardautotunnel.core.orchestration

import com.dokar.sonner.ToastType
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.backend.state.ActiveTunnel
import com.wgtunnel.backend.state.BackendStatus
import com.wgtunnel.parser.AmneziaConfigNormalizer
import com.wgtunnel.parser.Config
import com.wgtunnel.parser.ConfigReconciler
import com.zaneschepke.networkmonitor.NetworkMonitor
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.event.TunnelErrorEvent
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import com.zaneschepke.wireguardautotunnel.data.repository.RoomDnsSettingsRepository
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelActionSource
import com.zaneschepke.wireguardautotunnel.domain.enums.TunnelMode
import com.zaneschepke.wireguardautotunnel.domain.events.TunnelActionEvent
import com.zaneschepke.wireguardautotunnel.domain.model.DnsSettings
import com.zaneschepke.wireguardautotunnel.domain.model.GeneralSettings
import com.zaneschepke.wireguardautotunnel.domain.model.LockdownSettings
import com.zaneschepke.wireguardautotunnel.domain.model.MonitoringSettings
import com.zaneschepke.wireguardautotunnel.domain.model.ProxySettings
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.AppStateRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.GlobalEffectRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.LockdownSettingsRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.MonitoringSettingsRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.ProxySettingsRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.service.NebulaVpnService
import com.zaneschepke.wireguardautotunnel.service.ServiceManager
import com.zaneschepke.wireguardautotunnel.util.StringValue
import com.zaneschepke.wireguardautotunnel.util.extensions.toTunnelDnsConfigOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.NonCancellable
import kotlin.time.Duration.Companion.minutes
import com.zaneschepke.wireguardautotunnel.util.network.MeshEnroll
import com.zaneschepke.wireguardautotunnel.util.network.withSplitOf
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import com.zaneschepke.wireguardautotunnel.util.network.Nebula
import com.wgtunnel.parser.ActiveConfig
import chat.warpsignal.nebmobile.Nebmobile

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelCoordinator(
    private val tunnelProvider: TunnelProvider,
    private val serviceManager: ServiceManager,
    private val bootstrapCoordinator: AppBoostrapCoordinator,
    settingsRepository: GeneralSettingRepository,
    private val tunnelRepository: TunnelRepository,
    dnsSettingsRepository: RoomDnsSettingsRepository,
    monitoringSettingsRepository: MonitoringSettingsRepository,
    globalEffectRepository: GlobalEffectRepository,
    proxyRepository: ProxySettingsRepository,
    lockdownModeRepository: LockdownSettingsRepository,
    private val appStateRepository: AppStateRepository,
    networkMonitor: NetworkMonitor,
    scope: CoroutineScope,
) {

    // Invoked synchronously inside the user action's lock, so an auto tunnel action queued behind
    // it already sees the override when it checks its guard
    @Volatile var userOverrideListener: (() -> Unit)? = null

    private fun markUserOverride() {
        userOverrideListener?.invoke()
    }

    data class RuntimeSettingsSnapshot(
        val general: GeneralSettings,
        val dns: DnsSettings,
        val monitoring: MonitoringSettings,
        val proxy: ProxySettings,
        val lockdown: LockdownSettings,
    )

    private val runtimeSettingsSnapshot =
        combine(
            settingsRepository.flow,
            dnsSettingsRepository.flow,
            monitoringSettingsRepository.flow,
            proxyRepository.flow,
            lockdownModeRepository.flow,
        ) { general, dns, monitoring, proxy, lockdown ->
            RuntimeSettingsSnapshot(
                general = general,
                dns = dns,
                monitoring = monitoring,
                proxy = proxy,
                lockdown = lockdown,
            )
        }

    private val _actions = MutableSharedFlow<TunnelActionEvent>(extraBufferCapacity = 8)
    val actions = _actions.asSharedFlow()

    private val runtimeSettingsSnapshotState =
        runtimeSettingsSnapshot.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /** Nebula Status() as an ActiveConfig, polled at the monitoring interval while a Nebula tunnel runs. */
    private val nebulaStats: Flow<ActiveConfig?> =
        combine(NebulaVpnService.active, runtimeSettingsSnapshotState) { neb, snap -> neb to snap?.monitoring }
            .flatMapLatest { (neb, mon) ->
                flow {
                    emit(null)
                    if (neb == null || mon?.tunnelStatisticsEnabled != true) return@flow
                    val ip = tunnelRepository.getById(neb.first)?.takeIf { it.isNebula }
                        ?.let { Nebula.parse(it.nebulaProfile).addresses } ?: return@flow
                    while (true) {
                        val st = withContext(Nebula.dispatcher) { runCatching { Nebmobile.status() }.getOrNull() }
                        emit(st?.let { runCatching { Nebula.activeConfig(it, ip, System.currentTimeMillis() / 1000) }.getOrNull() })
                        delay(mon.tunnelStatisticsPollInterval.coerceAtLeast(1).seconds)
                    }
                }.flowOn(Dispatchers.IO)
            }

    /** WireGuard backend status plus the running Nebula tunnel, its hosts as peers. */
    val backendStatus: StateFlow<BackendStatus> =
        combine(tunnelProvider.backendStatus, NebulaVpnService.active, nebulaStats) { s, neb, stats ->
                if (neb == null) s
                else s.copy(activeTunnels = s.activeTunnels + (neb.first to
                    ActiveTunnel(transportState = Tunnel.State.Up.Healthy, uptime = neb.second, activeConfig = stats,
                        lastStatsAtMs = if (stats != null) System.currentTimeMillis() else 0L)))
            }
            .stateIn(scope, SharingStarted.Eagerly, tunnelProvider.backendStatus.value)

    /** Running tunnel ids without [backendStatus]'s stateIn lag, for decisions under [tunnelMutex]. */
    private fun activeIds(): Set<Int> =
        tunnelProvider.backendStatus.value.activeTunnels.keys + listOfNotNull(NebulaVpnService.active.value?.first)

    private val ladder =
        EndpointLadder(scope, backendStatus) {
            networkMonitor.connectivityStateFlow.value?.activeNetwork?.network
        }

    private val directPaths = DirectPaths(scope, backendStatus, tunnelProvider)

    /**
     * Nebula auto-renew (Phase 34): the running Nebula tunnel calls the hub's renew route on start and every 6 h (half
     * the TTL when shorter). The hub decides: before half-life it is a refresh (rules, exits, lighthouses), past it a new
     * cert. A failure retries in 15 min. Expired certs can't connect, so they still need a QR renew.
     */
    private fun nebulaRenewLoop(scope: CoroutineScope) = scope.launch {
        NebulaVpnService.active.map { it?.first }.distinctUntilChanged().collectLatest { id ->
            id ?: return@collectLatest
            delay(10.seconds) // let the first handshake to the hub finish
            while (true) {
                val t = tunnelRepository.getById(id)?.takeIf { it.isNebula } ?: return@collectLatest
                val wait = runCatching { renewNebula(t) }
                    .onFailure { Timber.w(it, "Nebula renew failed") }
                    .map { Nebula.renewDueAt(System.currentTimeMillis(), it) - System.currentTimeMillis() }
                    .getOrDefault(15.minutes.inWholeMilliseconds)
                delay(wait)
            }
        }
    }

    /**
     * Saves the renewed profile in place (only when it changed) and reloads Nebula when the VPN interface is
     * unchanged, else restarts the tunnel. Returns the cert's expiry (epoch ms) or null.
     */
    private suspend fun renewNebula(t: TunnelConfig): Long? {
        val old = Nebula.parse(t.nebulaProfile)
        val key = t.getConfig().`interface`.privateKey
        val (json, expires) = withContext(Dispatchers.IO) { MeshEnroll.renewNebula(old.dns, Config.generatePublicKeyFromPrivateKey(key)) }
        val expiresMs = expires?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
        val fresh = Nebula.parse(json).withChoicesOf(old)
        val quick = Config.parseQuickString(
            MeshEnroll.nebulaPlaceholder(key, fresh.ip, MeshEnroll.header(t.quickConfig, "Name"), expires)).withSplitOf(t.getConfig()).asQuickString()
        if (fresh == old && quick == t.quickConfig) return expiresMs
        val updated = t.copy(
            quickConfig = quick,
            nebulaProfile = Nebula.encode(fresh),
            // like a QR renew: a newly granted exit turns Full Warp on, a dropped one off
            isExitNode = if (fresh.hasExit != old.hasExit) fresh.hasExit else t.isExitNode,
        )
        tunnelRepository.save(updated)
        Timber.i("Nebula ${t.name} profile renewed, expires $expires")
        if (NebulaVpnService.active.value?.first != t.id) return expiresMs
        if (updated.nebulaRunProfile().sameVpn(t.nebulaRunProfile())) {
            val yaml = updated.nebulaYaml()
            val reloaded = withContext(Nebula.dispatcher) { runCatching { Nebmobile.reload(yaml) } }
            if (reloaded.isSuccess) return expiresMs.also { Timber.i("Nebula ${t.name} reloaded in place") }
            Timber.i(reloaded.exceptionOrNull(), "Nebula reload refused, restarting")
        }
        // the stop clears NebulaVpnService.active, which cancels this renew loop (collectLatest) before the start
        withContext(NonCancellable) {
            stopTunnel(t.id)
            startTunnel(updated)
        }
        return expiresMs
    }

    init {
        nebulaRenewLoop(scope)
        // new network: whatever blocked UDP may be gone, so fallen-back tunnels climb back to entry 0
        scope.launch {
            networkMonitor.connectivityStateFlow
                .filterNotNull()
                .map { it.activeNetwork.key() }
                .distinctUntilChanged()
                // stepping down with no network at all would only climb back down the ladder
                .filter { it != "none" }
                .drop(1)
                .collect {
                    serviceManager.rebindNebula()
                    ladder.steppedDown().forEach { restartOnLadder(it, 0, TunnelActionSource.USER) }
                }
        }
        scope.launch {
            combine(
                    runtimeSettingsSnapshot,
                    tunnelRepository.userTunnelsFlow,
                    backendStatus,
                ) { snapshot, tunnels, status ->
                    val activeIds = status.activeTunnels.keys
                    LiveTunnelFeatureKey(
                        statsEnabled = snapshot.monitoring.tunnelStatisticsEnabled,
                        statsInterval = snapshot.monitoring.tunnelStatisticsPollInterval,
                        seamlessRecovery = snapshot.general.seamlessRecoveryEnabled,
                        bounceDelaySec = snapshot.general.seamlessRecoveryBounceDelaySec,
                        perTunnel =
                            tunnels.associate { tun ->
                                tun.id to
                                    Triple(
                                        tun.isDDNSTunnel,
                                        tun.isIpv6Preferred,
                                        tun.ipv6RestoreEnabled,
                                    )
                            },
                    ) to
                        LiveTunnelFeaturePayload(
                            general = snapshot.general,
                            monitoring = snapshot.monitoring,
                            tunnels = tunnels.filter { it.id in activeIds && !it.isNebula },
                        )
                }
                .distinctUntilChangedBy { it.first }
                .drop(1)
                .collect { (_, payload) ->
                    payload.tunnels.forEach { config ->
                        tunnelProvider
                            .updateTunnel(
                                config.toBackendTunnel(
                                    payload.monitoring,
                                    payload.general.tunnelScriptingEnabled,
                                    payload.general,
                                )
                            )
                            .onFailure {
                                Timber.e(
                                    it,
                                    "Failed to apply live tunnel features to tunnel ${config.id}",
                                )
                            }
                            .onSuccess {
                                globalEffectRepository.post(
                                    GlobalSideEffect.Snackbar(
                                        message =
                                            StringValue.StringResource(
                                                R.string.active_tunnel_updated
                                            ),
                                        ToastType.Success,
                                    )
                                )
                            }
                    }
                }
        }
    }

    private data class LiveTunnelFeatureKey(
        val statsEnabled: Boolean,
        val statsInterval: Int,
        val seamlessRecovery: Boolean,
        val bounceDelaySec: Int,
        val perTunnel: Map<Int, Triple<Boolean, Boolean, Boolean>>,
    )

    private data class LiveTunnelFeaturePayload(
        val general: GeneralSettings,
        val monitoring: MonitoringSettings,
        val tunnels: List<TunnelConfig>,
    )

    private suspend fun getSnapshot(): RuntimeSettingsSnapshot {
        return runtimeSettingsSnapshotState.filterNotNull().first()
    }

    private var lastActiveTunnels: List<Int> = emptyList()
    private val tunnelMutex = Mutex()
    private val _errors = MutableSharedFlow<TunnelErrorEvent>()
    val errors = _errors.asSharedFlow()

    suspend fun awaitReady() {
        bootstrapCoordinator.isReady.first { it }
    }

    suspend fun startTunnel(
        config: TunnelConfig,
        source: TunnelActionSource = TunnelActionSource.USER,
    ) = tunnelMutex.withLock {
        // wait for app to be bootstrapped
        bootstrapCoordinator.isReady.first { it }

        if (source == TunnelActionSource.USER) {
            markUserOverride()
        }

        startExclusive(config, source)
    }

    // enforce single tunnel, for now. We do not clear the last active here as start success
    // replaces it
    private suspend fun startExclusive(config: TunnelConfig, source: TunnelActionSource) {
        if (activeIds().isNotEmpty()) {
            stopActiveTunnelsInternal(source, persistLastActive = false)
        }

        startTunnelInternal(config, source)
    }

    suspend fun stopTunnel(id: Int, source: TunnelActionSource = TunnelActionSource.USER) =
        tunnelMutex.withLock {
            if (source == TunnelActionSource.USER) {
                markUserOverride()
            }
            stopTunnelInternal(id, source)
        }

    /** Start/stop without taking [tunnelMutex], only reachable through [exclusively]. */
    inner class Exclusive internal constructor() {
        suspend fun start(config: TunnelConfig, source: TunnelActionSource) =
            startExclusive(config, source)

        suspend fun stop(id: Int, source: TunnelActionSource) = stopTunnelInternal(id, source)
    }

    private val exclusive = Exclusive()

    /**
     * Runs [block] holding the lock every start/stop holds, so a caller can decide and act without
     * anything changing in between.
     */
    suspend fun <T> exclusively(block: suspend (Exclusive) -> T): T = tunnelMutex.withLock {
        block(exclusive)
    }

    suspend fun stopActiveTunnels(source: TunnelActionSource = TunnelActionSource.USER) =
        tunnelMutex.withLock {
            if (source == TunnelActionSource.USER) {
                markUserOverride()
            }
            stopActiveTunnelsInternal(source, persistLastActive = true)
        }

    private suspend fun restartOnLadder(id: Int, step: Int, source: TunnelActionSource) =
        tunnelMutex.withLock {
            if (!backendStatus.value.activeTunnels.containsKey(id)) return@withLock
            val config = tunnelRepository.getById(id) ?: return@withLock
            stopTunnelInternal(id, source)
            startTunnelInternal(config, source, step)
        }

    private suspend fun startTunnelInternal(
        tunnelConfig: TunnelConfig,
        source: TunnelActionSource,
        ladderStep: Int = 0,
    ) {
        if (tunnelConfig.isNebula) return startNebula(tunnelConfig, source)
        // one VPN at a time
        NebulaVpnService.active.value?.let { stopTunnelInternal(it.first, source) }

        val snapshot = getSnapshot()
        val settings = snapshot.general
        val dnsSettings = snapshot.dns
        val proxySettings = snapshot.proxy
        val monitoringSettings = snapshot.monitoring
        val lockdownSettings = snapshot.lockdown

        var config = tunnelConfig.getRunConfig(ladder.prepare(tunnelConfig, ladderStep))

        // makes sure Amnezia configs are 2.0 compatible
        config = AmneziaConfigNormalizer.ensureAmneziaCompatibility(config)

        val policy =
            ConfigReconciler.ConfigReconcilePolicy(
                dnsSettings.isGlobalTunnelConfigDnsEnabled,
                settings.isGlobalSplitTunnelEnabled,
                settings.isGlobalAmneziaEnabled,
            )

        val globalConfig =
            if (policy.hasAnyOverrides) {
                tunnelRepository.globalTunnelFlow.firstOrNull()?.getConfig()
            } else {
                null
            }

        val runConfig =
            if (policy.hasAnyOverrides) {
                ConfigReconciler.reconcileConfig(config, globalConfig, policy)
            } else config

        val tunnelDnsConfig = dnsSettings.toTunnelDnsConfigOrNull(runConfig)
        val outerConfig = resolveOuterConfig(tunnelConfig, globalConfig, policy)

        val backendMode =
            when (settings.tunnelMode) {
                TunnelMode.VPN -> {

                    if (!serviceManager.hasVpnPermission()) {
                        ladder.stop(tunnelConfig.id)
                        _errors.emit(TunnelErrorEvent.VpnPermissionDenied(tunnelConfig.id))
                        return
                    }

                    BackendMode.Vpn(runConfig)
                }

                TunnelMode.PROXY -> {
                    BackendMode.Proxy.Standard(
                        config = runConfig,
                        proxyConfig = proxySettings.toProxyConfig(),
                    )
                }

                TunnelMode.LOCK_DOWN -> {
                    BackendMode.Proxy.KillSwitchPrimary(
                        runConfig,
                        lockdownSettings.toKillSwitchConfig(),
                    )
                }
            }

        tunnelProvider
            .startTunnel(
                tunnel =
                    tunnelConfig.toBackendTunnel(
                        monitoringSettings,
                        settings.tunnelScriptingEnabled,
                        settings,
                    ),
                mode = backendMode,
                tunnelDnsConfig = tunnelDnsConfig,
                outerConfig = outerConfig,
            )
            .onSuccess {
                _actions.emit(
                    TunnelActionEvent.Started(tunnelId = tunnelConfig.id, source = source)
                )
                replaceLastActiveTunnelIds(
                    tunnelProvider.backendStatus.value.activeTunnels.keys + tunnelConfig.id
                )
                // the handshake check reads live stats; without them the ladder stays on its entry
                if (monitoringSettings.tunnelStatisticsEnabled) {
                    ladder.watch(tunnelConfig, monitoringSettings.tunnelStatisticsPollInterval) {
                        restartOnLadder(tunnelConfig.id, it, source)
                    }
                }
                directPaths.start(tunnelConfig, monitoringSettings.tunnelStatisticsEnabled)
            }
            .onFailure {
                ladder.stop(tunnelConfig.id)
                Timber.e(it)
                _errors.emit(TunnelErrorEvent.from(it, tunnelConfig.id))
            }
    }

    // ponytail: Nebula ignores the tunnel mode (always VpnService), lockdown, the ladder and live stats
    private suspend fun startNebula(tunnelConfig: TunnelConfig, source: TunnelActionSource) {
        if (!serviceManager.hasVpnPermission()) {
            _errors.emit(TunnelErrorEvent.VpnPermissionDenied(tunnelConfig.id))
            return
        }
        // one VPN at a time
        if (tunnelProvider.backendStatus.value.activeTunnels.isNotEmpty()) {
            stopActiveTunnelsInternal(source, persistLastActive = false)
        }
        // split tunneling: the same per-app lists as WireGuard (the tunnel's own, or the global tunnel's when enabled)
        val splitSource = if (getSnapshot().general.isGlobalSplitTunnelEnabled) tunnelRepository.globalTunnelFlow.firstOrNull() ?: tunnelConfig else tunnelConfig
        val iface = runCatching { splitSource.getConfig().`interface` }.getOrNull()
        runCatching { tunnelConfig.nebulaYaml() }
            .mapCatching {
                serviceManager.startNebula(tunnelConfig.id, tunnelConfig.name, Nebula.encode(tunnelConfig.nebulaRunProfile()), it,
                    iface?.allIncludedApps.orEmpty(), iface?.allExcludedApps.orEmpty()).getOrThrow()
            }
            .onSuccess {
                _actions.emit(TunnelActionEvent.Started(tunnelId = tunnelConfig.id, source = source))
                replaceLastActiveTunnelIds(setOf(tunnelConfig.id))
                directPaths.start(tunnelConfig, liveStats = false)
            }
            .onFailure {
                Timber.e(it, "Nebula start failed")
                _errors.emit(TunnelErrorEvent.InternalFailure(tunnelConfig.id, it.message ?: "Nebula start failed"))
            }
    }

    private suspend fun resolveOuterConfig(
        exit: TunnelConfig,
        globalConfig: Config?,
        policy: ConfigReconciler.ConfigReconcilePolicy,
    ): Config? {
        val entryId = exit.entryTunnelId ?: return null
        if (entryId == exit.id) {
            Timber.w("Ignoring self-referential entry tunnel on ${exit.name}")
            return null
        }
        val entry = tunnelRepository.getById(entryId)
        if (entry == null) {
            Timber.w("Entry tunnel id=$entryId missing for ${exit.name}; starting one-hop")
            return null
        }
        return runCatching {
            var outer = AmneziaConfigNormalizer.ensureAmneziaCompatibility(entry.getConfig())
            if (policy.amnezia) {
                outer =
                    ConfigReconciler.reconcileConfig(
                        outer,
                        globalConfig,
                        ConfigReconciler.ConfigReconcilePolicy(
                            dns = false,
                            splitTunnel = false,
                            amnezia = true,
                        ),
                    )
            }
            outer
        }
            .onFailure {
                Timber.e(it, "Failed to load entry tunnel ${entry.name} for ${exit.name}")
            }
            .getOrNull()
    }

    /** Persists Full Warp and restarts the tunnel if it's running so the new routes apply. */
    suspend fun setExitNode(tunnel: TunnelConfig, to: Boolean) {
        val updated = tunnel.copy(isExitNode = to)
        tunnelRepository.save(updated)
        if (backendStatus.value.activeTunnels.containsKey(tunnel.id)) {
            stopTunnel(tunnel.id)
            startTunnel(updated)
        }
    }

    suspend fun startDefault() {
        tunnelRepository.getDefaultTunnel()?.let { tunnel -> startTunnel(tunnel) }
    }

    suspend fun restartActiveTunnels() = tunnelMutex.withLock {
        val configs = activeIds().mapNotNull { tunnelRepository.getById(it) }
        if (configs.isEmpty()) return@withLock
        stopActiveTunnelsInternal(TunnelActionSource.USER, persistLastActive = true)
        configs.forEach { startTunnelInternal(it, TunnelActionSource.USER) }
    }

    /**
     * Rebuild the kill-switch TUN in place. HEV is rebound to the new fd by VpnService using the
     * still-running SOCKS listener
     */
    suspend fun applyLockdownSettings(settings: LockdownSettings) = tunnelMutex.withLock {
        tunnelProvider
            .setLockDown(settings)
            .onFailure { Timber.e(it, "Failed to apply lockdown/kill-switch settings") }
            .getOrThrow()
    }

    suspend fun toggleTunnel(
        tunnelConfig: TunnelConfig,
        source: TunnelActionSource = TunnelActionSource.USER,
    ) = tunnelMutex.withLock {
        if (source == TunnelActionSource.USER) {
            markUserOverride()
        }

        val isActive = tunnelConfig.id in activeIds()
        if (isActive) {
            stopTunnelInternal(tunnelConfig.id, source)
            return@withLock
        }
        startTunnelInternal(tunnelConfig, source)
    }

    // for quick settings tile
    suspend fun toggleActiveTunnels(source: TunnelActionSource = TunnelActionSource.USER) =
        tunnelMutex.withLock {
            if (source == TunnelActionSource.USER) {
                markUserOverride()
            }

            val active = activeIds()
            if (active.isNotEmpty()) {
                lastActiveTunnels = active.toList()

                active.forEach { id ->
                    _actions.emit(TunnelActionEvent.Stopped(tunnelId = id, source = source))
                }

                stopActiveTunnelsInternal(source, persistLastActive = true)
                return@withLock
            }

            val tunnelsToStart =
                when {
                    lastActiveTunnels.isNotEmpty() -> {
                        lastActiveTunnels.mapNotNull { tunnelRepository.getById(it) }
                    }

                    else -> {
                        tunnelRepository.getDefaultTunnel()?.let(::listOf) ?: emptyList()
                    }
                }

            tunnelsToStart.forEach { startTunnelInternal(it, source) }
        }

    private suspend fun stopTunnelInternal(id: Int, source: TunnelActionSource) {
        if (id == NebulaVpnService.active.value?.first) {
            directPaths.stop(id)
            serviceManager.stopNebula()
            _actions.emit(TunnelActionEvent.Stopped(tunnelId = id, source = source))
            replaceLastActiveTunnelIds(tunnelProvider.backendStatus.value.activeTunnels.keys)
            return
        }
        ladder.stop(id)
        directPaths.stop(id)
        tunnelProvider
            .stopTunnel(id)
            .onSuccess {
                _actions.emit(TunnelActionEvent.Stopped(tunnelId = id, source = source))
                replaceLastActiveTunnelIds(
                    tunnelProvider.backendStatus.value.activeTunnels.keys - id
                )
            }
            .onFailure { _errors.emit(TunnelErrorEvent.from(it, id)) }
    }

    private suspend fun stopActiveTunnelsInternal(
        source: TunnelActionSource = TunnelActionSource.USER,
        persistLastActive: Boolean,
    ) {
        val active = tunnelProvider.backendStatus.value.activeTunnels

        active.keys.forEach { id ->
            ladder.stop(id)
            directPaths.stop(id)
            _actions.emit(TunnelActionEvent.Stopped(tunnelId = id, source = source))
        }
        NebulaVpnService.active.value?.let {
            directPaths.stop(it.first)
            serviceManager.stopNebula()
            _actions.emit(TunnelActionEvent.Stopped(tunnelId = it.first, source = source))
        }

        tunnelProvider.stopActiveTunnels()
        if (persistLastActive) {
            replaceLastActiveTunnelIds(emptySet())
        }
    }

    private suspend fun replaceLastActiveTunnelIds(ids: Set<Int>) {
        if (ids.isEmpty()) {
            appStateRepository.clearLastActiveTunnelIds()
        } else {
            appStateRepository.setLastActiveTunnelIds(ids.toList())
        }
    }
}
