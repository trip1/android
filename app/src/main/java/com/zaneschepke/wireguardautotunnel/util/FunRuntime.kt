package com.zaneschepke.wireguardautotunnel.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.telephony.TelephonyManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.zaneschepke.networkmonitor.ActiveNetwork
import com.zaneschepke.networkmonitor.AndroidNetworkMonitor
import com.zaneschepke.networkmonitor.NetworkMonitor
import com.zaneschepke.wireguardautotunnel.core.orchestration.EndpointLadder
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.data.DataStoreManager
import com.zaneschepke.wireguardautotunnel.data.dao.TravelSessionDao
import com.zaneschepke.wireguardautotunnel.data.entity.TravelSession
import com.zaneschepke.wireguardautotunnel.domain.events.TunnelActionEvent
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/** Optional Starship UI sounds (off by default), synthesized with AudioTrack: no audio assets. */
object UiSounds {
    val KEY = booleanPreferencesKey("UI_SOUNDS_ENABLED")
    @Volatile var enabled = false

    fun bind(scope: CoroutineScope, dataStore: DataStoreManager, coordinator: TunnelCoordinator) {
        scope.launch { dataStore.getFromStoreFlow(KEY).collect { enabled = it == true } }
        scope.launch {
            coordinator.actions.collect {
                when (it) {
                    is TunnelActionEvent.Started -> play("engage", Tone(330.0, 660.0, 260), Tone(660.0, 990.0, 140))
                    is TunnelActionEvent.Stopped -> play("disengage", Tone(990.0, 330.0, 380))
                }
            }
        }
    }

    fun chirp() = play("chirp", Tone(1400.0, 1900.0, 70), Tone(0.0, 0.0, 40), Tone(1900.0, 2400.0, 70))

    /** A linear frequency sweep; freq 0 = silence. */
    data class Tone(val from: Double, val to: Double, val ms: Int)

    private fun play(name: String, vararg tones: Tone) {
        if (!enabled) return
        thread(name = "ui-sound") {
            runCatching {
                val rate = 44_100
                val pcm = ShortArray(tones.sumOf { it.ms } * rate / 1000)
                var i = 0
                var phase = 0.0
                tones.forEach { t ->
                    val n = t.ms * rate / 1000
                    for (k in 0 until n) {
                        val f = t.from + (t.to - t.from) * k / n
                        phase += 2 * PI * f / rate
                        // 5 ms fade in/out per tone so the edges don't click
                        val env = minOf(1.0, k / 220.0, (n - k) / 220.0)
                        pcm[i++] = if (f == 0.0) 0 else (sin(phase) * env * 0.35 * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / rate + 50)
                track.release()
                Timber.i("UiSounds: $name played")
            }.onFailure { Timber.w(it, "UiSounds: $name failed") }
        }
    }
}

/**
 * Travel log recorder: one row per tunnel session, written at start, every minute while bytes move, and at the end.
 * Local only. SSID only when location permission is already granted; country = networkCountryIso (offline).
 */
class TravelLog(
    private val context: Context,
    private val dao: TravelSessionDao,
    private val coordinator: TunnelCoordinator,
    private val tunnels: TunnelRepository,
    private val networkMonitor: NetworkMonitor,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private class Open(var row: TravelSession, var savedAt: Long)

    fun bind(scope: CoroutineScope) = scope.launch(ioDispatcher) {
        val open = mutableMapOf<Int, Open>()
        coordinator.backendStatus.collect { st ->
            val now = System.currentTimeMillis()
            st.activeTunnels.forEach { (id, t) ->
                val peers = t.activeConfig?.peers.orEmpty()
                val rx = peers.sumOf { it.rxBytes ?: 0L }
                val tx = peers.sumOf { it.txBytes ?: 0L }
                val relayed = EndpointLadder.routes.value[id] == "HTTPS" || peers.any { it.endpoint?.startsWith("relay ") == true }
                val o = open[id]
                if (o == null) {
                    val name = tunnels.getById(id)?.name ?: return@forEach
                    val row = start(now, name).copy(rxBytes = rx, txBytes = tx, relayed = relayed)
                    open[id] = Open(row.copy(id = runCatching { dao.insert(row) }.getOrDefault(0)), now)
                } else {
                    // ponytail: counters that reset mid-session (backend recovery) keep the larger value, not the sum
                    // restored at process start before connectivity is known: fill the network in once it is
                    if (o.row.network == "Offline" && networkMonitor.connectivityStateFlow.value?.activeNetwork != null) {
                        start(now, o.row.tunnelName).let { f ->
                            o.row = o.row.copy(network = f.network, ssid = f.ssid, country = f.country ?: o.row.country)
                        }
                    }
                    o.row = o.row.copy(endMs = now, rxBytes = maxOf(o.row.rxBytes, rx), txBytes = maxOf(o.row.txBytes, tx),
                        relayed = o.row.relayed || relayed)
                    if (now - o.savedAt > 60_000) { save(o.row); o.savedAt = now }
                }
            }
            (open.keys - st.activeTunnels.keys).forEach { id -> open.remove(id)?.let { save(it.row.copy(endMs = now)) } }
        }
    }

    private suspend fun save(row: TravelSession) {
        if (row.id != 0L) runCatching { dao.update(row) }.onFailure { Timber.w(it, "Travel log update failed") }
    }

    private fun start(now: Long, name: String): TravelSession {
        val conn = networkMonitor.connectivityStateFlow.value
        val active = conn?.activeNetwork
        val ssid = (active as? ActiveNetwork.Wifi)?.ssid
            ?.takeIf { conn.locationPermissionsGranted && it != AndroidNetworkMonitor.ANDROID_UNKNOWN_SSID }
        val country = runCatching { context.getSystemService(TelephonyManager::class.java)?.networkCountryIso }
            .getOrNull()?.takeIf { it.isNotBlank() }?.uppercase()
        val network = when (active) {
            is ActiveNetwork.Wifi -> "Wi-Fi"
            is ActiveNetwork.Cellular -> "Cellular"
            is ActiveNetwork.Ethernet -> "Ethernet"
            else -> "Offline"
        }
        return TravelSession(startMs = now, endMs = now, tunnelName = name, network = network, ssid = ssid, country = country)
    }
}
