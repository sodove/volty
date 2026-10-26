package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.alert.ActiveAlert
import ru.sodovaya.volty.domain.usecase.AlertKind
import ru.sodovaya.volty.domain.usecase.AlertSeverity
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class AlertBannerRowTest {

    @Test
    fun `numbers are shown in the rider's units and the right precision`() {
        assertEquals("87 %", alertBannerNumber(AlertKind.DUTY_HIGH, 87.4f, UnitSystem.METRIC))
        assertEquals("14 %", alertBannerNumber(AlertKind.SOC_LOW, 14.2f, UnitSystem.METRIC))
        assertEquals("52", alertBannerNumber(AlertKind.SPEED_HIGH, 52f, UnitSystem.METRIC))
        // 52 km/h is 32.3 mph: the number converts, so the template must say mph, not km/h
        assertEquals("32", alertBannerNumber(AlertKind.SPEED_HIGH, 52f, UnitSystem.IMPERIAL))
        assertEquals("85", alertBannerNumber(AlertKind.ESC_TEMP_HIGH, 85.4f, UnitSystem.IMPERIAL))
        assertEquals("90", alertBannerNumber(AlertKind.MOTOR_TEMP_HIGH, 89.6f, UnitSystem.METRIC))
        assertEquals("4.25", alertBannerNumber(AlertKind.CELL_HIGH, 4.254f, UnitSystem.METRIC))
        assertEquals("250", alertBannerNumber(AlertKind.CELL_DELTA, 250.2f, UnitSystem.METRIC))
    }

    @Test
    fun `an alert row is keyed by its kind so episodes of one kind share a row`() {
        assertEquals(alertRowKey(AlertKind.DUTY_HIGH), alertRowKey(AlertKind.DUTY_HIGH))
        kotlin.test.assertNotEquals(alertRowKey(AlertKind.DUTY_HIGH), alertRowKey(AlertKind.SPEED_HIGH))
    }

    private fun alertRow(severity: AlertSeverity) = RideDashboardComponent.FaultEntry(
        alertRowKey(AlertKind.DUTY_HIGH), 1, true, ActiveAlert(AlertKind.DUTY_HIGH, 85f, severity)
    )

    @Test
    fun `the banner is red for any fault or critical alert and amber only for warnings`() {
        assertEquals(BannerTone.WARNING, bannerTone(listOf(alertRow(AlertSeverity.WARNING))))
        assertEquals(BannerTone.WARNING, bannerTone(listOf(alertRow(AlertSeverity.INFO))))
        assertEquals(BannerTone.ERROR, bannerTone(listOf(alertRow(AlertSeverity.CRITICAL))))
        assertEquals(
            BannerTone.ERROR,
            bannerTone(listOf(alertRow(AlertSeverity.WARNING), RideDashboardComponent.FaultEntry("over-voltage")))
        )
    }

    @Test
    fun `the title says faults while any real fault is listed and alerts otherwise`() {
        assertEquals(true, bannerTitleIsFaults(listOf(RideDashboardComponent.FaultEntry("over-voltage"))))
        assertEquals(false, bannerTitleIsFaults(listOf(alertRow(AlertSeverity.CRITICAL))))
        assertEquals(
            true,
            bannerTitleIsFaults(listOf(alertRow(AlertSeverity.CRITICAL), RideDashboardComponent.FaultEntry("x")))
        )
    }

    @Test
    fun `the wheel's own alarm has its own row key and is not folded into the duty row`() {
        assertEquals("alert:WHEEL_PWM_ALARM", alertRowKey(AlertKind.WHEEL_PWM_ALARM))
        kotlin.test.assertNotEquals(alertRowKey(AlertKind.DUTY_HIGH), alertRowKey(AlertKind.WHEEL_PWM_ALARM))
        assertEquals(false, autoHides(AlertKind.WHEEL_PWM_ALARM))
    }
}
