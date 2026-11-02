package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoOverlayTextTest {
    private val russian = VideoOverlayStrings(
        labels = VideoMetric.entries.associateWith { "ру-${it.name}" },
        kmh = "км/ч",
        watt = "Вт",
        demo = "ДЕМО",
    )

    // --- a label follows its metric ---

    @Test
    fun aTemplateCardChangedToAnotherMetricNeverKeepsTheOldMetricsWord() {
        val soc = OverlayTemplates.hud().widgets.single { it.metric == VideoMetric.SOC }
        val speed = overlayWidgetWithMetric(soc, VideoMetric.SPEED)

        assertEquals(VideoMetric.SPEED, speed.metric)
        assertEquals("", speed.label)
        assertEquals(OverlayIcon.AUTO, speed.icon, "SOC's own battery icon must not stay over a speed")
        assertEquals("ру-SPEED", overlayLabel(speed, russian))
        assertEquals("km/h", speed.unit)
    }

    @Test
    fun aSavedPresetWithTheOldEnglishDefaultLabelIsStillFollowedAndLocalized() {
        val legacy = OverlayWidget(id = "v", metric = VideoMetric.VOLTAGE, label = "U batt", icon = OverlayIcon.BATTERY)

        assertTrue(isAutoOverlayLabel(legacy))
        assertEquals("ру-VOLTAGE", overlayLabel(legacy, russian))
        assertEquals("ру-BATTERY_POWER", overlayLabel(overlayWidgetWithMetric(legacy, VideoMetric.BATTERY_POWER), russian))
    }

    @Test
    fun textTheRiderTypedIsTheirsAndSurvivesAMetricChange() {
        val custom = OverlayWidget(
            id = "c", metric = VideoMetric.SOC, label = "Мой заряд", icon = OverlayIcon.WARNING,
        )

        assertFalse(isAutoOverlayLabel(custom))
        val changed = overlayWidgetWithMetric(custom, VideoMetric.VOLTAGE)
        assertEquals("Мой заряд", changed.label)
        assertEquals("Мой заряд", overlayLabel(changed, russian))
        assertEquals(OverlayIcon.WARNING, changed.icon, "a deliberate icon choice stays")
    }

    @Test
    fun anotherMetricsDefaultWordOnThisMetricIsNotAutomaticItIsSomebodysText() {
        // Deliberately incoherent: SOC carrying SPEED's old default. No template ever wrote it, so it is the rider's.
        val odd = OverlayWidget(id = "o", metric = VideoMetric.SOC, label = "Speed")

        assertFalse(isAutoOverlayLabel(odd))
        assertEquals("Speed", overlayWidgetWithMetric(odd, VideoMetric.VOLTAGE).label)
    }

    @Test
    fun changingToTheSameMetricChangesNothing() {
        val widget = OverlayWidget(id = "w", metric = VideoMetric.SPEED, label = "SPEED")

        assertEquals(widget, overlayWidgetWithMetric(widget, VideoMetric.SPEED))
    }

    @Test
    fun noTemplateBurnsAnEnglishLiteralIn() {
        OverlayTemplates.all().flatMap { it.widgets }.forEach { widget ->
            assertEquals("", widget.label, "${widget.id} must follow its metric")
        }
    }

    // --- units come from the formatter, miles are honoured ---

    @Test
    fun speedUnitAndValueFollowTheUnitSystemThroughTheFormatter() {
        val strings = VideoOverlayStrings()
        assertEquals(UnitFormatter.speedUnit(UnitSystem.IMPERIAL), strings.unit(VideoMetric.SPEED, UnitSystem.IMPERIAL))
        assertEquals("mph", strings.unit(VideoMetric.SPEED, UnitSystem.IMPERIAL))
        assertEquals("km/h", strings.unit(VideoMetric.SPEED, UnitSystem.METRIC))
        assertEquals("км/ч", russian.unit(VideoMetric.SPEED, UnitSystem.METRIC))

        val reading = VideoReading(80.6)
        assertEquals("81", videoValueText(VideoMetric.SPEED, reading, UnitSystem.METRIC))
        assertEquals(UnitFormatter.speed(80.6f, UnitSystem.IMPERIAL), videoValueText(VideoMetric.SPEED, reading, UnitSystem.IMPERIAL))
        assertEquals("50", videoValueText(VideoMetric.SPEED, reading, UnitSystem.IMPERIAL))
    }

    @Test
    fun onlySpeedAndDistanceChangeWithTheUnitSystem() {
        VideoMetric.entries.filter { it != VideoMetric.SPEED && it != VideoMetric.DISTANCE }.forEach { metric ->
            assertEquals(
                VideoOverlayStrings().unit(metric, UnitSystem.METRIC),
                VideoOverlayStrings().unit(metric, UnitSystem.IMPERIAL),
                metric.name,
            )
            assertEquals(
                videoValueText(metric, VideoReading(33.4), UnitSystem.METRIC),
                videoValueText(metric, VideoReading(33.4), UnitSystem.IMPERIAL),
                metric.name,
            )
        }
    }

    @Test
    fun localizedUnitsReachTheNonSpeedMetrics() {
        assertEquals("Вт", russian.unit(VideoMetric.CONTROLLER_POWER, UnitSystem.METRIC))
        assertEquals("Вт", russian.unit(VideoMetric.BATTERY_POWER, UnitSystem.IMPERIAL))
    }

    @Test
    fun theUnitSystemHonoursTheModelsStoredUnitFieldNoLonger() {
        // A saved widget whose free-text unit says "mph" over km/h: the burned-in unit must come from the system.
        val stale = OverlayWidget(id = "s", metric = VideoMetric.SPEED, unit = "mph")

        assertEquals("km/h", VideoOverlayStrings().unit(stale.metric, UnitSystem.METRIC))
    }

    // --- no -0, one minus, provenance ---

    @Test
    fun aSmallNegativeIsZeroNotMinusZero() {
        (VideoMetric.entries - VideoMetric.ELAPSED - VideoMetric.DISTANCE - VideoMetric.VOLTAGE).forEach { metric ->
            listOf(-0.3, -0.49, -0.0).forEach { value ->
                val text = videoValueText(metric, VideoReading(value), UnitSystem.METRIC)
                assertFalse("-0" in text, "$metric $value -> $text")
                assertEquals("0", text, "$metric $value")
            }
        }
        assertEquals("−2", videoValueText(VideoMetric.CONTROLLER_POWER, VideoReading(-1.6), UnitSystem.METRIC))
        assertEquals("0", videoValueText(VideoMetric.SPEED, VideoReading(-0.3), UnitSystem.IMPERIAL))
    }

    @Test
    fun unknownAndNonFiniteValuesAreADashNeverANumber() {
        assertEquals("—", videoValueText(VideoMetric.SOC, null, UnitSystem.METRIC))
        assertEquals("—", videoValueText(VideoMetric.SOC, VideoReading(null), UnitSystem.METRIC))
        assertEquals("—", videoValueText(VideoMetric.SOC, VideoReading(Double.NaN), UnitSystem.METRIC))
        assertEquals("—", videoValueText(VideoMetric.SPEED, VideoReading(Double.POSITIVE_INFINITY), UnitSystem.IMPERIAL))
    }

    @Test
    fun dutyKeepsItsEstimateMarksThroughTheSharedDashboardRule() {
        val metric = VideoMetric.DUTY
        assertEquals("67", videoValueText(metric, VideoReading(67.4), UnitSystem.METRIC))
        assertEquals("~67", videoValueText(metric, VideoReading(67.4, estimated = true), UnitSystem.METRIC))
        assertEquals("≥100", videoValueText(metric, VideoReading(100.0, estimated = true, atCeiling = true), UnitSystem.METRIC))
        // Only an estimate can be at its ceiling: a measured 100 is a number.
        assertEquals("100", videoValueText(metric, VideoReading(100.0, estimated = false, atCeiling = true), UnitSystem.METRIC))
    }

    @Test
    fun voltageKeepsItsDecimalLikeTheDashboard() {
        assertEquals("96.4", videoValueText(VideoMetric.VOLTAGE, VideoReading(96.42), UnitSystem.METRIC))
    }

    @Test
    fun theExemplarsUseTheSameMinusAsTheValues() {
        assertEquals("−12", videoValueText(VideoMetric.BATTERY_CURRENT, VideoReading(-12.0), UnitSystem.METRIC))
        VideoMetric.entries.forEach { metric ->
            assertFalse('-' in videoValueExemplar(metric), "${metric.name} exemplar must use the minus the values print")
        }
    }
}
