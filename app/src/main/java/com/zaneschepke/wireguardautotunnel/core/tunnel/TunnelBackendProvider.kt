package com.zaneschepke.wireguardautotunnel.core.tunnel

import com.wgtunnel.backend.Backend
import com.wgtunnel.backend.Tunnel
import com.wgtunnel.backend.TunnelBackend
import com.wgtunnel.backend.model.BackendMode
import com.wgtunnel.backend.model.dns.TunnelDnsConfig
import com.wgtunnel.backend.state.BackendStatus
import com.wgtunnel.parser.Config
import com.wgtunnel.parser.PeerSection
import com.zaneschepke.wireguardautotunnel.domain.model.LockdownSettings
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
class TunnelBackendProvider(
    private val backend: Backend,
    applicationScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : TunnelProvider {

    override val backendStatus: StateFlow<BackendStatus> =
        backend.status.stateIn(
            scope = applicationScope.plus(ioDispatcher),
            started = SharingStarted.Eagerly,
            initialValue = BackendStatus(),
        )

    override val events = backend.events

    override suspend fun startTunnel(
        tunnel: Tunnel,
        mode: BackendMode,
        tunnelDnsConfig: TunnelDnsConfig?,
        outerConfig: Config?,
    ): Result<Unit> {
        return backend.start(
            tunnel = tunnel,
            mode = mode,
            tunnelDnsConfig = tunnelDnsConfig,
            outerConfig = outerConfig,
        )
    }

    override suspend fun stopTunnel(tunnelId: Int): Result<Unit> {
        return backend.stop(tunnelId)
    }

    override suspend fun stopActiveTunnels(): Result<Unit> {
        return backend.stopAllActiveTunnels()
    }

    override suspend fun setLockDown(settings: LockdownSettings): Result<Unit> {
        return backend.setKillSwitch(settings.toKillSwitchConfig())
    }

    override suspend fun disableLockDown(): Result<Unit> {
        return backend.disableKillSwitch()
    }

    override suspend fun updateTunnel(tunnel: Tunnel): Result<Unit> {
        return backend.updateTunnel(tunnel)
    }

    // Backend has no public peer update; its engine does (UAPI replace_peers, used by its own recovery). Fields kept
    // by name in proguard-rules.pro.
    override suspend fun updatePeers(tunnelId: Int, peers: List<PeerSection>): Result<Unit> = runCatching {
        fun field(name: String) = TunnelBackend::class.java.getDeclaredField(name).apply { isAccessible = true }.get(backend)
        // TunnelEngine is Kotlin-internal: call its suspend updatePeers(handle, mode, peers) reflectively
        val engine = field("engine")
        // byHandle (native handle -> tunnel id); R8 folds the inverse byTunnelId map away
        val handle = (field("byHandle") as Map<*, *>).entries.firstOrNull { it.value == tunnelId }?.key as? Int
            ?: error("tunnel $tunnelId not running")
        val mode = backendStatus.value.activeTunnels[tunnelId]?.mode ?: error("tunnel $tunnelId has no mode")
        val m = engine.javaClass.methods.first { it.name == "updatePeers" && it.parameterCount == 4 }
        suspendCoroutineUninterceptedOrReturn<Any?> { cont -> m.invoke(engine, handle, mode, peers, cont) }
        Unit
    }
}
