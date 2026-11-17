package com.zaneschepke.wireguardautotunnel

import com.zaneschepke.wireguardautotunnel.util.network.MeshDirectory
import org.junit.Assert.assertEquals
import org.junit.Test

class MeshDirectoryTest {
    private val json = """
        {"self":{"name":"zz-emu","mesh_ip":"10.89.200.5","expires_at":null,"peers":true,"exit":false,"lan":true},
         "devices":[
          {"name":"pi-5-1","mesh_ip":"10.89.0.1","kind":"node","online":true,"public_key":"N","endpoint":""},
          {"name":"zz-emu","mesh_ip":"10.89.200.5","kind":"client","online":true,"public_key":"S","endpoint":"1.1.1.1:5"},
          {"name":"laptop","mesh_ip":"10.89.200.6","kind":"client","online":true,"public_key":"L","endpoint":"2.2.2.2:51820"},
          {"name":"relay","mesh_ip":"10.89.200.7","kind":"client","online":true,"public_key":"R","endpoint":""},
          {"name":"off","mesh_ip":"10.89.200.8","kind":"client","online":false,"public_key":"O","endpoint":"3.3.3.3:1"}]}
    """

    @Test
    fun directPeersOnlyForOnlineClientsWithEndpoint() {
        val dir = MeshDirectory.parse(json)
        val peers = MeshDirectory.directPeers(dir, onRelay = false, failed = emptyMap(), now = 0)
        assertEquals(listOf("L"), peers.map { it.publicKey })
        assertEquals("10.89.200.6/32", peers[0].allowedIPs)
        assertEquals("2.2.2.2:51820", peers[0].endpoint)
        assertEquals("25", peers[0].persistentKeepalive)
        // never while on the WebSocket relay
        assertEquals(emptyList<String>(), MeshDirectory.directPeers(dir, true, emptyMap(), 0).map { it.publicKey })
        // failed endpoint: skipped for 5 min, retried after, or at once when the endpoint changes
        val failed = mapOf("L" to ("2.2.2.2:51820" to 1_000L))
        assertEquals(0, MeshDirectory.directPeers(dir, false, failed, 1_000L + MeshDirectory.RETRY_MS - 1).size)
        assertEquals(1, MeshDirectory.directPeers(dir, false, failed, 1_000L + MeshDirectory.RETRY_MS).size)
        assertEquals(1, MeshDirectory.directPeers(dir, false, mapOf("L" to ("9.9.9.9:1" to 1_000L)), 1_001L).size)
        // self not a peers client: nothing
        assertEquals(0, MeshDirectory.directPeers(dir.copy(selfPeers = false), false, emptyMap(), 0).size)
    }

    @Test
    fun parsesServicesWolAndAddresses() {
        val d = MeshDirectory.parse("""
            {"self":{"mesh_ip":"10.90.200.7"},
             "devices":[{"name":"ws","mesh_ip":"10.89.200.9","kind":"client","addresses":["fd00::9/128","10.89.200.9/32"]},
                        {"name":"neb","mesh_ip":"10.90.200.8","kind":"nebula","ip6":"fdee::8"}],
             "services":[{"name":"Grafana","url":"http://grafana.mesh:3000","kind":"app"},{"name":"bad","url":"javascript:x"}],
             "wol":[{"name":"nas","mac":"aa:bb:cc:dd:ee:ff"}]}
        """)
        assertEquals(listOf(MeshDirectory.Service("Grafana", "http://grafana.mesh:3000", "app")), d.services)
        assertEquals(listOf(MeshDirectory.Wol("nas", "aa:bb:cc:dd:ee:ff")), d.wol)
        // protocol-agnostic Beam target: v4 first, prefix stripped; old hubs fall back to mesh_ip + ip6
        assertEquals("10.89.200.9", d.devices[0].reachable)
        assertEquals(listOf("10.90.200.8", "fdee::8"), d.devices[1].addresses)
        assertEquals(0, MeshDirectory.parse(json).services.size)
    }

    @Test
    fun pathClassification() {
        val names = mapOf("10.90.0.2" to "mini-1")
        assertEquals(MeshDirectory.PathKind.RELAYED to "mini-1", MeshDirectory.pathOf("relay 10.90.0.2", false, names))
        assertEquals(MeshDirectory.PathKind.RELAYED to "10.90.0.9", MeshDirectory.pathOf("relay 10.90.0.9", false, names))
        assertEquals(MeshDirectory.PathKind.DIRECT to null, MeshDirectory.pathOf("192.168.1.5:4242", false, names))
        assertEquals(MeshDirectory.PathKind.LIGHTHOUSE to null, MeshDirectory.pathOf("1.2.3.4:4242", true, names))
        assertEquals(MeshDirectory.PathKind.UNKNOWN to null, MeshDirectory.pathOf(null, false, names))
        assertEquals(80.0, MeshDirectory.mbps(10_000_000, 1.0), 1e-9)
    }
}
