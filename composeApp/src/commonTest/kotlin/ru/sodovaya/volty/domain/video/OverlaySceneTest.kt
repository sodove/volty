package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlaySceneTest {
    private val strings = VideoOverlayStrings(
        labels = VideoOverlayStrings.DEFAULT_LABELS + mapOf(VideoMetric.DUTY to "ШИМ", VideoMetric.BATTERY_CURRENT to "Ток батареи"),
        kmh = "км/ч", noLink = "НЕТ СВЯЗИ", demo = "ДЕМО",
    )

    private fun live(vararg readings: Pair<VideoMetric, VideoReading>, demo: Boolean = false) =
        VideoSnapshot(1_000, "v", readings.toMap(), demo)

    private val full = live(
        VideoMetric.SPEED to VideoReading(42.0),
        VideoMetric.DUTY to VideoReading(61.0, estimated = true),
        VideoMetric.BATTERY_CURRENT to VideoReading(-18.4),
        VideoMetric.ESC_TEMPERATURE to VideoReading(34.0),
        VideoMetric.MOTOR_TEMPERATURE to VideoReading(41.0),
        VideoMetric.SOC to VideoReading(78.0),
        VideoMetric.VOLTAGE to VideoReading(96.4),
    )

    private fun scene(preset: OverlayPreset, snapshot: VideoSnapshot?, width: Int, height: Int, system: UnitSystem = UnitSystem.METRIC, trip: OverlayTrip = OverlayTrip(), scales: Map<VideoMetric, Float> = emptyMap()) =
        buildOverlayScene(OverlaySceneInput(preset, snapshot, strings, system, width, height, trip, scales))

    private fun OverlayScene.texts() = ops.filterIsInstance<OverlayTextOp>()
    private fun OverlayScene.allRuns() = texts().flatMap { it.runs }
    private fun OverlayScene.run(text: String) = allRuns().single { it.text == text }
    private fun OverlayScene.has(text: String) = allRuns().any { it.text == text }

    // --- the mock's size table: u is the short edge / 1080 ---

    @Test
    fun cardValueAndLabelMatchTheApprovedSizeTableInEveryFrame() {
        val frames = listOf(
            Triple(720, 1280, 720f / 1080f), Triple(1280, 720, 720f / 1080f),
            Triple(1080, 1920, 1f), Triple(1920, 1080, 1f), Triple(3840, 2160, 2f),
        )
        for ((w, h, u) in frames) {
            val scene = scene(OverlayTemplates.hud(), full, w, h)
            assertEquals(64f * u, scene.run("34").size, 0.01f, "value in ${w}x$h")
            assertEquals(24f * u, scene.allRuns().first { it.text == "CONTROLLER" || it.text == "ESC TEMP" }.size, 0.01f, "label in ${w}x$h")
        }
    }

    @Test
    fun theSamePresetIsPhysicallyTheSameInPortraitAndLandscape() {
        val portrait = scene(OverlayTemplates.hud(), full, 1080, 1920)
        val landscape = scene(OverlayTemplates.hud(), full, 1920, 1080)
        assertEquals(portrait.run("42").size, landscape.run("42").size)
    }

    // --- geometry: nothing leaves the frame, nothing collides ---

    private val frames = listOf(
        720 to 1280, 1280 to 720, 1080 to 1920, 1920 to 1080, 2160 to 3840, 3840 to 2160,
        480 to 640, 640 to 480, 1080 to 1440, 1440 to 1080, 2520 to 1080, 1080 to 2520,
    )

    @Test
    fun noBuiltInWidgetLeavesTheFrameOrCollidesInAnyFrame() {
        for (preset in OverlayTemplates.all()) for ((w, h) in frames) {
            val scene = scene(preset, full, w, h)
            val where = "${preset.id} ${w}x$h"
            val margin = minOf(w, h) * 0.03f
            assertTrue(scene.widgets.isNotEmpty(), where)
            for (b in scene.widgets) {
                assertTrue(b.left >= margin && b.top >= margin && b.right <= w - margin && b.bottom <= h - margin, "$where ${b.id} $b")
            }
            for (i in scene.widgets.indices) for (j in i + 1 until scene.widgets.size) {
                val a = scene.widgets[i]
                val b = scene.widgets[j]
                val apart = a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top
                assertTrue(apart, "$where ${a.id} overlaps ${b.id}")
            }
        }
    }

    @Test
    fun aWidgetHiddenInOneOrientationIsNotDrawnThere() {
        val racing = OverlayTemplates.racing()
        assertTrue(scene(racing, full, 1080, 1920).widgets.any { it.id == "motor-temperature" })
        assertFalse(scene(racing, full, 1920, 1080).widgets.any { it.id == "motor-temperature" })
    }

    @Test
    fun aFourByThreeFrameShrinksTheLayoutInsteadOfCollidingAndWiderFramesDoNot() {
        assertEquals(1f, OverlayFrameScale(1920, 1080).layoutScale)
        assertEquals(1f, OverlayFrameScale(2520, 1080).layoutScale)
        assertEquals(0.75f, OverlayFrameScale(1440, 1080).layoutScale)
        assertEquals(0.75f, OverlayFrameScale(1080, 1440).layoutScale)
    }

    @Test
    fun anEmptyFrameDrawsNothingRatherThanDividingByZero() {
        assertTrue(scene(OverlayTemplates.hud(), full, 0, 0).ops.isEmpty())
    }

    // --- honesty: unknown is a dash, never a zero and never a full dial ---

    @Test
    fun anUnknownValueShowsADashWithNoUnitAndAnEmptyDial() {
        // Incoherent on purpose: finite-looking keys holding nothing, and a NaN no producer emits.
        val incoherent = live(
            VideoMetric.SPEED to VideoReading(null),
            VideoMetric.DUTY to VideoReading(Double.NaN, estimated = true, atCeiling = true),
            VideoMetric.SOC to VideoReading(Double.POSITIVE_INFINITY),
            VideoMetric.BATTERY_CURRENT to VideoReading(5.0),
        )
        val scene = scene(OverlayTemplates.hud(), incoherent, 1080, 1920)

        assertFalse(scene.has("км/ч"), "a dash carries no unit")
        assertFalse(scene.has("%"))
        assertFalse(scene.has("0"), "no fabricated zero")
        // Each quarter dial draws its under-stroke and track only.
        assertEquals(4, scene.ops.filterIsInstance<OverlayArcOp>().size)
        // The SoC bar has its track; no filled rectangle at the battery colour.
        val battery = OverlayPalette.BATTERY.toInt()
        assertTrue(scene.ops.filterIsInstance<OverlayRectOp>().none { it.fill == battery })
    }

    @Test
    fun aKnownValueFillsItsDialToItsFractionOfTheLearnedScale() {
        val scene = scene(OverlayTemplates.hud(), full, 1080, 1920, scales = mapOf(VideoMetric.SPEED to 80f))
        val fills = scene.ops.filterIsInstance<OverlayArcOp>().filter { it.color == OverlayPalette.CYAN.toInt() }
        assertEquals(-90f * 42f / 80f, fills.single().sweepDegrees, 0.01f)
    }

    @Test
    fun anEstimatedDutyHasASmallOrangeTildeAndTheCeilingSaysAtLeast() {
        val scene = scene(OverlayTemplates.hud(), full, 1080, 1920)
        val tilde = scene.run("~")
        assertEquals(OverlayPalette.ORANGE.toInt(), tilde.color)
        assertEquals(150f * 0.66f, tilde.size, 0.01f)
        assertTrue(scene.has("61"))

        val ceiling = live(VideoMetric.DUTY to VideoReading(100.0, estimated = true, atCeiling = true))
        assertTrue(scene(OverlayTemplates.hud(), ceiling, 1080, 1920).has("≥100"))
    }

    @Test
    fun aNegativeCarriesATypographicMinusAndARoundedZeroCarriesNone() {
        assertTrue(scene(OverlayTemplates.hud(), full, 1080, 1920).has("−18"))
        val tiny = live(VideoMetric.BATTERY_CURRENT to VideoReading(-0.3))
        val runs = scene(OverlayTemplates.hud(), tiny, 1080, 1920)
        assertTrue(runs.has("0"))
        assertFalse(runs.has("−0"))
        assertFalse(runs.has("-0"))
    }

    @Test
    fun milesAreHonouredOnTheHeroSpeed() {
        val scene = scene(OverlayTemplates.minimal(), live(VideoMetric.SPEED to VideoReading(100.0)), 1920, 1080, UnitSystem.IMPERIAL)
        assertTrue(scene.has("62"))
        assertTrue(scene.has("mph"))
    }

    // --- the burned-in state chips ---

    @Test
    fun noLinkIsBurnedInOnlyWhenNothingAtAllIsComing() {
        assertTrue(scene(OverlayTemplates.hud(), null, 1080, 1920).has("НЕТ СВЯЗИ"))
        val allDead = live(VideoMetric.SPEED to VideoReading(null), VideoMetric.SOC to VideoReading(Double.NaN))
        assertTrue(scene(OverlayTemplates.hud(), allDead, 1080, 1920).has("НЕТ СВЯЗИ"))
        // A battery-only vehicle under a speed preset has a link; its dashes are not a lost connection.
        val batteryOnly = live(VideoMetric.SPEED to VideoReading(null), VideoMetric.BATTERY_TEMPERATURE to VideoReading(30.0))
        assertFalse(scene(OverlayTemplates.minimal(), batteryOnly, 1080, 1920).has("НЕТ СВЯЗИ"))
        assertFalse(scene(OverlayTemplates.hud(), full, 1080, 1920).has("НЕТ СВЯЗИ"))
    }

    @Test
    fun demoIsAChipThatStacksUnderNoLinkInsteadOfOnIt() {
        val demo = live(VideoMetric.SPEED to VideoReading(null), demo = true)
        val scene = scene(OverlayTemplates.minimal(), demo, 1080, 1920)
        val noLink = scene.texts().single { it.runs.single().text == "НЕТ СВЯЗИ" }
        val badge = scene.texts().single { it.runs.single().text == "ДЕМО" }
        assertTrue(badge.baseline >= noLink.baseline + 66f)
    }

    @Test
    fun eachLayoutPutsTheChipWhereTheMockDoes() {
        val portrait = scene(OverlayTemplates.hud(), null, 1080, 1920).texts().single { it.runs.single().text == "НЕТ СВЯЗИ" }
        assertEquals(586f + 66f * 0.68f, portrait.baseline, 0.01f)
    }

    // --- bright frames ---

    @Test
    fun theHudProtectsItselfOnABrightFrame() {
        val scene = scene(OverlayTemplates.hud(), full, 1080, 1920)
        val scrims = scene.ops.filterIsInstance<OverlayScrimOp>()
        assertEquals(setOf(true, false), scrims.map { it.darkAtTop }.toSet())
        assertEquals(620f, scrims.single { it.darkAtTop }.bottom)
        assertEquals(1920f - 620f, scrims.single { !it.darkAtTop }.top)
        assertEquals(2, scene.ops.filterIsInstance<OverlayDiscOp>().size)

        val arcs = scene.ops.filterIsInstance<OverlayArcOp>()
        val under = arcs.first()
        assertEquals(OverlayPalette.GAUGE_UNDER.toInt(), under.color, "the dark under-stroke is painted first")
        assertTrue(under.strokeWidth > arcs[1].strokeWidth)
        // Any number standing on the frame carries a halo; one inside a card has the card.
        assertTrue(scene.texts().first { it.runs.any { run -> run.text == "42" } }.haloWidth > 0f)
    }

    @Test
    fun minimalLeavesTheTopOfTheFrameFree() {
        val scene = scene(OverlayTemplates.minimal(), full, 1920, 1080)
        assertTrue(scene.ops.filterIsInstance<OverlayScrimOp>().none { it.darkAtTop })
        assertTrue(scene.widgets.all { it.top > 1080 / 2 })
    }

    @Test
    fun onlyATopThresholdEarnsAChipAndItSaysTheNumber() {
        val hud = OverlayTemplates.hud()
        val hot = live(VideoMetric.ESC_TEMPERATURE to VideoReading(85.0), VideoMetric.DUTY to VideoReading(90.0))
        val scene = scene(hud, hot, 1080, 1920)
        val chip = scene.texts().single { it.runs.singleOrNull()?.text?.endsWith("90 %") == true }
        assertEquals(OverlayPalette.ON_ORANGE.toInt(), chip.runs.single().color)
        assertFalse(scene.texts().any { it.runs.singleOrNull()?.text?.endsWith("85 °C") == true }, "one chip, first widget wins")

        assertNull(overlayWarning(hud, live(VideoMetric.ESC_TEMPERATURE to VideoReading(70.0))), "the lower tier only recolours")
        assertNull(overlayWarning(hud, live(VideoMetric.ESC_TEMPERATURE to VideoReading(Double.NaN))))
        assertNull(overlayWarning(hud, null))
        assertEquals("soc", overlayWarning(hud, live(VideoMetric.SOC to VideoReading(10.0)))?.id)
    }

    // --- the racing preset ---

    @Test
    fun racingPrintsTheTripAndTicksTheSpeedDialFromItsScale() {
        val scene = scene(
            OverlayTemplates.racing(), full, 1080, 1920,
            trip = OverlayTrip(elapsedSeconds = 768, distanceKm = 8.44),
            scales = mapOf(VideoMetric.SPEED to 80f),
        )
        assertTrue(scene.has("12:48"))
        assertTrue(scene.has("8.4"))
        assertTrue(listOf("0", "20", "40", "60", "80").all { scene.has(it) })
        assertFalse(scene.has("100"))
    }

    @Test
    fun anUnknownTripIsADash() {
        val scene = scene(OverlayTemplates.racing(), full, 1080, 1920)
        assertEquals(2, scene.allRuns().count { it.text == NO_VALUE })
    }

    // --- a card keeps its size whatever the number ---

    @Test
    fun aValueSizeComesFromTheMetricsWidestTextNotFromTheCurrentNumber() {
        val narrow = OverlayTemplates.hud().widgets.first { it.metric == VideoMetric.BATTERY_CURRENT }
        val power = narrow.copy(metric = VideoMetric.BATTERY_POWER)
        val preset = OverlayTemplates.hud().copy(widgets = listOf(power))
        val small = scene(preset, live(VideoMetric.BATTERY_POWER to VideoReading(9.0)), 1920, 1080).run("9")
        val big = scene(preset, live(VideoMetric.BATTERY_POWER to VideoReading(12345.0)), 1920, 1080).run("12345")
        assertEquals(small.size, big.size)
    }

    // --- rules ---

    @Test
    fun aFiredRuleRecoloursTheValueAndTheLabelAndAnUnknownFiresNothing() {
        val hud = OverlayTemplates.hud()
        val hot = scene(hud, live(VideoMetric.ESC_TEMPERATURE to VideoReading(85.0)), 1080, 1920)
        assertEquals(OverlayPalette.PINK.toInt(), hot.run("85").color)
        assertEquals(OverlayPalette.PINK.toInt(), hot.allRuns().first { it.text.startsWith("CONTROLLER") || it.text.startsWith("ESC") }.color)

        val esc = hud.widgets.first { it.metric == VideoMetric.ESC_TEMPERATURE }
        assertFalse(overlayRuleFired(esc, VideoReading(null)))
        assertFalse(overlayRuleFired(esc, VideoReading(Double.NaN)))
        assertTrue(overlayRuleFired(esc, VideoReading(60.0)))
    }

    @Test
    fun aStudioRuleSwapsTheWholeCard() {
        val topRow = OverlayTemplates.byId("top-row")!!
        val scene = scene(topRow, live(VideoMetric.SOC to VideoReading(10.0)), 1920, 1080)
        assertTrue(scene.ops.filterIsInstance<OverlayRectOp>().any { it.fill == overlayColorArgb("#CCFF0000") })
        assertEquals(overlayColorArgb("#FF000000"), scene.run("10").color)
    }

    @Test
    fun aWarningIconShowsOnlyWhileARuleHasFired() {
        val duty = OverlayWidget(
            id = "d", metric = VideoMetric.DUTY, kind = OverlayWidgetKind.ICON_VALUE, icon = OverlayIcon.WARNING,
            rules = listOf(OverlayRule(OverlayComparison.GTE, 80.0, foreground = "#FFFF0000")),
        )
        assertEquals(OverlayIcon.GAUGE, overlayIconShown(duty, VideoReading(10.0)))
        assertEquals(OverlayIcon.GAUGE, overlayIconShown(duty, null))
        assertEquals(OverlayIcon.WARNING, overlayIconShown(duty, VideoReading(90.0)))
        assertEquals(OverlayIcon.GAUGE, autoOverlayIcon(VideoMetric.DUTY))
    }

    // --- saved presets, drawn by the older flow rules ---

    private fun card(id: String, metric: VideoMetric, kind: OverlayWidgetKind = OverlayWidgetKind.ICON_VALUE) =
        OverlayWidget(id = id, metric = metric, kind = kind)

    @Test
    fun aSavedPresetFlowsItsCardsAndDrawsTheArcWithNoSquareBehindIt() {
        val preset = OverlayPreset(
            id = "mine", name = "Mine",
            widgets = listOf(
                card("a", VideoMetric.VOLTAGE),
                card("b", VideoMetric.SOC, OverlayWidgetKind.VALUE_CARD),
                OverlayWidget(id = "arc", metric = VideoMetric.SPEED, kind = OverlayWidgetKind.ARC, arc = OverlayArc(y = 84f, radius = 130f)),
            ),
        )
        val scene = scene(preset, full, 1920, 1080)
        assertEquals(setOf("a", "b", "arc"), scene.widgets.map { it.id }.toSet())
        assertEquals(1, scene.ops.filterIsInstance<OverlayIconOp>().size)
        val arc = scene.widgets.first { it.id == "arc" }
        assertTrue(
            scene.ops.filterIsInstance<OverlayRectOp>().none { it.left == arc.left && it.top == arc.top },
            "the first port painted the card style behind a dial",
        )
    }

    @Test
    fun noRowIsEverDropped() {
        val metrics = VideoMetric.entries
        val preset = OverlayPreset("many", "Many", List(24) { card("w$it", metrics[it % metrics.size], OverlayWidgetKind.VALUE_CARD) })
        for ((w, h) in listOf(720 to 1280, 1280 to 720, 640 to 480)) {
            val scene = scene(preset, full, w, h)
            assertEquals(24, scene.widgets.size)
            assertTrue(scene.widgets.all { it.bottom <= h && it.right <= w && it.left >= 0 }, "${w}x$h")
        }
    }

    @Test
    fun theFlowsSizeFollowsTheShortEdgeSoAnOldPresetIsNoLongerTinyInPortrait() {
        val preset = OverlayPreset("p", "P", listOf(card("a", VideoMetric.SOC, OverlayWidgetKind.VALUE_CARD)))
        val value = scene(preset, live(VideoMetric.SOC to VideoReading(50.0)), 720, 1280).run("50")
        assertEquals(64f * 720f / 1080f, value.size, 0.01f)
    }

    @Test
    fun aRiderTypedLabelIsBurnedInAsTyped() {
        val preset = OverlayPreset("p", "P", listOf(card("a", VideoMetric.SOC, OverlayWidgetKind.VALUE_CARD).copy(label = "Мой заряд")))
        assertNotNull(scene(preset, full, 1920, 1080).allRuns().firstOrNull { it.text == "Мой заряд" })
    }

    @Test
    fun colourStringsParseAndGarbageIsWhiteNotACrash() {
        assertEquals(0xFF112233.toInt(), overlayColorArgb("#112233"))
        assertEquals(0x80112233.toInt(), overlayColorArgb("#80112233"))
        assertEquals(-1, overlayColorArgb("nonsense"))
        assertEquals("#FFEAF4F8", OverlayPalette.hex(OverlayPalette.TEXT))
        assertEquals("#00000001", OverlayPalette.hex(1L))
    }
}
