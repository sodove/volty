package ru.sodovaya.volty.presentation.video

import ru.sodovaya.volty.domain.video.*
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VideoDisplayedReadingTest {
    private fun snapshot(readings: Map<VideoMetric, VideoReading>, vehicle: String = "v") =
        VideoSnapshot(0, vehicle, readings)

    private fun speed(kmh: Double?, vehicle: String = "v") =
        snapshot(mapOf(VideoMetric.SPEED to VideoReading(kmh)), vehicle)

    private val hud = OverlayTemplates.hud()
    private val dial = hud.widgets.first { it.metric == VideoMetric.SPEED }

    @Test
    fun displayedReadingUsesInterpolationOnlyWhileRawIsFiniteAndPreservesProvenance() {
        val raw = VideoReading(50.0, estimated = true, atCeiling = true)
        val dials = mapOf(VideoMetric.SPEED to 45.0, VideoMetric.ELAPSED to 1.0, VideoMetric.DISTANCE to 2.0)

        assertEquals(VideoReading(45.0, estimated = true, atCeiling = true), displayedReading(VideoMetric.SPEED, raw, dials))
        assertNull(displayedReading(VideoMetric.SPEED, null, dials))
        assertEquals(VideoReading(null), displayedReading(VideoMetric.SPEED, VideoReading(null), dials))
        assertEquals(raw, displayedReading(VideoMetric.ELAPSED, raw, dials))
        assertEquals(raw, displayedReading(VideoMetric.DISTANCE, raw, dials))
        assertEquals(raw.copy(value = null), displayedReading(VideoMetric.SPEED, raw, mapOf(VideoMetric.SPEED to Double.NaN)))
        assertEquals(raw.copy(value = null), displayedReading(VideoMetric.SPEED, raw, emptyMap()))
    }

    @Test
    fun everyWidgetTextAndArcUseTheInterpolatedValueWhileStyleUsesRawThresholds() {
        val rawRule = listOf(
            OverlayRule(OverlayComparison.GTE, 48.0, foreground = "#FFFF0000"),
            OverlayRule(OverlayComparison.GTE, 100.0, background = "#FFFF0000"),
        )
        val widgets = listOf(
            OverlayWidget(id = "value", metric = VideoMetric.SPEED, kind = OverlayWidgetKind.VALUE_CARD, rules = rawRule),
            OverlayWidget(id = "icon-value", metric = VideoMetric.SPEED, kind = OverlayWidgetKind.ICON_VALUE),
            OverlayWidget(id = "chip", metric = VideoMetric.SPEED, kind = OverlayWidgetKind.ICON_CHIP),
            OverlayWidget(id = "arc", metric = VideoMetric.SPEED, kind = OverlayWidgetKind.ARC),
            OverlayWidget(id = "left", metric = VideoMetric.SPEED, kind = OverlayWidgetKind.ARC_LEFT),
            OverlayWidget(id = "right", metric = VideoMetric.SPEED, kind = OverlayWidgetKind.ARC_RIGHT),
            OverlayWidget(
                id = "bar", metric = VideoMetric.SPEED, kind = OverlayWidgetKind.BAR,
                bar = OverlayBar(valueBelow = true, secondary = VideoMetric.SPEED),
            ),
            OverlayWidget(id = "text", metric = VideoMetric.SPEED, kind = OverlayWidgetKind.TEXT),
        )
        val snapshot = speed(50.0)
        val scene = buildOverlayScene(
            OverlaySceneInput(
                preset = OverlayPreset("smooth", "Smooth", widgets),
                snapshot = snapshot,
                strings = VideoOverlayStrings(),
                system = UnitSystem.METRIC,
                width = 1920,
                height = 1080,
                scales = mapOf(VideoMetric.SPEED to 100f),
                dials = mapOf(VideoMetric.SPEED to 45.0),
            ),
        )
        val valueRuns = scene.ops.filterIsInstance<OverlayTextOp>().flatMap { it.runs }

        assertEquals(9, valueRuns.count { it.text == "45" }, "all eight widget kinds, including the bar's secondary value")
        assertFalse(valueRuns.any { it.text == "50" })
        assertEquals(overlayColorArgb("#FFFF0000"), valueRuns.first { it.text == "45" }.color, "threshold remains based on raw 50")
        val filledArcs = scene.ops.filterIsInstance<OverlayArcOp>().filter { it.color == overlayColorArgb("#FFFFFFFF") }
        assertEquals(3, filledArcs.size)
        assertTrue(filledArcs.any { it.sweepDegrees == 270f * 0.45f })
        assertTrue(filledArcs.any { it.sweepDegrees == -90f * 0.45f })
        assertTrue(filledArcs.any { it.sweepDegrees == 90f * 0.45f })
    }

    @Test
    fun drawKeyAndSceneUseTheSameDisplayedTextAndDialStep() {
        val strings = VideoOverlayStrings()
        val snapshot = speed(50.0)
        val scales = mapOf(VideoMetric.SPEED to 100f)
        val dials = mapOf(VideoMetric.SPEED to 45.0)
        val geometry = OverlayGeometry(1080, 1920, 0, false, 0, 0, 1080, 1920)
        val scene = buildOverlayScene(OverlaySceneInput(hud, snapshot, strings, UnitSystem.METRIC, 1080, 1920, scales = scales, dials = dials))
        val key = overlayDrawKey(hud, snapshot, strings, UnitSystem.METRIC, geometry, scales = scales, dials = dials)
        val speedFacet = key.facets[hud.widgets.indexOfFirst { it.metric == VideoMetric.SPEED }]

        assertTrue(scene.ops.filterIsInstance<OverlayTextOp>().flatMap { it.runs }.any { it.text == "45" })
        assertFalse(scene.ops.filterIsInstance<OverlayTextOp>().flatMap { it.runs }.any { it.text == "50" })
        assertEquals("45", speedFacet.text)
        assertNotEquals(speedFacet.arcStep, overlayDrawKey(hud, speed(50.0), VideoOverlayStrings(), UnitSystem.METRIC,
            geometry, dials = mapOf(VideoMetric.SPEED to 45.1)).facets.first { it.arcStep != null }.arcStep)
    }

    @Test
    fun aSmoothedValueCannotBringBackAnUnknownReading() {
        val dials = mapOf(VideoMetric.SPEED to 45.0)
        assertNull(overlayFraction(dial, VideoReading(null), UnitSystem.METRIC, emptyMap(), dials))
        assertNull(overlayFraction(dial, null, UnitSystem.METRIC, emptyMap(), dials))
    }
}
