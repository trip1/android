package com.zaneschepke.wireguardautotunnel

import com.zaneschepke.wireguardautotunnel.domain.model.FULL_TUNNEL_ALLOWED_IPS
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class FullTunnelTest {
    private val conf =
        """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.8.0.2/32

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        AllowedIPs = 10.8.0.0/24
        Endpoint = 1.2.3.4:51820

        [Peer]
        PublicKey = TrMvSoP4jYQlY6RIzBgbssQqY3vxI2Pi+y71lOWWXX0=
        AllowedIPs = 10.9.0.0/24
        """
            .trimIndent()

    @Test
    fun exitNodeRoutesAllPeersAndOffIsUnchanged() {
        val off = TunnelConfig(name = "t", quickConfig = conf)
        assertEquals(listOf("10.8.0.0/24", "10.9.0.0/24"), off.getRunConfig().peers.map { it.allowedIPs })
        val on = off.copy(isExitNode = true).getRunConfig()
        assertEquals(listOf(FULL_TUNNEL_ALLOWED_IPS, FULL_TUNNEL_ALLOWED_IPS), on.peers.map { it.allowedIPs })
        assertEquals(off.getConfig().peers.map { it.endpoint }, on.peers.map { it.endpoint })
    }
}
