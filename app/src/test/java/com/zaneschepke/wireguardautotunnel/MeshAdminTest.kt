package com.zaneschepke.wireguardautotunnel

import com.zaneschepke.wireguardautotunnel.util.network.MeshAdmin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshAdminTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun requestBuildingAndParsing() {
        // nebula: kind + groups, no peers flag (the hub 400s it); WG "never" omits ttl (= no expiry)
        val neb = obj(MeshAdmin.NewClient("zz-a", nebula = true, lan = true, groups = listOf("laptops"), ttl = "7d").json())
        assertEquals("nebula", neb["kind"]!!.jsonPrimitive.content)
        assertNull(neb["peers"])
        assertEquals("[\"laptops\"]", neb["groups"].toString())
        assertEquals("7d", neb["ttl"]!!.jsonPrimitive.content)
        val wg = obj(MeshAdmin.NewClient("zz-b", peers = true, ttl = "never").json())
        assertEquals("wireguard", wg["kind"]!!.jsonPrimitive.content)
        assertNull(wg["ttl"])
        assertEquals("true", wg["peers"]!!.jsonPrimitive.content)
        // path segments are encoded so a name can't reach another route
        assertEquals("/v1/mesh/admin/clients/a%2F..%2Fb/renew", MeshAdmin.path("clients", "a/../b", "renew"))
        assertEquals("{\"from_group\":\"any\",\"to_group\":\"g\",\"proto\":\"any\",\"port\":\"7449\"}",
            MeshAdmin.ruleJson("any", "g", "", "7449"))

        val cs = MeshAdmin.parseClients("""[
            {"name":"zz-b","kind":"wireguard","mesh_ip":"10.89.200.9","peers":true,"exit":false,"lan":true,"admin":true,
             "expires_at":null,"enrolled_at":"2026-11-17T00:00:00Z"},
            {"name":"zz-a","kind":"nebula","mesh_ip":"10.89.200.8","groups":["g"],"expires_at":"2026-11-18T00:00:00Z","enrolled_at":null}]""")
        assertEquals(listOf("zz-a", "zz-b"), cs.map { it.name })
        assertTrue(cs[0].nebula); assertFalse(cs[0].enrolled); assertEquals(listOf("g"), cs[0].groups)
        assertTrue(cs[1].admin && cs[1].lan && cs[1].peers && cs[1].enrolled)
        assertEquals("transwarp://enroll?u=x&t=y", MeshAdmin.codeOf("""{"name":"zz-a","code":"transwarp://enroll?u=x&t=y"}"""))
        assertEquals(listOf(true, false), MeshAdmin.parseServices(
            """[{"name":"grafana","url":"http://g","kind":"web","source":"manual"},{"name":"s","url":"http://s","source":"stack"}]""").map { it.second })
        assertEquals("02:00:00:00:00:02", MeshAdmin.parseWol("""[{"name":"w","mac":"02:00:00:00:00:02"}]""")[0].mac)
    }

    @Test
    fun guestPresetIs24hWithNoLanExitOrPeers() {
        for (nebula in listOf(true, false)) {
            val g = obj(MeshAdmin.guest("zz-guest-520", nebula).json())
            assertEquals("24h", g["ttl"]!!.jsonPrimitive.content)
            assertEquals("false", g["lan"]!!.jsonPrimitive.content)
            assertEquals("false", g["exit"]!!.jsonPrimitive.content)
            assertTrue(g["peers"] == null || g["peers"]!!.jsonPrimitive.content == "false")
            assertNull(g["groups"])
        }
    }
}
