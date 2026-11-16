package com.zaneschepke.wireguardautotunnel.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import chat.warpsignal.nebmobile.Nebmobile
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.notification.AndroidNotificationService.NotificationChannels
import com.zaneschepke.wireguardautotunnel.notification.NotificationService
import com.zaneschepke.wireguardautotunnel.util.Constants
import com.zaneschepke.wireguardautotunnel.util.network.Nebula
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import org.koin.android.ext.android.inject
import timber.log.Timber

/** Nebula's own VpnService (the WireGuard backend's VpnService isn't reusable). Driven by TunnelCoordinator. */
class NebulaVpnService : VpnService() {
    private val notificationService: NotificationService by inject()
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            // a start queued right behind this stop (Nebula restart) keeps the service alive
            shutdown(startId)
            return START_NOT_STICKY
        }
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
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
        // ponytail: Nebula starts on the main thread (no DNS in our configs, ~ms); move off it if starts get slow
        val result = runCatching {
            val p = Nebula.parse(intent.getStringExtra(EXTRA_PROFILE)!!)
            Nebmobile.stop()
            tun?.close()
            tun = null
            val (addr, bits) = p.ip.split('/')
            val builder =
                Builder()
                    .setSession(name)
                    .setMtu(Nebula.MTU)
                    .addAddress(addr, bits.toInt())
                    // Nebula's UDP socket lives in this app: keep it (and the app) off the tunnel, or the LAN
                    // lighthouse endpoint inside a routed CIDR would loop
                    .addDisallowedApplication(packageName)
            p.network.split('/').let { (a, b) -> builder.addRoute(a, b.toInt()) }
            if (p.isClient) {
                p.routes.forEach { r -> r.cidr.split('/').let { (a, b) -> builder.addRoute(a, b.toInt()) } }
                if (p.dns.isNotBlank()) builder.addDnsServer(p.dns)
            }
            val fd = builder.establish() ?: error("VPN permission revoked")
            tun = fd
            Nebmobile.start(intent.getStringExtra(EXTRA_YAML)!!, fd.fd.toLong())
            _active.value = id to System.currentTimeMillis()
        }
        result.onFailure {
            Timber.e(it, "Nebula start failed")
            shutdown(startId)
        }
        pending?.complete(result)
        return START_NOT_STICKY
    }

    // another VPN (the WireGuard backend) took over, or the user revoked us in system settings
    override fun onRevoke() = shutdown()

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown(startId: Int? = null) {
        Nebmobile.stop()
        tun?.close()
        tun = null
        _active.value = null
        if (startId == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (stopSelfResult(startId)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    companion object {
        private const val ACTION_START = "nebula.start"
        private const val ACTION_STOP = "nebula.stop"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_YAML = "yaml"

        private val _active = MutableStateFlow<Pair<Int, Long>?>(null)
        /** (tunnel id, start time ms) of the running Nebula tunnel, or null. */
        val active = _active.asStateFlow()

        @Volatile private var pending: CompletableDeferred<Result<Unit>>? = null

        /** Starts (or replaces) the Nebula VPN; returns once Nebula runs or failed. Caller holds the tunnel mutex. */
        suspend fun start(context: Context, id: Int, name: String, profileJson: String, yaml: String): Result<Unit> {
            val done = CompletableDeferred<Result<Unit>>().also { pending = it }
            context.startForegroundService(
                Intent(context, NebulaVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_PROFILE, profileJson)
                    .putExtra(EXTRA_YAML, yaml)
            )
            return runCatching { withTimeout(20_000) { done.await() } }.getOrElse { Result.failure(it) }
        }

        fun stop(context: Context) {
            if (_active.value == null) return
            _active.value = null // the list flips immediately; the service tears down right after
            context.startService(Intent(context, NebulaVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
