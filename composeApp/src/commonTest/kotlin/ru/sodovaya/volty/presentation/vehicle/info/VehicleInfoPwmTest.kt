package ru.sodovaya.volty.presentation.vehicle.info

import ru.sodovaya.volty.domain.alert.availabilityFor
import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.domain.pwm.AlarmOnset
import ru.sodovaya.volty.domain.pwm.PwmBasis
import ru.sodovaya.volty.domain.pwm.PwmCalibration
import ru.sodovaya.volty.domain.pwm.PwmCalibrationSource
import ru.sodovaya.volty.domain.pwm.PwmEvidence
import ru.sodovaya.volty.domain.pwm.PwmMemory
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The PWM block of the vehicle-info screen: the evidence with its reason, the threshold, the calibration, the live
 * estimate and the wheel's own alarm - and, beside it, the per-controller duty row that must not present the T4's
 * constant 0 as a measurement. Fixtures are deliberately incoherent where the contract is about absent data.
 */
@OptIn(ExperimentalTime::class)
class VehicleInfoPwmTest {
    private val wheel = Vehicle(
        id = "wheel", name = "T4", iconKey = "wheel",
        packs = listOf(Pack(0, "Wheel pack", BmsType.BEGODE, "WW")),
        controllers = listOf(Controller(0, "Wheel", ControllerType.BEGODE, "WW")),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0),
    )

    private fun cal(
        source: PwmCalibrationSource = PwmCalibrationSource.WIZARD, k: Float = 0.8421f, r: Float = 0.0312f,
        points: Int = 3, spread: Float? = 0.042f, basis: PwmBasis = PwmBasis.ONSET,
    ) = PwmCalibration(k, r, points, source, spread, basis, onsets = listOf(AlarmOnset(68.4f, 96.8f, 3f, 1L)))

    private fun motion(
        duty: Float = 0f, has: Boolean = true, source: DutySource = DutySource.MEASURED, ceiling: Boolean = false,
        alarm: Boolean? = null,
    ) = ControllerData(
        speedKmh = 40f, speedSource = SpeedSource.REPORTED, dutyPercent = duty, hasDuty = has,
        dutySource = source, dutyAtCeiling = ceiling, wheelPwmAlarm = alarm, isConnected = true,
    )

    private fun data(controller: ControllerData, vehicleLevel: ControllerData = controller) = VehicleData(
        controllers = listOf(ControllerState(wheel.controllers.single(), controller, isOnline = true)),
        motion = vehicleLevel,
    )

    private fun info(
        memory: PwmMemory, vehicle: Vehicle = wheel, d: VehicleData? = data(motion()), connected: Boolean = true,
    ): VehicleInfo = vehicleInfoOf(
        vehicle, connected, d, d?.motion, emptyList(), null, null, availabilityFor(vehicle, d?.motion),
        UnitSystem.METRIC, 100_000L, pwm = memory,
    )

    private fun VehicleInfo.pwm(label: InfoLabel): InfoRow =
        sections.first { it.kind == InfoSectionKind.PWM }.rows.first { it.label == label }

    private fun VehicleInfo.hasPwm(label: InfoLabel) =
        sections.first { it.kind == InfoSectionKind.PWM }.rows.any { it.label == label }

    private fun InfoRow.vu() = value to unit

    @Test fun `the PWM section sits right after the controllers`() {
        val kinds = info(PwmMemory.EMPTY).sections.map { it.kind }
        assertEquals(InfoSectionKind.CONTROLLERS, kinds[kinds.indexOf(InfoSectionKind.PWM) - 1])
    }

    @Test fun `the evidence state carries its own reason`() {
        assertEquals(InfoChoice.PWM_EVIDENCE_UNKNOWN, info(PwmMemory(null, PwmEvidence.UNKNOWN)).pwm(InfoLabel.PWM_EVIDENCE).choice)
        assertEquals(InfoChoice.PWM_EVIDENCE_NEVER_REPORTED, info(PwmMemory(null, PwmEvidence.NEVER_REPORTED)).pwm(InfoLabel.PWM_EVIDENCE).choice)
        assertEquals(InfoChoice.PWM_EVIDENCE_REPORTS, info(PwmMemory(null, PwmEvidence.REPORTS)).pwm(InfoLabel.PWM_EVIDENCE).choice)
    }

    @Test fun `the evidence and the threshold are shown even with no live link`() {
        val i = info(PwmMemory(null, PwmEvidence.NEVER_REPORTED), vehicle = wheel.copy(pwmAlarmThresholdPercent = 75), d = null, connected = false)
        assertEquals(InfoChoice.PWM_EVIDENCE_NEVER_REPORTED, i.pwm(InfoLabel.PWM_EVIDENCE).choice)
        assertEquals("75" to InfoUnit.PERCENT, i.pwm(InfoLabel.PWM_THRESHOLD).vu())
        assertEquals(InfoAbsence.NOT_CONNECTED, i.pwm(InfoLabel.PWM_ESTIMATE).reason)
        assertEquals(InfoAbsence.NOT_CONNECTED, i.pwm(InfoLabel.PWM_WHEEL_ALARM).reason)
    }

    @Test fun `a learned calibration shows its k, R, points, spread, source and basis`() {
        val i = info(PwmMemory(cal(), PwmEvidence.NEVER_REPORTED))
        assertEquals("0.842" to InfoUnit.VOLT_PER_KMH, i.pwm(InfoLabel.PWM_K).vu())
        assertEquals("0.0312" to InfoUnit.OHM, i.pwm(InfoLabel.PWM_R).vu())
        assertEquals("3", i.pwm(InfoLabel.PWM_POINTS).value)
        assertEquals("4.2" to InfoUnit.PERCENT, i.pwm(InfoLabel.PWM_SPREAD).vu())
        assertEquals(InfoChoice.PWM_SOURCE_WIZARD, i.pwm(InfoLabel.PWM_SOURCE).choice)
        assertEquals(InfoChoice.PWM_BASIS_ONSET, i.pwm(InfoLabel.PWM_BASIS).choice)
        assertEquals(
            InfoChoice.PWM_SOURCE_RIDING,
            info(PwmMemory(cal(PwmCalibrationSource.RIDING), PwmEvidence.NEVER_REPORTED)).pwm(InfoLabel.PWM_SOURCE).choice
        )
    }

    @Test fun `a spread that was not measured is not earned, never zero`() {
        val i = info(PwmMemory(cal(spread = null, basis = PwmBasis.FREE_SPIN), PwmEvidence.NEVER_REPORTED))
        assertEquals(InfoAbsence.NOT_EARNED, i.pwm(InfoLabel.PWM_SPREAD).reason)
        assertEquals(InfoChoice.PWM_BASIS_FREE_SPIN, i.pwm(InfoLabel.PWM_BASIS).choice)
    }

    @Test fun `no calibration at all is not earned on every calibration row`() {
        val i = info(PwmMemory(null, PwmEvidence.NEVER_REPORTED))
        for (label in listOf(InfoLabel.PWM_SOURCE, InfoLabel.PWM_K, InfoLabel.PWM_R, InfoLabel.PWM_POINTS, InfoLabel.PWM_SPREAD)) {
            assertEquals(InfoAbsence.NOT_EARNED, i.pwm(label).reason, "$label")
            assertNull(i.pwm(label).value, "$label")
        }
    }

    @Test fun `a stored MANUAL rows k is never shown, the typed point is`() {
        // Incoherent on purpose: a stale k of 9.999 beside a typed point that implies a very different one.
        val manualRow = cal(PwmCalibrationSource.MANUAL, k = 9.999f, r = 7.7777f, points = 4, basis = PwmBasis.FREE_SPIN)
        val v = wheel.copy(pwmManual = PwmManual(81.5f, 96.5f))
        val i = info(PwmMemory(manualRow, PwmEvidence.NEVER_REPORTED), vehicle = v)
        assertEquals(InfoChoice.PWM_SOURCE_MANUAL, i.pwm(InfoLabel.PWM_SOURCE).choice)
        assertFalse(i.hasPwm(InfoLabel.PWM_K), "the stale k has no row")
        assertFalse(i.hasPwm(InfoLabel.PWM_R), "nor has its R")
        assertEquals("81.5" to InfoUnit.KMH, i.pwm(InfoLabel.PWM_MANUAL_KMH).vu())
        assertEquals("96.5" to InfoUnit.VOLT, i.pwm(InfoLabel.PWM_MANUAL_VOLTS).vu())
        val shown = i.sections.flatMap { it.rows }.mapNotNull { it.value }.joinToString("|")
        assertFalse("9.999" in shown || "7.7777" in shown, "stale stored numbers leaked: $shown")
    }

    @Test fun `a typed point with no stored calibration is MANUAL too`() {
        val i = info(PwmMemory(null, PwmEvidence.NEVER_REPORTED), vehicle = wheel.copy(pwmManual = PwmManual(81.5f, 96.5f)))
        assertEquals(InfoChoice.PWM_SOURCE_MANUAL, i.pwm(InfoLabel.PWM_SOURCE).choice)
        assertEquals("81.5", i.pwm(InfoLabel.PWM_MANUAL_KMH).value)
    }

    @Test fun `the live estimate is shown with its value, and at the ceiling as at-least`() {
        val est = info(
            PwmMemory(cal(), PwmEvidence.NEVER_REPORTED),
            d = data(motion(duty = 61.6f, source = DutySource.ESTIMATED))
        )
        assertEquals("62" to InfoUnit.PERCENT, est.pwm(InfoLabel.PWM_ESTIMATE).vu())
        assertNull(est.pwm(InfoLabel.PWM_ESTIMATE).choice)

        val ceiling = info(
            PwmMemory(cal(), PwmEvidence.NEVER_REPORTED),
            d = data(motion(duty = 100f, source = DutySource.ESTIMATED, ceiling = true))
        )
        assertNull(ceiling.pwm(InfoLabel.PWM_ESTIMATE).value, "no number past the ceiling")
        assertEquals(InfoChoice.PWM_ESTIMATE_CEILING, ceiling.pwm(InfoLabel.PWM_ESTIMATE).choice)
    }

    @Test fun `no estimate says why`() {
        // proven and calibrated but not published this sample (hasDuty = false) with a number beside it: incoherent
        val notNow = info(
            PwmMemory(cal(), PwmEvidence.NEVER_REPORTED),
            d = data(motion(duty = 55f, has = false, source = DutySource.ESTIMATED))
        )
        assertNull(notNow.pwm(InfoLabel.PWM_ESTIMATE).value)
        assertEquals(InfoAbsence.NOT_YET, notNow.pwm(InfoLabel.PWM_ESTIMATE).reason)
        // proven but uncalibrated
        assertEquals(
            InfoAbsence.NOT_EARNED,
            info(PwmMemory(null, PwmEvidence.NEVER_REPORTED), d = data(motion(has = false))).pwm(InfoLabel.PWM_ESTIMATE).reason
        )
        // a wheel that reports its own PWM needs none, even if a stale calibration is kept
        assertEquals(
            InfoAbsence.PWM_WHEEL_REPORTS,
            info(PwmMemory(cal(), PwmEvidence.REPORTS), d = data(motion(duty = 12f))).pwm(InfoLabel.PWM_ESTIMATE).reason
        )
        // not proven yet
        assertEquals(
            InfoAbsence.PWM_NOT_PROVEN,
            info(PwmMemory(cal(), PwmEvidence.UNKNOWN), d = data(motion(duty = 0f))).pwm(InfoLabel.PWM_ESTIMATE).reason
        )
    }

    @Test fun `the wheels own alarm is surfaced as a fact, three ways`() {
        assertEquals(InfoChoice.PWM_ALARM_SOUNDING, info(PwmMemory.EMPTY, d = data(motion(alarm = true))).pwm(InfoLabel.PWM_WHEEL_ALARM).choice)
        assertEquals(InfoChoice.PWM_ALARM_QUIET, info(PwmMemory.EMPTY, d = data(motion(alarm = false))).pwm(InfoLabel.PWM_WHEEL_ALARM).choice)
        assertEquals(InfoAbsence.NOT_REPORTED, info(PwmMemory.EMPTY, d = data(motion(alarm = null))).pwm(InfoLabel.PWM_WHEEL_ALARM).reason)
    }

    // ---- the per-controller duty row (carried from the Task 3 review) ----------------------------

    private fun controllerDuty(i: VehicleInfo): InfoRow =
        i.sections.first { it.kind == InfoSectionKind.CONTROLLERS }.rows.first { it.label == InfoLabel.DUTY }

    @Test fun `a proven non-reporting wheels controller row never shows the raw constant as a duty`() {
        // The T4: the controller state still carries hasDuty = true and a constant; the vehicle-level motion carries
        // the estimate. A non-zero raw value is deliberately incoherent: it must not leak either.
        val raw = motion(duty = 12.8f, has = true)
        val i = info(
            PwmMemory(cal(), PwmEvidence.NEVER_REPORTED),
            d = data(raw, vehicleLevel = motion(duty = 61f, source = DutySource.ESTIMATED))
        )
        val row = controllerDuty(i)
        assertNull(row.value, "the wheel's constant is not a measurement")
        assertEquals(InfoAbsence.NOT_REPORTED, row.reason)
    }

    @Test fun `an unproven wheels controller row still shows what it reports, and a reporting wheels too`() {
        assertEquals("0.0", controllerDuty(info(PwmMemory(null, PwmEvidence.UNKNOWN), d = data(motion(duty = 0f)))).value)
        assertEquals("12.8", controllerDuty(info(PwmMemory(null, PwmEvidence.REPORTS), d = data(motion(duty = 12.8f)))).value)
    }
}
