package com.zaneschepke.wireguardautotunnel.core.orchestration

import android.net.Network
import com.wgtunnel.backend.state.BackendStatus
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.util.network.WsBridge
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Mesh endpoint ladder (`# Transwarp-Endpoints`): start on entry 0, step down when the hub gives no handshake
 * within [STEP_TIMEOUT]; a `wss://` entry runs WireGuard through a [WsBridge]. Inert for single-entry tunnels.
 * ponytail: no periodic re-probe upward (a probe restarts the tunnel); a network change climbs back to entry 0.
 */
class EndpointLadder(
    private val scope: CoroutineScope,
    private val status: StateFlow<BackendStatus>,
    private val underlay: () -> Network?,
) {
    private class Run(val step: Int, val bridge: WsBridge?) {
        var job: Job? = null
    }

    private val runs = ConcurrentHashMap<Int, Run>()

    /** Endpoint override for [step] (null = stored conf); opens the bridge for a wss entry. */
    fun prepare(tunnel: TunnelConfig, step: Int): String? {
        stop(tunnel.id)
        val ladder = tunnel.endpointLadder()
        if (ladder.size < 2) return null
        val entry = ladder[step.coerceIn(ladder.indices)]
        val bridge = if (entry.startsWith("wss://") || entry.startsWith("ws://")) WsBridge(entry, underlay) else null
        runs[tunnel.id] = Run(step, bridge)
        _routes.update { it + (tunnel.id to label(entry)) }
        Timber.i("Ladder ${tunnel.name}: step $step $entry")
        return bridge?.let { "127.0.0.1:${it.port}" } ?: entry.takeIf { step > 0 }
    }

    /** After a successful start: no handshake in time -> [next] with the following step. */
    fun watch(tunnel: TunnelConfig, next: suspend (Int) -> Unit) {
        val run = runs[tunnel.id] ?: return
        if (run.step >= tunnel.endpointLadder().lastIndex) return
        run.job =
            scope.launch {
                val ok =
                    withTimeoutOrNull(STEP_TIMEOUT) {
                        status.first { s ->
                            s.activeTunnels[tunnel.id]?.activeConfig?.peers.orEmpty().any {
                                (it.lastHandshakeSeconds ?: 0) > 0
                            }
                        }
                    } != null
                // own coroutine: the restart stops this run and would cancel us mid-way
                if (!ok && runs[tunnel.id] === run) scope.launch { next(run.step + 1) }
            }
    }

    /** Tunnels currently on a fallback entry. */
    fun steppedDown(): List<Int> = runs.filterValues { it.step > 0 }.keys.toList()

    fun stop(id: Int) {
        runs.remove(id)?.let {
            it.job?.cancel()
            it.bridge?.close()
        }
        _routes.update { it - id }
    }

    companion object {
        val STEP_TIMEOUT = 8.seconds
        private val _routes = MutableStateFlow<Map<Int, String>>(emptyMap())
        /** Active ladder route per tunnel id, e.g. "UDP 51820" / "UDP 443" / "HTTPS". */
        val routes: StateFlow<Map<Int, String>> = _routes

        fun label(entry: String) =
            if (entry.contains("://")) "HTTPS" else "UDP ${entry.substringAfterLast(':')}"
    }
}
