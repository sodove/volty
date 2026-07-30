package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.MotorConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `G §9.2`'s whole mechanism, which is pure by design so that all of it — the ladder, the headroom,
 * the spike guard and the display-vs-persistence split — is provable without a screen.
 */
class GaugeScaleTest {

    // --- the ladder ------------------------------------------------------------------------------

    @Test fun the_two_ladders_are_the_ones_the_brief_names() {
        assertEquals(listOf(10f, 20f, 30f, 60f, 100f, 150f, 200f, 300f, 500f), GaugeScale.CURRENT_RUNGS_A)
        assertEquals(
            listOf(500f, 1_000f, 2_000f, 3_000f, 5_000f, 10_000f, 20_000f, 30_000f, 50_000f),
            GaugeScale.POWER_RUNGS_W
        )
        assertEquals(1.25f, GaugeScale.HEADROOM)
    }

    /** Both ladders must ascend, or `firstOrNull { it >= wanted }` is not "the smallest rung". */
    @Test fun both_ladders_ascend() {
        assertEquals(GaugeScale.CURRENT_RUNGS_A, GaugeScale.CURRENT_RUNGS_A.sorted())
        assertEquals(GaugeScale.POWER_RUNGS_W, GaugeScale.POWER_RUNGS_W.sorted())
    }

    @Test fun a_rung_is_the_smallest_one_that_leaves_the_headroom() {
        val rungs = GaugeScale.CURRENT_RUNGS_A
        // 6 A * 1.25 = 7.5 -> 10 is the smallest rung at or above it.
        assertEquals(10f, GaugeScale.rungFor(6f, rungs))
        // 8.1 A * 1.25 = 10.125, which 10 does NOT cover, so the next rung up.
        assertEquals(20f, GaugeScale.rungFor(8.1f, rungs))
        // Exactly on the boundary: 8 A * 1.25 = 10, and `>=` means 10 still serves it.
        assertEquals(10f, GaugeScale.rungFor(8f, rungs))
        assertEquals(60f, GaugeScale.rungFor(45f, rungs))
        assertEquals(300f, GaugeScale.rungFor(210f, rungs))
    }

    /**
     * **The headroom is load-bearing, not padding.** Without it `rungFor` would hand back the rung a
     * peak exactly reaches, so every new high-water mark would leave the needle pinned at full scale
     * while the range was still growing.
     *
     * Stated as the invariant rather than as an example, over every rung of both ladders: a learned
     * peak must never occupy more than `1 / HEADROOM` = 80 % of its own scale. The top rung is
     * excluded because there is nothing wider to grow into — that is the documented edge, not a leak.
     */
    @Test fun the_headroom_keeps_a_learned_peak_off_the_end_of_its_scale() {
        listOf(GaugeScale.CURRENT_RUNGS_A, GaugeScale.POWER_RUNGS_W).forEach { rungs ->
            val topRung = rungs.last()
            // Sample the space below the top rung densely enough to catch a per-rung mistake.
            (1..400).map { it * topRung / 400f }.forEach { peak ->
                val rung = GaugeScale.rungFor(peak, rungs)
                if (rung == topRung) return@forEach
                assertTrue(
                    peak / rung <= 1f / GaugeScale.HEADROOM + 1e-4f,
                    "peak $peak fills ${peak / rung} of rung $rung — the headroom is not being applied"
                )
            }
        }
    }

    /**
     * The three degenerate peaks the brief names, all answering the FIRST rung. Zero is the honest
     * seed for a vehicle nobody has ridden; negative and non-finite are upstream bugs, and the
     * narrowest readable scale is a better answer than a `NaN` in the geometry.
     */
    @Test fun a_zero_negative_or_non_finite_peak_gets_the_first_rung() {
        val rungs = GaugeScale.CURRENT_RUNGS_A
        assertEquals(10f, GaugeScale.rungFor(0f, rungs))
        assertEquals(10f, GaugeScale.rungFor(-250f, rungs))
        assertEquals(10f, GaugeScale.rungFor(Float.NaN, rungs))
        assertEquals(10f, GaugeScale.rungFor(Float.NEGATIVE_INFINITY, rungs))
        // +Infinity is NON-FINITE before it is large, so it takes this branch and not the
        // past-the-ladder one below. Deliberate: an infinite peak is a bug, and the narrowest
        // readable scale is a better answer to a bug than the widest.
        assertEquals(10f, GaugeScale.rungFor(Float.POSITIVE_INFINITY, rungs))
    }

    @Test fun a_peak_past_the_whole_ladder_gets_the_last_rung() {
        assertEquals(500f, GaugeScale.rungFor(500f, GaugeScale.CURRENT_RUNGS_A))
        assertEquals(500f, GaugeScale.rungFor(5_000f, GaugeScale.CURRENT_RUNGS_A))
        // MAX_VALUE is finite, and `peak * headroom` overflowing to Infinity still means "past
        // every rung" -- so the overflow lands on the right answer rather than a NaN.
        assertEquals(500f, GaugeScale.rungFor(Float.MAX_VALUE, GaugeScale.CURRENT_RUNGS_A))
        assertEquals(50_000f, GaugeScale.rungFor(1_000_000f, GaugeScale.POWER_RUNGS_W))
    }

    /** An empty ladder answers 0 rather than throwing — unreachable, but never a crash. */
    @Test fun an_empty_ladder_answers_zero() {
        assertEquals(0f, GaugeScale.rungFor(42f, emptyList()))
    }

    /** A growing peak must never yield a narrower dial. */
    @Test fun the_rung_never_shrinks_as_the_peak_grows() {
        val rungs = GaugeScale.POWER_RUNGS_W
        val seen = (0..600).map { GaugeScale.rungFor(it * 100f, rungs) }
        assertEquals(seen, seen.sorted())
    }

    // --- display vs. persistence (item 3) --------------------------------------------------------

    /**
     * **The two numbers the brief insists are different.** The dial draws
     * `max(learnedPeak, abs(sample))`; only the learned half is ever stored. Collapse them into one
     * and this test fails in both directions: a live 200 A excursion on a dial that has only learned
     * 6 A must widen the ring *now*, while the learned peak stays where the median left it.
     */
    @Test fun a_live_excursion_widens_the_dial_without_becoming_the_learned_peak() {
        val learned = 6f
        val quiet = GaugeScale.currentDisplayRungA(learned, 6f)
        val excursion = GaugeScale.currentDisplayRungA(learned, 200f)

        assertEquals(10f, quiet, "a 6 A dial sits on the 10 A rung")
        assertEquals(300f, excursion, "a 200 A sample must be on scale in the frame it arrives")
        assertTrue(excursion > quiet)

        // ...and the learned peak itself is untouched by that display decision: the rung that gets
        // PERSISTED is still the quiet one. If display and persistence were one number this would
        // read 300f.
        assertEquals(quiet, GaugeScale.rungFor(learned, GaugeScale.CURRENT_RUNGS_A))
    }

    /** The sample's SIGN is irrelevant: both dials are bipolar, so regen widens them too. */
    @Test fun the_display_rung_follows_the_absolute_sample() {
        assertEquals(
            GaugeScale.currentDisplayRungA(0f, 200f),
            GaugeScale.currentDisplayRungA(0f, -200f)
        )
        assertEquals(
            GaugeScale.powerDisplayRungW(0f, 4_000f),
            GaugeScale.powerDisplayRungW(0f, -4_000f)
        )
    }

    /**
     * A `NaN` sample must not collapse a wide dial. `max(300f, NaN)` is `NaN` in IEEE-754 and
     * `rungFor(NaN)` answers the FIRST rung, so without the guard in [GaugeScale.displayRung] a
     * single bad frame would visibly snap a 500 A ring down to 10 A.
     */
    @Test fun a_non_finite_sample_leaves_the_learned_dial_alone() {
        val wide = GaugeScale.currentDisplayRungA(400f, 0f)
        assertEquals(500f, wide)
        assertEquals(wide, GaugeScale.currentDisplayRungA(400f, Float.NaN))
        assertEquals(wide, GaugeScale.currentDisplayRungA(400f, Float.POSITIVE_INFINITY.let { -it }))
    }

    @Test fun a_non_finite_learned_peak_does_not_poison_the_display_rung() {
        assertEquals(
            GaugeScale.currentDisplayRungA(0f, 50f),
            GaugeScale.currentDisplayRungA(Float.NaN, 50f)
        )
    }

    // --- the spike guard (item 2) ----------------------------------------------------------------

    @Test fun a_fresh_tracker_has_learned_nothing() {
        assertEquals(0f, PeakTracker().learnedPeak)
        assertEquals(3, PeakTracker.WINDOW)
    }

    /**
     * **The concrete failure mode: one garbage 300 A frame must not blow the dial open for the life
     * of the vehicle.** Begode frames carry no checksum, so this is not hypothetical.
     */
    @Test fun a_single_spurious_sample_never_becomes_the_learned_peak() {
        val tracker = PeakTracker().accept(6f).accept(6f).accept(300f)
        assertEquals(6f, tracker.learnedPeak)
        assertEquals(10f, GaugeScale.rungFor(tracker.learnedPeak, GaugeScale.CURRENT_RUNGS_A))

        // And it stays rejected as the window rolls past it.
        val after = tracker.accept(6f).accept(6f)
        assertEquals(6f, after.learnedPeak)
    }

    /** The spike in the middle, and as the first sample of a connection — same answer. */
    @Test fun a_spurious_sample_is_rejected_wherever_it_lands_in_the_window() {
        assertEquals(6f, PeakTracker().accept(6f).accept(300f).accept(6f).learnedPeak)
        assertEquals(6f, PeakTracker().accept(300f).accept(6f).accept(6f).learnedPeak)
    }

    /**
     * The other half of the same rule: a reading that two neighbours corroborate IS learned. Without
     * this, "ignores spikes" could be satisfied by a tracker that learns nothing at all.
     */
    @Test fun a_corroborated_reading_is_learned() {
        // The MEDIAN of the run, not its maximum: 200 A is corroborated by 190, so 190 is what a
        // window of three can honestly assert.
        assertEquals(190f, PeakTracker().accept(180f).accept(190f).accept(200f).learnedPeak)
        assertEquals(42f, PeakTracker().accept(42f).accept(42f).accept(42f).learnedPeak)
    }

    /** Nothing is learned before the window is full — a partial "median" is the sample itself. */
    @Test fun nothing_is_learned_until_the_window_is_full() {
        assertEquals(0f, PeakTracker().accept(300f).learnedPeak)
        assertEquals(0f, PeakTracker().accept(300f).accept(300f).learnedPeak)
        assertEquals(300f, PeakTracker().accept(300f).accept(300f).accept(300f).learnedPeak)
    }

    @Test fun the_learned_peak_never_decreases() {
        var tracker = PeakTracker()
        var seen = listOf<Float>()
        listOf(90f, 100f, 110f, 5f, 5f, 5f, 0f, 0f, 0f, 120f, 130f, 140f).forEach { sample ->
            tracker = tracker.accept(sample)
            seen = seen + tracker.learnedPeak
        }
        assertEquals(seen, seen.sorted())
        assertEquals(130f, tracker.learnedPeak)
    }

    /** Bipolar: a regen braking run teaches the tracker exactly as much as an acceleration run. */
    @Test fun the_tracker_learns_from_the_absolute_value() {
        assertEquals(
            PeakTracker().accept(120f).accept(130f).accept(140f).learnedPeak,
            PeakTracker().accept(-120f).accept(-130f).accept(-140f).learnedPeak
        )
    }

    /**
     * Non-finite samples are ignored, never propagated — `kotlin.math.max` and a sorted window both
     * carry `NaN` through, and one poisoned [PeakTracker.learnedPeak] is permanent.
     */
    @Test fun a_non_finite_sample_is_ignored_rather_than_learned() {
        val baseline = PeakTracker().accept(100f).accept(100f).accept(100f)
        val poisoned = baseline.accept(Float.NaN).accept(Float.POSITIVE_INFINITY).accept(Float.NaN)
        assertEquals(100f, poisoned.learnedPeak)
        assertFalse(poisoned.learnedPeak.isNaN())

        // Nor may a NaN advance the WINDOW: if it did, three NaNs would displace the three real
        // samples and the next single genuine reading would be a full window on its own.
        assertEquals(baseline, poisoned)
    }

    /** [PeakTracker.seededAt] is the `GET_MCCONF` seam, and it sanitises what the file hands back. */
    @Test fun a_seeded_tracker_starts_from_its_seed_and_refuses_nonsense() {
        assertEquals(240f, PeakTracker.seededAt(240f).learnedPeak)
        assertEquals(0f, PeakTracker.seededAt(0f).learnedPeak)
        assertEquals(0f, PeakTracker.seededAt(-5f).learnedPeak)
        assertEquals(0f, PeakTracker.seededAt(Float.NaN).learnedPeak)
        assertEquals(0f, PeakTracker.seededAt(Float.POSITIVE_INFINITY).learnedPeak)
        // A seed is a floor, not a ceiling: real riding still grows past a configured limit.
        assertEquals(
            310f,
            PeakTracker.seededAt(240f).accept(300f).accept(310f).accept(320f).learnedPeak
        )
        // And a smaller corroborated run never pulls it back down.
        assertEquals(240f, PeakTracker.seededAt(240f).accept(5f).accept(5f).accept(5f).learnedPeak)
    }

    /**
     * **The unknown-vs-zero contract, from the tracker's side** (item 6, Task 6's rule reaching its
     * third consumer).
     *
     * The sample is deliberately INCOHERENT — `powerW = 4200f` with `hasPower = false` — which no
     * producer emits, and that is exactly why it separates the contract from the producers' habits.
     * A fixture that set `powerW = 0f` alongside the false flag would pass against an implementation
     * that ignored the flag entirely.
     */
    @Test fun an_unobserved_power_never_reaches_the_tracker() {
        val incoherent = ControllerData(powerW = 4200f, hasPower = false, isConnected = true)
        assertEquals(null, MotionReadings.powerW(incoherent), "the flag, not the number, decides")

        // What the component does: filter through MotionReadings, then fold.
        var tracker = PeakTracker()
        repeat(6) { MotionReadings.powerW(incoherent)?.let { tracker = tracker.accept(it) } }
        assertEquals(0f, tracker.learnedPeak)
        assertEquals(
            GaugeScale.POWER_RUNGS_W.first(),
            GaugeScale.rungFor(tracker.learnedPeak, GaugeScale.POWER_RUNGS_W),
            "a Begode must not teach its dial that peak power is 0 W and then be stuck there"
        )

        // The coherent twin, to prove the filter is not simply rejecting everything.
        val observed = incoherent.copy(hasPower = true)
        var learned = PeakTracker()
        repeat(3) { MotionReadings.powerW(observed)?.let { learned = learned.accept(it) } }
        assertEquals(4200f, learned.learnedPeak)
    }

    // --- clearing on a hardware change (item 7) --------------------------------------------------

    private fun controller(
        index: Int,
        type: ControllerType = ControllerType.VESC,
        address: String = "AA:BB",
        canId: Int? = null,
        label: String = "Main"
    ) = Controller(
        index = index,
        label = label,
        controllerType = type,
        address = address,
        canId = canId,
        motor = MotorConfig()
    )

    @Test fun the_peaks_survive_an_unchanged_controller_set() {
        val set = listOf(controller(0), controller(1, address = "CC:DD"))
        assertTrue(GaugeScale.peaksStillApply(set, set))
        // Reordered: the cards moved, the hardware did not.
        assertTrue(GaugeScale.peaksStillApply(set, set.reversed()))
    }

    @Test fun the_peaks_do_not_survive_added_removed_or_swapped_hardware() {
        val one = listOf(controller(0))
        val two = one + controller(1, address = "CC:DD")
        assertFalse(GaugeScale.peaksStillApply(one, two), "a second controller doubles the current")
        assertFalse(GaugeScale.peaksStillApply(two, one))
        assertFalse(GaugeScale.peaksStillApply(one, emptyList()))
        assertFalse(
            GaugeScale.peaksStillApply(one, listOf(controller(0, type = ControllerType.FARDRIVER))),
            "a different protocol is a different board"
        )
        assertFalse(GaugeScale.peaksStillApply(one, listOf(controller(0, address = "ZZ:ZZ"))))
        assertFalse(GaugeScale.peaksStillApply(one, listOf(controller(0, canId = 11))))
        // Two identical boards on one bus are two boards, not one.
        assertFalse(GaugeScale.peaksStillApply(listOf(controller(0)), listOf(controller(0), controller(1))))
    }

    /**
     * The other direction, and the reason the rule is not simply `controllersEdited`: a rider who
     * renames a card, corrects a wheel diameter or answers the derived-battery question has not
     * changed what the ESC can pull, and losing a hard-learned dial range to a typo fix would be its
     * own defect.
     */
    @Test fun cosmetic_and_geometric_edits_do_not_clear_the_peaks() {
        val before = listOf(controller(0, label = "Main"))
        assertTrue(GaugeScale.peaksStillApply(before, listOf(controller(0, label = "Front"))))
        assertTrue(
            GaugeScale.peaksStillApply(
                before,
                listOf(controller(0).copy(motor = MotorConfig(polePairs = 15, wheelDiameterMm = 500)))
            )
        )
        assertTrue(
            GaugeScale.peaksStillApply(before, listOf(controller(0).copy(providesDerivedBattery = true)))
        )
        // Index is a display position, not hardware.
        assertTrue(GaugeScale.peaksStillApply(before, listOf(controller(7))))
    }

    /** A vehicle with no controllers at all — a plain BMS battery — is not a change from itself. */
    @Test fun a_controller_less_vehicle_keeps_its_peaks() {
        assertTrue(GaugeScale.peaksStillApply(emptyList(), emptyList()))
    }

    // --- the two together --------------------------------------------------------------------

    /**
     * The whole arc of a wheel's first ride, as one sequence: opens on the narrowest rung, walks up
     * as the machine reports itself, and settles.
     */
    @Test fun a_wheels_first_ride_walks_the_rung_up_and_then_stops_moving() {
        var tracker = PeakTracker()
        val rungs = mutableListOf(GaugeScale.rungFor(tracker.learnedPeak, GaugeScale.CURRENT_RUNGS_A))
        // A cruise, then a hill, then a cruise again — every reading corroborated.
        listOf(6f, 6f, 6f, 20f, 20f, 20f, 6f, 6f, 6f).forEach { sample ->
            tracker = tracker.accept(sample)
            rungs += GaugeScale.rungFor(tracker.learnedPeak, GaugeScale.CURRENT_RUNGS_A)
        }
        assertEquals(10f, rungs.first())
        assertEquals(30f, rungs.last(), "20 A * 1.25 = 25, so the 30 A rung")
        assertEquals(rungs, rungs.sorted(), "the rung must never step back down")
        // Two distinct rungs over a whole ride: that is the write budget item 4 is about.
        assertEquals(listOf(10f, 30f), rungs.distinct())
        assertTrue(abs(tracker.learnedPeak - 20f) < 1e-6f)
    }
}
