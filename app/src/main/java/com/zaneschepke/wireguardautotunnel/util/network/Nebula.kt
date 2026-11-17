package com.zaneschepke.wireguardautotunnel.util.network

import com.wgtunnel.parser.ActiveConfig
import com.wgtunnel.parser.ActivePeer
import com.wgtunnel.parser.InterfaceSection
import java.util.Base64
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** gofire's Nebula profile (enroll response `nebula`, contract section 2) and its rendering into a Nebula config. */
object Nebula {
    /**
     * Every Nebmobile call runs on this one thread. The WireGuard backend is a second Go runtime in the process;
     * calling Nebula from pooled IO threads that also run WireGuard's JNI crashed it ("unexpected return pc for
     * runtime.cgocallback"), so Nebula's Go runtime only ever sees this thread.
     */
    val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "nebmobile") }
        .asCoroutineDispatcher()

    /** Blocking call on [dispatcher], for the service's synchronous start/stop. */
    fun <T> call(block: () -> T): T = kotlinx.coroutines.runBlocking(dispatcher) { block() }

    @Serializable
    data class Lighthouse(val name: String = "", val ip: String, val endpoints: List<String> = emptyList())

    @Serializable data class Route(val cidr: String, val via: String)

    @Serializable data class Exit(val name: String = "", val ip: String)

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
        /** Overlay IPv6 (gofire Phase 33, v2 certs), e.g. fdee:6e65:6275:6c61::c8:6/64; "" on a v1-only cert. */
        val ip6: String = "",
        /** Underlay ranges Nebula prefers when two members share a LAN (gofire Phase 34). */
        @SerialName("preferred_ranges") val preferredRanges: List<String> = emptyList(),
        /** Phase 35: this member's Nebula firewall.inbound, verbatim; empty = allow all. */
        @SerialName("firewall_inbound") val firewallInbound: List<JsonObject> = emptyList(),
        /** Phase 35: exits an exit client may pick (the hub first). */
        val exits: List<Exit> = emptyList(),
        /** The user's exit pick (an [exits] ip); "" or no longer offered = the hub. App-side, kept across renews. */
        @SerialName("exit_via") val exitVia: String = "",
    ) {
        val isLighthouse get() = role != ROLE_CLIENT
        val isClient get() = role != ROLE_LIGHTHOUSE
        /** gofire granted an internet exit (Phase 32): the profile routes 0.0.0.0/0 via the hub. */
        val hasExit get() = routes.any { it.cidr == DEFAULT_ROUTE }

        /**
         * Full Warp off drops gofire's exit route. Without an exit grant there is none to keep: the hub would drop
         * that traffic, so the toggle can't black-hole the internet.
         */
        fun forRun(fullWarp: Boolean): Profile =
            if (!fullWarp) copy(routes = routes.filter { it.cidr != DEFAULT_ROUTE })
            else exitIp?.let { ip -> copy(routes = routes.map { if (it.cidr == DEFAULT_ROUTE) it.copy(via = ip) else it }) } ?: this

        /** The picked exit's ip while gofire still offers it, else null (= the profile's own route, the hub). */
        val exitIp: String? get() = exitVia.takeIf { v -> v.isNotEmpty() && exits.any { it.ip == v } }

        /** A renewed profile keeps what the user picked on this device. */
        fun withChoicesOf(old: Profile) = copy(role = old.role, listenPort = old.listenPort, exitVia = old.exitVia)

        /** 10.90.200.7/16 -> 10.90.0.0/16, the Nebula network routed into the VPN. */
        val network: String get() = networkOf(ip)

        /** Tunnel address CIDRs, v4 then v6, as the ActiveConfig interface address (Beam binds these). */
        val addresses: String get() = listOf(ip, ip6).filter { it.isNotBlank() }.joinToString()

        /** Same VPN interface (addresses, routes, DNS) as [o]: a profile change that only needs a Nebula reload. */
        fun sameVpn(o: Profile) = ip == o.ip && ip6 == o.ip6 && routes == o.routes && dns == o.dns &&
            role == o.role && listenPort == o.listenPort

        /** fdee:…::c8:6/64 -> fdee:…::/64, or null without a v6 address. */
        val network6: String? get() = ip6.takeIf { it.isNotBlank() }?.let(::networkOf)
    }

    /** Network address of a CIDR (v4 or v6 literal; no DNS lookup for literals). */
    fun networkOf(cidr: String): String {
        val (addr, bits) = cidr.split('/')
        val b = java.net.InetAddress.getByName(addr).address
        val n = bits.toInt()
        for (i in b.indices) {
            val keep = (n - 8 * i).coerceIn(0, 8)
            b[i] = (b[i].toInt() and (0xff shl (8 - keep))).toByte()
        }
        return java.net.InetAddress.getByAddress(b).hostAddress!! + "/$n"
    }

    /** Renew/refresh every 6 h (rules, exits; the hub re-signs past half-life). */
    const val REFRESH_MS = 6 * 3_600_000L

    /** Next renew call after one at [nowMs]: 6 h, or half the time left for a short-lived cert. */
    fun renewDueAt(nowMs: Long, expiresMs: Long?): Long =
        nowMs + minOf(REFRESH_MS, expiresMs?.let { ((it - nowMs) / 2).coerceAtLeast(60_000) } ?: REFRESH_MS)

    const val DEFAULT_ROUTE = "0.0.0.0/0"
    const val ROLE_CLIENT = "client"
    const val ROLE_LIGHTHOUSE = "lighthouse"
    const val ROLE_BOTH = "both"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(profileJson: String): Profile = json.decodeFromString(profileJson)

    @Serializable
    private data class Host(
        @SerialName("vpn_addrs") val vpnAddrs: List<String> = emptyList(),
        val name: String = "",
        val remote: String? = null,
        val relays: List<String> = emptyList(),
        @SerialName("rx_bytes") val rxBytes: Long = 0,
        @SerialName("tx_bytes") val txBytes: Long = 0,
        @SerialName("last_rx") val lastRx: Long = 0,
    )

    @Serializable private data class Status(val hosts: List<Host> = emptyList())

    /**
     * nebmobile Status() as the WireGuard backend's ActiveConfig, so the tunnel card, notification, Sensor Array and
     * Beam read Nebula hosts like WireGuard peers: publicKey = the host's cert name, endpoint = its current remote
     * (or "relay <ip>"). Nebula exposes no handshake time; an established host shows its newest packet, or [nowSec]
     * when idle (it is only in the hostmap while the tunnel is alive).
     */
    fun activeConfig(statusJson: String, ip: String, nowSec: Long): ActiveConfig {
        val st = json.decodeFromString<Status>(statusJson)
        return ActiveConfig(
            InterfaceSection(privateKey = "", address = ip),
            st.hosts.map { h ->
                ActivePeer(
                    publicKey = h.name.ifEmpty { h.vpnAddrs.firstOrNull().orEmpty() },
                    allowedIPs = h.vpnAddrs.joinToString { if (':' in it) "$it/128" else "$it/32" },
                    endpoint = h.remote ?: h.relays.firstOrNull()?.let { "relay $it" },
                    presharedKey = null,
                    persistentKeepalive = null,
                    lastHandshakeSeconds = h.lastRx.takeIf { it > 0 } ?: nowSec,
                    lastHandshakeNanos = 0,
                    txBytes = h.txBytes,
                    rxBytes = h.rxBytes,
                )
            },
        )
    }

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
                    // a v2 cert (has ip6) initiates v2 handshakes; a v1-only cert must stay on v1 (Nebula refuses 2)
                    if (p.ip6.isNotBlank()) put("initiating_version", 2)
                }
                if (p.preferredRanges.isNotEmpty()) putJsonArray("preferred_ranges") { p.preferredRanges.forEach { add(it) } }
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
                    // gofire's group rules (Phase 35); no rules = allow all
                    putJsonArray("inbound") { if (p.firewallInbound.isEmpty()) add(any) else p.firewallInbound.forEach { add(it) } }
                }
                putJsonObject("logging") { put("level", "info") }
            }
            .toString()
    }

    const val MTU = 1300
}
