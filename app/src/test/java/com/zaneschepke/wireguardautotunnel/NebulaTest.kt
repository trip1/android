package com.zaneschepke.wireguardautotunnel

import com.zaneschepke.wireguardautotunnel.util.network.MeshEnroll
import com.zaneschepke.wireguardautotunnel.util.network.Nebula
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NebulaTest {
    // contract section 2 example, as gofire sends it
    private val enrollNebula =
        """{"ip":"10.90.200.7/16","ca_pem":"CA","cert_pem":"CERT","lighthouses":[
            {"name":"pi-5-1","ip":"10.90.0.1","endpoints":["180.181.68.247:4242","10.0.1.219:4242"]},
            {"name":"trona-tablet","ip":"10.90.200.4","endpoints":["203.0.113.5:4242"]}],
           "routes":[{"cidr":"10.89.0.0/16","via":"10.90.0.1"},{"cidr":"10.0.0.0/16","via":"10.90.0.1"}],
           "dns":"10.89.0.1","listen_port":4242,"role":"client","future_field":1}"""
    private val key = Nebula.privateKeyPem(ByteArray(32) { it.toByte() })

    private fun render(role: String): JsonObject {
        val p = Nebula.parse(Nebula.encode(Nebula.parse(enrollNebula).copy(role = role)))
        return Json.parseToJsonElement(Nebula.yaml(p, key)).jsonObject
    }

    private fun JsonObject.at(path: String) =
        path.split('.').fold(this as kotlinx.serialization.json.JsonElement) { e, k -> e.jsonObject[k]!! }

    private fun JsonObject.always() {
        assertEquals("CA", at("pki.ca").jsonPrimitive.content)
        assertEquals("CERT", at("pki.cert").jsonPrimitive.content)
        assertEquals(key, at("pki.key").jsonPrimitive.content)
        assertTrue(at("pki.disconnect_invalid").jsonPrimitive.boolean)
        assertEquals(listOf("180.181.68.247:4242", "10.0.1.219:4242"), at("static_host_map").jsonObject["10.90.0.1"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, at("static_host_map").jsonObject.size)
        assertTrue(at("punchy.punch").jsonPrimitive.boolean && at("punchy.respond").jsonPrimitive.boolean)
        assertEquals(1300, at("tun.mtu").jsonPrimitive.int)
        assertEquals("any", at("firewall.inbound").jsonArray[0].jsonObject["host"]!!.jsonPrimitive.content)
        assertEquals("any", at("firewall.outbound").jsonArray[0].jsonObject["proto"]!!.jsonPrimitive.content)
        assertEquals("info", at("logging.level").jsonPrimitive.content)
    }

    @Test
    fun client() {
        val c = render(Nebula.ROLE_CLIENT)
        c.always()
        assertEquals(false, c.at("lighthouse.am_lighthouse").jsonPrimitive.boolean)
        assertEquals(listOf("10.90.0.1", "10.90.200.4"), c.at("lighthouse.hosts").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, c.at("listen.port").jsonPrimitive.int)
        assertEquals(listOf("10.89.0.0/16", "10.0.0.0/16"), c.at("tun.unsafe_routes").jsonArray.map { it.jsonObject["route"]!!.jsonPrimitive.content })
        assertEquals("10.90.0.1", c.at("tun.unsafe_routes").jsonArray[0].jsonObject["via"]!!.jsonPrimitive.content)
        assertTrue(c.at("relay.use_relays").jsonPrimitive.boolean)
        assertEquals(listOf("10.90.0.1"), c.at("relay.relays").jsonArray.map { it.jsonPrimitive.content })
    }

    // Phase 36: mini-1 as a second lighthouse + relay
    @Test
    fun twoLighthousesTwoRelays() {
        val p = Nebula.parse(enrollNebula.replace("trona-tablet", "mini-1").replace("10.90.200.4", "10.90.0.2")
            .replace("\"203.0.113.5:4242\"", "\"10.0.3.3:4243\",\"180.181.68.247:4243\"")
            .replace("\"role\":\"client\"", "\"role\":\"client\",\"relays\":[\"10.90.0.1\",\"10.90.0.2\"]"))
        val c = Json.parseToJsonElement(Nebula.yaml(p, key)).jsonObject
        assertEquals(listOf("10.0.3.3:4243", "180.181.68.247:4243"), c.at("static_host_map").jsonObject["10.90.0.2"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("10.90.0.1", "10.90.0.2"), c.at("lighthouse.hosts").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("10.90.0.1", "10.90.0.2"), c.at("relay.relays").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(c.at("relay.use_relays").jsonPrimitive.boolean)
    }

    @Test
    fun lighthouse() {
        val c = render(Nebula.ROLE_LIGHTHOUSE)
        c.always()
        assertTrue(c.at("lighthouse.am_lighthouse").jsonPrimitive.boolean)
        assertEquals(0, c.at("lighthouse.hosts").jsonArray.size)
        assertEquals(4242, c.at("listen.port").jsonPrimitive.int)
        assertEquals(0, c.at("tun.unsafe_routes").jsonArray.size)
        assertEquals(false, c.at("relay.use_relays").jsonPrimitive.boolean)
        assertEquals(0, c.at("relay.relays").jsonArray.size)
    }

    @Test
    fun both() {
        val c = render(Nebula.ROLE_BOTH)
        c.always()
        assertTrue(c.at("lighthouse.am_lighthouse").jsonPrimitive.boolean)
        assertEquals(0, c.at("lighthouse.hosts").jsonArray.size)
        assertEquals(4242, c.at("listen.port").jsonPrimitive.int)
        assertEquals(2, c.at("tun.unsafe_routes").jsonArray.size)
        assertTrue(c.at("relay.use_relays").jsonPrimitive.boolean)
    }

    @Test
    fun keyNetworkAndCode() {
        val pem = Nebula.privateKeyPem(ByteArray(32))
        assertEquals(
            "-----BEGIN NEBULA X25519 PRIVATE KEY-----\nAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n-----END NEBULA X25519 PRIVATE KEY-----\n",
            pem,
        )
        assertEquals("10.90.0.0/16", Nebula.parse(enrollNebula).network)
        val ca = "ab".repeat(32)
        assertTrue(MeshEnroll.parseCode("transwarp://enroll?u=https%3A%2F%2Fx&t=a&ca=$ca&k=nebula")!!.nebula)
        assertEquals(false, MeshEnroll.parseCode("transwarp://enroll?u=https%3A%2F%2Fx&t=a&ca=$ca")!!.nebula)
        // the placeholder conf keeps the key and the renew/expiry headers through the parser
        val conf = MeshEnroll.nebulaPlaceholder("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", "10.90.200.7/16", "zz-neb", "2027-11-16T00:00:00Z")
        val stored = com.wgtunnel.parser.Config.parseQuickString(conf).asQuickString()
        assertEquals("zz-neb", MeshEnroll.header(stored, "Name"))
        assertTrue(MeshEnroll.expiresInDays(stored, 0)!! > 0)
        assertEquals("yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", com.wgtunnel.parser.Config.parseQuickString(stored).`interface`.privateKey)
    }

    // nebmobile Status() hosts become Sensor Array peers: cert name, current remote or relay, counters
    @Test
    fun statusAsActiveConfig() {
        val st = """{"running":true,"hosts":[
            {"vpn_addrs":["10.90.0.1"],"name":"pi-5-1","remote":"10.0.1.201:4242","rx_bytes":1200,"tx_bytes":800,"last_rx":1700000000},
            {"vpn_addrs":["10.90.200.4"],"name":"trona-tablet","relays":["10.90.0.1"],"rx_bytes":0,"tx_bytes":0}]}"""
        val a = Nebula.activeConfig(st, "10.90.200.7/16", 1700000099)
        assertEquals("10.90.200.7/16", a.interfaceSection.address)
        val (hub, tab) = a.peers
        assertEquals(listOf("pi-5-1", "10.90.0.1/32", "10.0.1.201:4242"), listOf(hub.publicKey, hub.allowedIPs, hub.endpoint))
        assertEquals(listOf(1200L, 800L, 1700000000L), listOf(hub.rxBytes, hub.txBytes, hub.lastHandshakeSeconds))
        assertEquals("relay 10.90.0.1", tab.endpoint)
        assertEquals(1700000099L, tab.lastHandshakeSeconds) // idle but established: "now"
    }

    // Phase 32 exit: gofire's 0.0.0.0/0 route is kept only with Full Warp on, and never invented without a grant
    @Test
    fun fullWarpFollowsExitGrant() {
        val granted = Nebula.parse(enrollNebula).let { it.copy(routes = it.routes + Nebula.Route("0.0.0.0/0", "10.90.0.1")) }
        assertTrue(granted.hasExit)
        assertEquals(3, granted.forRun(true).routes.size)
        assertEquals(listOf("10.89.0.0/16", "10.0.0.0/16"), granted.forRun(false).routes.map { it.cidr })
        val plain = Nebula.parse(enrollNebula)
        assertEquals(plain.routes, plain.forRun(true).routes)
        val routes = Json.parseToJsonElement(Nebula.yaml(granted.forRun(true), key)).jsonObject.at("tun.unsafe_routes").jsonArray
        assertTrue(routes.any { it.jsonObject["route"]!!.jsonPrimitive.content == "0.0.0.0/0" })
    }

    // Phase 33: a v2 profile's ip6 routes its /64; a v1 profile (5.13.x row, no ip6) still parses and has none
    @Test
    fun ipv6Overlay() {
        val v2 = Nebula.parse(enrollNebula.replace("\"role\"", "\"ip6\":\"fdee:6e65:6275:6c61::c8:7/64\",\"role\""))
        assertEquals("fdee:6e65:6275:6c61:0:0:0:0/64", v2.network6)
        assertEquals(null, Nebula.parse(enrollNebula).network6)
        assertEquals(2, Json.parseToJsonElement(Nebula.yaml(v2, key)).jsonObject.at("pki.initiating_version").jsonPrimitive.int)
        assertEquals(null, Json.parseToJsonElement(Nebula.yaml(Nebula.parse(enrollNebula), key)).jsonObject.at("pki").jsonObject["initiating_version"])
        assertEquals("10.90.0.0/16", Nebula.networkOf("10.90.200.7/16"))
        val a = Nebula.activeConfig("""{"hosts":[{"vpn_addrs":["10.90.0.1","fdee:6e65:6275:6c61::1"],"name":"pi-5-1"}]}""", v2.ip, 1)
        assertEquals("10.90.0.1/32, fdee:6e65:6275:6c61::1/128", a.peers[0].allowedIPs)
    }

    // Phase 34: preferred_ranges render top level; renew due at min(6 h, half-life); a cert-only renew reloads in place
    @Test
    fun renewAndPreferredRanges() {
        val p = Nebula.parse(enrollNebula.replace("\"role\"", "\"preferred_ranges\":[\"10.0.0.0/16\",\"192.168.0.0/16\"],\"role\""))
        assertEquals(listOf("10.0.0.0/16", "192.168.0.0/16"),
            Json.parseToJsonElement(Nebula.yaml(p, key)).jsonObject["preferred_ranges"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(null, Json.parseToJsonElement(Nebula.yaml(Nebula.parse(enrollNebula), key)).jsonObject["preferred_ranges"])
        val h = 3_600_000L
        assertEquals(6 * h, Nebula.renewDueAt(0, 90 * 24 * h)) // 90 d TTL: the 6 h refresh
        assertEquals(2 * h, Nebula.renewDueAt(0, 4 * h)) // short TTL: half the time left
        assertEquals(6 * h, Nebula.renewDueAt(0, null)) // never expires: refresh only
        assertEquals(60_000L, Nebula.renewDueAt(0, -h)) // expired / clock skew: no busy loop
        assertTrue(p.sameVpn(p.copy(certPem = "NEW", caPem = "NEWCA", preferredRanges = emptyList())))
        assertTrue(!p.sameVpn(p.copy(routes = p.routes.drop(1))))
        assertTrue(!p.sameVpn(p.copy(ip6 = "fdee:6e65:6275:6c61::c8:7/64"))) // v1 -> v2 renew restarts (new address)
        assertEquals("10.90.200.7/16", p.addresses)
    }

    // Phase 35: firewall_inbound verbatim (none = allow all); the exit pick re-routes 0.0.0.0/0 and survives a renew
    @Test
    fun firewallAndExitPicker() {
        val exitJson = enrollNebula.replace("\"routes\":[", "\"routes\":[{\"cidr\":\"0.0.0.0/0\",\"via\":\"10.90.0.1\"},")
            .replace("\"role\"", "\"firewall_inbound\":[{\"proto\":\"icmp\",\"port\":\"any\",\"host\":\"any\"},{\"proto\":\"tcp\",\"port\":\"7449\",\"group\":\"family\"}]," +
                "\"exits\":[{\"name\":\"pi-5-1\",\"ip\":\"10.90.0.1\"},{\"name\":\"zz-exit-vm\",\"ip\":\"10.90.200.12\"}],\"role\"")
        val p = Nebula.parse(exitJson)
        val inbound = Json.parseToJsonElement(Nebula.yaml(p, key)).jsonObject.at("firewall.inbound").jsonArray
        assertEquals(listOf("icmp", "tcp"), inbound.map { it.jsonObject["proto"]!!.jsonPrimitive.content })
        assertEquals("family", inbound[1].jsonObject["group"]!!.jsonPrimitive.content)
        assertEquals("any", render("client").at("firewall.inbound").jsonArray.single().jsonObject["host"]!!.jsonPrimitive.content)
        fun via(q: Nebula.Profile) = Json.parseToJsonElement(Nebula.yaml(q, key)).jsonObject.at("tun.unsafe_routes").jsonArray
            .map { it.jsonObject }.firstOrNull { it["route"]!!.jsonPrimitive.content == "0.0.0.0/0" }?.get("via")?.jsonPrimitive?.content
        assertEquals("10.90.0.1", via(p.forRun(true))) // default: the hub
        val picked = p.copy(exitVia = "10.90.200.12")
        assertEquals("10.90.200.12", via(picked.forRun(true)))
        assertEquals(null, via(picked.forRun(false))) // Full Warp off: no default route at all
        assertEquals("10.90.0.1", via(picked.copy(exits = p.exits.take(1)).forRun(true))) // exit withdrawn: back to the hub
        assertEquals("10.90.200.12", Nebula.parse(exitJson).withChoicesOf(Nebula.parse(Nebula.encode(picked))).exitVia)
    }
}
