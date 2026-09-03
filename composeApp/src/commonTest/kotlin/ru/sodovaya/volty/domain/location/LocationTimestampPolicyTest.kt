package ru.sodovaya.volty.domain.location

import kotlin.test.Test
import kotlin.test.assertEquals

class LocationTimestampPolicyTest {
    @Test
    fun `last known location keeps its original age`() {
        assertEquals(
            90_000L,
            LocationTimestampPolicy.capturedAtForLastKnown(
                lastKnownEpochMillis = 90_000L,
                nowEpochMillis = 100_000L,
            ),
        )
    }

    @Test
    fun `a clock-skewed future last known location is not published from the future`() {
        assertEquals(
            100_000L,
            LocationTimestampPolicy.capturedAtForLastKnown(
                lastKnownEpochMillis = 110_000L,
                nowEpochMillis = 100_000L,
            ),
        )
    }
}
