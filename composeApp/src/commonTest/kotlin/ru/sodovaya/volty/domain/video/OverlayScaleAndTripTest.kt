package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayScaleAndTripTest {
    private val racing = OverlayTemplates.racing()
    private val strings = VideoOverlayStrings()
    private val geometry = OverlayGeometry(1920, 1080, 0, false, 0, 0, 1920, 1080)

    private fun speed(kmh: Double?, vehicle: String = "v") =
        VideoSnapshot(0, vehicle, mapOf(VideoMetric.SPEED to VideoReading(kmh)))

    private val speedDial get() = racing.widgets.first { it.metric == VideoMetric.SPEED }

    @AfterTest
    fun clearClock() {
        RideSessionClock.startedAtEpochMillis = null
    }

    @Test
    fun aSpeedDialWidensInstantlyAndNeverNarrows() {
        val tracker = OverlayScaleTracker()
        assertEquals(30f, tracker.update(racing, speed(0.0), UnitSystem.METRIC)[VideoMetric.SPEED])
        assertEquals(60f, tracker.update(racing, speed(42.0), UnitSystem.METRIC)[VideoMetric.SPEED])
        assertEquals(60f, tracker.update(racing, speed(5.0), UnitSystem.METRIC)[VideoMetric.SPEED])
        assertEquals(120f, tracker.update(racing, speed(90.0), UnitSystem.METRIC)[VideoMetric.SPEED])
    }

    @Test
    fun aLostLinkKeepsTheRangeAndAnotherVehicleStartsOver() {
        val tracker = OverlayScaleTracker()
        tracker.update(racing, speed(90.0), UnitSystem.METRIC)
        assertEquals(120f, tracker.update(racing, null, UnitSystem.METRIC)[VideoMetric.SPEED])
        assertEquals(120f, tracker.update(racing, speed(null), UnitSystem.METRIC)[VideoMetric.SPEED])
        assertEquals(30f, tracker.update(racing, speed(1.0, "other"), UnitSystem.METRIC)[VideoMetric.SPEED])
    }

    @Test
    fun theRangeIsInTheDisplayedUnit() {
        val miles = OverlayScaleTracker().update(racing, speed(100.0), UnitSystem.IMPERIAL)
        assertEquals(80f, miles[VideoMetric.SPEED]) // 62 mph with headroom
        val fraction = overlayFraction(speedDial, VideoReading(100.0), UnitSystem.IMPERIAL, miles)!!
        assertEquals(62.1 / 80.0, fraction, 0.01)
    }

    @Test
    fun anUnknownIsNoFractionAndAValueIsClamped() {
        assertNull(overlayFraction(speedDial, null, UnitSystem.METRIC, emptyMap()))
        assertNull(overlayFraction(speedDial, VideoReading(Double.NaN), UnitSystem.METRIC, emptyMap()))
        assertEquals(1.0, overlayFraction(speedDial, VideoReading(999.0), UnitSystem.METRIC, mapOf(VideoMetric.SPEED to 30f)))
        assertEquals(0.0, overlayFraction(speedDial, VideoReading(-5.0), UnitSystem.METRIC, mapOf(VideoMetric.SPEED to 30f)))
    }

    @Test
    fun aStoredRangeIsUsedWhenTheDialIsNotAuto() {
        val fixed = OverlayWidget(id = "a", metric = VideoMetric.SPEED, kind = OverlayWidgetKind.ARC, arc = OverlayArc(max = 50f))
        assertEquals(50f, overlayScaleMax(fixed, mapOf(VideoMetric.SPEED to 120f)))
        assertEquals(0.5, overlayFraction(fixed, VideoReading(25.0), UnitSystem.METRIC, emptyMap()))
    }

    @Test
    fun tickStepsStayRoundAndFew() {
        for (rung in OVERLAY_SPEED_RUNGS) {
            val step = overlayTickStep(rung)
            assertEquals(0f, rung % step, "$rung")
            assertTrue(rung / step <= 7f, "$rung")
        }
    }

    // --- the redraw gate sees everything a preset can show ---

    private fun key(snapshot: VideoSnapshot?, trip: OverlayTrip = OverlayTrip(), scales: Map<VideoMetric, Float> = emptyMap(), preset: OverlayPreset = racing) =
        overlayDrawKey(preset, snapshot, strings, UnitSystem.METRIC, geometry, trip = trip, scales = scales)

    @Test
    fun aWiderScaleRedrawsEvenIfTheValueIsTheSame() {
        val s = speed(0.0)
        assertNotEquals(key(s, scales = mapOf(VideoMetric.SPEED to 30f)), key(s, scales = mapOf(VideoMetric.SPEED to 60f)))
    }

    @Test
    fun aTickingTimeRedrawsAPresetThatShowsItAndOnlyThat() {
        val s = speed(10.0)
        assertNotEquals(key(s, OverlayTrip(elapsedSeconds = 1)), key(s, OverlayTrip(elapsedSeconds = 2)))
        val hud = OverlayTemplates.hud()
        assertEquals(key(s, OverlayTrip(elapsedSeconds = 1), preset = hud), key(s, OverlayTrip(elapsedSeconds = 2), preset = hud))
    }

    @Test
    fun theSecondaryNumberOfABarRedraws() {
        val hud = OverlayTemplates.hud()
        val a = VideoSnapshot(0, "v", mapOf(VideoMetric.VOLTAGE to VideoReading(96.0)))
        val b = VideoSnapshot(0, "v", mapOf(VideoMetric.VOLTAGE to VideoReading(97.0)))
        assertNotEquals(key(a, preset = hud), key(b, preset = hud))
    }

    @Test
    fun aBarFractionRedrawsAndLosingTheLinkRedrawsTheChip() {
        val hud = OverlayTemplates.hud()
        val a = VideoSnapshot(0, "v", mapOf(VideoMetric.SOC to VideoReading(50.0)))
        val b = VideoSnapshot(0, "v", mapOf(VideoMetric.SOC to VideoReading(51.0)))
        assertNotEquals(key(a, preset = hud), key(b, preset = hud))
        assertTrue(key(null, preset = hud).noLink)
        assertNotEquals(key(null, preset = hud), key(VideoSnapshot(0, "v", emptyMap()).copy(demo = true), preset = hud))
    }

    // --- time and distance ---

    @Test
    fun recordingDistanceCountsFromTheStartOfTheRecording() {
        val tracker = VideoTripTracker()
        assertEquals(0.0, tracker.recordingDistance(12.0))
        assertEquals(1.5, tracker.recordingDistance(13.5))
    }

    @Test
    fun aDroppedCounterIsANewBaselineNotNegativeDistance() {
        val tracker = VideoTripTracker()
        tracker.recordingDistance(10.0)
        tracker.recordingDistance(13.0)
        assertEquals(3.0, tracker.recordingDistance(0.0))
        assertEquals(4.0, tracker.recordingDistance(1.0))
    }

    @Test
    fun anUnknownCounterIsUnknownAndLosesNothing() {
        val tracker = VideoTripTracker()
        tracker.recordingDistance(10.0)
        tracker.recordingDistance(11.0)
        assertNull(tracker.recordingDistance(null))
        assertNull(tracker.recordingDistance(Double.NaN))
        assertEquals(2.0, tracker.recordingDistance(12.0))
    }

    @Test
    fun theDefaultScopeCountsTheRecordingAndStartsOverWithTheNextOne() {
        val tracker = VideoTripTracker()
        assertEquals(OverlayTrip(0, null), tracker.trip(OverlayTripScope.RECORDING, false, 0, 500, 7.0))
        assertEquals(OverlayTrip(1, 0.0), tracker.trip(OverlayTripScope.RECORDING, true, 1, 500, 7.0))
        assertEquals(OverlayTrip(9, 2.0), tracker.trip(OverlayTripScope.RECORDING, true, 9, 500, 9.0))
        tracker.trip(OverlayTripScope.RECORDING, false, 0, 500, 9.0)
        assertEquals(OverlayTrip(1, 0.0), tracker.trip(OverlayTripScope.RECORDING, true, 1, 500, 9.0))
    }

    @Test
    fun theRideScopeIsTheDashboardsSession() {
        val tracker = VideoTripTracker()
        assertEquals(OverlayTrip(500, 7.0), tracker.trip(OverlayTripScope.RIDE, false, 0, 500, 7.0))
        assertEquals(OverlayTrip(null, null), tracker.trip(OverlayTripScope.RIDE, true, 3, null, null))
    }

    @Test
    fun theRideClockIsUnknownUntilADashboardSessionHasStarted() {
        RideSessionClock.startedAtEpochMillis = null
        assertNull(RideSessionClock.secondsAt(5_000))
        RideSessionClock.startedAtEpochMillis = 2_000
        assertEquals(3, RideSessionClock.secondsAt(5_000))
        assertEquals(0, RideSessionClock.secondsAt(1_000))
    }

    @Test
    fun onlyTimeOrDistanceWidgetsMakeTheScopeMatter() {
        assertTrue(overlayUsesTrip(OverlayTemplates.racing()))
        assertEquals(false, overlayUsesTrip(OverlayTemplates.hud()))
    }

    @Test
    fun timeFormatsPastTheFirstHour() {
        assertEquals("00:00", videoElapsedText(0))
        assertEquals("12:48", videoElapsedText(768))
        assertEquals("1:02:48", videoElapsedText(3768))
        assertEquals("00:00", videoElapsedText(-5))
        assertEquals("12:48", videoValueText(VideoMetric.ELAPSED, VideoReading(768.0), UnitSystem.METRIC))
    }

    @Test
    fun distanceHasOneDecimalAndFollowsMiles() {
        assertEquals("8.4", videoValueText(VideoMetric.DISTANCE, VideoReading(8.44), UnitSystem.METRIC))
        assertEquals("6.2", videoValueText(VideoMetric.DISTANCE, VideoReading(10.0), UnitSystem.IMPERIAL))
        assertEquals("0.0", videoValueText(VideoMetric.DISTANCE, VideoReading(0.0), UnitSystem.METRIC))
        assertEquals("—", videoValueText(VideoMetric.DISTANCE, VideoReading(null), UnitSystem.METRIC))
        assertEquals("mi", strings.unit(VideoMetric.DISTANCE, UnitSystem.IMPERIAL))
        assertEquals("km", strings.unit(VideoMetric.DISTANCE, UnitSystem.METRIC))
    }
}
