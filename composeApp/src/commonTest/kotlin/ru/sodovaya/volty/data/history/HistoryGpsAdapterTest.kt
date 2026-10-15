package ru.sodovaya.volty.data.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import ru.sodovaya.volty.domain.history.HistoryTick
import ru.sodovaya.volty.domain.history.HistoryGpsProvider
import ru.sodovaya.volty.domain.location.LocationSource
import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.navigation.GeoCoordinate

class HistoryGpsAdapterTest {
    private val origin = HistoryTick(epochMillis = 50_000L, elapsedMillis = 1_000L, clockGeneration = 7L)
    private val now = HistoryTick(epochMillis = 900_000L, elapsedMillis = 3_000L, clockGeneration = 7L)

    @Test fun actualProviderSurvivesNativeAdmissionWithoutInferringGpsForOtherSources() {
        for ((native, expected) in listOf(LocationSource.GPS to HistoryGpsProvider.GPS,
            LocationSource.NETWORK to HistoryGpsProvider.NETWORK, LocationSource.PASSIVE to HistoryGpsProvider.PASSIVE)) {
            val result = assertNotNull(historyGpsFix(origin, now, fix().copy(source = native), 5_000))
            assertEquals(expected, result.provider)
        }
    }

    @Test fun historyUsesActualMeasurementWallTimeRatherThanNavigationReceiptPolicy() {
        val input = fix(capturedAtEpochMillis = 899_000L).copy(measurementAtEpochMillis = 123_456L)
        val result = assertNotNull(historyGpsFix(origin, now, input, maxAgeMillis = 5_000L))
        assertEquals(HistoryTick(123_456L, 2_000L, 7L), result.tick)
        assertEquals(899_000L, input.capturedAtEpochMillis)
    }

    @Test fun missingMeasurementWallTimeCannotBeInventedFromFreshReceiptTime() {
        val input = fix(capturedAtEpochMillis = 899_000L).copy(measurementAtEpochMillis = null)
        assertNull(historyGpsFix(origin, now, input, maxAgeMillis = 5_000L))
    }

    @Test
    fun preserves_raw_wall_and_monotonic_capture_times() {
        val result = assertNotNull(
            historyGpsFix(
                origin = origin,
                now = now,
                fix = fix(capturedAtEpochMillis = 123_456L, elapsedRealtimeMillis = 2_000L),
                maxAgeMillis = 1_000L,
            ),
        )

        assertEquals(HistoryTick(123_456L, 2_000L, origin.clockGeneration), result.tick)
        assertEquals(51_000L, ru.sodovaya.volty.domain.history.historyAxisEpochMillis(origin, result.tick))
        assertEquals(56.8, result.lat)
        assertEquals(60.6, result.lon)
        assertEquals(5.0, result.accuracyMeters)
    }

    @Test
    fun rejects_missing_pre_origin_future_and_stale_monotonic_times() {
        assertNull(historyGpsFix(origin, now, fix(elapsedRealtimeMillis = null), maxAgeMillis = 5_000L))
        assertNull(historyGpsFix(origin, now, fix(elapsedRealtimeMillis = 999L), maxAgeMillis = 5_000L))
        assertNull(historyGpsFix(origin, now, fix(elapsedRealtimeMillis = 3_001L), maxAgeMillis = 5_000L))
        assertNull(historyGpsFix(origin, now, fix(elapsedRealtimeMillis = 1_500L), maxAgeMillis = 1_499L))
        assertNotNull(historyGpsFix(origin, now, fix(elapsedRealtimeMillis = 1_500L), maxAgeMillis = 1_500L))
    }

    @Test
    fun rejects_now_from_another_clock_generation_or_with_axis_overflow() {
        assertNull(
            historyGpsFix(
                origin,
                now.copy(clockGeneration = 8L),
                fix(elapsedRealtimeMillis = 2_000L),
                maxAgeMillis = 5_000L,
            ),
        )

        val nearOverflowOrigin = HistoryTick(Long.MAX_VALUE - 1L, 1_000L, 7L)
        assertNull(
            historyGpsFix(
                nearOverflowOrigin,
                HistoryTick(0L, 1_002L, 7L),
                fix(elapsedRealtimeMillis = 1_001L),
                maxAgeMillis = 5_000L,
            ),
        )
    }

    @Test
    fun keeps_valid_zero_coordinates_and_zero_heading_distinct_from_absent_heading() {
        val knownZero = assertNotNull(
            historyGpsFix(
                origin,
                now,
                fix(latitude = 0.0, longitude = 0.0, bearingDegrees = 0.0, elapsedRealtimeMillis = 2_000L),
                maxAgeMillis = 5_000L,
            ),
        )
        val absentHeading = assertNotNull(
            historyGpsFix(
                origin,
                now,
                fix(latitude = 0.0, longitude = 0.0, bearingDegrees = null, elapsedRealtimeMillis = 2_000L),
                maxAgeMillis = 5_000L,
            ),
        )

        assertEquals(0.0, knownZero.lat)
        assertEquals(0.0, knownZero.lon)
        assertEquals(0.0, knownZero.heading)
        assertEquals(null, absentHeading.heading)
    }

    @Test
    fun rejects_negative_maximum_age() {
        assertFailsWith<IllegalArgumentException> {
            historyGpsFix(origin, now, fix(elapsedRealtimeMillis = 2_000L), maxAgeMillis = -1L)
        }
    }

    private fun fix(
        latitude: Double = 56.8,
        longitude: Double = 60.6,
        accuracyMeters: Double = 5.0,
        bearingDegrees: Double? = 271.0,
        capturedAtEpochMillis: Long = 123_456L,
        elapsedRealtimeMillis: Long? = 2_000L,
    ) = RideLocationFix(
        coordinate = GeoCoordinate(latitude, longitude),
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = 4.5,
        bearingDegrees = bearingDegrees,
        capturedAtEpochMillis = capturedAtEpochMillis,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        source = LocationSource.GPS,
        measurementAtEpochMillis = capturedAtEpochMillis,
    )
}
