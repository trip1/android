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
