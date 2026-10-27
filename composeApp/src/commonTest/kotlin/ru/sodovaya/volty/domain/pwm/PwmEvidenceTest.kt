package ru.sodovaya.volty.domain.pwm

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class PwmEvidenceTest {

    private fun sample(
        atMillis: Long,
        speed: Float,
        duty: Float,
        hasDuty: Boolean = true,
        speedSource: SpeedSource = SpeedSource.REPORTED
    ) = ControllerData(
        speedKmh = speed, speedSource = speedSource, dutyPercent = duty, hasDuty = hasDuty,
        timestamp = Instant.fromEpochMilliseconds(atMillis)
    )

    private fun run(vararg samples: ControllerData, from: PwmEvidenceProgress = PwmEvidenceProgress()): PwmEvidenceProgress =
        samples.fold(from) { p, s -> p.next(s) }

    /** One sample per second, [seconds]+1 samples, constant speed and duty. */
    private fun steady(seconds: Int, speed: Float, duty: Float, hasDuty: Boolean = true, startMs: Long = 0L) =
        Array(seconds + 1) { sample(startMs + it * 1000L, speed, duty, hasDuty) }

    @Test
    fun `T4 duty 0 while moving 6 s at 30 kmh is NEVER_REPORTED`() {
        assertEquals(PwmEvidence.NEVER_REPORTED, run(*steady(6, 30f, 0f)).evidence)
    }

    @Test
    fun `T4 duty 0 but only 4 s moving stays UNKNOWN`() {
        assertEquals(PwmEvidence.UNKNOWN, run(*steady(4, 30f, 0f)).evidence)
    }

    @Test
    fun `exactly 5 s cumulative is enough and below 10 kmh does not count`() {
        assertEquals(PwmEvidence.NEVER_REPORTED, run(*steady(5, 10f, 0f)).evidence)
        assertEquals(PwmEvidence.UNKNOWN, run(*steady(30, 9.9f, 0f)).evidence)
    }

    @Test
    fun `ET Max duty 2 standing is REPORTS from two consecutive samples`() {
        assertEquals(PwmEvidence.REPORTS, run(sample(0, 0f, 2f), sample(310, 0f, 2f)).evidence)
    }

    @Test
    fun `one non-zero sample between zeros does not latch REPORTS (Task 7 retraction of any-non-zero-latches)`() {
        val p = run(sample(0, 30f, 0f), sample(310, 30f, 37f), sample(620, 30f, 0f), sample(930, 30f, 0f))
        assertEquals(PwmEvidence.UNKNOWN, p.evidence)
        // a lone non-zero followed by a zero is a broken run: the next non-zero starts over
        assertEquals(PwmEvidence.UNKNOWN, run(sample(0, 0f, 5f), sample(310, 0f, 0f), sample(620, 0f, 5f)).evidence)
    }

    @Test
    fun `a sample with hasDuty false between two non-zero samples is no sample at all`() {
        val p = run(sample(0, 0f, 5f), sample(310, 0f, 99f, hasDuty = false), sample(620, 0f, 5f))
        assertEquals(PwmEvidence.REPORTS, p.evidence)
    }

    @Test
    fun `the reflashed T4 duty rising from two consecutive non-zero samples is REPORTS`() {
        assertEquals(PwmEvidence.REPORTS, run(sample(0, 5f, 3f), sample(310, 9f, 4f)).evidence)
    }

    @Test
    fun `a standing wheel with duty 0 stays UNKNOWN however long`() {
        assertEquals(PwmEvidence.UNKNOWN, run(*steady(120, 0f, 0f)).evidence)
    }

    @Test
    fun `REPORTS never reverts`() {
        val p = run(sample(0, 0f, 2f), sample(310, 0f, 2f), *steady(30, 40f, 0f, startMs = 1000L))
        assertEquals(PwmEvidence.REPORTS, p.evidence)
    }

    @Test
    fun `a non-zero sample after NEVER_REPORTED latches REPORTS`() {
        val never = run(*steady(6, 30f, 0f))
        assertEquals(PwmEvidence.NEVER_REPORTED, never.evidence)
        assertEquals(PwmEvidence.REPORTS, never.next(sample(7000, 30f, 3f)).next(sample(7310, 30f, 3f)).evidence)
    }

    @Test
    fun `persisted NEVER_REPORTED survives zeros and is overturned by a non-zero`() {
        val loaded = PwmEvidenceProgress(PwmEvidence.NEVER_REPORTED)
        assertEquals(PwmEvidence.NEVER_REPORTED, run(sample(0, 0f, 0f), sample(1000, 50f, 0f), from = loaded).evidence)
        assertEquals(PwmEvidence.REPORTS, run(sample(0, 50f, 1f), sample(310, 50f, 1f), from = loaded).evidence)
    }

    @Test
    fun `a constant non-zero duty is REPORTS not NEVER_REPORTED`() {
        assertEquals(PwmEvidence.REPORTS, run(*steady(10, 30f, 12.8f)).evidence)
    }

    @Test
    fun `a duty that changes while moving is REPORTS`() {
        assertEquals(PwmEvidence.REPORTS, run(*steady(3, 30f, 0f), sample(4000, 30f, 1f), sample(4310, 30f, 1f)).evidence)
    }

    @Test
    fun `hasDuty alone is not evidence`() {
        // The T4 publishes hasDuty = true with a constant 0; one standing sample of that proves nothing.
        assertEquals(PwmEvidence.UNKNOWN, run(sample(0, 0f, 0f, hasDuty = true)).evidence)
    }

    @Test
    fun `incoherent hasDuty false with a duty value gives no evidence either way`() {
        // 37 % beside hasDuty = false: a combination no producer emits. It must not latch REPORTS ...
        assertEquals(PwmEvidence.UNKNOWN, run(sample(0, 0f, 37f, hasDuty = false)).evidence)
        // ... nor count moving time toward NEVER_REPORTED, whatever number rides beside the false flag.
        assertEquals(PwmEvidence.UNKNOWN, run(*steady(30, 40f, 37f, hasDuty = false)).evidence)
        assertEquals(PwmEvidence.UNKNOWN, run(*steady(30, 40f, 0f, hasDuty = false)).evidence)
    }

    @Test
    fun `incoherent samples do not disturb an earlier NEVER_REPORTED or REPORTS`() {
        val never = run(*steady(6, 30f, 0f))
        assertEquals(PwmEvidence.NEVER_REPORTED, never.next(sample(7000, 30f, 55f, hasDuty = false)).evidence)
        val reports = run(sample(0, 0f, 2f), sample(310, 0f, 2f))
        assertEquals(PwmEvidence.REPORTS, reports.next(sample(1000, 30f, 0f, hasDuty = false)).evidence)
    }

    @Test
    fun `speed that is not known does not count as moving`() {
        val s = Array(30) { sample(it * 1000L, 40f, 0f, speedSource = SpeedSource.NONE) }
        assertEquals(PwmEvidence.UNKNOWN, run(*s).evidence)
    }

    @Test
    fun `moving time is cumulative across stops`() {
        val a = steady(3, 30f, 0f)                       // 3 s moving
        val stop = steady(10, 0f, 0f, startMs = 4000L)   // standing: not counted, breaks the interval
        val b = steady(3, 30f, 0f, startMs = 20_000L)    // 3 s more
        assertEquals(PwmEvidence.UNKNOWN, run(*a, *stop).evidence, "3 s is not yet 5")
        assertEquals(PwmEvidence.NEVER_REPORTED, run(*a, *stop, *b).evidence)
    }

    @Test
    fun `a long gap between samples is not counted as moving time`() {
        assertEquals(PwmEvidence.UNKNOWN, run(sample(0, 30f, 0f), sample(60_000, 30f, 0f)).evidence)
    }

    @Test
    fun `non-finite duty or speed is no evidence`() {
        assertEquals(PwmEvidence.UNKNOWN, run(sample(0, 30f, Float.NaN), sample(1000, 30f, Float.NaN)).evidence)
        assertEquals(PwmEvidence.UNKNOWN, run(*Array(20) { sample(it * 1000L, Float.NaN, 0f) }).evidence)
    }
}
