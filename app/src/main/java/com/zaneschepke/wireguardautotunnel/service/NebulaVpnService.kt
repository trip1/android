package com.zaneschepke.wireguardautotunnel.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import chat.warpsignal.nebmobile.Nebmobile
import chat.warpsignal.nebmobile.Protector
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.notification.AndroidNotificationService.NotificationChannels
import com.zaneschepke.wireguardautotunnel.notification.NotificationService
import com.zaneschepke.wireguardautotunnel.util.Constants
import com.zaneschepke.wireguardautotunnel.util.network.Nebula
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.android.ext.android.inject
import timber.log.Timber

/** Nebula's own VpnService (the WireGuard backend's VpnService isn't reusable). Driven by TunnelCoordinator. */
class NebulaVpnService : VpnService() {
    private val notificationService: NotificationService by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opMutex = Mutex()
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            val done = pendingStop
            serviceScope.launch {
                opMutex.withLock { shutdownLocked(startId) }
                done?.complete(Unit)
            }
            return START_NOT_STICKY
        }
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val profileJson = intent.getStringExtra(EXTRA_PROFILE)
        val yaml = intent.getStringExtra(EXTRA_YAML)
        val included = intent.getStringArrayListExtra(EXTRA_INCLUDED).orEmpty().filter { it != packageName }
        val excluded = intent.getStringArrayListExtra(EXTRA_EXCLUDED).orEmpty().filter { it != packageName }
        ServiceCompat.startForeground(
            this,
            NotificationService.NEBULA_NOTIFICATION_ID,
            notificationService.createNotification(
                NotificationChannels.Tunnel.VPN,
                title = name,
                description = getString(R.string.nebula_running),
                onGoing = true,
            ),
            Constants.SPECIAL_USE_SERVICE_TYPE_ID,
        )
        val done = pendingStart
        serviceScope.launch {
            val result = opMutex.withLock {
                runCatching {
                    val p = Nebula.parse(profileJson!!)
                    withContext(Nebula.dispatcher) { Nebmobile.stop() }
                    tun?.close()
                    tun = null
                    val (addr, bits) = p.ip.split('/')
                    val builder =
                        Builder()
                            .setSession(name)
                            .setMtu(Nebula.MTU)
                            .addAddress(addr, bits.toInt())
                    // the app itself uses the tunnel (directory, Beam, renew); only Nebula's own UDP socket is protect()ed in
                    // Nebmobile.start, or the LAN lighthouse endpoint inside a routed CIDR would loop
                    p.network.split('/').let { (a, b) -> builder.addRoute(a, b.toInt()) }
                    // v2 cert: overlay v6 address + its /64 (Phase 33)
                    if (p.ip6.isNotBlank()) p.ip6.split('/').let { (a, b) -> builder.addAddress(a, b.toInt()) }
                    p.network6?.split('/')?.let { (a, b) -> builder.addRoute(a, b.toInt()) }
                    if (p.isClient) {
                        p.routes.forEach { r -> r.cidr.split('/').let { (a, b) -> builder.addRoute(a, b.toInt()) } }
                        // exit: like WireGuard's ::/0, IPv6 goes into the tunnel and dies there (the hub has no v6) instead of leaking
                        if (p.routes.any { it.cidr == Nebula.DEFAULT_ROUTE }) builder.addRoute("::", 0)
                        if (p.dns.isNotBlank()) builder.addDnsServer(p.dns)
                    }
                    // split tunneling (same lists as WireGuard). The app stays inside its own VPN either way: directory, Beam
                    // and renew go over Nebula, and Nebula's own UDP socket is protect()ed
                    if (included.isNotEmpty()) (included + packageName).forEach { runCatching { builder.addAllowedApplication(it) } }
                    else excluded.forEach { runCatching { builder.addDisallowedApplication(it) } } // uninstalled app: skip
                    val fd = builder.establish() ?: error("VPN permission revoked")
                    tun = fd
                    val protector = Protector { sock -> protect(sock.toInt()) }
                    withContext(Nebula.dispatcher) { Nebmobile.start(yaml!!, fd.fd.toLong(), protector) }
                    _active.value = id to System.currentTimeMillis()
                }.onFailure {
                    Timber.e(it, "Nebula start failed")
                    shutdownLocked(startId)
                }
            }
            done?.complete(result)
        }
        return START_NOT_STICKY
    }

    // another VPN (the WireGuard backend) took over, or the user revoked us in system settings
    override fun onRevoke() {
        serviceScope.launch { opMutex.withLock { shutdownLocked() } }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // best-effort teardown off the main thread so onDestroy never ANRs on a busy Nebula.dispatcher
        _active.value = null
        val fd = tun.also { tun = null }
        Nebula.dispatcher.executor.execute {
            runCatching { Nebmobile.stop() }
            runCatching { fd?.close() }
        }
        pendingStart?.complete(Result.failure(IllegalStateException("NebulaVpnService destroyed")))
        pendingStop?.complete(Unit)
        super.onDestroy()
    }

    private suspend fun shutdownLocked(startId: Int? = null) {
        withContext(Nebula.dispatcher) { runCatching { Nebmobile.stop() } }
        runCatching { tun?.close() }
        tun = null
        _active.value = null
        withContext(Dispatchers.Main.immediate) {
            if (startId == null) {
                ServiceCompat.stopForeground(this@NebulaVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else if (stopSelfResult(startId)) {
                ServiceCompat.stopForeground(this@NebulaVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
        }
    }

    companion object {
        private const val ACTION_START = "nebula.start"
        private const val ACTION_STOP = "nebula.stop"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_YAML = "yaml"
        private const val EXTRA_INCLUDED = "included"
        private const val EXTRA_EXCLUDED = "excluded"

        private val _active = MutableStateFlow<Pair<Int, Long>?>(null)
        /** (tunnel id, start time ms) of the running Nebula tunnel, or null. */
        val active = _active.asStateFlow()

        @Volatile private var pendingStart: CompletableDeferred<Result<Unit>>? = null
        @Volatile private var pendingStop: CompletableDeferred<Unit>? = null

        /** Starts (or replaces) the Nebula VPN; returns once Nebula runs or failed. Caller holds the tunnel mutex. */
        suspend fun start(
            context: Context, id: Int, name: String, profileJson: String, yaml: String,
            included: List<String> = emptyList(), excluded: List<String> = emptyList(),
        ): Result<Unit> {
            val done = CompletableDeferred<Result<Unit>>().also { pendingStart = it }
            context.startForegroundService(
                Intent(context, NebulaVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_PROFILE, profileJson)
                    .putExtra(EXTRA_YAML, yaml)
                    .putStringArrayListExtra(EXTRA_INCLUDED, ArrayList(included))
                    .putStringArrayListExtra(EXTRA_EXCLUDED, ArrayList(excluded))
            )
            return try {
                withTimeout(20_000) { done.await() }
            } catch (e: Throwable) {
                Result.failure(e)
            } finally {
                if (pendingStart === done) pendingStart = null
            }
        }

        /** Stops the Nebula VPN and waits until teardown completes (capped at 5 s). Caller holds the tunnel mutex. */
        suspend fun stop(context: Context) {
            if (_active.value == null) return
            val done = CompletableDeferred<Unit>().also { pendingStop = it }
            _active.value = null // the list flips immediately; the service tears down right after
            context.startService(Intent(context, NebulaVpnService::class.java).setAction(ACTION_STOP))
            try {
                withTimeout(5_000) { done.await() }
            } catch (_: Throwable) {
                // service was already gone or timed out
            } finally {
                if (pendingStop === done) pendingStop = null
            }
        }
    }
}
