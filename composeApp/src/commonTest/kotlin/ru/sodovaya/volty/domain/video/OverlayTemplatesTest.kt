package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayTemplatesTest {
    @Test
    fun hudHasSixNamedSourceCardsAndALargeTransparentSpeedArc() {
        val hud = OverlayTemplates.hud()
        val cards = hud.widgets.filter { it.kind == OverlayWidgetKind.ICON_VALUE }
        val speedArc = hud.widgets.single { it.metric == VideoMetric.SPEED }

        assertEquals(
            listOf(
                VideoMetric.VOLTAGE,
                VideoMetric.SOC,
                VideoMetric.BATTERY_TEMPERATURE,
                VideoMetric.CONTROLLER_POWER,
                VideoMetric.CONTROLLER_CURRENT,
                VideoMetric.DUTY,
            ),
            cards.map { it.metric },
        )
        assertEquals(OverlayWidgetKind.ARC, speedArc.kind)
        assertEquals(OverlayIcon.GAUGE, speedArc.icon)
        assertEquals(50f, speedArc.arc.x)
        assertEquals(84f, speedArc.arc.y)
        assertEquals(130f, speedArc.arc.radius)
        assertEquals("#00000000", speedArc.style.background)
        assertEquals("#FFFFC857", speedArc.style.foreground)
        assertEquals(48f, speedArc.style.fontSize)
    }

    @Test
    fun hudThresholdsApplyOrderedSocTemperatureAndDutyWarnings() {
        val hud = OverlayTemplates.hud()
        val soc = hud.widgets.single { it.metric == VideoMetric.SOC }
        val batteryTemperature = hud.widgets.single { it.metric == VideoMetric.BATTERY_TEMPERATURE }
        val duty = hud.widgets.single { it.metric == VideoMetric.DUTY }

        assertEquals("#FFFFC857", overlayStyle(soc, VideoReading(20.0)).foreground)
        assertEquals("#FFFF7070", overlayStyle(soc, VideoReading(10.0)).foreground)
        assertEquals("#FFFFFFFF", overlayStyle(soc, VideoReading(40.0)).foreground)
        assertEquals("#FFFFC857", overlayStyle(batteryTemperature, VideoReading(70.0)).foreground)
        assertEquals("#FFFF7070", overlayStyle(batteryTemperature, VideoReading(80.0)).foreground)
        assertEquals("#FFFFC857", overlayStyle(duty, VideoReading(75.0)).foreground)
        assertEquals("#FFFF7070", overlayStyle(duty, VideoReading(90.0)).foreground)
    }

    @Test
    fun topRowMinimalAndRacingKeepTheirDistinctMetricSets() {
        val topRow = OverlayTemplates.all().single { it.id == "top-row" }
        val minimal = OverlayTemplates.all().single { it.id == "minimal" }
        val racing = OverlayTemplates.all().single { it.id == "racing" }

        assertEquals(
            listOf(
                VideoMetric.SPEED,
                VideoMetric.VOLTAGE,
                VideoMetric.SOC,
                VideoMetric.BATTERY_TEMPERATURE,
                VideoMetric.CONTROLLER_POWER,
                VideoMetric.CONTROLLER_CURRENT,
            ),
            topRow.widgets.map { it.metric },
        )
        assertEquals(setOf(VideoMetric.SPEED, VideoMetric.SOC), minimal.widgets.map { it.metric }.toSet())
        assertEquals(48f, minimal.widgets.single { it.metric == VideoMetric.SPEED }.style.fontSize)
        assertEquals(
            listOf(
                VideoMetric.DUTY,
                VideoMetric.MOTOR_CURRENT,
                VideoMetric.ESC_TEMPERATURE,
                VideoMetric.MOTOR_TEMPERATURE,
                VideoMetric.CONTROLLER_POWER,
                VideoMetric.SOC,
            ),
            racing.widgets.filter { it.kind != OverlayWidgetKind.ARC }.map { it.metric },
        )
        assertEquals(OverlayWidgetKind.ARC, racing.widgets.single { it.metric == VideoMetric.SPEED }.kind)
    }
}
