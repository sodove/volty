package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayTemplatesTest {
    private val orange = OverlayPalette.hex(OverlayPalette.ORANGE)
    private val pink = OverlayPalette.hex(OverlayPalette.PINK)
    private val text = OverlayPalette.hex(OverlayPalette.TEXT)

    @Test
    fun theHudIsTheMocksC2AndHasNoIconsAtAll() {
        val hud = OverlayTemplates.hud()
        assertEquals(
            listOf(
                VideoMetric.SPEED, VideoMetric.DUTY, VideoMetric.BATTERY_CURRENT,
                VideoMetric.ESC_TEMPERATURE, VideoMetric.MOTOR_TEMPERATURE, VideoMetric.SOC,
            ),
            hud.widgets.map { it.metric },
        )
        assertEquals(OverlayWidgetKind.ARC_LEFT, hud.widgets[0].kind)
        assertEquals(OverlayWidgetKind.ARC_RIGHT, hud.widgets[1].kind)
        assertEquals(VideoMetric.VOLTAGE, hud.widgets.last().bar.secondary)
        assertTrue(hud.widgets.none { it.kind == OverlayWidgetKind.ICON_VALUE })
        assertTrue(hud.widgets.all { it.placement != null })
        val speedBox = hud.widgets[0].placement!!.portrait!!
        assertEquals(OverlayAnchor.TOP_START, speedBox.anchor)
        assertEquals(60f, speedBox.dx)
        assertEquals(150f, speedBox.valueSize)
    }

    @Test
    fun anAnchorStoresItsDistanceFromTheEdgeItIsAnchoredTo() {
        val hud = OverlayTemplates.hud()
        val duty = hud.widgets[1].placement!!
        assertEquals(OverlayAnchor.TOP_END, duty.portrait!!.anchor)
        assertEquals(1080f - 568f - 452f, duty.portrait!!.dx)
        val card = hud.widgets[3].placement!!.landscape!!
        assertEquals(OverlayAnchor.BOTTOM_CENTER, card.anchor)
        assertEquals(838f + 125f - 960f, card.dx)
        assertEquals(1080f - 694f - 166f, card.dy)
    }

    @Test
    fun ruleThresholdsUseThePaletteAndKeepTheirOrder() {
        val hud = OverlayTemplates.hud()
        val soc = hud.widgets.single { it.metric == VideoMetric.SOC }
        val temperature = hud.widgets.single { it.metric == VideoMetric.ESC_TEMPERATURE }
        val duty = hud.widgets.single { it.metric == VideoMetric.DUTY }

        assertEquals(orange, overlayStyle(soc, VideoReading(20.0)).foreground)
        assertEquals(pink, overlayStyle(soc, VideoReading(10.0)).foreground)
        assertEquals(text, overlayStyle(soc, VideoReading(40.0)).foreground)
        assertEquals(orange, overlayStyle(temperature, VideoReading(70.0)).foreground)
        assertEquals(pink, overlayStyle(temperature, VideoReading(80.0)).foreground)
        assertEquals(orange, overlayStyle(duty, VideoReading(75.0)).foreground)
        assertEquals(pink, overlayStyle(duty, VideoReading(90.0)).foreground)
    }

    @Test
    fun minimalIsSpeedAndDuty() {
        val minimal = OverlayTemplates.minimal()
        assertEquals(listOf(VideoMetric.SPEED, VideoMetric.DUTY), minimal.widgets.map { it.metric })
        assertEquals(OverlayWidgetKind.TEXT, minimal.widgets[0].kind)
        assertEquals(240f, minimal.widgets[0].placement!!.portrait!!.valueSize)
        assertEquals(OverlayWidgetKind.BAR, minimal.widgets[1].kind)
    }

    @Test
    fun racingHasTheTripTheTickedDialAndNoMotorCardInLandscape() {
        val racing = OverlayTemplates.racing()
        assertTrue(racing.widgets.any { it.metric == VideoMetric.ELAPSED })
        assertTrue(racing.widgets.any { it.metric == VideoMetric.DISTANCE })
        val speed = racing.widgets.single { it.metric == VideoMetric.SPEED }
        assertEquals(OverlayWidgetKind.ARC, speed.kind)
        assertTrue(speed.arc.ticks && speed.arc.autoScale)
        assertNull(racing.widgets.single { it.metric == VideoMetric.MOTOR_TEMPERATURE }.placement!!.landscape)
    }

    @Test
    fun theStudioPortsAreItsCardsItsRulesAndItsFlow() {
        val topRow = OverlayTemplates.byId("top-row")!!
        assertEquals(
            listOf(
                VideoMetric.SPEED, VideoMetric.VOLTAGE, VideoMetric.SOC, VideoMetric.BATTERY_TEMPERATURE,
                VideoMetric.CONTROLLER_POWER, VideoMetric.CONTROLLER_CURRENT,
            ),
            topRow.widgets.map { it.metric },
        )
        assertFalse(topRow.centerCards)
        assertTrue(topRow.widgets.all { it.placement == null && it.style.background == "#B3000000" && it.style.cornerRadius == 16f })
        assertEquals("#CCFFA500", overlayStyle(topRow.widgets[2], VideoReading(20.0)).background)
        assertEquals("#CCFF0000", overlayStyle(topRow.widgets[3], VideoReading(85.0)).background)

        val panel = OverlayTemplates.byId("studio-panel")!!
        assertEquals(OverlayWidgetKind.ARC, panel.widgets.single { it.metric == VideoMetric.SPEED }.kind)
        assertEquals(setOf(VideoMetric.SPEED, VideoMetric.SOC), OverlayTemplates.byId("studio-large")!!.widgets.map { it.metric }.toSet())
    }

    @Test
    fun noPresetCarriesASpeedRule() {
        // Studio turns a card yellow at 40 km/h: meaningless across a bicycle and a wheel. The one exception is the
        // owner's own dashboard (meow_icons), reproduced by the owner's ruling of 2026-11-03 with the thresholds its
        // JSON carries; it is his wheel's, chosen by him, not a default.
        OverlayTemplates.all().filterNot { it.id.startsWith("ts-meow") }.flatMap { it.widgets }.filter { it.metric == VideoMetric.SPEED }.forEach {
            assertTrue(it.rules.isEmpty(), it.id)
        }
    }

    @Test
    fun theBuiltInsAreEightDistinctValidPresets() {
        val all = OverlayTemplates.all()
        assertEquals(8, all.map { it.id }.toSet().size)
        assertTrue(all.all(::validOverlayPreset))
        assertEquals("hud", all[1].id)
        assertNull(OverlayTemplates.byId("nope"))
    }

    // --- old documents ---

    /** A document as 0.7.63 wrote it: English labels, no new field. */
    private val shipped = """
        {"version":1,"selected":{"id":"minimal","name":"Minimal","widgets":[
        {"id":"speed","metric":"SPEED","kind":"ICON_VALUE","icon":"GAUGE","label":"Speed","unit":"km/h",
         "style":{"background":"#99000000","foreground":"#FFFFFFFF","fontSize":48.0,"cornerRadius":10.0,"padding":12.0,"shadow":true},
         "rules":[],"arc":{"x":50.0,"y":82.0,"radius":110.0,"min":0.0,"max":100.0,"thickness":9.0}},
        {"id":"soc","metric":"SOC","kind":"ICON_VALUE","icon":"BATTERY","label":"SOC batt","unit":"%",
         "style":{"background":"#99000000","foreground":"#FFFFFFFF","fontSize":28.0,"cornerRadius":10.0,"padding":12.0,"shadow":true},
         "rules":[{"comparison":"LT","threshold":30.0,"foreground":"#FFFFC857","background":null},
                  {"comparison":"LT","threshold":15.0,"foreground":"#FFFF7070","background":null}],
         "arc":{"x":50.0,"y":82.0,"radius":110.0,"min":0.0,"max":100.0,"thickness":9.0}}
        ],"centerCards":true},"saved":[]}
    """.trimIndent()

    @Test
    fun anUntouchedShippedPresetBecomesTheNewPresetOfThatName() {
        val decoded = OverlayCodec.decode(shipped)
        assertEquals(OverlayTemplates.minimal(), decoded.selected)
        assertEquals(OverlayTripScope.RECORDING, decoded.tripScope)
    }

    @Test
    fun aPresetTheRiderEditedDecodesAsItIs() {
        val edited = shipped.replace("\"fontSize\":48.0", "\"fontSize\":50.0")
        val decoded = OverlayCodec.decode(edited)
        assertNotEquals(OverlayTemplates.minimal(), decoded.selected)
        assertEquals("minimal", decoded.selected.id)
        assertEquals(50f, decoded.selected.widgets[0].style.fontSize)
        assertNull(decoded.selected.widgets[0].placement)
    }

    @Test
    fun aShippedCopyUnderAnotherIdIsNotABuiltInAndStaysInTheSavedList() {
        val shippedPreset = OverlayCodec.decode(shipped.replace("\"minimal\"", "\"keep\"")).selected
        val document = OverlayDocument(selected = OverlayTemplates.hud(), saved = listOf(shippedPreset))
        assertEquals(document, OverlayCodec.decode(OverlayCodec.encode(document)))
    }

    @Test
    fun aNewDocumentRoundTripsWithItsPlacementsAndScope() {
        val document = OverlayDocument(selected = OverlayTemplates.racing(), saved = OverlayTemplates.all(), tripScope = OverlayTripScope.RIDE)
        assertEquals(document, OverlayCodec.decode(OverlayCodec.encode(document)))
    }

    @Test
    fun invalidGeometryIsRejected() {
        val hud = OverlayTemplates.hud()
        fun withBox(box: OverlayBox) = hud.copy(widgets = hud.widgets.map { it.copy(placement = OverlayPlacement(box, null)) })
        val good = OverlayBox(OverlayAnchor.CENTER, 0f, 0f, 100f, 100f)
        assertTrue(validOverlayPreset(withBox(good)))
        assertFalse(validOverlayPreset(withBox(good.copy(width = Float.NaN))))
        assertFalse(validOverlayPreset(withBox(good.copy(valueSize = 10_000f))))
        assertFalse(validOverlayPreset(withBox(good.copy(dx = 1e9f))))
        val widget = hud.widgets.first()
        assertFalse(validOverlayPreset(hud.copy(widgets = listOf(widget.copy(style = widget.style.copy(accent = "cyan"))))))
        assertFalse(validOverlayPreset(hud.copy(widgets = listOf(widget.copy(bar = OverlayBar(height = 0f))))))
        val layout = hud.layout!!
        assertFalse(validOverlayPreset(hud.copy(layout = layout.copy(portrait = OverlayFrameSpec(chipY = Float.NaN)))))
    }
}
