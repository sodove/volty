package ru.sodovaya.volty.presentation.map

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RideMapMotionEstimatorTest {
    @Test
    fun session_timestamp_normalizer_keeps_wall_and_elapsed_fixes_in_one_domain() {
        val normalizer = RideMapSessionTimestampNormalizer(
            wallClockStartMillis = 1_000_000L,
            elapsedRealtimeStartMillis = 5_000L,
        )

        assertEquals(
            1_001_000L,
            normalizer.normalize(
                timestampMillis = 6_000L,
                source = RideMapTimestampSource.ELAPSED_REALTIME,
            ),
        )
        assertEquals(
            1_000_500L,
            normalizer.normalize(
                timestampMillis = 1_000_500L,
                source = RideMapTimestampSource.WALL_CLOCK,
            ),
        )
    }

    @Test
    fun gps_correction_uses_the_longer_smoother_duration() {
        assertEquals(500L, defaultRideMapMotionEstimatorPolicy.correctionDurationMillis)
    }

    @Test
    fun prediction_uses_fix_measurement_time_and_speed() {
        val estimator = RideMapMotionEstimator()
        estimator.accept(fix(timestampMillis = 0L, speedMetersPerSecond = 10f, bearingDegrees = 90f))

        val estimate = checkNotNull(estimator.estimate(nowMillis = 1_000L))

        assertEquals(56.8, estimate.coordinate.latitude, absoluteTolerance = 0.000001)
        assertTrue(estimate.coordinate.longitude > 60.6)
    }

    @Test
    fun prediction_is_capped_after_gps_age_limit() {
        val estimator = RideMapMotionEstimator()
        estimator.accept(fix(timestampMillis = 0L, speedMetersPerSecond = 10f, bearingDegrees = 90f))

        val capped = checkNotNull(estimator.estimate(defaultRideMapMotionEstimatorPolicy.maxPredictionAgeMillis))
        val late = checkNotNull(estimator.estimate(nowMillis = 10_000L))

        assertEquals(capped.coordinate.latitude, late.coordinate.latitude, absoluteTolerance = 0.000001)
        assertEquals(capped.coordinate.longitude, late.coordinate.longitude, absoluteTolerance = 0.000001)
    }

    @Test
    fun late_fix_is_corrected_without_teleporting_the_display() {
        val estimator = RideMapMotionEstimator()
        estimator.accept(fix(timestampMillis = 0L, speedMetersPerSecond = 10f, bearingDegrees = 90f))
        val beforeCorrection = checkNotNull(estimator.estimate(nowMillis = 1_000L)).coordinate

        estimator.accept(
            fix(
                timestampMillis = 1_000L,
                longitude = 60.60025,
                speedMetersPerSecond = 10f,
                bearingDegrees = 90f,
            ),
        )
        val afterCorrection = checkNotNull(estimator.estimate(nowMillis = 1_000L)).coordinate

        assertTrue(afterCorrection.longitude < 60.60025)
        assertTrue(afterCorrection.longitude >= beforeCorrection.longitude - 0.000001)
    }

    @Test
    fun out_of_order_fix_is_rejected() {
        val estimator = RideMapMotionEstimator()
        assertTrue(estimator.accept(fix(timestampMillis = 1_000L, speedMetersPerSecond = 5f, bearingDegrees = 0f)))
        assertFalse(estimator.accept(fix(timestampMillis = 500L, speedMetersPerSecond = 50f, bearingDegrees = 180f)))

        val estimate = checkNotNull(estimator.estimate(nowMillis = 1_000L))
        assertEquals(0f, estimate.bearingDegrees)
    }

    @Test
    fun missing_speed_and_bearing_are_derived_from_consecutive_fixes() {
        val estimator = RideMapMotionEstimator()
        estimator.accept(fix(timestampMillis = 0L, speedMetersPerSecond = null, bearingDegrees = null))
        estimator.accept(
            fix(
                timestampMillis = 1_000L,
                longitude = 60.6001,
                speedMetersPerSecond = null,
                bearingDegrees = null,
            ),
        )

        val estimate = checkNotNull(estimator.estimate(nowMillis = 1_000L))

        assertTrue((estimate.speedMetersPerSecond ?: 0f) > 1f)
        assertTrue(abs((estimate.bearingDegrees ?: 0f) - 90f) < 15f)
    }

    @Test
    fun bearing_filter_takes_the_shortest_wraparound_path() {
        val estimator = RideMapMotionEstimator()
        estimator.accept(fix(timestampMillis = 0L, speedMetersPerSecond = 10f, bearingDegrees = 359f))
        estimator.accept(fix(timestampMillis = 1_000L, speedMetersPerSecond = 10f, bearingDegrees = 1f))

        val bearing = checkNotNull(checkNotNull(estimator.estimate(nowMillis = 1_000L)).bearingDegrees)

        assertTrue(bearing < 5f || bearing > 355f)
    }

    @Test
    fun stationary_fix_does_not_replace_the_last_trustworthy_bearing() {
        val estimator = RideMapMotionEstimator()
        estimator.accept(fix(timestampMillis = 0L, speedMetersPerSecond = 10f, bearingDegrees = 90f))
        estimator.accept(fix(timestampMillis = 1_000L, speedMetersPerSecond = 0f, bearingDegrees = 180f))

        val bearing = checkNotNull(checkNotNull(estimator.estimate(nowMillis = 1_000L)).bearingDegrees)

        assertEquals(90f, bearing, absoluteTolerance = 0.1f)
    }

    @Test
    fun a_valid_vehicle_speed_override_wins_over_stale_location_speed() {
        val estimator = RideMapMotionEstimator()
        estimator.accept(
            fix(
                timestampMillis = 0L,
                speedMetersPerSecond = 1f,
                bearingDegrees = 90f,
            ),
        )

        val locationSpeedEstimate = checkNotNull(estimator.estimate(nowMillis = 1_000L))
        val vehicleSpeedEstimate = checkNotNull(
            estimator.estimate(nowMillis = 1_000L, speedMetersPerSecondOverride = 10f),
        )

        assertTrue(vehicleSpeedEstimate.coordinate.longitude > locationSpeedEstimate.coordinate.longitude)
    }

    private fun fix(
        timestampMillis: Long,
        latitude: Double = 56.8,
        longitude: Double = 60.6,
        speedMetersPerSecond: Float?,
        bearingDegrees: Float?,
    ) = RideMapMotionFix(
        latitude = latitude,
        longitude = longitude,
        timestampMillis = timestampMillis,
        accuracyMeters = 3f,
        speedMetersPerSecond = speedMetersPerSecond,
        bearingDegrees = bearingDegrees,
    )
}
