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
    )

    data class Directory(val selfIp: String, val selfPeers: Boolean, val devices: List<Device>)

    fun parse(json: String): Directory {
        val o = Json.parseToJsonElement(json).jsonObject
        fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content.orEmpty()
        val self = o["self"]?.jsonObject
        return Directory(
            self?.s("mesh_ip").orEmpty(),
            self?.get("peers")?.jsonPrimitive?.booleanOrNull == true,
            o["devices"]?.jsonArray.orEmpty().map {
                val d = it.jsonObject
                Device(d.s("name"), d.s("mesh_ip"), d.s("kind"), d["online"]?.jsonPrimitive?.booleanOrNull == true,
                    d.s("public_key"), d.s("endpoint"))
            },
        )
    }

    /** Blocking. Raw socket (like Beam): cleartext HTTP is blocked for OkHttp at this targetSdk. */
    fun fetch(hub: String): Directory =
        Socket().use { s ->
            s.connect(InetSocketAddress(hub, PORT), 5_000)
            s.soTimeout = 5_000
            // HTTP/1.0: no chunked encoding, body ends at EOF
            s.getOutputStream().write("GET /v1/mesh/directory HTTP/1.0\r\nHost: $hub:$PORT\r\n\r\n".toByteArray())
            val res = s.getInputStream().readBytes().decodeToString()
            val status = res.substringBefore("\r\n").split(' ').getOrNull(1)
            if (status != "200") error("directory: HTTP $status")
            parse(res.substringAfter("\r\n\r\n"))
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
