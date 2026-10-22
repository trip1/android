package com.zaneschepke.wireguardautotunnel.util.network

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.data.DataStoreManager
import com.zaneschepke.wireguardautotunnel.notification.AndroidNotificationService.NotificationChannels
import com.zaneschepke.wireguardautotunnel.notification.NotificationService
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Transwarp Beam: Taildrop-style file drop between mesh peers over plain HTTP on port 7449.
 *
 * The ONLY authentication is the WireGuard tunnel itself: the listener binds solely to the tunnel
 * interface address(es) from the active config, never to wifi/cellular, so only peers that can route
 * to us through the tunnel can reach it. Anyone holding a peer key on that mesh can drop files.
 */
object Beam {
    const val PORT = 7449
    const val MAX_BYTES = 512L * 1024 * 1024

    data class Request(val method: String, val target: String)

    fun parseRequestLine(line: String): Request? {
        val parts = line.trim().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        if (parts[0] != "PUT" && parts[0] != "POST") return null
        if (!parts[1].startsWith("/")) return null
        return Request(parts[0], parts[1])
    }

    /** Basename only: no directories, no traversal, no control chars. Null if nothing usable is left. */
    fun sanitizeFileName(target: String): String? {
        val decoded = runCatching { URLDecoder.decode(target.substringBefore('?'), "UTF-8") }.getOrNull() ?: return null
        val base = decoded.replace('\\', '/').substringAfterLast('/')
        val clean = base.filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimStart('.')
        return clean.take(200).takeIf { it.isNotEmpty() }
    }

    /** Tunnel interface addresses without prefix length. */
    fun interfaceAddresses(address: String?): List<String> =
        address.orEmpty().split(',').map { it.trim().substringBefore('/') }.filter { it.isNotEmpty() }

    /** Blocking. Raw socket because cleartext HTTP is blocked for OkHttp at this targetSdk. */
    fun send(host: String, name: String, length: Long, body: InputStream): Int {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, PORT), 10_000)
            s.soTimeout = 60_000
            val out = s.getOutputStream().buffered()
            val path = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            out.write("PUT /$path HTTP/1.1\r\nHost: $host:$PORT\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
            body.copyTo(out)
            out.flush()
            val status = readLine(s.getInputStream()) ?: error("no response")
            return status.split(' ').getOrNull(1)?.toIntOrNull() ?: error("bad response: $status")
        }
    }

    internal fun readLine(input: InputStream, max: Int = 8192): String? {
        val sb = StringBuilder()
        while (sb.length < max) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }
}

class BeamReceiver(
    private val context: Context,
    private val dataStore: DataStoreManager,
    private val tunnelCoordinator: TunnelCoordinator,
    private val notificationService: NotificationService,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val key = booleanPreferencesKey("BEAM_RECEIVER_ENABLED")

    val enabled: Flow<Boolean> = dataStore.getFromStoreFlow(key).map { it == true }

    suspend fun setEnabled(on: Boolean) = dataStore.saveToDataStore(key, on)

    fun bind(scope: CoroutineScope) {
        scope.launch(ioDispatcher) {
            combine(
                    enabled,
                    tunnelCoordinator.backendStatus.map { status ->
                        status.activeTunnels.values.flatMap {
                            Beam.interfaceAddresses(it.activeConfig?.interfaceSection?.address)
                        }.toSet()
                    },
                ) { on, addrs -> if (on) addrs else emptySet() }
                .distinctUntilChanged()
                .collectLatest { addrs -> coroutineScope { addrs.forEach { launch { listen(it) } } } }
        }
    }

    private suspend fun listen(address: String) {
        val server =
            runCatching { ServerSocket(Beam.PORT, 8, InetAddress.getByName(address)) }
                .onFailure { Timber.w(it, "Beam: cannot bind $address:${Beam.PORT}") }
                .getOrNull() ?: return
        Timber.i("Beam receiver listening on $address:${Beam.PORT}")
        coroutineScope {
            // accept() blocks; closing the socket when this scope is cancelled unblocks it
            val closer = launch { try { awaitCancellation() } finally { server.close() } }
            while (isActive) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                launch { client.use { handle(it) } }
            }
            closer.cancel()
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = socket.getInputStream().buffered()
        val out = socket.getOutputStream()
        val req = Beam.readLine(input)?.let(Beam::parseRequestLine) ?: return reply(out, 400)
        var length: Long? = null
        while (true) {
            val h = Beam.readLine(input) ?: return reply(out, 400)
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toLongOrNull()
            if (h.startsWith("Expect:", true)) out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
        }
        if (length == null || length < 0) return reply(out, 400)
        if (length > Beam.MAX_BYTES) return reply(out, 413)
        val name = Beam.sanitizeFileName(req.target) ?: "beam-${System.currentTimeMillis()}"
        val saved = runCatching { save(name, length, input) }.onFailure { Timber.e(it, "Beam save failed") }.getOrDefault(false)
        if (!saved) return reply(out, 400)
        reply(out, 201)
        val peer = socket.inetAddress.hostAddress.orEmpty()
        notificationService.show(
            BEAM_NOTIFICATION_BASE_ID + (name.hashCode() and 0xffff),
            notificationService.createNotification(
                NotificationChannels.Events,
                title = context.getString(R.string.beam_received),
                description = context.getString(R.string.beam_received_desc, name, peer),
            ),
        )
    }

    private fun save(name: String, length: Long, input: InputStream): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
            val ok = runCatching { resolver.openOutputStream(uri)!!.use { copyExactly(input, it, length) } }.getOrDefault(false)
            if (!ok) {
                resolver.delete(uri, null, null)
                return false
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return true
        }
        // ponytail: pre-Android 10 saves to the app's own Downloads dir (no storage permission prompt)
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
        val file = File(dir, name)
        val ok = file.outputStream().use { copyExactly(input, it, length) }
        if (!ok) file.delete()
        return ok
    }

    private fun copyExactly(input: InputStream, out: OutputStream, length: Long): Boolean {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n == -1) return false
            out.write(buf, 0, n)
            left -= n
        }
        return true
    }

    private fun reply(out: OutputStream, code: Int) {
        val reason = when (code) { 201 -> "Created"; 413 -> "Payload Too Large"; else -> "Bad Request" }
        runCatching { out.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush() }
    }

    companion object {
        const val BEAM_NOTIFICATION_BASE_ID = 7449_0000
    }
}
