package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.Controller
import kotlin.math.abs
import kotlin.math.max

/**
 * **How wide a bipolar dial's scale is, learned from the vehicle** (`G §9.2`).
 *
 * Classic's CURRENT dial used to floor at ±60 A and POWER at ±10 000 W — VESC's
 * own `RtDataSetup.qml` defaults. An electric unicycle cruises at ~6 A and
 * ~571 W, so on a wheel both needles lived in the first tenth of scale and never
 * visibly moved: two instruments that read the same at every speed the machine
 * can reach.
 *
 * VESC Tool does not have this problem because it rewrites both maxima from the
 * motor configuration (`l_current_max`, `l_watt_max`) the moment a controller
 * answers `COMM_GET_MCCONF`. Volty cannot: `GET_MCCONF` is Part B `§14`, still
 * unimplemented, and it exists on exactly one of the protocols this app speaks.
 *
 * **So the range is LEARNED, per vehicle, from what the machine has actually
 * reported.** That decision (2026-07-30) is deliberately not "ask the rider" — a
 * current limit is a number most riders do not know — and deliberately not
 * per-protocol, because the same protocol drives a 250 A scooter and a 15 A
 * wheel. `GET_MCCONF` remains a **seed** for this mechanism rather than a
 * replacement for it: see [PeakTracker.seededAt], which is the one place a
 * future `mcconf` read would hand its `l_current_max` in, without any of the
 * arithmetic below changing.
 *
 * ## Why a ladder of rungs and not the peak itself
 *
 * A scale that tracked the peak continuously would move under the rider's eyes
 * all ride — the reason [ClassicDialSpecs][ru.sodovaya.volty.presentation.ride.ClassicDialSpecs]
 * has always insisted its scales only ever grow. Quantising to a short ladder
 * makes the *displayed* scale change a handful of times over a vehicle's life
 * and then never again, which is what separates an instrument from an animation.
 * It is also what makes the persistence in
 * [RideDashboardComponent][ru.sodovaya.volty.presentation.ride.DefaultRideDashboardComponent]
 * cheap: a write per rung change, not a write per BLE notification.
 */
object GaugeScale {

    /**
     * How much room above the learned peak a rung must leave — and it is
     * **load-bearing, not padding**.
     *
     * Without it, [rungFor] would pick the rung a peak exactly reaches, so the
     * needle would sit pinned at full scale for every new high-water mark while
     * the range was still growing — precisely the unreadable state this whole
     * object exists to remove, just at the other end of the dial. With it, a
     * learned peak can never exceed `rung / 1.25` = 80 % of full scale (except
     * on the last rung, where there is nothing wider to grow into), and
     * `the headroom keeps a learned peak off the end of its scale` pins that.
     */
    const val HEADROOM: Float = 1.25f

    /**
     * The CURRENT ladder, in amps.
     *
     * Bottom rung 10 A because a wheel cruises around 6 A; top rung 500 A
     * because the widest machine this app is aimed at is a two-controller
     * scooter at 2 × 250 A. The rungs in between are the round numbers real
     * controller limits cluster on (30 A wheels, 60 A — VESC's own default —
     * 100 A single uBox, 150/200 A tuned singles, 300 A pairs).
     */
    val CURRENT_RUNGS_A: List<Float> =
        listOf(10f, 20f, 30f, 60f, 100f, 150f, 200f, 300f, 500f)

    /**
     * The POWER ladder, in watts. Same shape as [CURRENT_RUNGS_A]: 500 W is
     * inside a wheel's cruise (~571 W), 10 000 W is VESC's own default, 50 000 W
     * covers the scooter at the top of [CURRENT_RUNGS_A].
     */
    val POWER_RUNGS_W: List<Float> =
        listOf(500f, 1_000f, 2_000f, 3_000f, 5_000f, 10_000f, 20_000f, 30_000f, 50_000f)

    /**
     * The smallest rung of [rungs] that leaves [headroom] above [peak].
     *
     * Three edges, all deliberate:
     *
     *  - **a peak past the top of the ladder gets the last rung**, not an
     *    extrapolation. The ladder is the ceiling, so no reading — spurious or
     *    genuine — can grow the scale without bound the way the pre-ladder
     *    snap-and-grow arithmetic could;
     *  - **zero, negative and non-finite peaks all get the FIRST rung.** Zero is
     *    the honest seed for a vehicle nobody has ridden yet (`§9.2` item 5), and
     *    a negative or `NaN` peak is a bug upstream — answering it with the
     *    narrowest scale keeps the dial readable rather than propagating the
     *    nonsense into the geometry. A bipolar dial's peak is always fed as an
     *    absolute value, so a negative peak is never a legitimate input;
     *  - **an empty ladder answers 0.** Unreachable through the two constants
     *    above, and `VescGaugeRange` already draws a zero-span range as a resting
     *    needle rather than a `NaN`, so this cannot become a crash.
     */
    fun rungFor(peak: Float, rungs: List<Float>, headroom: Float = HEADROOM): Float {
        val first = rungs.firstOrNull() ?: return 0f
        if (!peak.isFinite() || peak <= 0f) return first
        val wanted = peak * headroom
        return rungs.firstOrNull { it >= wanted } ?: rungs.last()
    }

    /**
     * **What the dial actually draws: `max(learnedPeak, abs(sample))`.**
     *
     * The two inputs are different numbers on purpose (`§9.2` item 3). The
     * learned peak is median-filtered and slow ([PeakTracker]) so a corrupt frame
     * can never widen the scale for the life of the vehicle; the live sample is
     * raw and instant so a *genuine* excursion is on-scale in the very frame it
     * happens instead of three frames later, pinned at the end of the old rung.
     *
     * Only the learned half is ever persisted. `a live excursion widens the dial
     * without being learned or written` is the test that keeps the two from being
     * collapsed into one number by a future tidy-up.
     *
     * A non-finite [sample] contributes nothing rather than poisoning the max:
     * `max(x, NaN)` is `NaN` in IEEE-754, and `rungFor(NaN)` answers the *first*
     * rung — so without this guard one `NaN` frame would visibly collapse a wide
     * dial to its narrowest scale.
     */
    fun displayRung(learnedPeak: Float, sample: Float, rungs: List<Float>): Float {
        val instant = if (sample.isFinite()) abs(sample) else 0f
        val learned = if (learnedPeak.isFinite()) learnedPeak else 0f
        return rungFor(max(learned, instant), rungs)
    }

    /** [displayRung] against [CURRENT_RUNGS_A] — the CURRENT dial's `±max`, in amps. */
    fun currentDisplayRungA(learnedPeakA: Float, sampleA: Float): Float =
        displayRung(learnedPeakA, sampleA, CURRENT_RUNGS_A)

    /** [displayRung] against [POWER_RUNGS_W] — the POWER dial's `±max`, in watts. */
    fun powerDisplayRungW(learnedPeakW: Float, sampleW: Float): Float =
        displayRung(learnedPeakW, sampleW, POWER_RUNGS_W)

    /**
     * Does a peak learned on [before]'s controllers still describe [after]'s?
     *
     * `§9.2` item 7: a learned peak is a statement about **hardware**, so when
     * the composer changes which controllers are on a vehicle, the old peak
     * describes something that is no longer bolted to it — a 300 A scooter
     * controller swapped for a 15 A wheel board would leave the wheel reading its
     * predecessor's scale forever, since the peak only ever grows.
     *
     * Identity is `(type, address, canId)` and **not** label, motor geometry or
     * `providesDerivedBattery`: renaming a card or correcting a wheel diameter
     * does not change what the ESC can pull, and clearing a hard-won range for a
     * typo fix would be its own defect. Order does not matter either — a reorder
     * moves the cards, not the hardware — so the keys are compared sorted, which
     * also makes two identical controllers on one bus count as two.
     */
    fun peaksStillApply(before: List<Controller>, after: List<Controller>): Boolean =
        before.map { it.hardwareKey() }.sorted() == after.map { it.hardwareKey() }.sorted()

    private fun Controller.hardwareKey(): String =
        "${controllerType.name}@$address#${canId ?: "-"}"
}

/**
 * The learned half of [GaugeScale] — a per-vehicle high-water mark that a single
 * corrupt frame cannot move.
 *
 * ## Why a median, and why three
 *
 * Begode frames carry **no checksum** (`D`), so a garbled 300 A sample is
 * indistinguishable from a real one at the decode layer. The peak is monotone and
 * persisted, so accepting one such sample does not spoil a ride — it spoils the
 * vehicle, permanently, until somebody edits its source list. A median over the
 * last [WINDOW] samples costs two floats of state and rejects any *single*
 * outlier outright: to move the median, a reading has to be corroborated by at
 * least one of its neighbours.
 *
 * Three is the smallest window with a median at all, and at the 5-10 Hz poll rate
 * these protocols run (`B §3`) it means a genuine acceleration is learned within
 * a few hundred milliseconds. It is also why nothing here needs a timer: the
 * window advances on samples, so this class can be folded synchronously in a
 * `collect` without scheduling a delay — the unbounded-delay shape that wedges
 * `runTest` instead of failing it.
 *
 * The window must be **full** before anything is learned. With a partial window
 * the "median" of one sample is that sample, which would let a spike arriving as
 * the very first frame of a connection through — exactly the case the median is
 * here to stop. Nothing is lost by waiting: [GaugeScale.displayRung] already
 * shows the live sample on-scale in the meantime.
 *
 * ## What may be fed in
 *
 * Only observed values. `§9.2` item 6 is Task 6's contract reaching the third
 * consumer of the same flags: a `powerW` whose
 * [ControllerData.hasPower][ru.sodovaya.volty.domain.model.ControllerData.hasPower]
 * is false is a placeholder, not a measurement, and letting it into the window
 * would teach every Begode that its peak power is 0 W — and, worse, dilute a
 * real neighbour out of the median. Callers filter through
 * [MotionReadings]; this class only guards non-finite samples, which no producer
 * emits today and which would otherwise poison [learnedPeak] forever.
 *
 * Battery current has no known-flag to check, and deliberately so — see
 * [MotionReadings]' own note on why a flag for it could never be set to false.
 */
data class PeakTracker(
    /**
     * The confirmed high-water mark, in the sample's own units. Monotone: never
     * decreases for any sequence of samples, which is what makes a rung change a
     * one-way event and therefore worth persisting.
     */
    val learnedPeak: Float = 0f,
    /** The last [WINDOW] absolute samples, oldest first. */
    private val window: List<Float> = emptyList()
) {

    /** Folds one raw (signed) sample in. Both dials are bipolar, so the magnitude is what counts. */
    fun accept(sample: Float): PeakTracker {
        if (!sample.isFinite()) return this
        val next = (window + abs(sample)).takeLast(WINDOW)
        if (next.size < WINDOW) return copy(window = next)
        val median = next.sorted()[WINDOW / 2]
        return PeakTracker(learnedPeak = max(learnedPeak, median), window = next)
    }

    companion object {
        const val WINDOW: Int = 3

        /**
         * A tracker that already knows [peak] — the stored
         * `Vehicle.gaugePeakCurrentA` / `gaugePeakPowerW` on connect.
         *
         * **This is the `GET_MCCONF` seam** (`B §14`): the day a VESC's
         * `l_current_max` is readable, it seeds a tracker here exactly the way a
         * persisted peak does, and nothing else in this file changes. It is a
         * seed and not an override because a rider's actual peak can exceed a
         * configured limit (two controllers, a mis-set config), and the machine
         * reporting its own behaviour outranks its paperwork.
         *
         * A non-finite or negative seed is read as "nothing learned" rather than
         * carried: the column is `REAL NOT NULL DEFAULT 0` but SQLite will hand
         * back whatever is in the file.
         */
        fun seededAt(peak: Float): PeakTracker =
            PeakTracker(learnedPeak = if (peak.isFinite() && peak > 0f) peak else 0f)
    }
}
