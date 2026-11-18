package com.zaneschepke.wireguardautotunnel

import com.zaneschepke.wireguardautotunnel.data.entity.TravelSession
import com.zaneschepke.wireguardautotunnel.util.MeshMapLayout
import com.zaneschepke.wireguardautotunnel.util.Party
import com.zaneschepke.wireguardautotunnel.util.Travel
import com.zaneschepke.wireguardautotunnel.util.Warp
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunTest {
    @Test
    fun warpFactor() {
        assertEquals(1.0, Warp.factor(0.0), 1e-9)
        assertEquals(1.0, Warp.factor(-5.0), 1e-9)
        assertEquals(2.0, Warp.factor(8.0), 1e-9)
        assertEquals(5.0, Warp.factor(125.0), 1e-9)
        assertEquals(9.99, Warp.factor(5000.0), 1e-9)
    }

    private val day = 86_400_000L

    private fun s(dayN: Int, hours: Int, gb: Double = 0.0, country: String? = null, relayed: Boolean = false) =
        TravelSession(startMs = dayN * day + 3_600_000, endMs = dayN * day + 3_600_000 + hours * 3_600_000L, tunnelName = "t",
            network = "Wi-Fi", country = country, rxBytes = (gb * 1e9).toLong(), relayed = relayed)

    @Test
    fun travelStatsStreakAchievements() {
        val utc = ZoneOffset.UTC
        // days 0-6 = 7-day streak, gap, day 10; a session crossing midnight counts both days
        val week = (0..6).map { s(it, 1, gb = 1.0, country = listOf("au", "NZ", "jp")[it % 3]) } + s(10, 30, relayed = true)
        val st = Travel.stats(week, utc)
        assertEquals(7, st.longestStreakDays)
        assertEquals(setOf("AU", "NZ", "JP"), st.countries)
        assertEquals(37.0, st.hours, 1e-9)
        assertEquals(7_000_000_000L, st.bytes)
        assertEquals(Travel.Achievement.entries.toSet() - Travel.Achievement.TEN_GB,
            Travel.Achievement.entries.filter { it.unlocked(st) }.toSet())
        assertTrue(Travel.Achievement.TEN_GB.unlocked(Travel.stats(week + s(20, 1, gb = 3.5), utc)))
        // 30 h from day 10 01:00 ends day 11 07:00: a 2-day streak on its own
        assertEquals(2, Travel.stats(listOf(s(10, 30)), utc).longestStreakDays)
        assertEquals(0, Travel.stats(emptyList(), utc).longestStreakDays)
        assertTrue(Travel.Achievement.entries.none { it.unlocked(Travel.stats(emptyList(), utc)) })
    }

    @Test
    fun partyNamesAndRevokeList() {
        assertEquals(listOf("party-nova-1", "party-nova-2"), Party.names("Nova!", 2))
        assertEquals(Party.MAX_GUESTS, Party.names("x", 99).size)
        assertEquals(listOf("party-nova-1"), Party.names("", 0))
        val tracked = Party.decode(Party.encode(listOf("party-nova-1", "party-nova-2", "pixel-neb")))
        // only tracked party-* names the hub still lists; a real client is never revoked even if tracked by mistake
        assertEquals(listOf("party-nova-2"), Party.revokeList(tracked, listOf("party-nova-2", "pixel-neb", "party-other-1")))
        assertEquals(emptyList<String>(), Party.decode(""))
    }

    @Test
    fun mapLayout() {
        val one = MeshMapLayout.positions(1, 4)
        assertEquals(5, one.size)
        assertEquals(0f, hypot(one[0].first, one[0].second), 1e-6f) // a single hub sits in the centre
        one.drop(1).forEach { (x, y) -> assertEquals(MeshMapLayout.RING, hypot(x, y), 1e-4f) }
        assertTrue(abs(one[1].first) < 1e-4f && one[1].second < 0) // ring node 0 (this device) at the top
        val two = MeshMapLayout.positions(2, 3)
        two.take(2).forEach { (x, y) -> assertEquals(MeshMapLayout.HUB_RING, hypot(x, y), 1e-4f) }
        assertEquals(two[0].second, -two[1].second, 1e-4f)
    }
}
