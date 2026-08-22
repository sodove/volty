package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RideMapLocationPolicyTest {
    @Test
    fun map_does_not_discard_sub_meter_or_sub_second_fixes() {
        assertEquals(100L, defaultRideMapLocationUpdatePolicy.minIntervalMillis)
        assertEquals(0f, defaultRideMapLocationUpdatePolicy.minDistanceMeters)
        assertEquals(16L, defaultRideMapLocationUpdatePolicy.renderIntervalMillis)
    }

    @Test
    fun visual_prediction_is_bounded_when_a_fix_is_late() {
        val capped = predictRideMapCoordinate(
            latitude = 56.8,
            longitude = 60.6,
            speedMetersPerSecond = 10f,
            bearingDegrees = 90f,
            ageMillis = defaultRideMapLocationUpdatePolicy.maxPredictionAgeMillis,
        )
        val late = predictRideMapCoordinate(
            latitude = 56.8,
            longitude = 60.6,
            speedMetersPerSecond = 10f,
            bearingDegrees = 90f,
            ageMillis = 10_000L,
        )

        assertEquals(capped.latitude, late.latitude, absoluteTolerance = 0.000001)
        assertEquals(capped.longitude, late.longitude, absoluteTolerance = 0.000001)
    }

    @Test
    fun visual_prediction_follows_the_last_fix_in_motion() {
        val predicted = predictRideMapCoordinate(
            latitude = 56.8,
            longitude = 60.6,
            speedMetersPerSecond = 10f,
            bearingDegrees = 90f,
            ageMillis = 1_000L,
        )

        assertEquals(56.8, predicted.latitude, absoluteTolerance = 0.000001)
        assertTrue(predicted.longitude > 60.6)
    }
}
