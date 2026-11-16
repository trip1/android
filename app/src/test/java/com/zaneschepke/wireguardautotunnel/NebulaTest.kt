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
}
