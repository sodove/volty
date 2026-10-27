package ru.sodovaya.volty.presentation.history

import kotlin.test.*
import ru.sodovaya.volty.domain.history.*

class HistoryRideDistanceTest {
    private val origin = HistoryTick(1_000_000, 0, 1)
    private val odo = HistorySeriesKey("esc", "odometer_km")
    private fun tick(millis: Long, generation: Long = 1) = HistoryTick(1_000_000 + millis, millis, generation)
    private fun at(millis: Long, km: Double) = HistorySample(tick(millis), odo, km)

    /** The rider's ride: 950 s, an odometer sample every 500 ms rising 6.0 m/s from 2816.400 km. */
    private fun ride(needleAtMillis: Long? = null, needleKm: Double = 0.0, resetAtMillis: Long? = null): List<HistorySample> =
        (0..1900).map { i ->
            val t = i * 500L
            val truth = if (resetAtMillis != null && t >= resetAtMillis) 2650.0 + 6.0 * (t - resetAtMillis) / 1_000_000.0
            else 2816.4 + 6.0 * t / 1_000_000.0
            at(t, if (t == needleAtMillis) needleKm else truth)
        }

    /** Fixes every second along a meridian at 6 m/s (one degree of latitude is 111 194.9 m). */
    private fun track(fromSeconds: Long = 0, toSeconds: Long = 950, accuracy: Double = 5.0) = (fromSeconds..toSeconds).map { s ->
        HistoryGpsFix(tick(s * 1000), 55.0 + 6.0 * s / 111_194.9, 37.0, accuracy, null)
    }

    private val rideMillis = 950_000L

    @Test fun theRidersRideIsReconstructedFromItsCounterWithoutTheNeedle() {
        // At t = 272 s the true value is 2818.032 (0x2AFFF0 m); one sample reads 2883.568 (0x2BFFF0 m).
        // The old max-minus-min arithmetic gave 67.17 km over 950 s (254 km/h): kept here as a comment, not asserted.
        val d = checkNotNull(historyRideDistance(ride(272_000, 2883.568), emptyList(), origin, 55.1, rideMillis))
        assertEquals(5.700, d.km, 0.01)
        assertTrue(d.approximate, "a rejected step and a filled stretch both make it approximate")
        val average = d.km / (rideMillis / 3_600_000.0)
        assertTrue(average < 55.1, "the average stays below the peak: $average")
    }

    @Test fun gpsFirstIsExactWhenTheTrackCoversTheWholeRide() {
        val d = checkNotNull(historyRideDistance(ride(), track(), origin, 55.1, rideMillis))
        assertEquals(5.70, d.km, 0.02)
        assertFalse(d.approximate, "every counter increment lies inside a GPS-covered interval: nothing is filled")
    }

    @Test fun aRideWithoutATrackAndWithAPlainCounterIsApproximateBecauseItIsAllFill() {
        val d = checkNotNull(historyRideDistance(ride(), emptyList(), origin, 55.1, rideMillis))
        assertEquals(5.70, d.km, 0.01)
        assertTrue(d.approximate)
    }

    @Test fun theWheelFillsTheHeadBeforeTheFirstFix() {
        val d = checkNotNull(historyRideDistance(ride(), track(fromSeconds = 120), origin, 55.1, rideMillis))
        // 120 s x 6 m/s = 0.720 km of fill, plus 4.98 km of GPS.
        assertEquals(5.70, d.km, 0.02)
        assertTrue(d.approximate)
    }

    @Test fun aCounterIncrementInsideAGpsCoveredIntervalIsNotAddedTwice() {
        // Counter says 6 m/s for all 950 s; GPS covers only seconds 400..500 -> the rest must be fill, not both.
        val d = checkNotNull(historyRideDistance(ride(), track(400, 500), origin, 55.1, rideMillis))
        assertEquals(5.70, d.km, 0.02)
    }

    @Test fun aPersistentResetInARecordedRideCostsOneStepAndNeverFreezesTheTail() {
        val d = checkNotNull(historyRideDistance(ride(resetAtMillis = 500_000), emptyList(), origin, 55.1, rideMillis))
        assertEquals(5.697, d.km, 0.01)
        assertTrue(d.approximate, "a re-baseline is approximate")
    }

    @Test fun anUnknownSampleAClockGenerationAndAMarkerEachEndTheRunAndAddZeroAcrossTheBreak() {
        fun walk(break_: List<HistorySample>) = checkNotNull(historyRideDistance(
            listOf(at(0, 100.000), at(500, 100.003)) + break_ + listOf(at(10_000, 200.000), at(10_500, 200.003)),
            emptyList(), origin, null, 11_000))
        val unknown = HistorySample(tick(5_000), odo, null, HistoryUnknownReason.GAP)
        // Two real 3 m steps; the 100 km jump across the break is nobody's progress.
        assertEquals(0.006, walk(listOf(unknown)).km, 1e-6)
        val markers = listOf(HistoryMarker(tick(5_000), HistoryMarkerKind.BLE_GAP))
        val viaMarker = checkNotNull(historyRideDistance(
            listOf(at(0, 100.000), at(500, 100.003), at(10_000, 200.000), at(10_500, 200.003)), emptyList(), origin, null, 11_000, markers))
        assertEquals(0.006, viaMarker.km, 1e-6)
        val gpsGap = checkNotNull(historyRideDistance(
            listOf(at(0, 100.000), at(500, 100.003), at(10_000, 100.010), at(10_500, 100.013)), emptyList(), origin, null, 11_000,
            listOf(HistoryMarker(tick(5_000), HistoryMarkerKind.GPS_GAP))))
        assertEquals(0.013, gpsGap.km, 1e-6, "a GPS gap says nothing about the wheel counter")
    }

    @Test fun aSampleOnAnotherClockGenerationIsNotOnTheRideAxisAndEndsTheRun() {
        val d = checkNotNull(historyRideDistance(
            listOf(at(0, 100.000), at(500, 100.003), HistorySample(tick(600, generation = 2), odo, 500.0),
                HistorySample(tick(1_100, generation = 2), odo, 500.003)), emptyList(), origin, null, 2_000))
        assertEquals(0.003, d.km, 1e-6)
    }

    @Test fun anImpossibleDistanceDropsTheCounterAndShowsGpsAlone() {
        // Deliberately incoherent: a monotone counter that walks 67 km in 950 s of a ride whose own peak is 55.1 km/h.
        val counter = listOf(at(0, 2816.4), at(950_000, 2883.6))
        assertNull(historyRideDistance(counter, emptyList(), origin, 55.1, rideMillis), "no GPS: no distance, never a clamp")
        // A full track covers the one giant increment, so the counter adds nothing and GPS is the distance, exact.
        val covered = checkNotNull(historyRideDistance(counter, track(), origin, 55.1, rideMillis))
        assertEquals(5.70, covered.km, 0.02); assertFalse(covered.approximate)
        // A partial track leaves the giant increment as fill: the cross-check fires and only the GPS length remains.
        val withGps = checkNotNull(historyRideDistance(counter, track(0, 100), origin, 55.1, rideMillis))
        assertEquals(0.60, withGps.km, 0.01)
        assertTrue(withGps.approximate)
        // Without a peak there is no witness and the counter stands.
        assertEquals(67.2, checkNotNull(historyRideDistance(counter, emptyList(), origin, null, rideMillis)).km, 0.01)
    }

    @Test fun crossCheckToleratesFivePercentOverThePeakTimesTheDuration() {
        // peak 60 km/h x 1 h x 1.05 = 63 km.
        val ok = listOf(at(0, 0.0), at(3_600_000, 62.9))
        val over = listOf(at(0, 0.0), at(3_600_000, 63.1))
        assertNotNull(historyRideDistance(ok, emptyList(), origin, 60.0, 3_600_000))
        assertNull(historyRideDistance(over, emptyList(), origin, 60.0, 3_600_000))
    }

    @Test fun anEmptyRideHasNoDistance() {
        assertNull(historyRideDistance(emptyList(), emptyList(), origin, 55.1, rideMillis))
        assertNull(historyRideDistance(listOf(at(0, 5.0), at(500, 5.0)), emptyList(), origin, 55.1, rideMillis))
    }

    @Test fun poorAndSilentFixesDoNotCoverAnIntervalSoTheWheelFillsThem() {
        // Fixes at 100 m accuracy cover nothing: the counter carries the whole ride, GPS length is zero.
        val d = checkNotNull(historyRideDistance(ride(), track(accuracy = 100.0), origin, 55.1, rideMillis))
        assertEquals(5.70, d.km, 0.01)
        assertTrue(d.approximate)
    }

    @Test fun theStatsFallbackAppliesTheSameCrossCheck() {
        val esc = HistorySource("esc", HistorySourceKind.CONTROLLER, "ESC")
        val stats = mapOf(
            odo to HistoryStats(2816.4, 2883.6, null, complete = true), // incoherent: complete, yet a needle
            HistorySeriesKey("esc", "speed_kmh") to HistoryStats(0.0, 55.1, null, true))
        assertNull(historyStatsDistance(listOf(esc), stats, rideMillis, null))
        val gps = checkNotNull(historyStatsDistance(listOf(esc), stats, rideMillis, 5.6))
        assertEquals(5.6, gps.km, 1e-9); assertTrue(gps.approximate)
        // A coherent counter still wins over GPS, and stays exact when its stats are complete.
        val fine = mapOf(odo to HistoryStats(2816.4, 2822.1, null, true), HistorySeriesKey("esc", "speed_kmh") to HistoryStats(0.0, 55.1, null, true))
        val kept = checkNotNull(historyStatsDistance(listOf(esc), fine, rideMillis, 5.9))
        assertEquals(5.7, kept.km, 1e-6); assertFalse(kept.approximate)
    }

    @Test fun theTripCounterIsPreferredOverTheOdometerInTheStatsFallback() {
        val esc = HistorySource("esc", HistorySourceKind.CONTROLLER, "ESC")
        val stats = mapOf(HistorySeriesKey("esc", "trip_distance_km") to HistoryStats(0.0, 3.0, null, true),
            odo to HistoryStats(100.0, 104.0, null, true))
        assertEquals(3.0, checkNotNull(historyStatsDistance(listOf(esc), stats, 3_600_000, null)).km, 1e-9)
    }
}
