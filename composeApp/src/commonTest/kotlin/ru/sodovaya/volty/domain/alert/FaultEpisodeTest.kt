package ru.sodovaya.volty.domain.alert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class FaultEpisodeTest {
    private val t0 = Instant.fromEpochSeconds(1_000)

    @Test
    fun theGapIsTwoSeconds() {
        assertEquals(2.seconds, EPISODE_GAP)
    }

    @Test
    fun anEpisodeContinuesUntilTwoSecondsOfAbsence() {
        assertFalse(startsNewEpisode(null, t0))
        assertFalse(startsNewEpisode(t0, t0 + 1_999.milliseconds))
        assertTrue(startsNewEpisode(t0, t0 + 2_000.milliseconds))
    }
}
