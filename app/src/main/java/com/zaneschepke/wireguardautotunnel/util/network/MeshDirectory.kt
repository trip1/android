package com.zaneschepke.wireguardautotunnel.util.network

import com.wgtunnel.parser.PeerSection
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** gofire mesh directory (`GET http://<hub mesh ip>:7453/v1/mesh/directory`, caller = WireGuard source IP). */
object MeshDirectory {
    const val PORT = 7453
    const val RETRY_MS = 5 * 60_000L

    data class Device(
        val name: String,
        val meshIp: String,
        val kind: String,
        val online: Boolean,
        val publicKey: String,
        val endpoint: String,
        /** Every overlay address (Phase 37); older hubs: mesh_ip (+ ip6). */
        val addresses: List<String> = listOf(meshIp),
    ) {
        /** Beam/ping target: any address, v4 first (every tunnel kind routes the v4 mesh ranges via the hub). */
        val reachable: String get() = addresses.firstOrNull { ':' !in it } ?: addresses.firstOrNull() ?: meshIp
    }

    data class Service(val name: String, val url: String, val kind: String)

    /** Phase 37: name only (MACs stay on the hub). */
    data class Wol(val name: String)

    data class Directory(
        val selfIp: String,
        val selfPeers: Boolean,
        val devices: List<Device>,
        val services: List<Service> = emptyList(),
        val wol: List<Wol> = emptyList(),
        /** Phase 38: the caller may use the hub's admin API ([MeshAdmin]). */
        val selfAdmin: Boolean = false,
    )

    fun parse(json: String): Directory {
        val o = Json.parseToJsonElement(json).jsonObject
        fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content.orEmpty()
        val self = o["self"]?.jsonObject
        return Directory(
            self?.s("mesh_ip").orEmpty(),
            self?.get("peers")?.jsonPrimitive?.booleanOrNull == true,
            o["devices"]?.jsonArray.orEmpty().map {
                val d = it.jsonObject
                val addrs = d["addresses"]?.jsonArray?.map { a -> a.jsonPrimitive.content.substringBefore('/') }
                    ?.filter { a -> a.isNotEmpty() }
                    ?.takeIf { a -> a.isNotEmpty() } ?: listOf(d.s("mesh_ip"), d.s("ip6")).filter { a -> a.isNotEmpty() }
                Device(d.s("name"), d.s("mesh_ip"), d.s("kind"), d["online"]?.jsonPrimitive?.booleanOrNull == true,
                    d.s("public_key"), d.s("endpoint"), addrs)
            },
            o["services"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { it.s("url").startsWith("http") }
                .map { Service(it.s("name"), it.s("url"), it.s("kind")) },
            o["wol"]?.jsonArray.orEmpty().map { it.jsonObject }.map { Wol(it.s("name")) },
            self?.get("admin")?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    /** Blocking. Raw socket (like Beam): cleartext HTTP is blocked for OkHttp at this targetSdk. */
    fun fetch(hub: String): Directory = parse(request(hub, "GET", "/v1/mesh/directory"))

    /** Blocking HTTP/1.0 to the hub's directory listener (source-IP auth over the tunnel); the 2xx body. */
    fun request(hub: String, method: String, path: String, json: String? = null): String =
        Socket().use { s ->
            s.connect(InetSocketAddress(hub, PORT), 5_000)
            s.soTimeout = 15_000
            val body = json?.toByteArray() ?: ByteArray(0)
            val head = "$method $path HTTP/1.0\r\nHost: $hub:$PORT\r\n" +
                (if (json != null) "Content-Type: application/json\r\nContent-Length: ${body.size}\r\n" else "") + "\r\n"
            // HTTP/1.0: no chunked encoding, body ends at EOF
            s.getOutputStream().apply { write(head.toByteArray() + body); flush() }
            val res = s.getInputStream().readBytes().decodeToString()
            val status = res.substringBefore("\r\n").split(' ').getOrNull(1)
            if (status?.startsWith("2") != true) error("$path: HTTP $status ${res.substringAfter("\r\n\r\n").take(200)}".trim())
            res.substringAfter("\r\n\r\n")
        }

    /** POST /v1/mesh/wol: the hub sends the magic packet for a registered machine; throws "HTTP 403/429 …". */
    fun wake(hub: String, w: Wol) {
        request(hub, "POST", "/v1/mesh/wol", kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive(w.name))
        }.toString())
    }

    enum class PathKind { DIRECT, RELAYED, LIGHTHOUSE, UNKNOWN }

    /**
     * Path of a Nebula host from its Status() endpoint: a lighthouse itself, `relay <ip>` = relayed via that host
     * (named from [names], ip -> name), `ip:port` = direct, null = no tunnel yet. Returns the kind plus the relay name.
     */
    fun pathOf(endpoint: String?, isLighthouse: Boolean, names: Map<String, String>): Pair<PathKind, String?> =
        when {
            isLighthouse -> PathKind.LIGHTHOUSE to null
            endpoint == null -> PathKind.UNKNOWN to null
            endpoint.startsWith("relay ") -> endpoint.removePrefix("relay ").let { PathKind.RELAYED to (names[it] ?: it) }
            else -> PathKind.DIRECT to null
        }

    /**
     * Hub speed test (Phase 37): GET /v1/mesh/speed/down?bytes=N or POST /v1/mesh/speed/up with N bytes; Mbit/s.
     * Throws "HTTP 429" when rate-limited.
     */
    fun speed(hub: String, up: Boolean, bytes: Int = SPEED_BYTES): Double =
        Socket().use { s ->
            s.connect(InetSocketAddress(hub, PORT), 5_000)
            s.soTimeout = 30_000
            val path = if (up) "/v1/mesh/speed/up" else "/v1/mesh/speed/down?bytes=$bytes"
            val head = (if (up) "POST" else "GET") + " $path HTTP/1.0\r\nHost: $hub:$PORT\r\n" +
                (if (up) "Content-Type: application/octet-stream\r\nContent-Length: $bytes\r\n" else "") + "\r\n"
            val out = s.getOutputStream()
            val t0 = System.nanoTime()
            out.write(head.toByteArray())
            if (up) ByteArray(64 * 1024).let { buf -> var left = bytes; while (left > 0) { val n = minOf(left, buf.size); out.write(buf, 0, n); left -= n } }
            out.flush()
            val input = s.getInputStream().buffered()
            val status = readHeadLine(input)
            if (status?.split(' ')?.getOrNull(1) != "200") error("HTTP ${status?.split(' ')?.getOrNull(1)}")
            while (readHeadLine(input)?.isNotEmpty() == true) { }
            val buf = ByteArray(64 * 1024)
            var got = 0L
            while (true) { val n = input.read(buf); if (n < 0) break; got += n }
            val secs = (System.nanoTime() - t0) / 1e9
            mbps(if (up) bytes.toLong() else got, secs)
        }

    const val SPEED_BYTES = 10_000_000

    fun mbps(bytes: Long, secs: Double): Double = if (secs <= 0) 0.0 else bytes * 8 / secs / 1e6

    private fun readHeadLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    /**
     * Contract section 6: a direct [Peer] for every other online peers client with a known endpoint, unless self is
     * on the WebSocket relay or that endpoint already failed (no handshake) less than [RETRY_MS] ago.
     */
    fun directPeers(
        dir: Directory,
        onRelay: Boolean,
        failed: Map<String, Pair<String, Long>>,
        now: Long,
    ): List<PeerSection> {
        if (onRelay || !dir.selfPeers) return emptyList()
        return dir.devices
            .filter { it.kind == "client" && it.online && it.endpoint.isNotEmpty() && it.meshIp != dir.selfIp }
            .filter { d -> failed[d.publicKey]?.let { (ep, at) -> ep == d.endpoint && now - at < RETRY_MS } != true }
            .map {
                PeerSection(publicKey = it.publicKey, endpoint = it.endpoint, allowedIPs = "${it.meshIp}/32",
                    persistentKeepalive = "25")
            }
    }
}
