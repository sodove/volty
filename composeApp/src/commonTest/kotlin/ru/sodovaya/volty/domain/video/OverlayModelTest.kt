package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayModelTest {
    @Test
    fun widgetDefaultsUseTheMetricUnit() {
        val expectedUnits = mapOf(
            VideoMetric.SPEED to "km/h",
            VideoMetric.DUTY to "%",
            VideoMetric.ESC_TEMPERATURE to "°C",
            VideoMetric.MOTOR_TEMPERATURE to "°C",
            VideoMetric.MOTOR_CURRENT to "A",
            VideoMetric.CONTROLLER_CURRENT to "A",
            VideoMetric.CONTROLLER_POWER to "W",
            VideoMetric.SOC to "%",
            VideoMetric.VOLTAGE to "V",
            VideoMetric.BATTERY_CURRENT to "A",
            VideoMetric.BATTERY_POWER to "W",
            VideoMetric.BATTERY_TEMPERATURE to "°C",
        )

        expectedUnits.forEach { (metric, unit) ->
            assertEquals(unit, OverlayWidget(id = metric.name, metric = metric).unit)
        }
    }

    @Test
    fun templatesIncludeAllStartingLayoutsAndHudIsTheDefault() {
        assertEquals(
            setOf("Top Row", "HUD", "Minimal", "Racing", "Icon chips", "Icon chips + trip", "Panel", "Large"),
            OverlayTemplates.all().map { it.name }.toSet(),
        )
        assertEquals(OverlayTemplates.hud(), OverlayDocument().selected)
        assertTrue(OverlayTemplates.all().all(::validOverlayPreset))
    }

    @Test
    fun validCustomizedDocumentSurvivesJsonRoundTrip() {
        val widget = OverlayWidget(
            id = "speed-main",
            metric = VideoMetric.SPEED,
            kind = OverlayWidgetKind.ARC,
            icon = OverlayIcon.GAUGE,
            label = "Скорость",
            style = OverlayStyle(
                background = "#99000000",
                foreground = "#FFFFFFFF",
                fontSize = 32f,
                cornerRadius = 14f,
                padding = 8f,
                shadow = false,
            ),
            rules = listOf(
                OverlayRule(
                    comparison = OverlayComparison.GTE,
                    threshold = 45.0,
                    foreground = "#FFFF0000",
                ),
            ),
            arc = OverlayArc(x = 48f, y = 80f, radius = 125f, min = 0f, max = 70f, thickness = 11f),
        )
        val selected = OverlayPreset(id = "custom", name = "Ride", widgets = listOf(widget))
        val document = OverlayDocument(selected = selected, saved = listOf(OverlayTemplates.hud()))

        assertEquals(document, OverlayCodec.decode(OverlayCodec.encode(document)))
    }

    @Test
    fun decodeFallsBackForMalformedUnsupportedAndOversizedJson() {
        val defaultDocument = OverlayDocument()

        assertEquals(defaultDocument, OverlayCodec.decode("not json"))
        assertEquals(defaultDocument, OverlayCodec.decode("{\"version\":99}"))
        assertEquals(
            defaultDocument,
            OverlayCodec.decode("""{"version":1,"selected":{"id":"","name":"","widgets":[]} ,"saved":[]}"""),
        )
        assertEquals(defaultDocument, OverlayCodec.decode(" ".repeat(70_000)))
    }

    @Test
    fun presetValidationRejectsTooManyWidgetsAndDuplicateIds() {
        val tooMany = OverlayPreset(
            id = "large",
            name = "Large",
            widgets = (0..24).map { OverlayWidget(id = "widget-$it", metric = VideoMetric.SPEED) },
        )
        val duplicateIds = OverlayPreset(
            id = "duplicate",
            name = "Duplicate",
            widgets = listOf(
                OverlayWidget(id = "same", metric = VideoMetric.SPEED),
                OverlayWidget(id = "same", metric = VideoMetric.SOC),
            ),
        )

        assertFalse(validOverlayPreset(tooMany))
        assertFalse(validOverlayPreset(duplicateIds))
    }

    @Test
    fun presetValidationRejectsMalformedColorsAndNonFiniteDimensions() {
        val invalidColor = OverlayPreset(
            id = "invalid-color",
            name = "Invalid color",
            widgets = listOf(
                OverlayWidget(
                    id = "speed",
                    metric = VideoMetric.SPEED,
                    style = OverlayStyle(foreground = "red"),
                ),
            ),
        )
        val nonFiniteFont = OverlayPreset(
            id = "nan-font",
            name = "NaN font",
            widgets = listOf(
                OverlayWidget(
                    id = "speed",
                    metric = VideoMetric.SPEED,
                    style = OverlayStyle(fontSize = Float.NaN),
                ),
            ),
        )
        val nonFiniteArc = OverlayPreset(
            id = "infinite-arc",
            name = "Infinite arc",
            widgets = listOf(
                OverlayWidget(
                    id = "speed",
                    metric = VideoMetric.SPEED,
                    kind = OverlayWidgetKind.ARC,
                    arc = OverlayArc(radius = Float.POSITIVE_INFINITY),
                ),
            ),
        )

        assertFalse(validOverlayPreset(invalidColor))
        assertFalse(validOverlayPreset(nonFiniteFont))
        assertFalse(validOverlayPreset(nonFiniteArc))
    }

    @Test
    fun presetValidationRejectsInvertedArcBoundsAndNonFiniteThresholds() {
        val invertedArc = OverlayPreset(
            id = "inverted-arc",
            name = "Inverted arc",
            widgets = listOf(
                OverlayWidget(
                    id = "speed",
                    metric = VideoMetric.SPEED,
                    kind = OverlayWidgetKind.ARC,
                    arc = OverlayArc(min = 80f, max = 20f),
                ),
            ),
        )
        val nonFiniteRule = OverlayPreset(
            id = "infinite-rule",
            name = "Infinite rule",
            widgets = listOf(
                OverlayWidget(
                    id = "speed",
                    metric = VideoMetric.SPEED,
                    rules = listOf(OverlayRule(OverlayComparison.GT, Double.POSITIVE_INFINITY)),
                ),
            ),
        )

        assertFalse(validOverlayPreset(invertedArc))
        assertFalse(validOverlayPreset(nonFiniteRule))
    }
}
