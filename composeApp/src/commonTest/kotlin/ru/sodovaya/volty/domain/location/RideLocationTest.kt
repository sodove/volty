package ru.sodovaya.volty.domain.location

import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RideLocationTest {
    @Test
    fun location_fix_rejects_non_finite_or_negative_accuracy() {
        assertFailsWith<IllegalArgumentException> { testFix(accuracyMeters = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { testFix(accuracyMeters = -0.1) }
    }

    @Test
    fun location_fix_rejects_non_finite_or_negative_speed() {
        assertFailsWith<IllegalArgumentException> {
            testFix(speedMetersPerSecond = Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            testFix(speedMetersPerSecond = -0.1)
        }
    }

    @Test
    fun location_fix_rejects_non_finite_or_out_of_range_bearing() {
        assertFailsWith<IllegalArgumentException> {
            testFix(bearingDegrees = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            testFix(bearingDegrees = 360.0)
        }
    }

    @Test
    fun location_fix_preserves_raw_platform_fields() {
        val fix = testFix(
            speedMetersPerSecond = 4.5,
            bearingDegrees = 271.0,
            elapsedRealtimeMillis = 8_000L,
            source = LocationSource.GPS,
        )

        assertEquals(GeoCoordinate(56.8, 60.6), fix.coordinate)
        assertEquals(4.5, fix.speedMetersPerSecond)
        assertEquals(271.0, fix.bearingDegrees)
        assertEquals(8_000L, fix.elapsedRealtimeMillis)
        assertEquals(LocationSource.GPS, fix.source)
    }

    @Test
    fun location_state_defaults_to_not_requested_without_demands() {
        assertEquals(RideLocationStatus.NotRequested, RideLocationState().status)
        assertEquals(emptySet(), RideLocationState().demands)
    }

    private fun testFix(
        accuracyMeters: Double = 5.0,
        speedMetersPerSecond: Double? = null,
        bearingDegrees: Double? = null,
        elapsedRealtimeMillis: Long? = null,
        source: LocationSource = LocationSource.NETWORK,
    ) = RideLocationFix(
        coordinate = GeoCoordinate(56.8, 60.6),
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        bearingDegrees = bearingDegrees,
        capturedAtEpochMillis = 10_000L,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        source = source,
    )
}
