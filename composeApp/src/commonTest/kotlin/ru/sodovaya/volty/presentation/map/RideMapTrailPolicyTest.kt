package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RideMapTrailPolicyTest {
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
