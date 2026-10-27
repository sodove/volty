package ru.sodovaya.volty.domain.pwm

import ru.sodovaya.volty.data.bms.BegodeProtocol
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

        fun feed(atMs: Long, speed: Float, packV: Float, alarm: Boolean?, phase: Float = 0f, extraNanos: Long = 0L, since: Long? = null) {
            nowMs = atMs
            conn.submit(0, BmsData(voltage = packV, isConnected = true))
            conn.submitMotion(
                0,
                // dutyPercent 0 + hasDuty: what a T4 on the OLD firmware publishes, so the wheel is never proven to
                // report PWM and the estimator keeps learning (the dump's true duty is kept apart as the truth).
                ControllerData(
                    speedKmh = speed, speedSource = SpeedSource.REPORTED, dutyPercent = 0f, hasDuty = true, wheelPwmAlarm = alarm, wheelPwmAlarmSinceMillis = since, motorCurrentA = phase,
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
            rig.feed(s.atMillis, m.speedKmh, m.inputVoltageV, m.wheelPwmAlarm, m.motorCurrentA, since = m.wheelPwmAlarmSinceMillis)
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
    fun `riding on the real dump, the estimate is never below the true duty and within six points above it`() {
        val rig = Rig()
        val out = replayThrough(rig)
        // The true duty (frame 0x07) of a cycle arrives in the LAST sample of that cycle, so the samples carrying
        // the cycle's truth are the ones whose duty field just changed; judge the estimate on those.
        // Retraction (Task 9, R12): this used to judge the 70.8 km/h / true 84 cycle, where the wheel's alarm bit was first
        // published. The debounce publishes the alarm, and so installs the learned calibration, one 0x04 later, at the
        // 75.7 km/h / true 88 cycle; at 70.8 there is now honestly NO estimate yet (hasDuty false), which is asserted
        // below. The same fit judged where it first exists: never below the truth, and within 6 above (it was 5 at the
        // old point; the single-onset fit varies about +-3 % across this dump, see OnsetLookback).
        val first = out.first { (_, pub) -> pub.hasDuty }
        assertEquals(75.7f, first.first.motion.speedKmh, 0.1f, "the calibration lands at the confirming cycle")
        assertTrue(out.filter { (s, _) -> s.motion.speedKmh in 70.7f..70.8f }.none { (_, pub) -> pub.hasDuty }, "no estimate before the alarm is confirmed")
        val atEdge = out.filter { (s, _) -> s.motion.speedKmh in 75.7f..75.8f && s.motion.dutyPercent == 88f }
        assertTrue(atEdge.isNotEmpty(), "the confirming cycle's 0x07 sample must be in the replay")
        for ((_, pub) in atEdge) {
            assertTrue(pub.hasDuty)
            assertTrue(pub.dutyPercent >= 88f, "estimate ${pub.dutyPercent} must not be below the true 88 at 75.7 km/h")
            assertTrue(pub.dutyPercent <= 94f, "estimate ${pub.dutyPercent} within +6 of 88")
            assertEquals(DutySource.ESTIMATED, pub.dutySource)
        }
        // M6: the retracted claim, pinned at every estimated cycle that is not saturated (75.7, 79.7, 80.5 km/h).
        val estimated = out.filter { (s, pub) -> pub.hasDuty && s.motion.dutyPercent < 100f }
        assertTrue(estimated.size >= 3)
        for ((s, pub) in estimated) assertTrue(pub.dutyPercent >= s.motion.dutyPercent, "estimate ${pub.dutyPercent} below the true ${s.motion.dutyPercent} at ${s.motion.speedKmh} km/h")
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

    // ---- Begode R12 (Task 9): the alarm bit is debounced, the onset must not move later with it -----------------------

    private fun hexBytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val liveBase = hexBytes("55 AA 19 06 F7 43 00 6A 00 65 FE 5C F1 D2 08 A0 00 05 00 18 5A 5A 5A 5A")
    private val alertBase = hexBytes("55 AA 00 2A DC AD 28 00 1C 20 00 C8 00 00 01 00 00 12 04 18 5A 5A 5A 5A")
    private fun liveAt(kmh: Float) = liveBase.copyOf().also {
        val raw = kotlin.math.round(kmh / 0.036f).toInt()
        it[4] = (raw shr 8).toByte(); it[5] = raw.toByte()
    }
    private fun alertWith(bits: Int) = alertBase.copyOf().also { it[14] = bits.toByte() }

    /** One decoded sample the way the connection hands it to the estimator, and to the wizard. */
    private class Decoded(val atMs: Long, val motion: ControllerData)

    /**
     * Drives the REAL BegodeProtocol (its alarm clock is the script's) with one cycle per entry: a live frame at the
     * entry's speed, then its 0x04 with the given alert byte 10 ms later; cycles are 310 ms apart. The odometer never
     * moves, so the odometer gate holds each 0x04, as on a wheel standing still. Every NEW motion instance is a sample.
     */
    private fun decode(cycles: List<Pair<Float, Int>>): List<Decoded> {
        var t = 1_000L
        val protocol = BegodeProtocol(cellCount = 24, nowMillis = { t })
        var last: ControllerData? = null
        val out = mutableListOf<Decoded>()
        fun take() {
            val m = protocol.latestMotion(0) ?: return
            if (m !== last) { last = m; out += Decoded(t, m) }
        }
        for ((speed, bits) in cycles) {
            protocol.onNotification(liveAt(speed)); take()
            t += 10
            protocol.onNotification(alertWith(bits)); take()
            t += 300
        }
        return out
    }

    private fun Rig.ride(samples: List<Decoded>): Rig = apply {
        for (d in samples) feed(d.atMs, d.motion.speedKmh, d.motion.inputVoltageV, d.motion.wheelPwmAlarm, since = d.motion.wheelPwmAlarmSinceMillis)
    }

    private fun List<Decoded>.wizard(): PwmWizardState {
        var st = thresholdState()
        for (d in this) st = st.onSample(WizardSample(d.atMs, d.motion.speedKmh, d.motion.inputVoltageV, d.motion.wheelPwmAlarm, d.motion.wheelPwmAlarmSinceMillis))
        return st
    }

    // A ramp: bit 0 is first SET with the 68.4 km/h cycle's 0x04 and confirmed by the 70.1 km/h cycle's.
    private val ramp = listOf(64.0f to 0, 66.2f to 0, 68.4f to 1, 70.1f to 1, 71.0f to 1)

    @Test
    fun `riding learns the onset from before the FIRST set frame, not before the confirming one (Ruling 1)`() {
        // With the debounce the alarm is published at 70.1 km/h. Taking "the sample before the published edge" would
        // learn 68.4 km/h: a later, faster onset, k too small, the estimate LOW. The onset is the sample before the
        // cycle where the wheel first reported the bit.
        val samples = decode(ramp)
        assertTrue(samples.first { it.motion.wheelPwmAlarm == true }.motion.speedKmh > 70f, "the alarm is published only at the confirming cycle")
        val onset = Rig().ride(samples).onsets().single()
        assertEquals(66.2f, onset.speedKmh, 0.05f)
    }

    @Test
    fun `the wizard learns the same onset as riding on the same samples (Ruling 1)`() {
        val samples = decode(ramp)
        assertEquals(66.2f, samples.wizard().onsets.single().speedKmh, 0.05f)
        assertEquals(Rig().ride(samples).onsets().single().speedKmh, samples.wizard().onsets.single().speedKmh)
    }

    @Test
    fun `a one-frame bit 0 blip teaches nothing, riding or in the wizard`() {
        val samples = decode(listOf(64.0f to 0, 66.2f to 0, 68.4f to 1, 70.1f to 0, 71.0f to 0, 72.0f to 0))
        assertTrue(samples.none { it.motion.wheelPwmAlarm == true })
        assertTrue(Rig().ride(samples).onsets().isEmpty())
        assertTrue(samples.wizard().onsets.isEmpty())
    }

    @Test
    fun `the debounced edge is judged from the samples, so a speed jump before the first set frame still rejects the run`() {
        // 60 -> 68.4 is a 8.4 km/h jump into the cycle where the bit was first set: where the alarm fired is unknown.
        val samples = decode(listOf(60.0f to 0, 60.0f to 0, 68.4f to 1, 70.1f to 1))
        assertTrue(Rig().ride(samples).onsets().isEmpty())
    }

    @Test
    fun `the contract on plain samples, without a protocol (a deliberately incoherent since that is ignored when not earlier)`() {
        // alarm true on the 70.1 sample, first set at 1_630 ms (just after the 68.4 sample at 1_620).
        val rig = Rig().apply {
            feed(1_000, 64.0f, 96f, false); feed(1_310, 66.2f, 96f, false); feed(1_620, 68.4f, 96f, false)
            feed(1_930, 70.1f, 96f, true, since = 1_630)
        }
        assertEquals(66.2f, rig.onsets().single().speedKmh, 0.001f)
        // The control: the same samples with no since (a producer that does not debounce) take 68.4, as before.
        val plain = Rig().apply {
            feed(1_000, 64.0f, 96f, false); feed(1_310, 66.2f, 96f, false); feed(1_620, 68.4f, 96f, false)
            feed(1_930, 70.1f, 96f, true)
        }
        assertEquals(68.4f, plain.onsets().single().speedKmh, 0.001f)
    }

    // ---- the real dump, wizard ---------------------------------------------------------------------------------

    private fun wizardSamples() = T4OnsetReplay.samples().map {
        WizardSample(it.atMillis, it.motion.speedKmh, it.motion.inputVoltageV, it.motion.wheelPwmAlarm, it.motion.wheelPwmAlarmSinceMillis)
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
