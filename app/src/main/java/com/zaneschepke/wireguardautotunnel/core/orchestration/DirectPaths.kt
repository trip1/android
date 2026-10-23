package com.zaneschepke.wireguardautotunnel.core.orchestration

import com.wgtunnel.backend.state.BackendStatus
import com.wgtunnel.parser.PeerSection
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.util.network.MeshDirectory
import com.zaneschepke.wireguardautotunnel.util.network.MeshEnroll
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Mesh directory poller + direct client-to-client paths (contract section 6), for tunnels enrolled with a
 * `# Transwarp-Name`. Direct peers are pushed live into the running device ([TunnelProvider.updatePeers]), no restart.
 * ponytail: peers are replaced against the start-time run config; if the backend's own recovery replaces peers, the
 * direct ones come back at the next directory change rather than the next poll.
 */
class DirectPaths(
    private val scope: CoroutineScope,
    private val status: StateFlow<BackendStatus>,
    private val provider: TunnelProvider,
) {
    private val jobs = ConcurrentHashMap<Int, Job>()

    fun start(tunnel: TunnelConfig, liveStats: Boolean) {
        stop(tunnel.id)
        if (MeshEnroll.header(tunnel.quickConfig, "Name") == null) return
        val hub = runCatching { tunnel.getConfig().`interface`.dns }.getOrNull()?.split(',')?.firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return
        jobs[tunnel.id] = scope.launch { poll(tunnel.id, hub, liveStats) }
    }

    fun stop(id: Int) {
        jobs.remove(id)?.cancel()
        _directories.update { it - id }
        _direct.update { it - id }
    }

    private suspend fun poll(id: Int, hub: String, liveStats: Boolean) {
        val failed = mutableMapOf<String, Pair<String, Long>>()
        var applied = emptyList<PeerSection>()
        while (scope.isActive) {
            val dir = runCatching { withContext(Dispatchers.IO) { MeshDirectory.fetch(hub) } }
                .onFailure { Timber.d(it, "Mesh directory") }.getOrNull()
            if (dir != null) _directories.update { it + (id to dir) }
            // no live stats = no handshake check, so no direct peers (traffic stays via the hub)
            if (dir != null && liveStats) {
                val onRelay = EndpointLadder.routes.value[id] == "HTTPS"
                val want = MeshDirectory.directPeers(dir, onRelay, failed, System.currentTimeMillis())
                val fresh = want - applied.toSet()
                if (want != applied && apply(id, want)) {
                    applied = want
                    if (fresh.isNotEmpty()) {
                        delay(HANDSHAKE_TIMEOUT)
                        val peers = status.value.activeTunnels[id]?.activeConfig?.peers.orEmpty()
                        val dead = fresh.filter { f -> peers.none { it.publicKey == f.publicKey && (it.lastHandshakeSeconds ?: 0) > 0 } }
                        dead.forEach { failed[it.publicKey] = (it.endpoint.orEmpty()) to System.currentTimeMillis() }
                        if (dead.isNotEmpty() && apply(id, applied - dead.toSet())) applied = applied - dead.toSet()
                    }
                }
            }
            delay(POLL)
        }
    }

    private suspend fun apply(id: Int, direct: List<PeerSection>): Boolean {
        val base = status.value.activeTunnels[id]?.mode?.config?.peers ?: return false
        val keys = direct.map { it.publicKey }.toSet()
        return provider.updatePeers(id, base.filter { it.publicKey !in keys } + direct)
            .onFailure { Timber.w(it, "Direct paths: peer update failed") }
            .onSuccess {
                Timber.i("Direct paths $id: ${direct.map { it.allowedIPs }}")
                _direct.update { m -> m + (id to keys) }
            }
            .isSuccess
    }

    companion object {
        val POLL = 30.seconds
        val HANDSHAKE_TIMEOUT = 10.seconds
        private val _directories = MutableStateFlow<Map<Int, MeshDirectory.Directory>>(emptyMap())
        /** Latest directory per mesh tunnel id (Beam picker, Sensor Array). */
        val directories: StateFlow<Map<Int, MeshDirectory.Directory>> = _directories
        private val _direct = MutableStateFlow<Map<Int, Set<String>>>(emptyMap())
        /** Public keys currently reached over a direct path, per tunnel id. */
        val direct: StateFlow<Map<Int, Set<String>>> = _direct
    }
}
