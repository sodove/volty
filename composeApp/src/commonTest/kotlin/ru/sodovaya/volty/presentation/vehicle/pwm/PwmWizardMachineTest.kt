package ru.sodovaya.volty.presentation.vehicle.pwm

import ru.sodovaya.volty.domain.pwm.PwmBasis
import ru.sodovaya.volty.domain.pwm.PwmCalibrationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The calibration wizard's state machine, driven with the rider's own T4 dump numbers: the wheel's alarm
 * switched on at 68.4 km/h at 96.8 V and the free-spin plateau was 81.7 km/h at 96.5 V.
 */
class PwmWizardMachineTest {
    private fun fresh(threshold: Int = 80) = PwmWizardState(thresholdPercent = threshold)
    private fun s(at: Long, kmh: Float, v: Float? = 96.8f, alarm: Boolean? = false) = WizardSample(at, kmh, v, alarm)

    /**
     * One free-spin run of the T4: alarm off at [kmh], then the bit is first SEEN 3 km/h later. The onset is the sample
     * BEFORE the edge, so it is [kmh] that is captured (see PwmOnsetCaptureTest for the rule and the real dump).
     */
    private fun PwmWizardState.runToOnset(t0: Long = 0L, kmh: Float = 68.4f, v: Float = 96.8f): PwmWizardState =
        onSample(s(t0, kmh, v, false)).onSample(s(t0 + 100, kmh + 3f, v, true))

    /** Holds [kmh] at [v] for 2.5 s of samples. */
    private fun PwmWizardState.holdFor(t0: Long, kmh: Float, v: Float): PwmWizardState {
        var st = this
        for (i in 0..25) st = st.onSample(s(t0 + i * 100L, kmh, v, null))
        return st
    }

    @Test fun startRefusedWithoutASample() {
        val st = fresh().start(connected = true)
        assertEquals(PwmWizardStep.INTRO, st.step)
        assertEquals(PwmWizardNotice.NO_DATA, st.notice)
    }

    @Test fun startRefusedWhenNotConnected() {
        val st = fresh().onSample(s(0, 0f)).start(connected = false)
        assertEquals(PwmWizardStep.INTRO, st.step)
        assertEquals(PwmWizardNotice.NOT_CONNECTED, st.notice)
    }

    @Test fun aWheelWithTheAlarmSignalGoesIntroToThreshold() {
        val st = fresh().onSample(s(0, 0f, alarm = false)).start(connected = true)
        assertEquals(PwmWizardStep.THRESHOLD, st.step)
        assertNull(st.notice)
    }

    @Test fun aWheelWithoutTheAlarmSignalGoesIntroToPlateau() {
        val st = fresh().onSample(s(0, 0f, alarm = null)).start(connected = true)
        assertEquals(PwmWizardStep.PLATEAU, st.step, "the threshold step is skipped when the wheel reports no alarm bit")
    }

    @Test fun capturesTheFirstRisingEdgeAtTenKmhOrMore() {
        val st = fresh().onSample(s(0, 0f)).start(true).runToOnset()
        assertEquals(1, st.onsets.size)
        val o = st.onsets.single()
        assertEquals(68.4f, o.speedKmh); assertEquals(96.8f, o.packV); assertNull(o.phaseA)
        assertTrue(st.runCaptured)
    }

    @Test fun flickerAfterTheCaptureDoesNotCaptureAgain() {
        var st = fresh().onSample(s(0, 0f)).start(true).runToOnset()
        st = st.onSample(s(300, 67.0f, alarm = false)).onSample(s(400, 68.5f, alarm = true))
            .onSample(s(500, 68.4f, alarm = false)).onSample(s(600, 68.5f, alarm = true))
        assertEquals(1, st.onsets.size, "one onset per run, whatever the bit does afterwards")
    }

    @Test fun anAlarmAlreadyOnWhenTheRunBeginsIsNotAnEdge() {
        var st = fresh().onSample(s(0, 0f)).start(true)
        st = st.onSample(s(0, 70f, alarm = true)).onSample(s(100, 71f, alarm = true))
        assertTrue(st.onsets.isEmpty(), "the rising edge was never seen")
    }

    @Test fun anEdgeBelowTenKmhIsIgnored() {
        var st = fresh().onSample(s(0, 0f)).start(true)
        st = st.onSample(s(0, 5f, alarm = false)).onSample(s(100, 6f, alarm = true))
        assertTrue(st.onsets.isEmpty())
    }

    @Test fun anEdgeWithoutAVoltageCapturesNothing() {
        var st = fresh().onSample(s(0, 0f)).start(true)
        st = st.onSample(s(0, 60f, v = null, alarm = false)).onSample(s(100, 68.4f, v = null, alarm = true))
        assertTrue(st.onsets.isEmpty(), "no voltage, no k")
    }

    @Test fun upToThreeRunsThenNoMore() {
        var st = fresh().onSample(s(0, 0f)).start(true).runToOnset(0)
        st = st.runAgain().runToOnset(1_000, 69f)
        st = st.runAgain().runToOnset(2_000, 68f)
        assertEquals(3, st.onsets.size)
        assertFalse(st.canRunAgain)
        assertEquals(st, st.runAgain(), "a fourth run is refused")
    }

    @Test fun runAgainBeforeACaptureIsRefused() {
        val st = fresh().onSample(s(0, 0f)).start(true)
        assertFalse(st.canRunAgain)
        assertEquals(st, st.runAgain())
    }

    @Test fun aSecondRunNeedsTheAlarmToFallFirst() {
        var st = fresh().onSample(s(0, 0f)).start(true).runToOnset().runAgain()
        // the bit is still on from the first run: not an edge
        st = st.onSample(s(1_000, 70f, alarm = true))
        assertEquals(1, st.onsets.size)
        st = st.onSample(s(1_100, 66f, alarm = false)).onSample(s(1_200, 68.4f, alarm = true))
        assertEquals(2, st.onsets.size)
    }

    @Test fun resultFromThresholdOnlyUsesMedianKAndTheVehicleThreshold() {
        val st = fresh(80).onSample(s(0, 0f)).start(true).runToOnset(0, 68.4f, 96.8f).finish()
        assertEquals(PwmWizardStep.RESULT, st.step)
        val r = assertNotNull(st.result)
        assertEquals(0.8f * 96.8f / 68.4f, r.calibration.k, 1e-4f)
        assertEquals(PwmCalibrationSource.WIZARD, r.calibration.source)
        assertEquals(PwmBasis.ONSET, r.calibration.basis)
        assertEquals(0f, r.calibration.r)
        assertNull(r.ratio); assertFalse(r.ratioWarning)
    }

    @Test fun thresholdAndPlateauShowTheRidersRatioWithoutAWarning() {
        val st = fresh(80).onSample(s(0, 0f)).start(true).runToOnset(0, 68.4f, 96.8f)
            .toPlateau().holdFor(10_000, 81.7f, 96.5f).finish()
        val r = assertNotNull(st.result)
        assertEquals(0.837f, assertNotNull(r.ratio), 0.001f, "68.4 / 81.7")
        assertFalse(r.ratioWarning)
        assertEquals(68.4f, r.onsetKmh); assertEquals(81.7f, r.plateauKmh)
        // the saved k is the onset's, the plateau is the cross-check
        assertEquals(0.8f * 96.8f / 68.4f, r.calibration.k, 1e-4f)
    }

    @Test fun aRatioOutsideTheBandWarns() {
        val low = fresh(80).onSample(s(0, 0f)).start(true).runToOnset(0, 50f, 96.8f)
            .toPlateau().holdFor(10_000, 81.7f, 96.5f).finish().result
        assertTrue(assertNotNull(assertNotNull(low).ratio) < 0.75f)
        assertTrue(low.ratioWarning)
        val hi = fresh(80).onSample(s(0, 0f)).start(true).runToOnset(0, 78f, 96.8f)
            .toPlateau().holdFor(10_000, 81.7f, 96.5f).finish().result
        assertTrue(assertNotNull(hi).ratioWarning, "0.955 is above 0.92")
    }

    @Test fun plateauOnlyWheelUsesTheFreeSpinFit() {
        val st = fresh().onSample(s(0, 0f, alarm = null)).start(true).holdFor(1_000, 81.7f, 96.5f).finish()
        val r = assertNotNull(st.result)
        assertEquals(PwmBasis.FREE_SPIN, r.calibration.basis)
        assertEquals(96.5f / 81.7f, r.calibration.k, 1e-4f)
        assertEquals(PwmCalibrationSource.WIZARD, r.calibration.source)
        assertNull(r.ratio)
    }

    @Test fun finishWithNothingCapturedStaysAndSaysSo() {
        val st = fresh().onSample(s(0, 0f, alarm = null)).start(true).finish()
        assertEquals(PwmWizardStep.PLATEAU, st.step)
        assertEquals(PwmWizardNotice.NOTHING_CAPTURED, st.notice)
        assertNull(st.result)
        val th = fresh().onSample(s(0, 0f)).start(true).finish()
        assertEquals(PwmWizardStep.THRESHOLD, th.step)
        assertEquals(PwmWizardNotice.NOTHING_CAPTURED, th.notice)
    }

    @Test fun plateauNeedsTwoSecondsWithinOneKmh() {
        var st = fresh().onSample(s(0, 0f, alarm = null)).start(true)
        for (i in 0..15) st = st.onSample(s(i * 100L, 80f, 96.5f, null)) // 1.5 s only
        assertNull(st.plateau)
        // a slow climb of 0.4 km/h per sample never counts: the stretch spreads past 1 km/h
        var climb = fresh().onSample(s(0, 0f, alarm = null)).start(true)
        for (i in 0..60) climb = climb.onSample(s(i * 100L, 40f + i * 0.4f, 96.5f, null))
        assertNull(climb.plateau, "a ramp is not a plateau")
    }

    @Test fun plateauKeepsTheHighestSustainedSpeed() {
        var st = fresh().onSample(s(0, 0f, alarm = null)).start(true)
        st = st.holdFor(0, 60f, 96.9f).holdFor(5_000, 81.7f, 96.5f).holdFor(10_000, 30f, 97f)
        val p = assertNotNull(st.plateau)
        assertEquals(81.7f, p.kmh); assertEquals(96.5f, p.packV)
    }

    @Test fun plateauBelowTenKmhIsNotAPlateau() {
        val st = fresh().onSample(s(0, 0f, alarm = null)).start(true).holdFor(0, 5f, 96.5f)
        assertNull(st.plateau)
    }

    @Test fun disconnectMidRunReturnsToIntroWithAReasonAndForgetsTheRun() {
        var st = fresh().onSample(s(0, 0f)).start(true).runToOnset()
        assertEquals(1, st.onsets.size)
        st = st.onDisconnected()
        assertEquals(PwmWizardStep.INTRO, st.step)
        assertEquals(PwmWizardNotice.DISCONNECTED, st.notice)
        assertTrue(st.onsets.isEmpty())
        assertNull(st.plateau)
        val p = fresh().onSample(s(0, 0f, alarm = null)).start(true).holdFor(0, 80f, 96.5f).onDisconnected()
        assertEquals(PwmWizardStep.INTRO, p.step)
        assertNull(p.plateau)
    }

    @Test fun disconnectInIntroOrResultChangesNothing() {
        val intro = fresh()
        assertEquals(intro, intro.onDisconnected())
        val result = fresh().onSample(s(0, 0f)).start(true).runToOnset().finish()
        assertEquals(result, result.onDisconnected(), "the result is held; saving needs no link")
    }

    @Test fun restartFromTheResultStartsOver() {
        val st = fresh().onSample(s(0, 0f)).start(true).runToOnset().finish().restart()
        assertEquals(PwmWizardStep.INTRO, st.step)
        assertTrue(st.onsets.isEmpty()); assertNull(st.result)
    }

    @Test fun startIsRefusedUntilTheWizardIsReady() {
        val st = PwmWizardState(thresholdPercent = 80, ready = false).onSample(s(0, 0f)).start(true)
        assertEquals(PwmWizardStep.INTRO, st.step)
        assertEquals(PwmWizardNotice.LOADING, st.notice)
    }

    @Test fun blockIsTerminalFromAnyStepAndDiscardsEverything() {
        val running = fresh().onSample(s(0, 0f)).start(true).runToOnset()
        val result = running.finish()
        for (st in listOf(fresh(), running, result)) {
            val b = st.block(PwmWizardBlock.REPORTS)
            assertEquals(PwmWizardStep.BLOCKED, b.step)
            assertTrue(b.onsets.isEmpty()); assertNull(b.result)
            assertEquals(b, b.block(PwmWizardBlock.LOAD_FAILED), "idempotent; the first reason stays")
            assertEquals(b, b.start(true)); assertEquals(b, b.finish()); assertEquals(b, b.restart()); assertEquals(b, b.runAgain())
            assertEquals(b, b.onDisconnected())
        }
    }
}
