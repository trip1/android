package com.zaneschepke.wireguardautotunnel

import com.zaneschepke.wireguardautotunnel.util.network.MeshEnroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MeshEnrollTest {
    private val ca = "AB".repeat(32)

    @Test
    fun parsesCode() {
        val c = MeshEnroll.parseCode("transwarp://enroll?u=https%3A%2F%2F10.0.0.1%3A8443%2F&t=tok%2B1&ca=$ca")!!
        assertEquals("https://10.0.0.1:8443", c.baseUrl)
        assertEquals("tok+1", c.token)
        assertEquals("ab".repeat(32), c.caSha256)
        assertNull(MeshEnroll.parseCode("transwarp://enroll?u=http%3A%2F%2Fx&t=a&ca=$ca"))
        assertNull(MeshEnroll.parseCode("transwarp://enroll?u=https%3A%2F%2Fx&t=a&ca=123"))
        assertNull(MeshEnroll.parseCode("transwarp://enroll?u=https%3A%2F%2Fx&ca=$ca"))
        assertNull(MeshEnroll.parseCode("wg://x"))
    }

    @Test
    fun peerNamesSurviveTheParser() {
        val key = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="
        val conf =
            MeshEnroll.hoistPeerNames(
                "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress = 10.8.0.2/32\n\n" +
                    "[Peer]\n# Name = pi-5-1\nPublicKey = $key\nAllowedIPs = 10.8.0.1/32\n"
            )
        val stored = com.wgtunnel.parser.Config.parseQuickString(conf)
        val header = com.wgtunnel.parser.Config.parseQuickString(stored.asQuickString()).headerComments
        assertEquals("pi-5-1", MeshEnroll.peerName(header, key))
        assertNull(MeshEnroll.peerName(header, "other="))
    }

    @Test
    fun endpointLadderSurvivesStorageAndSwapsOnlyTheHub() {
        val hub = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="
        val conf =
            MeshEnroll.hoistPeerNames(
                "[Interface]\n# Transwarp-Endpoints = 1.2.3.4:51820, 1.2.3.4:443, wss://x.example/.well-known/transwarp\n" +
                    "PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress = 10.8.0.2/32\n\n" +
                    "[Peer]\nPublicKey = $hub\nEndpoint = 1.2.3.4:51820\nAllowedIPs = 10.8.0.1/32\n\n" +
                    "[Peer]\nPublicKey = TrMvSoP4jYQlY6RIzBgbssQqY3vxI2Pi+y71lOWWXX0=\nEndpoint = 5.6.7.8:51820\nAllowedIPs = 10.9.0.1/32\n"
            )
        // what importTunnelConfigs stores, read back
        val stored = com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig.fromConfig(
            com.wgtunnel.parser.Config.parseQuickString(conf), "t")
        val t = stored.copy(quickConfig = com.wgtunnel.parser.Config.parseQuickString(stored.quickConfig).asQuickString())
        assertEquals(listOf("1.2.3.4:51820", "1.2.3.4:443", "wss://x.example/.well-known/transwarp"), t.endpointLadder())
        assertEquals(listOf("127.0.0.1:5000", "5.6.7.8:51820"), t.getRunConfig("127.0.0.1:5000").peers.map { it.endpoint })
        assertEquals(listOf("1.2.3.4:51820", "5.6.7.8:51820"), t.getRunConfig().peers.map { it.endpoint })
        // plain tunnel: ladder is just its Endpoint, so the feature stays inert
        val plain = com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig(name = "p",
            quickConfig = "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress = 10.8.0.2/32\n\n[Peer]\nPublicKey = $hub\nEndpoint = 9.9.9.9:51820\nAllowedIPs = 0.0.0.0/0\n")
        assertEquals(listOf("9.9.9.9:51820"), plain.endpointLadder())
    }

    @Test
    fun exitHubHostSkipsDefaultRoutes() {
        assertEquals("10.89.0.1", com.zaneschepke.wireguardautotunnel.util.network.firstAllowedHost("0.0.0.0/0, ::/0, 10.89.0.1/32"))
        assertNull(com.zaneschepke.wireguardautotunnel.util.network.firstAllowedHost("0.0.0.0/0, ::/0"))
    }

    @Test
    fun insertsPrivateKeyAfterInterface() {
        val out = MeshEnroll.insertPrivateKey("# hi\n[Interface]\nAddress = 10.1.0.2/32\n\n[Peer]\nPublicKey = x", "PRIV")
        assertEquals("# hi\n[Interface]\nPrivateKey = PRIV\nAddress = 10.1.0.2/32\n\n[Peer]\nPublicKey = x", out)
    }


    private fun cert(pem: String) =
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.trimIndent().byteInputStream()) as java.security.cert.X509Certificate

    private val caPem = """
-----BEGIN CERTIFICATE-----
MIIBijCCATGgAwIBAgIUZtBEqz/yS2QVUXUCvSbQTjf1C/0wCgYIKoZIzj0EAwIw
EjEQMA4GA1UEAwwHdGVzdCBDQTAgFw0yNjEwMjIyMDQwNTNaGA8yMTI2MDkyODIw
NDA1M1owEjEQMA4GA1UEAwwHdGVzdCBDQTBZMBMGByqGSM49AgEGCCqGSM49AwEH
A0IABEXgP9GhcZUvtYtLYJXwm741kQDrkJhZyB8C8y76q/Dsn0pjP96P/l1+td1Q
9j/i3d5eNVWI6TjpSIzriMjwrXyjYzBhMB0GA1UdDgQWBBSnhhzSBJ1Okj9dVS0H
JuD2c2b/gjAfBgNVHSMEGDAWgBSnhhzSBJ1Okj9dVS0HJuD2c2b/gjAPBgNVHRMB
Af8EBTADAQH/MA4GA1UdDwEB/wQEAwICBDAKBggqhkjOPQQDAgNHADBEAiAFKyBh
KguOfCgr4TceK9kcn0ZONNq5jlZ5TDMdlHxo7QIgCTNYn6KxqZDF0bMaRqpXuaF7
9MkquCvx0QYpzEexMJA=
-----END CERTIFICATE-----
"""
    private val leafPem = """
-----BEGIN CERTIFICATE-----
MIIBjjCCATWgAwIBAgIUfAJFI9xeMupI85B/IyE3D4dE5sQwCgYIKoZIzj0EAwIw
EjEQMA4GA1UEAwwHdGVzdCBDQTAgFw0yNjEwMjIyMDQwNTNaGA8yMTI2MDkyODIw
NDA1M1owEjEQMA4GA1UEAwwHY29udHJvbDBZMBMGByqGSM49AgEGCCqGSM49AwEH
A0IABOMsRiN0vJ568cS1xPS/lHt/aMiTG7wPyWruv5vJUjXZYcwWAkKvYFfdCLLv
5+wAr8npGCZngy+jpDC7w6X14EijZzBlMBMGA1UdJQQMMAoGCCsGAQUFBwMBMA4G
A1UdDwEB/wQEAwIHgDAdBgNVHQ4EFgQUm0gjxPTKYj9DXofHGQ0AwaNvA98wHwYD
VR0jBBgwFoAUp4Yc0gSdTpI/XVUtBybg9nNm/4IwCgYIKoZIzj0EAwIDRwAwRAIg
KU7Ysxm6zYkiiYPfLVI62MLJ+BJ8fbgraZJ+97ifPsYCIFEd7zpYt00uyqgoQjbr
yX3Oc5Bfr7Ft6Cc8XpXvffPa
-----END CERTIFICATE-----
"""
    private val evilPem = """
-----BEGIN CERTIFICATE-----
MIIBejCCASGgAwIBAgIUfReXnfTgAk5cXRsKb0DXA/1LvIswCgYIKoZIzj0EAwIw
EjEQMA4GA1UEAwwHY29udHJvbDAgFw0yNjEwMjIyMDQwNTNaGA8yMTI2MDkyODIw
NDA1M1owEjEQMA4GA1UEAwwHY29udHJvbDBZMBMGByqGSM49AgEGCCqGSM49AwEH
A0IABB7/RXJxhgvLb/h7QIYUaNEpGD5P/qnMYUtYSdNVzABZEKVorrPZNIFix2Lf
BTMPOQ5E5ejF9t8Xuqe9koa5eXejUzBRMB0GA1UdDgQWBBRhtu0NE9hnGXLFld7b
9nAzbV7itDAfBgNVHSMEGDAWgBRhtu0NE9hnGXLFld7b9nAzbV7itDAPBgNVHRMB
Af8EBTADAQH/MAoGCCqGSM49BAMCA0cAMEQCIElUUvINgJUtVmV/qlaFlx1OWUFZ
/iiyZfHJgZ8a6SBAAiAMzzK8nVKkR6dqUAP3d+69TnM60DHVyEbPOSGSBJx1rA==
-----END CERTIFICATE-----
"""

    @Test
    fun pinRequiresLeafSignedByPinnedCa() {
        val ca = cert(caPem)
        val tm = MeshEnroll.PinnedTrustManager(MeshEnroll.sha256Hex(ca.encoded))
        tm.checkServerTrusted(arrayOf(cert(leafPem), ca), "ECDHE_ECDSA") // genuine chain passes
        // attacker appends the (public) real CA to its own cert: must fail
        assertThrows(java.security.cert.CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(cert(evilPem), ca), "ECDHE_ECDSA")
        }
        assertThrows(java.security.cert.CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(cert(leafPem)), "ECDHE_ECDSA")
        }
    }
}
