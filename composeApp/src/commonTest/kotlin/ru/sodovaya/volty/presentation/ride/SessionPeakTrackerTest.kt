package ru.sodovaya.volty.presentation.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SessionPeakTracker] is the fix for "one spurious sample pins the auto-scale for the rest of the
 * ride" (task-7, Fix 1): a rising reading only becomes the confirmed session peak once
 * [SessionPeakTracker.CONFIRM_SAMPLES] consecutive samples have corroborated it, and the value that
 * gets committed is the SMALLEST of that run, not its largest — see the class doc for why.
 */
class SessionPeakTrackerTest {

    @Test fun starts_at_zero_with_nothing_seen() {
        assertEquals(0f, SessionPeakTracker().committed)
    }

    @Test fun a_single_sample_above_zero_does_not_commit_alone() {
        val tracker = SessionPeakTracker().accept(50f)
        assertEquals(0f, tracker.committed, "one sample must not be enough to move the peak")
    }

    @Test fun two_consecutive_samples_do_not_commit_either() {
        val tracker = SessionPeakTracker().accept(50f).accept(60f)
        assertEquals(0f, tracker.committed, "two samples are still one short of CONFIRM_SAMPLES")
    }

    @Test fun three_consecutive_rising_samples_commit_the_runs_minimum() {
        // 50, 55, 60: all three corroborate at least 50, so 50 - not 60 - is what gets trusted.
        val tracker = SessionPeakTracker().accept(50f).accept(55f).accept(60f)
        assertEquals(50f, tracker.committed)
    }

    @Test fun three_identical_samples_commit_that_value() {
        val tracker = SessionPeakTracker().accept(42f).accept(42f).accept(42f)
        assertEquals(42f, tracker.committed)
    }

    /**
     * THE regression this whole class exists for: a single spurious sample (the concrete complaint
     * — a bad decode reporting 5000 A) surrounded by ordinary readings must never move the peak,
     * however large it is, because it never gets two more consecutive samples anywhere near it to
     * corroborate it.
     */
    @Test fun a_lone_spurious_spike_between_ordinary_readings_never_moves_the_peak() {
        // The three leading 10s legitimately confirm a peak of 10; the isolated 5000 (one frame,
        // immediately followed by a return to 10 — below it, so the run it started is discarded
        // before a second sample can corroborate it) must never be reflected in the result at all.
        val readings = listOf(10f, 10f, 10f, 5000f, 10f, 10f, 10f, 10f)
        val tracker = readings.fold(SessionPeakTracker()) { acc, sample -> acc.accept(sample) }
        assertEquals(10f, tracker.committed, "a one-frame 5000 spike must not have pinned the scale")
        assertTrue(tracker.committed < 100f)
    }

    /**
     * The naive version of this guard — track the largest sample seen during a streak of "N
     * consecutive samples above the old peak", then commit THAT — does not actually reject a single
     * outlier: two ordinary elevated readings plus one wild spike is still three consecutive
     * samples above zero. This pins that the tracker commits the run's MINIMUM instead, so the
     * spike inside an otherwise-legitimate run cannot smuggle its own extreme value in.
     */
    @Test fun a_spike_inside_an_otherwise_legitimate_run_only_raises_the_peak_to_the_runs_floor() {
        val tracker = SessionPeakTracker().accept(50f).accept(50f).accept(5000f)
        assertEquals(50f, tracker.committed, "the spike must not have been the value committed")
        assertTrue(tracker.committed < 5000f)
    }

    /**
     * A run must be discarded, not merely paused, by a sample back at/under the confirmed peak.
     * Traced by hand against the plausible bug this guards: an implementation that treats
     * `sample <= committed` as a silent no-op (leaving `runLength`/`runMinimum` untouched) instead
     * of clearing them would let the pre-break run of 2 (150, 160) combine with the next sample
     * (170) into a run of 3 and wrongly commit 150 — this asserts the real reset value, 100.
     */
    @Test fun a_broken_streak_after_a_commit_needs_a_full_fresh_run_not_a_partial_one() {
        val baseline = SessionPeakTracker().accept(100f).accept(100f).accept(100f)
        assertEquals(100f, baseline.committed)

        val progressing = baseline.accept(150f).accept(160f) // a run of 2, one short of committing
        val broken = progressing.accept(100f) // back at the baseline - the run of 2 must be dropped
        val afterBreak = broken.accept(170f).accept(180f) // only 2 MORE samples, not a fresh 3

        assertEquals(100f, afterBreak.committed, "the pre-break run of 2 must not have carried forward past the reset")
    }

    @Test fun the_peak_never_decreases_once_committed() {
        val tracker = SessionPeakTracker().accept(100f).accept(100f).accept(100f)
        assertEquals(100f, tracker.committed)
        // Samples at or below the committed peak (including a spurious LOW reading) never lower it.
        val afterLowSamples = tracker.accept(0f).accept(1f).accept(50f)
        assertEquals(100f, afterLowSamples.committed)
    }

    @Test fun a_confirmed_peak_can_still_grow_from_a_new_confirmed_run() {
        val first = SessionPeakTracker().accept(50f).accept(55f).accept(60f)
        assertEquals(50f, first.committed)
        val grown = first.accept(70f).accept(75f).accept(80f)
        assertEquals(70f, grown.committed, "a fresh confirmed run above the old peak must still be able to raise it")
    }

    @Test fun a_growing_sequence_of_confirmed_runs_never_decreases() {
        var tracker = SessionPeakTracker()
        val allSamples = listOf(
            10f, 12f, 14f, // confirms >= 10
            20f, 22f, 24f, // confirms >= 20
            5000f, 8f, 9f, // spurious spike, isolated — must not commit
            30f, 32f, 34f // confirms >= 30
        )
        val committedHistory = allSamples.map { sample ->
            tracker = tracker.accept(sample)
            tracker.committed
        }
        assertEquals(committedHistory, committedHistory.sorted(), "the committed peak must never decrease")
        assertEquals(30f, tracker.committed)
    }
}
