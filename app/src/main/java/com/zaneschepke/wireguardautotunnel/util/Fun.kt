package com.zaneschepke.wireguardautotunnel.util

import com.zaneschepke.wireguardautotunnel.data.entity.TravelSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sin

/** Speed test -> warp factor: cube root of Mbit/s, clamped to 1..9.99 (1 Mbit/s = warp 1, ~970 Mbit/s = 9.9). */
object Warp {
    fun factor(mbps: Double): Double = cbrt(mbps.coerceAtLeast(0.0)).coerceIn(1.0, 9.99)
}

/** Live mesh map layout in unit coordinates (-1..1): hubs at the centre, everything else on a ring. No physics. */
object MeshMapLayout {
    const val RING = 0.8f
    const val HUB_RING = 0.25f

    /** One position per hub, then one per ring node (ring node 0 at the top). */
    fun positions(hubs: Int, ring: Int): List<Pair<Float, Float>> =
        circle(hubs, if (hubs <= 1) 0f else HUB_RING) + circle(ring, RING)

    private fun circle(n: Int, r: Float) = List(n) { i ->
        val a = -PI / 2 + 2 * PI * i / n
        (r * cos(a)).toFloat() to (r * sin(a)).toFloat()
    }
}

/** Travel log totals + achievements, all from the local Room rows. */
object Travel {
    data class Stats(val bytes: Long, val hours: Double, val countries: Set<String>, val longestStreakDays: Int, val relayed: Boolean)

    enum class Achievement(val unlocked: (Stats) -> Boolean) {
        FIRST_SESSION({ it.hours > 0 || it.bytes > 0 }),
        FIRST_RELAYED({ it.relayed }),
        TEN_GB({ it.bytes >= 10L * 1_000_000_000 }),
        THREE_COUNTRIES({ it.countries.size >= 3 }),
        WEEK_STREAK({ it.longestStreakDays >= 7 }),
    }

    fun stats(sessions: List<TravelSession>, zone: ZoneId = ZoneId.systemDefault()) = Stats(
        bytes = sessions.sumOf { it.rxBytes + it.txBytes },
        hours = sessions.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) } / 3_600_000.0,
        countries = sessions.mapNotNull { it.country?.uppercase()?.takeIf(String::isNotBlank) }.toSet(),
        longestStreakDays = longestStreak(sessions.flatMap { days(it, zone) }.toSet()),
        relayed = sessions.any { it.relayed },
    )

    /** Every local calendar day a session touched. */
    private fun days(s: TravelSession, zone: ZoneId): List<LocalDate> {
        val a = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(maxOf(s.startMs, s.endMs)).atZone(zone).toLocalDate()
        return generateSequence(a) { it.plusDays(1) }.takeWhile { !it.isAfter(b) }.toList()
    }

    fun longestStreak(days: Set<LocalDate>): Int =
        days.filter { !days.contains(it.minusDays(1)) }
            .maxOfOrNull { start -> generateSequence(start) { it.plusDays(1) }.takeWhile(days::contains).count() } ?: 0
}

/** LAN party: guest names and which of them are still on the hub (only names this phone created). */
object Party {
    val WORDS = listOf("nova", "quasar", "pulsar", "comet", "nebula", "photon", "vortex", "ion")
    val TTLS = listOf("4h", "12h", "24h")
    // the hub allows 10 admin changes/min; 8 guests + the end-party deletes stay under it per action
    const val MAX_GUESTS = 8

    fun names(word: String, n: Int): List<String> {
        val w = word.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { WORDS.first() }.take(20)
        return (1..n.coerceIn(1, MAX_GUESTS)).map { "party-$w-$it" }
    }

    /** Tracked names still listed by the hub: what "End party" revokes. Never anything outside party-*. */
    fun revokeList(tracked: List<String>, onHub: List<String>): List<String> =
        tracked.filter { it.startsWith("party-") && it in onHub }

    fun encode(names: List<String>) = names.joinToString(",")

    fun decode(s: String?) = s.orEmpty().split(',').filter { it.isNotBlank() }
}
