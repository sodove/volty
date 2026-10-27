package ru.sodovaya.volty.domain.pwm

import ru.sodovaya.volty.data.ble.VehicleConnection
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DutySource
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.wheelVehicle
import ru.sodovaya.volty.presentation.vehicle.pwm.PwmWizardNotice
import ru.sodovaya.volty.presentation.vehicle.pwm.PwmWizardState
import ru.sodovaya.volty.presentation.vehicle.pwm.WizardSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Which sample an alarm onset is taken from. The rider's T4, after a firmware update, reports its TRUE PWM beside the
 * alarm bit, so the error of the onset speed became measurable: the bit is first SEEN one frame after it came on,
 * so the sample where it is first seen is faster than the speed it fired at, and a calibration fitted to it reads
 * LOW. The rule: the onset is the last sample BEFORE the rising edge (in practice the last one at the previous speed).
 *
 * Most of this drives the REAL BegodeProtocol over the REAL dump ([T4OnsetDump]); the synthetic cases are the
 * rule's edges (a jump, a gap, a bit already set, the in-between samples of one cycle).
 */
@OptIn(ExperimentalTime::class)
class PwmOnsetCaptureTest {

    private val epoch = Instant.fromEpochSeconds(0)
    private val vehicle: Vehicle = wheelVehicle("v1", "T4", "unicycle", "AA:BB", Chemistry.LI_ION_NMC, epoch)
    private val neverReported = PwmMemory(null, PwmEvidence.NEVER_REPORTED)

    /** A riding rig: a vehicle connection whose clock is the sample's own time, with a PwmEstimator that learns. */
    private inner class Rig {
        var nowMs = 1_000_000L
        val published = mutableListOf<VehicleData>()
        val est = PwmEstimator(vehicle, neverReported, false)
        val conn = VehicleConnection(
            packs = vehicle.packs, controllers = vehicle.controllers, topology = vehicle.topology,
            onVehicleData = { published += it }, clock = { Instant.fromEpochMilliseconds(nowMs) }, pwm = est,
        )

        fun feed(atMs: Long, speed: Float, packV: Float, alarm: Boolean?, phase: Float = 0f, extraNanos: Long = 0L) {
            nowMs = atMs
            conn.submit(0, BmsData(voltage = packV, isConnected = true))
            conn.submitMotion(
                0,
                // dutyPercent 0 + hasDuty: what a T4 on the OLD firmware publishes, so the wheel is never proven to
                // report PWM and the estimator keeps learning (the dump's true duty is kept apart as the truth).
                ControllerData(
                    speedKmh = speed, speedSource = SpeedSource.REPORTED, dutyPercent = 0f, hasDuty = true, wheelPwmAlarm = alarm, motorCurrentA = phase,
                    isConnected = true, timestamp = Instant.fromEpochSeconds(atMs / 1000, (atMs % 1000) * 1_000_000L + extraNanos),
                ),
            )
        }

        fun onsets(): List<AlarmOnset> = est.memory().calibration?.onsets.orEmpty()
    }

    /** Every replayed sample through the rig; each paired with what the estimator published for it. */
    private fun replayThrough(rig: Rig): List<Pair<ReplayedSample, ControllerData>> =
        T4OnsetReplay.samples().map { s ->
            val m = s.motion
            rig.feed(s.atMillis, m.speedKmh, m.inputVoltageV, m.wheelPwmAlarm, m.motorCurrentA)
            s to rig.published.last().motion
        }

    // ---- the real dump, riding ---------------------------------------------------------------------------------

    @Test
    fun `riding on the real T4 dump takes the onset speed from the cycle BEFORE the alarm bit was seen`() {
        val rig = Rig()
        replayThrough(rig)
        val onset = rig.onsets().single()
        assertEquals(65.1f, onset.speedKmh, 0.1f, "the last sample before the edge, not the 70.8 km/h edge sample")
        assertEquals(96.5f, onset.packV, 0.1f)
    }

    @Test
    fun `riding on the real dump, the estimate is never below the true duty and within five points above it`() {
        val rig = Rig()
        val out = replayThrough(rig)
        // The true duty (frame 0x07) of a cycle arrives in the LAST sample of that cycle, so the samples carrying
        // the cycle's truth are the ones whose duty field just changed; judge the estimate on those.
        val atEdge = out.filter { (s, _) -> s.motion.wheelPwmAlarm == true && s.motion.speedKmh in 70.7f..70.8f && s.motion.dutyPercent == 84f }
        assertTrue(atEdge.isNotEmpty(), "the edge cycle's 0x07 sample must be in the replay")
        for ((_, pub) in atEdge) {
            assertTrue(pub.hasDuty)
            assertTrue(pub.dutyPercent >= 84f, "estimate ${pub.dutyPercent} must not be below the true 84 at 70.8 km/h")
            assertTrue(pub.dutyPercent <= 89f, "estimate ${pub.dutyPercent} within +5 of 84")
            assertEquals(DutySource.ESTIMATED, pub.dutySource)
        }
        val plateau = out.last()
        assertEquals(100f, plateau.first.motion.dutyPercent, "the dump's true duty on the plateau")
        // Raw 99.4 % at the plateau against a true duty the wheel reports as the integer 100: one point of rounding.
        assertTrue(plateau.second.dutyPercent >= 99f, "plateau estimate ${plateau.second.dutyPercent} vs true 100")
        assertTrue(plateau.second.dutyPercent <= 105f)
    }

    // ---- the rule's edges, riding ------------------------------------------------------------------------------

    @Test
    fun `riding rejects a 9 kmh jump into the edge sample, and accepts a 5 kmh one (the control)`() {
        val jumped = Rig().apply { feed(1_000, 60f, 96f, false); feed(1_300, 69f, 96f, true) }
        assertTrue(jumped.onsets().isEmpty(), "a 9 km/h jump says nothing about where the alarm fired")
        val steady = Rig().apply { feed(1_000, 60f, 96f, false); feed(1_300, 65f, 96f, true) }
        assertEquals(60f, steady.onsets().single().speedKmh, "the control: the same path with a 5 km/h jump learns, from the PREVIOUS sample")
    }

    @Test
    fun `riding rejects an edge more than a second after the previous sample`() {
        val gap = Rig().apply { feed(1_000, 60f, 96f, false); feed(2_500, 63f, 96f, true) }
        assertTrue(gap.onsets().isEmpty())
    }

    @Test
    fun `riding learns nothing when the bit is already set on the first sample, and learns the next real edge`() {
        val rig = Rig()
        rig.feed(1_000, 60f, 96f, true)
        rig.feed(1_300, 62f, 96f, true)
        assertTrue(rig.onsets().isEmpty())
        rig.feed(1_600, 55f, 96f, false)
        rig.feed(1_900, 58f, 96f, true)
        assertEquals(55f, rig.onsets().single().speedKmh, "control: a real edge afterwards still teaches")
    }

    @Test
    fun `riding decelerating into the alarm takes the slower speed, not the faster previous one`() {
        val rig = Rig().apply { feed(1_000, 72f, 96f, false); feed(1_300, 68.4f, 96f, true) }
        assertEquals(68.4f, rig.onsets().single().speedKmh)
    }

    @Test
    fun `a battery-only republish of the same motion is not a new sample, and a stalled link stays rejected`() {
        val rig = Rig().apply { feed(1_000, 60f, 96f, false) }
        val before = rig.published.size
        rig.nowMs = 3_000
        rig.conn.submit(0, BmsData(voltage = 96f, isConnected = true))
        assertTrue(rig.published.size > before, "the republish must actually have happened")
        rig.feed(3_300, 63f, 96f, true)
        assertTrue(rig.onsets().isEmpty(), "2.3 s since the last real motion sample")
    }

    @Test
    fun `riding sees an edge in a distinct motion sample that shares the millisecond of the previous one`() {
        val rig = Rig()
        rig.feed(1_000, 60f, 96f, false)
        rig.feed(1_000, 62f, 96f, true, extraNanos = 500_000L) // same epoch-ms, a different Instant
        assertEquals(60f, rig.onsets().single().speedKmh)
    }

    @Test
    fun `riding skips the in-between samples that repeat the edge speed (a live frame, then the alarm frame)`() {
        val rig = Rig()
        rig.feed(1_000, 60f, 96f, false)
        rig.feed(1_300, 64f, 96f, false) // the next cycle's live frame: new speed, stale alarm bit
        rig.feed(1_310, 64f, 96f, true)  // its 0x04 frame: the bit, at the SAME speed
        assertEquals(60f, rig.onsets().single().speedKmh)
    }

    // ---- the real dump, wizard ---------------------------------------------------------------------------------

    private fun wizardSamples() = T4OnsetReplay.samples().map {
        WizardSample(it.atMillis, it.motion.speedKmh, it.motion.inputVoltageV, it.motion.wheelPwmAlarm)
    }

    @Test
    fun `the wizard on the real T4 dump captures the onset from before the edge and the fit is safe`() {
        val samples = wizardSamples()
        var st = PwmWizardState(thresholdPercent = 80).onSample(samples[0]).onSample(samples[1]).start(connected = true)
        for (s in samples.drop(2)) st = st.onSample(s)
        val onset = st.onsets.single()
        assertEquals(65.1f, onset.speedKmh, 0.1f)
        assertEquals(96.5f, onset.packV, 0.1f)
        val cal = PwmCalibrationLearner.fromOnsets(st.onsets, 80f)!!
        val at70 = PwmModel.estimatePercent(cal, 70.776f, 96.39f, null)!!.percent
        assertTrue(at70 >= 84f && at70 <= 89f, "estimate $at70 at 70.8 km/h vs true 84")
        val atPlateau = PwmModel.estimatePercent(cal, 80.532f, 96.09f, null)!!.percent
        assertTrue(atPlateau >= 99f && atPlateau <= 105f, "estimate $atPlateau at the plateau vs true 100")
    }

    // ---- the rule's edges, wizard ------------------------------------------------------------------------------

    private fun thresholdState() = PwmWizardState(thresholdPercent = 80)
        .onSample(WizardSample(0, 0f, 96f, false)).start(connected = true)

    @Test
    fun `the wizard rejects a 9 kmh jump with a spin slower notice, keeps waiting, and takes the next slow run`() {
        var st = thresholdState().onSample(WizardSample(1_000, 60f, 96f, false)).onSample(WizardSample(1_300, 69f, 96f, true))
        assertTrue(st.onsets.isEmpty())
        assertEquals(PwmWizardNotice.SPIN_SLOWER, st.notice)
        assertFalse(st.runCaptured, "the same run keeps waiting")
        st = st.onSample(WizardSample(1_600, 20f, 96f, false))
            .onSample(WizardSample(1_900, 60f, 96f, false))
            .onSample(WizardSample(2_200, 64f, 96f, true))
        assertEquals(60f, st.onsets.single().speedKmh)
        assertNull(st.notice)
        assertTrue(st.runCaptured)
    }

    @Test
    fun `the wizard takes a 5 kmh jump from the previous sample (the control)`() {
        val st = thresholdState().onSample(WizardSample(1_000, 60f, 96.5f, false)).onSample(WizardSample(1_300, 65f, 96.4f, true))
        val o = st.onsets.single()
        assertEquals(60f, o.speedKmh)
        assertEquals(96.5f, o.packV, "the voltage of the previous sample too")
    }

    @Test
    fun `the wizard takes no onset when the bit is already set on the first sample, and no notice either`() {
        val st = thresholdState().onSample(WizardSample(1_000, 60f, 96f, true)).onSample(WizardSample(1_300, 62f, 96f, true))
        assertTrue(st.onsets.isEmpty())
        assertNull(st.notice)
    }

    @Test
    fun `the wizard skips in-between samples at the edge speed, like riding`() {
        val st = thresholdState().onSample(WizardSample(1_000, 60f, 96f, false))
            .onSample(WizardSample(1_300, 64f, 96f, false)).onSample(WizardSample(1_310, 64f, 96f, true))
        assertEquals(60f, st.onsets.single().speedKmh)
    }

    @Test
    fun `the wizard decelerating into the alarm takes the slower speed`() {
        val st = thresholdState().onSample(WizardSample(1_000, 72f, 96f, false)).onSample(WizardSample(1_300, 68.4f, 96f, true))
        assertEquals(68.4f, st.onsets.single().speedKmh)
    }

    @Test
    fun `the wizard clears a stale spin slower notice when the next edge has no usable previous sample`() {
        var st = thresholdState().onSample(WizardSample(1_000, 60f, 96f, false)).onSample(WizardSample(1_300, 69f, 96f, true))
        assertEquals(PwmWizardNotice.SPIN_SLOWER, st.notice)
        // a fresh run at a standstill-ish speed: the edge below 10 km/h is ignored, and says nothing
        st = st.onSample(WizardSample(1_600, 4f, 96f, false)).onSample(WizardSample(1_700, 5f, 96f, true))
        assertNull(st.notice)
    }

    @Test
    fun `the wizard rejects an edge more than a second after the previous sample`() {
        val st = thresholdState().onSample(WizardSample(1_000, 60f, 96f, false)).onSample(WizardSample(2_500, 63f, 96f, true))
        assertTrue(st.onsets.isEmpty())
        assertEquals(PwmWizardNotice.SPIN_SLOWER, st.notice)
    }
}
