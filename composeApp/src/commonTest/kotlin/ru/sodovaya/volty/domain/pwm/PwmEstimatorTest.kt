package ru.sodovaya.volty.domain.pwm

import ru.sodovaya.volty.data.ble.VehicleConnection
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DutySource
import ru.sodovaya.volty.domain.model.PwmManual
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.wheelVehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class PwmEstimatorTest {

    private val epoch = Instant.fromEpochSeconds(0)

    /** The rider's measured T4 onset: alarm at 68.4 km/h, 96.8 V, 80 % threshold -> k = 1.1322. */
    private val measured = PwmCalibrationLearner.fromOnsets(listOf(AlarmOnset(68.4f, 96.8f, null, 0L)), 80f)!!

    private fun wheel(manual: PwmManual? = null): Vehicle =
        wheelVehicle("v1", "T4", "unicycle", "AA:BB", Chemistry.LI_ION_NMC, epoch).copy(pwmManual = manual)

    private inner class Rig(
        val vehicle: Vehicle = wheel(),
        memory: PwmMemory = PwmMemory.EMPTY,
        awaitMemory: Boolean = false,
        val packV: Float? = 96f,
    ) {
        var nowMs = 1_000_000L
        val published = mutableListOf<VehicleData>()
        var firstApplies = 0
        val est = PwmEstimator(vehicle, memory, awaitMemory) { firstApplies++ }
        val conn = VehicleConnection(
            packs = vehicle.packs, controllers = vehicle.controllers, topology = vehicle.topology,
            onVehicleData = { published += it }, clock = { Instant.fromEpochMilliseconds(nowMs) }, pwm = est,
        )
        val motion: ControllerData get() = published.last().motion

        /** One 250 ms step: a pack sample (when [packV] is known), then a motion sample stamped with the step's time. */
        fun step(
            speed: Float = 60f, duty: Float = 0f, hasDuty: Boolean = true, alarm: Boolean? = null,
            phase: Float = 0f, inputV: Float = 0f, hasInputV: Boolean = false, speedSource: SpeedSource = SpeedSource.REPORTED,
        ) {
            nowMs += 250
            if (packV != null) conn.submit(0, BmsData(voltage = packV, isConnected = true))
            conn.submitMotion(
                0,
                ControllerData(
                    speedKmh = speed, speedSource = speedSource, dutyPercent = duty, hasDuty = hasDuty,
                    wheelPwmAlarm = alarm, motorCurrentA = phase, inputVoltageV = inputV, hasInputVoltage = hasInputV,
                    isConnected = true, timestamp = Instant.fromEpochMilliseconds(nowMs),
                )
            )
        }
    }

    private val neverReported = PwmMemory(null, PwmEvidence.NEVER_REPORTED)
    private val provenWithMeasured = PwmMemory(measured, PwmEvidence.NEVER_REPORTED)

    @Test
    fun `a T4 with no calibration publishes no duty at all`() {
        val r = Rig(memory = neverReported)
        r.step(duty = 0f, hasDuty = true, alarm = false)
        assertFalse(r.motion.hasDuty, "the wheel's constant 0 is a placeholder once it is proven, not a reading")
        assertEquals(DutySource.MEASURED, r.motion.dutySource)
    }

    @Test
    fun `with the measured calibration the T4 at 60 kmh and 96 V reads about 70_8 percent, marked estimated`() {
        val r = Rig(memory = provenWithMeasured)
        r.step(speed = 60f, duty = 0f, hasDuty = true)
        assertTrue(r.motion.hasDuty)
        assertEquals(DutySource.ESTIMATED, r.motion.dutySource)
        assertEquals(70.76f, r.motion.dutyPercent, 0.05f)
        assertFalse(r.motion.dutyAtCeiling)
    }

    @Test
    fun `a wheel that reports real duty stays MEASURED even with a calibration present`() {
        val r = Rig(memory = PwmMemory(measured, PwmEvidence.REPORTS))
        r.step(speed = 60f, duty = 37f, hasDuty = true)
        assertEquals(37f, r.motion.dutyPercent)
        assertEquals(DutySource.MEASURED, r.motion.dutySource)
    }

    @Test
    fun `a measured non-zero duty latches REPORTS in the same snapshot and the estimate never shows`() {
        val r = Rig(memory = provenWithMeasured)
        r.step(speed = 60f, duty = 2f, hasDuty = true)
        assertEquals(PwmEvidence.REPORTS, r.est.evidence)
        assertEquals(2f, r.motion.dutyPercent)
        assertEquals(DutySource.MEASURED, r.motion.dutySource)
        assertTrue(r.est.dirty, "a latch is persisted promptly")
        r.step(speed = 60f, duty = 0f, hasDuty = true)
        assertEquals(DutySource.MEASURED, r.motion.dutySource, "REPORTS is one-way")
    }

    @Test
    fun `incoherent hasDuty false with dutyPercent 37 never leaks as an estimate or a reading`() {
        val withCal = Rig(memory = provenWithMeasured)
        withCal.step(speed = 60f, duty = 37f, hasDuty = false)
        assertEquals(70.76f, withCal.motion.dutyPercent, 0.05f)
        val noCal = Rig(memory = neverReported)
        noCal.step(speed = 60f, duty = 37f, hasDuty = false)
        assertFalse(noCal.motion.hasDuty)
        assertEquals(0f, noCal.motion.dutyPercent)
        val unknown = Rig()
        repeat(40) { unknown.step(speed = 60f, duty = 37f, hasDuty = false) }
        assertEquals(PwmEvidence.UNKNOWN, unknown.est.evidence, "a flagged-absent duty is no evidence either way")
    }

    @Test
    fun `evidence becomes NEVER_REPORTED after five seconds moving at a constant zero and is saved promptly`() {
        val r = Rig()
        repeat(10) { r.step(speed = 30f, duty = 0f) }
        assertEquals(PwmEvidence.UNKNOWN, r.est.evidence)
        assertTrue(r.motion.hasDuty, "until proven, the snapshot is untouched")
        assertFalse(r.est.dirty)
        repeat(12) { r.step(speed = 30f, duty = 0f) }
        assertEquals(PwmEvidence.NEVER_REPORTED, r.est.evidence)
        assertTrue(r.est.dirty)
        assertFalse(r.motion.hasDuty)
    }

    @Test
    fun `a republish of the same motion sample counts no moving time`() {
        val r = Rig()
        r.step(speed = 30f, duty = 0f)
        repeat(200) { r.conn.submit(0, BmsData(voltage = 96f, isConnected = true)) }
        assertEquals(PwmEvidence.UNKNOWN, r.est.evidence)
    }

    @Test
    fun `a rising edge of the wheel alarm at speed teaches one onset and persists it`() {
        val r = Rig(memory = neverReported)
        r.step(speed = 66f, alarm = false)
        assertNull(r.est.memory().calibration)
        r.step(speed = 68.4f, alarm = true)
        val cal = assertNotNull(r.est.memory().calibration)
        assertEquals(1, cal.onsets.size)
        assertEquals(68.4f, cal.onsets[0].speedKmh)
        assertEquals(96f, cal.onsets[0].packV)
        assertEquals(0.8f * 96f / 68.4f, cal.k, 1e-4f)
        assertTrue(r.est.dirty)
        assertEquals(DutySource.ESTIMATED, r.motion.dutySource)
        assertEquals(80f, r.motion.dutyPercent, 0.1f, "the estimate agrees with the alarm threshold at the onset")
    }

    @Test
    fun `a held alarm bit teaches once, not every frame`() {
        val r = Rig(memory = neverReported)
        r.step(speed = 66f, alarm = false)
        repeat(30) { r.step(speed = 68.4f, alarm = true) }
        assertEquals(1, r.est.memory().calibration!!.onsets.size)
        r.step(speed = 66f, alarm = false)
        r.step(speed = 69f, alarm = true)
        assertEquals(2, r.est.memory().calibration!!.onsets.size, "a new edge is a new onset")
    }

    @Test
    fun `an alarm that is already on at the first sample was not seen rising`() {
        val r = Rig(memory = neverReported)
        r.step(speed = 70f, alarm = true)
        r.step(speed = 70f, alarm = true)
        assertNull(r.est.memory().calibration)
    }

    @Test
    fun `an unknown alarm signal never teaches, and a rising edge below 10 kmh is ignored`() {
        val r = Rig(memory = neverReported)
        r.step(speed = 70f, alarm = null)
        r.step(speed = 70f, alarm = null)
        assertNull(r.est.memory().calibration)
        r.step(speed = 5f, alarm = false)
        r.step(speed = 5f, alarm = true)
        assertNull(r.est.memory().calibration)
    }

    @Test
    fun `a rising edge teaches nothing for a wheel that reports duty`() {
        val r = Rig(memory = PwmMemory(null, PwmEvidence.REPORTS))
        r.step(speed = 66f, duty = 3f, alarm = false)
        r.step(speed = 68.4f, duty = 80f, alarm = true)
        assertNull(r.est.memory().calibration)
    }

    @Test
    fun `a gap in the link resets the edge state`() {
        val r = Rig(memory = neverReported)
        r.step(speed = 66f, alarm = false)
        r.conn.markControllersOffline(listOf(0))
        r.step(speed = 68.4f, alarm = true)
        assertNull(r.est.memory().calibration, "the false before the gap is not a seen edge")
    }

    @Test
    fun `a typed manual free spin point estimates, and a stored calibration wins over it`() {
        val manual = PwmManual(81.6f, 96.5f)
        val m = Rig(vehicle = wheel(manual), memory = neverReported)
        m.step(speed = 81.6f)
        assertEquals(DutySource.ESTIMATED, m.motion.dutySource)
        // k = 96.5 / 81.6, at 81.6 km/h on a 96 V pack: 96.5 / 96 = 100.5 %, clamped.
        assertEquals(100f, m.motion.dutyPercent)
        assertTrue(m.motion.dutyAtCeiling)
        val stored = Rig(vehicle = wheel(manual), memory = provenWithMeasured)
        stored.step(speed = 60f)
        assertEquals(70.76f, stored.motion.dutyPercent, 0.05f, "the stored calibration, not the manual one")
    }

    @Test
    fun `a manual calibration learns onsets and is replaced by the onset fit after three`() {
        val r = Rig(vehicle = wheel(PwmManual(81.6f, 96.5f)), memory = neverReported)
        repeat(3) { i ->
            r.step(speed = 66f + i, alarm = false)
            r.step(speed = 68f + i, alarm = true)
        }
        val cal = r.est.memory().calibration!!
        assertEquals(PwmBasis.ONSET, cal.basis)
        assertEquals(3, cal.onsets.size)
    }

    @Test
    fun `above the ceiling the estimate is 100 flagged, never extrapolated`() {
        val r = Rig(memory = provenWithMeasured)
        r.step(speed = 100f)
        assertEquals(100f, r.motion.dutyPercent)
        assertTrue(r.motion.dutyAtCeiling)
    }

    @Test
    fun `without a known speed there is no estimate`() {
        val r = Rig(memory = provenWithMeasured)
        r.step(speed = 60f, speedSource = SpeedSource.NONE)
        assertFalse(r.motion.hasDuty)
    }

    @Test
    fun `voltage falls back to the controller's measured input voltage, and no voltage means no estimate`() {
        val viaInput = Rig(memory = provenWithMeasured, packV = null)
        viaInput.step(speed = 60f, inputV = 96f, hasInputV = true)
        assertEquals(70.76f, viaInput.motion.dutyPercent, 0.05f)
        val none = Rig(memory = provenWithMeasured, packV = null)
        none.step(speed = 60f, inputV = 96f, hasInputV = false)
        assertFalse(none.motion.hasDuty, "an unflagged input voltage is a placeholder")
    }

    @Test
    fun `a learned R takes the phase current from the aggregate`() {
        val withR = measured.copy(r = 0.05f)
        val r = Rig(memory = PwmMemory(withR, PwmEvidence.NEVER_REPORTED))
        r.step(speed = 60f, phase = 40f)
        assertEquals((withR.k * 60f + 0.05f * 40f) / 96f * 100f, r.motion.dutyPercent, 0.05f)
    }

    @Test
    fun `nothing happens until the memory is installed, then the estimate runs`() {
        val r = Rig(awaitMemory = true)
        r.step(speed = 60f, duty = 0f)
        assertEquals(1, r.firstApplies)
        assertTrue(r.motion.hasDuty)
        assertFalse(r.est.memoryReady)
        r.est.installMemory(provenWithMeasured)
        r.step(speed = 60f, duty = 0f)
        assertEquals(DutySource.ESTIMATED, r.motion.dutySource)
        assertEquals(1, r.firstApplies, "the load hook fires once")
    }

    @Test
    fun `a late install can lift UNKNOWN to the stored REPORTS but never lower it`() {
        val a = Rig(awaitMemory = true)
        a.est.installMemory(PwmMemory(null, PwmEvidence.REPORTS))
        assertEquals(PwmEvidence.REPORTS, a.est.evidence)
        val b = Rig(memory = PwmMemory(null, PwmEvidence.REPORTS), awaitMemory = true)
        b.est.installMemory(PwmMemory(null, PwmEvidence.UNKNOWN))
        assertEquals(PwmEvidence.REPORTS, b.est.evidence)
    }

    @Test
    fun `no estimator means the snapshot is published as built`() {
        val published = mutableListOf<VehicleData>()
        val v = wheel()
        val c = VehicleConnection(v.packs, controllers = v.controllers, topology = v.topology, onVehicleData = { published += it })
        c.submitMotion(0, ControllerData(speedKmh = 60f, speedSource = SpeedSource.REPORTED, dutyPercent = 0f, isConnected = true))
        assertEquals(DutySource.MEASURED, published.last().motion.dutySource)
        assertTrue(published.last().motion.hasDuty)
    }

    @Test
    fun `evidence progress starting at REPORTS with duty 0 while moving stays REPORTS`() {
        var q = PwmEvidenceProgress(PwmEvidence.REPORTS)
        for (i in 0 until 40) {
            q = q.next(
                ControllerData(
                    speedKmh = 30f, speedSource = SpeedSource.REPORTED, dutyPercent = 0f, hasDuty = true,
                    timestamp = Instant.fromEpochMilliseconds(i * 250L)
                )
            )
        }
        assertEquals(PwmEvidence.REPORTS, q.evidence)
    }
}
