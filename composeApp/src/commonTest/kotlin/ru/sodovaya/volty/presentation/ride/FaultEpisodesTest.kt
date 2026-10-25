package ru.sodovaya.volty.presentation.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class FaultEpisodesTest {
    private val t0 = Instant.fromEpochSeconds(1_000)

    @Test
    fun lingerOptionsMatchTheApprovedList() {
        assertEquals(listOf(0, 5, 10, 30, 60, 120, 300), LINGER_OPTIONS_SEC)
    }

    @Test
    fun everyOfferedLingerMapsToItself() {
        LINGER_OPTIONS_SEC.forEach { assertEquals(it, lingerOptionFor(it)) }
    }

    @Test
    fun zeroIsWhileActive() {
        assertEquals(0, lingerOptionFor(0))
        assertEquals(0, lingerOptionFor(-7))
    }

    @Test
    fun anUnlistedValueMapsToTheNearestOption() {
        assertEquals(30, lingerOptionFor(25))
        assertEquals(60, lingerOptionFor(90 - 31))
        assertEquals(120, lingerOptionFor(150))
        assertEquals(300, lingerOptionFor(9_999))
        assertEquals(5, lingerOptionFor(3))
    }

    @Test
    fun aTieGoesToTheShorterOption() {
        assertEquals(60, lingerOptionFor(90))
    }

    @Test
    fun anEpisodeContinuesUntilTwoSecondsOfAbsence() {
        assertFalse(startsNewEpisode(null, t0))
        assertFalse(startsNewEpisode(t0, t0 + 1_999.milliseconds))
        assertTrue(startsNewEpisode(t0, t0 + 2_000.milliseconds))
    }
}
