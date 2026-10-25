package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.usecase.AlertKind
import ru.sodovaya.volty.domain.usecase.AlertSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * What the dashboard banner is told is engaged. The contract is about the GATED, graded state:
 * the same confirmation the notification and the sound alarm apply, never a raw sample.
 */
@OptIn(ExperimentalTime::class)
class ActiveAlertTrackerTest {

    private fun vehicle(
        rules: List<AlertRule> = listOf(
            AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f), AlertLevel(90f))),
            AlertRule(MotionAlertKind.SPEED, listOf(AlertLevel(40f))),
            AlertRule(MotionAlertKind.MOTOR_TEMP, listOf(AlertLevel(80f))),
            AlertRule(MotionAlertKind.ESC_TEMP, listOf(AlertLevel(80f))),
        ),
        config: AlertConfig = AlertConfig()
    ) = Vehicle(
        id = "v1", name = "Bike", iconKey = "bike", packs = emptyList(),
        controllers = listOf(Controller(index = 0, label = "Main", controllerType = ControllerType.VESC, address = "AA")),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now(),
        motionAlerts = rules, alertConfig = config
    )

    private fun motion(duty: Float = 10f, speed: Float = 10f, motor: Float = 30f, esc: Float = 30f) = ControllerData(
        dutyPercent = duty, speedKmh = speed, speedSource = SpeedSource.REPORTED,
        motorTempC = motor, hasMotorTemp = true, escTempC = esc,
        isConnected = true
    )

    @Test
    fun `duty over its step is an alert carrying the reading and the severity of the step`() {
        val t = ActiveAlertTracker()
        val v = vehicle()
        assertEquals(emptyList(), t.updateMotion(motion(duty = 50f), v))
        assertEquals(
            listOf(ActiveAlert(AlertKind.DUTY_HIGH, 85f, AlertSeverity.WARNING)),
            t.updateMotion(motion(duty = 85f), v)
        )
        assertEquals(
            listOf(ActiveAlert(AlertKind.DUTY_HIGH, 95f, AlertSeverity.CRITICAL)),
            t.updateMotion(motion(duty = 95f), v)
        )
    }

    @Test
    fun `speed is an alert in canonical km per hour`() {
        val t = ActiveAlertTracker()
        assertEquals(
            listOf(ActiveAlert(AlertKind.SPEED_HIGH, 52f, AlertSeverity.CRITICAL)),
            t.updateMotion(motion(speed = 52f), vehicle())
        )
    }

    @Test
    fun `one corrupt temperature frame creates no row, three agreeing frames do`() {
        val t = ActiveAlertTracker()
        val v = vehicle()
        t.updateMotion(motion(esc = 40f), v)
        t.updateMotion(motion(esc = 40f), v)
        t.updateMotion(motion(esc = 41f), v)
        // A checksum-less frame at the edge of range: 130 degrees from 41 is a jump, not a reading.
        assertEquals(emptyList(), t.updateMotion(motion(esc = 130f), v))
        assertEquals(emptyList(), t.updateMotion(motion(esc = 41f), v))
        // A genuine climb: three agreeing frames over the step engage it.
        t.updateMotion(motion(esc = 85f), v)
        t.updateMotion(motion(esc = 86f), v)
        assertEquals(
            listOf(ActiveAlert(AlertKind.ESC_TEMP_HIGH, 87f, AlertSeverity.CRITICAL)),
            t.updateMotion(motion(esc = 87f), v)
        )
    }

    @Test
    fun `a disconnected placeholder shows nothing and forgets the engaged alert`() {
        val t = ActiveAlertTracker()
        val v = vehicle()
        assertTrue(t.updateMotion(motion(duty = 95f), v).isNotEmpty())
        // zero-filled placeholder, deliberately incoherent: a duty of 95 that is "not connected"
        assertEquals(emptyList(), t.updateMotion(motion(duty = 95f).copy(isConnected = false), v))
    }

    @Test
    fun `no vehicle means no rows`() {
        assertEquals(emptyList(), ActiveAlertTracker().updateMotion(motion(duty = 95f), null))
        assertEquals(emptyList(), ActiveAlertTracker().updateBattery(BmsData(soc = 5f, isConnected = true), null))
    }

    @Test
    fun `a duty rule the rider switched off raises no row`() {
        val v = vehicle(rules = listOf(AlertRule(MotionAlertKind.DUTY, emptyList())))
        assertEquals(emptyList(), ActiveAlertTracker().updateMotion(motion(duty = 99f), v))
    }

    // ---- the battery half -------------------------------------------------------------

    private fun pack(soc: Float = 80f, socKnown: Boolean = true, cells: List<Float> = emptyList(), temps: List<Float> = emptyList()) =
        BmsData(soc = soc, socKnown = socKnown, cellVoltages = cells, temperatures = temps, isConnected = true)

    @Test
    fun `low charge is a row with the reading, released with the engine's hysteresis`() {
        val t = ActiveAlertTracker()
        val v = vehicle()
        assertEquals(emptyList(), t.updateBattery(pack(soc = 40f), v))
        assertEquals(
            listOf(ActiveAlert(AlertKind.SOC_LOW, 14f, AlertSeverity.WARNING)),
            t.updateBattery(pack(soc = 14f), v)
        )
        // 16..18 is inside the release band (soc must exceed threshold + 3): still engaged.
        assertEquals(1, t.updateBattery(pack(soc = 17f), v).size)
        assertEquals(emptyList(), t.updateBattery(pack(soc = 19f), v))
    }

    @Test
    fun `an unknown charge is not an empty one`() {
        // soc = 0 with socKnown = false: a combination the unknown-vs-zero contract exists for.
        assertEquals(emptyList(), ActiveAlertTracker().updateBattery(pack(soc = 0f, socKnown = false), vehicle()))
    }

    @Test
    fun `a cell over its limit is a critical row with the cell voltage`() {
        val rows = ActiveAlertTracker().updateBattery(pack(cells = listOf(4.1f, 4.25f, 4.1f)), vehicle())
        assertTrue(ActiveAlert(AlertKind.CELL_HIGH, 4.25f, AlertSeverity.CRITICAL) in rows, rows.toString())
    }

    @Test
    fun `a battery alert the rider disabled raises no row`() {
        val v = vehicle(config = AlertConfig(socLowEnabled = false))
        assertEquals(emptyList(), ActiveAlertTracker().updateBattery(pack(soc = 5f), v))
    }

    @Test
    fun `a single corrupt battery temperature is not an alert`() {
        val t = ActiveAlertTracker()
        val v = vehicle()
        repeat(3) { t.updateBattery(pack(temps = listOf(30f)), v) }
        assertEquals(emptyList(), t.updateBattery(pack(temps = listOf(120f)), v))
    }

    @Test
    fun `the battery stream going disconnected clears its rows`() {
        val t = ActiveAlertTracker()
        val v = vehicle()
        assertTrue(t.updateBattery(pack(soc = 5f), v).isNotEmpty())
        assertEquals(emptyList(), t.updateBattery(pack(soc = 5f).copy(isConnected = false), v))
        // and a returning sample is judged afresh
        assertEquals(1, t.updateBattery(pack(soc = 5f), v).size)
    }

    @Test
    fun `reset forgets what is engaged`() {
        val t = ActiveAlertTracker()
        val v = vehicle()
        t.updateBattery(pack(soc = 17f), v) // not yet engaged (17 >= 15)
        t.updateBattery(pack(soc = 5f), v)
        t.reset()
        // 17 would stay engaged under hysteresis if state survived the reset
        assertEquals(emptyList(), t.updateBattery(pack(soc = 17f), v))
    }

    @Test
    fun `worseOf keeps the lowest for the low kinds and the highest for the rest`() {
        assertEquals(2.9f, worseOf(AlertKind.CELL_LOW, 3.0f, 2.9f))
        assertEquals(2.9f, worseOf(AlertKind.CELL_LOW, 2.9f, 3.0f))
        assertEquals(14f, worseOf(AlertKind.SOC_LOW, 14f, 19f))
        assertEquals(12f, worseOf(AlertKind.SOC_LOW, 19f, 12f))
        assertEquals(95f, worseOf(AlertKind.DUTY_HIGH, 95f, 85f))
        assertEquals(95f, worseOf(AlertKind.DUTY_HIGH, 85f, 95f))
    }
}
