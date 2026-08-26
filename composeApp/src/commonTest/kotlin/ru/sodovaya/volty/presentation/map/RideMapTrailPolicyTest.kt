package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RideMapTrailPolicyTest {
    @Test
    fun disconnected_newest_point_does_not_hide_older_connected_segments() {
        val samples = listOf(
            sample(timestampMillis = 1_000L, longitude = 60.6000),
            sample(timestampMillis = 2_000L, longitude = 60.6001),
            sample(timestampMillis = 3_000L, longitude = 60.6002),
            sample(timestampMillis = 4_000L, longitude = 60.6020),
        )

        val segments = connectedRideMapTrailSegments(samples)

        assertEquals(2, segments.size)
        assertEquals(3, segments.first().size)
        assertEquals(1, segments.last().size)
        assertEquals(60.6002, segments.first().last().longitude)
    }

    @Test
    fun trail_does_not_connect_a_large_unreported_gps_jump() {
        val previous = sample(timestampMillis = 1_000L, longitude = 60.6000)
        val current = sample(timestampMillis = 2_000L, longitude = 60.6020)

        assertFalse(shouldConnectRideMapTrail(previous, current))
    }

    @Test
    fun trail_keeps_a_high_speed_fix_connected_when_distance_is_expected() {
        val previous = sample(timestampMillis = 1_000L, longitude = 60.6000, speedMetersPerSecond = 30f)
        val current = sample(timestampMillis = 2_000L, longitude = 60.6003, speedMetersPerSecond = 30f)

        assertTrue(shouldConnectRideMapTrail(previous, current))
    }

    @Test
    fun trail_does_not_connect_after_a_stale_fix_gap() {
        val previous = sample(timestampMillis = 1_000L)
        val current = sample(timestampMillis = 6_001L, longitude = 60.6001)

        assertFalse(shouldConnectRideMapTrail(previous, current))
    }

    private fun sample(
        timestampMillis: Long,
        latitude: Double = 56.8,
        longitude: Double = 60.6,
        speedMetersPerSecond: Float? = null,
        accuracyMeters: Float? = 5f,
    ) = RideMapTrailSample(
        latitude = latitude,
        longitude = longitude,
        timestampMillis = timestampMillis,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
    )
}
