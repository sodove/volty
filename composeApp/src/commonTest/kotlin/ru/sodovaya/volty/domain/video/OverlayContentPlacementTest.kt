package ru.sodovaya.volty.domain.video

import kotlin.test.*

class OverlayContentPlacementTest {
    private val snapshot = VideoSnapshot(0, "v", mapOf(VideoMetric.VOLTAGE to VideoReading(99.0)))

    @Test fun animatedPositionsInvalidateTheDrawKeyEvenWhenRoundedNumbersStopChanging() {
        val geometry = OverlayGeometry(1080, 1920, 0, false, 0, 0, 1080, 1920)
        val key = overlayDrawKey(OverlayTemplates.meowIcons(), snapshot, VideoOverlayStrings(),
            ru.sodovaya.volty.util.UnitSystem.METRIC, geometry)
        val gate = OverlayDrawGate()
        assertTrue(gate.shouldDraw(key.copy(textPositions = mapOf("voltage" to 100f))))
        assertFalse(gate.shouldDraw(key.copy(textPositions = mapOf("voltage" to 100f))))
        assertTrue(gate.shouldDraw(key.copy(textPositions = mapOf("voltage" to 101f))))
    }

    @Test fun chipMovesIconAndTextTogetherWithoutMovingItsBackground() {
        val preset = OverlayTemplates.meowIcons().let { p -> p.copy(widgets = p.widgets.filter { it.id == "voltage" }) }
        val input = OverlaySceneInput(preset, snapshot, VideoOverlayStrings(), ru.sodovaya.volty.util.UnitSystem.METRIC, 1080, 1920)
        val raw = buildOverlayScene(input)
        val moved = buildOverlayScene(input.copy(contentPosition = OverlayContentPosition { _, target -> target + 20f }))
        assertEquals(raw.ops.filterIsInstance<OverlayRectOp>(), moved.ops.filterIsInstance<OverlayRectOp>())
        assertEquals(raw.ops.filterIsInstance<OverlayIconOp>().first().x + 20f, moved.ops.filterIsInstance<OverlayIconOp>().first().x)
        assertEquals(raw.ops.filterIsInstance<OverlayTextOp>().first().x + 20f, moved.ops.filterIsInstance<OverlayTextOp>().first().x)
    }

    @Test fun cardCentersActualContentAndMovesLabelAndValueAsOneBlock() {
        val widget = OverlayWidget("card", VideoMetric.VOLTAGE, kind = OverlayWidgetKind.VALUE_CARD, label = "V")
        val input = OverlaySceneInput(OverlayPreset("p", "p", widgets = listOf(widget)), snapshot,
            VideoOverlayStrings(), ru.sodovaya.volty.util.UnitSystem.METRIC, 1080, 1920)
        val raw = buildOverlayScene(input)
        val moved = buildOverlayScene(input.copy(contentPosition = OverlayContentPosition { _, target -> target + 20f }))
        val text = raw.ops.filterIsInstance<OverlayTextOp>()
        val shifted = moved.ops.filterIsInstance<OverlayTextOp>()
        assertEquals(2, text.size)
        assertEquals(text.first().x, text.last().x)
        text.zip(shifted).forEach { (before, after) -> assertEquals(before.x + 20f, after.x) }
        val width = text.last().runs.sumOf { (it.gapBefore + EstimatedOverlayTextMeasure.width(it.text, it.size, it.bold)).toDouble() }.toFloat()
        val box = raw.widgets.single()
        assertEquals((box.left + box.right - width) / 2, text.last().x, 0.01f)
    }
}
