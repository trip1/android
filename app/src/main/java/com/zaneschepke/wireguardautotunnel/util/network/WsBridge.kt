package com.zaneschepke.wireguardautotunnel.util.network

import android.net.Network
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import timber.log.Timber

/**
 * WireGuard over WebSocket (gofire relay): WireGuard's Endpoint is set to `127.0.0.1:[port]`; every UDP
 * datagram it sends there becomes one binary WS message and every binary message goes back to WireGuard's
 * last source address. No framing beyond that. Reconnects with 1, 2, 4 … 30 s backoff until [close].
 *
 * Routing: the loopback leg never enters the VPN. The WS socket must not go into our own tunnel, so it is
 * created by the underlay [Network]'s socket factory (and resolved via its DNS). As the VPN owner this app may
 * bind to the underlying network, which is how VpnService.protect() works too; that also holds in lockdown /
 * kill-switch mode, whose TUN is our own VpnService. If no underlay is known the default route is used.
 */
class WsBridge(private val url: String, private val underlay: () -> Network?) : Closeable {
    // explicit IPv4: getLoopbackAddress() is ::1 on Android, and the Endpoint we hand WireGuard is 127.0.0.1
    private val udp = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    val port: Int = udp.localPort
    @Volatile private var wgAddress: SocketAddress? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    private var attempt = 0
    private val timer = Executors.newSingleThreadScheduledExecutor()

    init {
        thread(name = "ws-bridge-$port", isDaemon = true) {
            val buf = ByteArray(65535)
            val p = DatagramPacket(buf, buf.size)
            while (!closed) {
                runCatching { udp.receive(p) }.onFailure { return@thread }
                wgAddress = p.socketAddress
                // OkHttp queues sends made before the socket opens, so the first handshake isn't lost
                ws?.send(buf.toByteString(0, p.length))
            }
        }
        connect()
    }

    private fun connect() {
        if (closed) return
        val net = underlay()
        val client =
            BASE.newBuilder()
                .apply {
                    if (net != null) {
                        socketFactory(net.socketFactory)
                        dns { host -> net.getAllByName(host).toList() }
                    }
                }
                .build()
        ws = client.newWebSocket(Request.Builder().url(url).build(), Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            Timber.i("WsBridge :$port connected to $url")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val to = wgAddress ?: return
            runCatching { udp.send(DatagramPacket(bytes.toByteArray(), bytes.size, to)) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            retry(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.w("WsBridge :$port ${t.message} (HTTP ${response?.code})")
            retry(webSocket)
        }
    }

    @Synchronized
    private fun retry(dead: WebSocket) {
        if (closed || ws !== dead) return
        ws = null
        val wait = backoffSeconds(attempt++)
        runCatching { timer.schedule(::connect, wait, TimeUnit.SECONDS) }
    }

    override fun close() {
        closed = true
        timer.shutdownNow()
        ws?.cancel()
        udp.close()
    }

    companion object {
        fun backoffSeconds(attempt: Int): Long = minOf(30L, 1L shl minOf(attempt, 5))

        // ping catches a dead path long before TCP does; WG's 25 s keepalive keeps the relay's idle timer happy
        private val BASE = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    }
}
