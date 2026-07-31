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
     * **What the dial actually draws: never below [previousRung], and otherwise
     * `max(learnedPeak, abs(sample))`.**
     *
     * Two rules, and they are asymmetric on purpose.
     *
     * **Widening is instant** (`§9.2` item 3). The learned peak is median-filtered
     * and slow ([PeakTracker]) so a corrupt frame can never widen the scale for
     * the life of the vehicle; the live sample is raw so a *genuine* excursion is
     * on-scale in the very frame it happens rather than five frames later, pinned
     * at the end of the old rung. Only the learned half is ever persisted — `a
     * live excursion widens the dial without being learned or written` keeps the
     * two from being collapsed into one number by a future tidy-up.
     *
     * **Narrowing does not happen at all.** Item 3 asked for instant widening; a
     * *symmetric* rule would let a noisy vehicle step between rungs — and, on the
     * POWER dial, between tick UNITS (`ClassicDialSpecs.powerTicksInKilowatts`) —
     * frame to frame, which is exactly the animation the quantisation exists to
     * prevent. So [previousRung] is a floor: the displayed rung is monotone
     * non-decreasing for as long as the caller keeps feeding its own last answer
     * back in. The caller resets that chain when it adopts a different vehicle or
     * a cleared peak, which is what ends a "session" here.
     *
     * [previousRung] is snapped up onto [rungs] first (`headroom = 1f`, i.e. the
     * smallest rung at or above it), so a caller that hands back a raw peak, a
     * zero or a non-finite value cannot make the dial draw a width that is not a
     * rung — the ring's label step is only round on a rung.
     *
     * A non-finite [sample] contributes nothing rather than poisoning the max:
     * `max(x, NaN)` is `NaN` in IEEE-754, and `rungFor(NaN)` answers the *first*
     * rung — so without this guard one `NaN` frame would ask for the narrowest
     * scale (and, before the floor existed, visibly collapse a wide dial to it).
     */
    fun displayRung(previousRung: Float, learnedPeak: Float, sample: Float, rungs: List<Float>): Float {
        val instant = if (sample.isFinite()) abs(sample) else 0f
        val learned = if (learnedPeak.isFinite()) learnedPeak else 0f
        val wanted = rungFor(max(learned, instant), rungs)
        val floor = rungFor(previousRung, rungs, headroom = 1f)
        return max(wanted, floor)
    }

    /** [displayRung] against [CURRENT_RUNGS_A] — the CURRENT dial's `±max`, in amps. */
    fun currentDisplayRungA(previousRungA: Float, learnedPeakA: Float, sampleA: Float): Float =
        displayRung(previousRungA, learnedPeakA, sampleA, CURRENT_RUNGS_A)

    /** [displayRung] against [POWER_RUNGS_W] — the POWER dial's `±max`, in watts. */
    fun powerDisplayRungW(previousRungW: Float, learnedPeakW: Float, sampleW: Float): Float =
        displayRung(previousRungW, learnedPeakW, sampleW, POWER_RUNGS_W)

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
 * ## Why a median, and why FIVE
 *
 * Begode frames carry **no checksum** (`D`), so a garbled 300 A sample is
 * indistinguishable from a real one at the decode layer. The peak is monotone and
 * persisted, so accepting one such sample does not spoil a ride — it spoils the
 * vehicle, permanently, until somebody edits its source list. A median over the
 * last [WINDOW] samples rejects any run of corrupt frames shorter than half the
 * window: to move the median, a reading has to be corroborated by
 * `WINDOW / 2 + 1` of its neighbours.
 *
 * **Three was the original ruling and it was wrong.** A median of three moves on
 * **two** *adjacent* corrupt frames — `[6, 300, 300]` has a median of 300 — and
 * two adjacent bad frames on a checksum-less protocol are entirely ordinary: a
 * dropped BLE notification, a re-assembly boundary, or a burst of interference
 * hits consecutive frames far more often than isolated ones. A median of five
 * needs **three** consecutive frames that all pass the `5A5A5A5A` tail check and
 * the zero-payload gate, which is a materially different proposition.
 *
 * The cost is two extra samples of latency in **persistence only**. The displayed
 * range is unaffected: [GaugeScale.displayRung] takes the raw sample, so the
 * rider's own excursion is on scale in the frame it happens whatever the window
 * is. At the 5-10 Hz poll rate these protocols run (`B §3`) the extra latency is
 * a few hundred milliseconds before a number is *stored*, and nobody can see it.
 *
 * Five is also why nothing here needs a timer: the window advances on samples, so
 * this class can be folded synchronously in a `collect` without scheduling a
 * delay — the unbounded-delay shape that wedges `runTest` instead of failing it.
 *
 * What this does **not** have, deliberately: decay, shrinking, and a rider-facing
 * reset. A peak that could shrink is a scale that moves during a ride. The reset
 * path that exists is the composer's controller-set change
 * ([GaugeScale.peaksStillApply]); anything more is a recorded open item, not a
 * gap.
 *
 * The window must be **full** before anything is learned. With a partial window
 * the "median" of one or two samples is dominated by the newest of them, which
 * would let a spike arriving in the first frames of a connection through —
 * exactly the case the median is here to stop. Nothing is lost by waiting:
 * [GaugeScale.displayRung] already shows the live sample on-scale in the meantime.
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
        /**
         * Five, not three — see this class' doc. A median of three is moved by two
         * *adjacent* corrupt frames, which is an ordinary event on a protocol with
         * no checksum; five requires three consecutive ones.
         */
        const val WINDOW: Int = 5

        /**
         * A tracker that already knows [peak] — the stored
         * [GaugePeaks][ru.sodovaya.volty.domain.repository.GaugePeaks] this
         * vehicle carried into the ride.
         *
         * **This is the `GET_MCCONF` seam** (`B §14`): the day a VESC's
         * `l_current_max` is readable, it seeds a tracker here exactly the way a
         * persisted peak does, and nothing else in this file changes. It is a
         * seed and not an override because a rider's actual peak can exceed a
         * configured limit (two controllers, a mis-set config), and the machine
         * reporting its own behaviour outranks its paperwork.
         *
         * A non-finite or negative seed is read as "nothing learned" rather than
         * carried: `GaugePeakRow`'s columns are `REAL NOT NULL` but SQLite will
         * hand back whatever is in the file.
         */
        fun seededAt(peak: Float): PeakTracker =
            PeakTracker(learnedPeak = if (peak.isFinite() && peak > 0f) peak else 0f)
    }
}
