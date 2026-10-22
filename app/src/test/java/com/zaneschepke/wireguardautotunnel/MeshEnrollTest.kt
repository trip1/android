package com.zaneschepke.wireguardautotunnel

import com.zaneschepke.wireguardautotunnel.util.network.MeshEnroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
