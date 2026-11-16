package com.zaneschepke.wireguardautotunnel.util.network

import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** gofire's Nebula profile (enroll response `nebula`, contract section 2) and its rendering into a Nebula config. */
object Nebula {
    @Serializable
    data class Lighthouse(val name: String = "", val ip: String, val endpoints: List<String> = emptyList())

    @Serializable data class Route(val cidr: String, val via: String)

    /** Stored as the tunnel's `nebula_profile` JSON; [role] and [listenPort] are the user's choice afterwards. */
    @Serializable
    data class Profile(
        val ip: String,
        @SerialName("ca_pem") val caPem: String,
        @SerialName("cert_pem") val certPem: String,
        val lighthouses: List<Lighthouse> = emptyList(),
        val routes: List<Route> = emptyList(),
        val dns: String = "",
        @SerialName("listen_port") val listenPort: Int = 4242,
        val role: String = ROLE_CLIENT,
    ) {
        val isLighthouse get() = role != ROLE_CLIENT
        val isClient get() = role != ROLE_LIGHTHOUSE

        /** 10.90.200.7/16 -> 10.90.0.0/16, the Nebula network routed into the VPN. */
        val network: String
            get() {
                val (addr, bits) = ip.split('/')
                val b = bits.toInt()
                val n = addr.split('.').fold(0L) { a, o -> (a shl 8) or o.toLong() } and (0xffffffffL shl (32 - b))
                return (3 downTo 0).joinToString(".") { ((n shr (8 * it)) and 0xff).toString() } + "/$b"
            }
    }

    const val ROLE_CLIENT = "client"
    const val ROLE_LIGHTHOUSE = "lighthouse"
    const val ROLE_BOTH = "both"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(profileJson: String): Profile = json.decodeFromString(profileJson)

    fun encode(profile: Profile): String = json.encodeToString(profile)

    fun privateKeyPem(raw32: ByteArray): String {
        require(raw32.size == 32) { "X25519 key must be 32 bytes" }
        return "-----BEGIN NEBULA X25519 PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(raw32) +
            "\n-----END NEBULA X25519 PRIVATE KEY-----\n"
    }

    /**
     * Nebula config per role (contract section 3 table). Rendered as JSON, which is valid YAML, so PEMs need no
     * YAML quoting.
     */
    fun yaml(p: Profile, privateKeyPem: String): String {
        val hub = p.lighthouses.firstOrNull()?.ip // gofire lists the hub first
        return buildJsonObject {
                putJsonObject("pki") {
                    put("ca", p.caPem)
                    put("cert", p.certPem)
                    put("key", privateKeyPem)
                }
                putJsonObject("static_host_map") {
                    p.lighthouses.forEach { lh -> putJsonArray(lh.ip) { lh.endpoints.forEach { add(it) } } }
                }
                putJsonObject("lighthouse") {
                    put("am_lighthouse", p.isLighthouse)
                    putJsonArray("hosts") { if (!p.isLighthouse) p.lighthouses.forEach { add(it.ip) } }
                }
                putJsonObject("listen") {
                    put("host", "0.0.0.0")
                    put("port", if (p.isLighthouse) p.listenPort else 0)
                }
                putJsonObject("punchy") {
                    put("punch", true)
                    put("respond", true)
                }
                putJsonObject("relay") {
                    put("use_relays", p.isClient)
                    putJsonArray("relays") { if (p.isClient && hub != null) add(hub) }
                }
                putJsonObject("tun") {
                    put("mtu", MTU)
                    putJsonArray("unsafe_routes") {
                        if (p.isClient) p.routes.forEach { r -> addJsonObject { put("route", r.cidr); put("via", r.via) } }
                    }
                }
                putJsonObject("firewall") {
                    val any = buildJsonObject {
                        put("port", "any")
                        put("proto", "any")
                        put("host", "any")
                    }
                    putJsonArray("outbound") { add(any) }
                    putJsonArray("inbound") { add(any) }
                }
                putJsonObject("logging") { put("level", "info") }
            }
            .toString()
    }

    const val MTU = 1300
}
