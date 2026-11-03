package ru.sodovaya.volty.presentation.video

import ru.sodovaya.volty.domain.video.*
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoDialSmootherTest {
    private fun speed(kmh: Double?, vehicle: String = "v") =
        VideoSnapshot(0, vehicle, mapOf(VideoMetric.SPEED to VideoReading(kmh)))

    private val hud = OverlayTemplates.hud()
    private val dial = hud.widgets.first { it.metric == VideoMetric.SPEED }

    @Test
    fun theDialChasesANewValueAndNeverPassesIt() {
        val smoother = VideoDialSmoother()
        assertEquals(40.0, smoother.advance(speed(40.0), 0)[VideoMetric.SPEED])
        val halfway = smoother.advance(speed(50.0), 60)[VideoMetric.SPEED]!!
        assertEquals(45.0, halfway, 0.01)
        assertEquals(50.0, smoother.advance(speed(50.0), 1_000)[VideoMetric.SPEED])
    }

    @Test
    fun anUnknownClearsAtOnceAndComesBackWithoutAChase() {
        val smoother = VideoDialSmoother()
        smoother.advance(speed(40.0), 0)
        assertNull(smoother.advance(speed(null), 33)[VideoMetric.SPEED])
        assertEquals(10.0, smoother.advance(speed(10.0), 66)[VideoMetric.SPEED])
        assertNull(smoother.advance(speed(Double.NaN), 99)[VideoMetric.SPEED])
    }

    @Test
    fun aLostLinkOrAnotherVehicleStartsOver() {
        val smoother = VideoDialSmoother()
        smoother.advance(speed(40.0), 0)
        assertTrue(smoother.advance(null, 33).isEmpty())
        assertEquals(10.0, smoother.advance(speed(10.0), 66)[VideoMetric.SPEED])
        assertEquals(90.0, smoother.advance(speed(90.0, "other"), 70)[VideoMetric.SPEED])
    }

    @Test
    fun aMetricTheSmootherDoesNotCarryIsAbsent() {
        val snapshot = VideoSnapshot(0, "v", mapOf(VideoMetric.CONTROLLER_POWER to VideoReading(500.0)))
        assertTrue(VideoDialSmoother().advance(snapshot, 0).isEmpty())
    }

    @Test
    fun onlyTheSweepMovesTheNumberStaysTheReading() {
        val strings = VideoOverlayStrings()
        val snapshot = speed(50.0)
        val scales = mapOf(VideoMetric.SPEED to 100f)
        val dials = mapOf(VideoMetric.SPEED to 45.0)
        val scene = buildOverlayScene(OverlaySceneInput(hud, snapshot, strings, UnitSystem.METRIC, 1080, 1920, scales = scales, dials = dials))
        assertTrue(scene.ops.filterIsInstance<OverlayTextOp>().flatMap { it.runs }.any { it.text == "50" })
        assertFalse(scene.ops.filterIsInstance<OverlayTextOp>().flatMap { it.runs }.any { it.text == "45" })
        val fill = scene.ops.filterIsInstance<OverlayArcOp>().single { it.color == OverlayPalette.CYAN.toInt() }
        assertEquals(-90f * 0.45f, fill.sweepDegrees, 0.01f)
    }

    @Test
    fun aSmoothedValueCannotBringBackAnUnknownReading() {
        val dials = mapOf(VideoMetric.SPEED to 45.0)
        assertNull(overlayFraction(dial, VideoReading(null), UnitSystem.METRIC, emptyMap(), dials))
        assertNull(overlayFraction(dial, null, UnitSystem.METRIC, emptyMap(), dials))
    }

    @Test
    fun theGateRedrawsOnASweepStepAndIgnoresADriftBelowOne() {
        val geometry = OverlayGeometry(1080, 1920, 0, false, 0, 0, 1080, 1920)
        val scales = mapOf(VideoMetric.SPEED to 100f)
        fun key(value: Double) = overlayDrawKey(hud, speed(50.0), VideoOverlayStrings(), UnitSystem.METRIC, geometry, scales = scales, dials = mapOf(VideoMetric.SPEED to value))
        assertNotEquals(key(40.0), key(50.0))
        assertEquals(key(40.0), key(40.05))
    }
}
