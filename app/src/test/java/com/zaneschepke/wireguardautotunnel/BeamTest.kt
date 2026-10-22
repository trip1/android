package com.zaneschepke.wireguardautotunnel

import com.zaneschepke.wireguardautotunnel.util.network.Beam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeamTest {
    @Test
    fun sanitizesToBasename() {
        assertEquals("photo 1.jpg", Beam.sanitizeFileName("/photo%201.jpg"))
        assertEquals("passwd", Beam.sanitizeFileName("/..%2F..%2Fetc%2Fpasswd"))
        assertEquals("evil.sh", Beam.sanitizeFileName("/a\\..\\evil.sh"))
        assertEquals("hidden", Beam.sanitizeFileName("/.hidden"))
        assertEquals("ab.txt", Beam.sanitizeFileName("/a%00b.txt?x=1"))
        assertNull(Beam.sanitizeFileName("/"))
        assertNull(Beam.sanitizeFileName("/.."))
    }

    @Test
    fun parsesRequestLine() {
        assertEquals(Beam.Request("PUT", "/x.txt"), Beam.parseRequestLine("PUT /x.txt HTTP/1.1\r"))
        assertEquals(Beam.Request("POST", "/"), Beam.parseRequestLine("POST / HTTP/1.0"))
        assertNull(Beam.parseRequestLine("GET /x HTTP/1.1"))
        assertNull(Beam.parseRequestLine("PUT x HTTP/1.1"))
        assertNull(Beam.parseRequestLine("PUT /a b HTTP/1.1"))
        assertNull(Beam.parseRequestLine("garbage"))
    }

    @Test
    fun interfaceAddresses() {
        assertEquals(listOf("10.0.0.2", "fd00::2"), Beam.interfaceAddresses("10.0.0.2/32, fd00::2/128"))
    }
}
