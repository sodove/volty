package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayIconsTest {
    @Test
    fun theIconTableIsTelemetryStudiosNameForNameAndCodepointForCodepoint() {
        // A sample of TS's icons.ts, including its `wave` quirk (U+F0C9 is FA's bars glyph).
        val ts = mapOf(
            "bolt" to '', "car-battery" to '', "battery-full" to '', "fire" to '',
            "gauge" to '', "triangle-exclamation" to '', "compass" to '', "wave" to '',
            "hashtag" to '', "temperature-half" to '', "stopwatch" to '', "road" to '',
        )
        for ((name, codepoint) in ts) assertEquals(codepoint, OverlayIcons.byName(name)?.codepoint, name)
        assertEquals(47, OverlayIcons.all.size)
        assertEquals(OverlayIcons.all.size, OverlayIcons.all.map { it.name }.toSet().size, "names are unique")
        assertEquals(OverlayIconGroup.entries.toList(), OverlayIcons.all.map { it.group }.distinct(), "groups in icons.ts order")
        assertNull(OverlayIcons.byName("not-an-icon"))
    }

    @Test
    fun everyOlderEnumValueStillMeansAnIconAndAutoIsTheMetrics() {
        for (icon in OverlayIcon.entries - OverlayIcon.AUTO) assertNotNull(overlayIconName(icon)?.let(OverlayIcons::byName), icon.name)
        assertNull(overlayIconName(OverlayIcon.AUTO))
        for (metric in VideoMetric.entries) {
            val auto = autoOverlayIconName(metric)
            assertNotNull(OverlayIcons.byName(auto), "$metric's icon exists")
            assertTrue(auto != OverlayIcons.WARNING, "a warning sign is earned, never automatic")
            val widget = OverlayWidget(id = "w", metric = metric)
            assertEquals(auto, overlayIconShown(widget, null), "AUTO draws the metric's icon, not a circle")
        }
    }

    @Test
    fun aChosenNameWinsOverTheEnumAndAnUnknownNameFallsBack() {
        val widget = OverlayWidget(id = "w", metric = VideoMetric.SOC, icon = OverlayIcon.BOLT)
        assertEquals("bolt", overlayIconChosen(widget))
        assertEquals("fire", overlayIconChosen(widget.copy(iconName = "fire")))
        assertEquals("bolt", overlayIconChosen(widget.copy(iconName = "from-a-newer-ts")))
        assertTrue(overlayIconIsAuto(OverlayWidget(id = "w", metric = VideoMetric.SOC)))
        assertFalse(overlayIconIsAuto(widget))
    }

    @Test
    fun anOlderDocumentWithoutIconNamesDecodesUnchanged() {
        val old = """{"version":1,"selected":{"id":"mine","name":"Mine","widgets":[
            {"id":"soc","metric":"SOC","kind":"ICON_VALUE","icon":"BATTERY","label":"","unit":"%"}]}}"""
        val document = OverlayCodec.decode(old)
        val widget = document.selected.widgets.single()
        assertNull(widget.iconName)
        assertEquals("battery-full", overlayIconChosen(widget))
        assertTrue(widget.style.showLabel)
    }

    // --- the editor ---

    private fun editor(widget: OverlayWidget): OverlayEditorState {
        val preset = OverlayPreset("mine", "Mine", listOf(widget))
        return OverlayEditorState.open(OverlayDocument(selected = preset, saved = listOf(preset))).copy(selectedId = widget.id)
    }

    @Test
    fun theEditorMakesAChipAndPicksItsIcon() {
        val card = OverlayWidget(id = "c", metric = VideoMetric.VOLTAGE, kind = OverlayWidgetKind.VALUE_CARD)
        val chip = reduceOverlayEditor(editor(card), OverlayEditorAction.SetKind(OverlayWidgetKind.ICON_CHIP)).selected!!
        assertEquals(OverlayWidgetKind.ICON_CHIP, chip.kind)
        assertNotNull(chip.placement, "a chip is placed like any widget")

        var state = reduceOverlayEditor(editor(chip), OverlayEditorAction.SetIcon("fire"))
        assertEquals("fire", state.selected!!.iconName)
        assertTrue(state.canUndo)
        state = reduceOverlayEditor(state, OverlayEditorAction.SetIcon("no-such-icon"))
        assertEquals("fire", state.selected!!.iconName, "an unknown name changes nothing")
        state = reduceOverlayEditor(state, OverlayEditorAction.SetIcon(null))
        assertNull(state.selected!!.iconName)
        assertTrue(overlayIconIsAuto(state.selected!!))
        state = reduceOverlayEditor(state, OverlayEditorAction.Undo)
        assertEquals("fire", state.selected!!.iconName)
    }

    @Test
    fun aChipKeepsItsIconWhenItsMetricChangesUnlessItWasTheMetricsOwn() {
        val own = OverlayWidget(id = "c", metric = VideoMetric.VOLTAGE, kind = OverlayWidgetKind.ICON_CHIP, iconName = "car-battery")
        assertNull(overlayWidgetWithMetric(own, VideoMetric.SOC).iconName)
        val chosen = own.copy(iconName = "rocket")
        assertEquals("rocket", overlayWidgetWithMetric(chosen, VideoMetric.SOC).iconName)
    }

    @Test
    fun aTimeCanBeAChip() {
        assertTrue(overlayKindAllowed(OverlayWidgetKind.ICON_CHIP, VideoMetric.ELAPSED))
        assertFalse(overlayKindAllowed(OverlayWidgetKind.ARC, VideoMetric.ELAPSED))
    }

    // --- the telemetry-studio presets ---

    @Test
    fun meowIconsLeadsTheStudioPresetsAndHudStaysTheDefault() {
        val ids = OverlayTemplates.all().map { it.id }
        assertEquals(listOf("minimal", "hud", "racing", "ts-meow-icons", "ts-meow-icons-trip", "top-row", "studio-panel", "studio-large"), ids)
        assertEquals("hud", OverlayDocument().selected.id)
        for (preset in listOf(OverlayTemplates.meowIcons(), OverlayTemplates.meowIconsTrip())) {
            assertTrue(validOverlayPreset(preset), preset.id)
            assertEquals(preset, OverlayTemplates.byId(preset.id))
        }
        assertTrue(overlayUsesTrip(OverlayTemplates.meowIconsTrip()))
        assertFalse(overlayUsesTrip(OverlayTemplates.meowIcons()))
    }

    @Test
    fun meowIconsReadsTheFieldsTelemetryStudioReadsAndInventsNothing() {
        val metrics = OverlayTemplates.meowIcons().widgets.map { it.metric }
        assertEquals(
            listOf(
                VideoMetric.VOLTAGE, VideoMetric.SOC, VideoMetric.ESC_TEMPERATURE, VideoMetric.CONTROLLER_POWER,
                VideoMetric.BATTERY_CURRENT, VideoMetric.DUTY, VideoMetric.SPEED,
            ),
            metrics,
        )
        val scene = buildOverlayScene(OverlaySceneInput(OverlayTemplates.meowIcons(), null, VideoOverlayStrings(), UnitSystem.METRIC, 1920, 1080))
        val runs = scene.ops.filterIsInstance<OverlayTextOp>().flatMap { it.runs }.map { it.text }
        assertFalse("0" in runs, "no value is a dash, never a zero")
        assertTrue(runs.count { it == "—" } >= 7)
    }

    @Test
    fun meowIconsKeepsTelemetryStudiosSingleRowInLandscape() {
        val full = VideoSnapshot(1_000, "v", VideoMetric.entries.associateWith { VideoReading(1.0) })
        for ((w, h) in listOf(1920 to 1080, 1280 to 720)) {
            val scene = buildOverlayScene(OverlaySceneInput(OverlayTemplates.meowIcons(), full, VideoOverlayStrings(), UnitSystem.METRIC, w, h))
            val chips = scene.widgets.filter { it.id != "speed-arc" }
            assertEquals(1, chips.map { it.top }.distinct().size, "one row in ${w}x$h")
            assertTrue(chips.all { it.left >= 0f && it.right <= w })
            assertTrue(chips.all { it.bottom < scene.widgets.single { it.id == "speed-arc" }.top })
        }
    }
}
