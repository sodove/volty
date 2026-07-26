package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.model.ControllerData

/**
 * Turns a live motion sample plus the rider's armed configuration into a graded
 * [AlarmState] (F §10.2). Task 7 turns that state into sound; Task 8 feeds it
 * samples from the foreground service.
 *
 * **Deterministic — not pure.** It holds one piece of mutable state, the step
 * each kind is currently holding, which hysteresis cannot be expressed without.
 * What it has instead of purity is that the state is *entirely* derived from the
 * samples fed in: no clock, no coroutines, no platform API, so the same sequence
 * of samples from a fresh controller always produces the same sequence of
 * states, and [reset] returns it to the state it was constructed in. It is not
 * thread-safe; drive it from one collector.
 *
 * **It takes [ArmedRules], not `List<AlertRule>`, and that is deliberate.**
 * `F §10` says an alert the hardware cannot supply must be *impossible* to arm.
 * Raw rider config and gated config have identical structure, so a list
 * parameter would make that a convention enforced by code review; the value
 * class makes handing this engine ungated config a compile error. Do not unwrap
 * `.rules` at a call site to satisfy it — call [armedRules].
 *
 * **Availability is not re-derived here, so the caller must keep [ArmedRules]
 * fresh — recompute it whenever `activeVehicle` or `activeMotion` changes.**
 * [ControllerData.hasMotorTemp] and [ControllerData.hasEscTemp] are
 * [availabilityFor]'s business, and a second answer to that question living here
 * would be a second thing to keep in sync with it. The obligation that moves to
 * the caller in exchange is real and silent when broken: every sensor-backed
 * kind is [AlertAvailability.Unknown] until the first sample arrives, and
 * [armedRules] drops Unknown, so a service that computes [ArmedRules] once at
 * start-up **arms no temperature alert for the whole ride** — and nothing
 * reports it, because the alarm simply never sounds.
 *
 * ### Escalation
 *
 * Level is the **max across contributing kinds**: duty at step 2 with motor
 * temperature at step 1 is step 2. The rider is owed the most urgent thing that
 * is true.
 *
 * A **disabled level is skipped without shifting the ones above it**
 * ([AlertLevel.enabled]): with steps 1/2/3 and step 2 muted, a reading past the
 * second threshold reports step 1, and only the third threshold reaches step 3.
 * The tone a rider learned for the top step stays the top step.
 *
 * ### Hysteresis
 *
 * Attack is immediate — a step engages the instant `value >= threshold` — and
 * only the release is damped: a step holds until the value falls below
 * `threshold - release` ([AlarmHysteresis]). A reading parked on a threshold, or
 * wandering across the gap between two configured steps, therefore settles on
 * one step and stays there instead of chattering.
 *
 * A reading that is not a number (NaN, from a decoder that produced garbage)
 * fails both comparisons and reads as *below every step*, so it silences rather
 * than crashes. It cannot latch anything on, and the next real sample re-arms
 * immediately.
 *
 * ### Disconnected samples
 *
 * **A sample with [ControllerData.isConnected] false silences the alarm and
 * clears the memory**, exactly as [reset] would.
 * `BmsRepository.activeMotion` is a non-nullable flow that emits a default
 * `ControllerData()` — every field zero — while nothing is connected, and that
 * placeholder is not a measurement. Two failures are on the table and this
 * closes both:
 *
 *  - *sounding after the link drops.* Holding the last level through a dropout
 *    means an alarm nothing can clear, because clearing it needs the readings
 *    that just stopped arriving. Task 8's "the alarm must stop on disconnect" is
 *    then a rule enforced somewhere else, and every future consumer has to
 *    remember it;
 *  - *reading zeros as safe.* Numerically the placeholder does silence today —
 *    every motion alert fires on a value being too **high** — but only by
 *    coincidence of direction. Deciding it explicitly means an alert that ever
 *    fires on a value being too *low* does not quietly inherit "0 is fine".
 *
 * Clearing the memory (rather than only muting the output) is the other half:
 * after a gap, the first fresh sample must be judged on its own, not against a
 * step latched before the link went away. Attack is immediate, so a condition
 * that is still true re-arms on that very sample; all that is lost is a release
 * band that was measuring nothing.
 */
class AlarmController {

    /** Per kind, the step it is currently holding. Absent means 0. Hysteresis's only state. */
    private var heldSteps: Map<MotionAlertKind, Int> = emptyMap()

    /** The most recent result of [update] — the same value that call returned. */
    var state: AlarmState = AlarmState.SILENT
        private set

    /**
     * Fold one sample into the alarm. Returns the new [state]; see the class doc
     * for escalation, hysteresis and what a disconnected sample does.
     */
    fun update(sample: ControllerData, rules: ArmedRules): AlarmState {
        if (!sample.isConnected) {
            // Nothing was measured, so there is no memory worth keeping.
            // (No guard for empty rules: with nothing to walk, the loop below
            // produces no held steps and no contributors, which is the same
            // answer. A short-circuit here would be a second code path saying
            // it, and one that no test could tell apart from this one.)
            reset()
            return state
        }

        val steps = mutableMapOf<MotionAlertKind, Int>()
        val urgencies = mutableMapOf<MotionAlertKind, Float>()
        val values = mutableMapOf<MotionAlertKind, Float>()

        for (rule in rules.rules) {
            val value = sample.valueFor(rule.kind)
            val step = rule.stepFor(value, heldSteps[rule.kind] ?: 0)
            // Two things at once, which is why there is no separate `step == 0`
            // guard above it (that guard is an equivalent mutant — removing it
            // changes no behaviour and no test, so it would be a second code path
            // saying what this line already says):
            //
            //  - a kind below its first step never enters the map, because
            //    `0 > 0` is false whether or not the kind is present. "Not
            //    sounding" is the absence of an entry, so `heldSteps` carries
            //    only what is actually held;
            //  - `armedRules` preserves whatever it was given, and Vehicle already
            //    refuses a duplicated kind — but a caller assembling rules by hand
            //    could still repeat one, and the loudest reading is the honest
            //    answer to "what is this kind doing".
            if (step > (steps[rule.kind] ?: 0)) {
                steps[rule.kind] = step
                urgencies[rule.kind] = rule.urgencyAt(value, step)
                values[rule.kind] = value
            }
        }

        heldSteps = steps
        val contributors = MotionAlertKind.entries
            .filter { it in steps }
            .map { AlarmContributor(kind = it, level = steps.getValue(it), value = values.getValue(it)) }
            .sortedByDescending { it.level }
        val level = contributors.maxOfOrNull { it.level } ?: 0
        // Among the kinds tied at the top step, the one furthest through its own
        // band decides how urgent the tone is — the same "most urgent thing that
        // is true" rule that picked the level.
        val urgency = contributors
            .filter { it.level == level }
            .maxOfOrNull { urgencies.getValue(it.kind) }
            ?: 0f

        state = if (level == 0) AlarmState.SILENT else AlarmState(level, urgency, contributors)
        return state
    }

    /**
     * Silence the alarm and forget every held step. The next [update] is judged
     * on its sample alone. Task 8 calls this when the ride ends.
     */
    fun reset() {
        heldSteps = emptyMap()
        state = AlarmState.SILENT
    }
}

/** The reading a kind thresholds against, in the kind's own unit. */
private fun ControllerData.valueFor(kind: MotionAlertKind): Float = when (kind) {
    MotionAlertKind.DUTY -> dutyPercent
    MotionAlertKind.SPEED -> speedKmh
    MotionAlertKind.MOTOR_TEMP -> motorTempC
    MotionAlertKind.ESC_TEMP -> escTempC
}

/**
 * The step this rule is at for [value], given the step it was holding.
 *
 * A level counts as engaged when the reading has reached its threshold, **or**
 * when it was already holding that step and has not yet fallen a full release
 * band below it. Because [AlertRule] guarantees ascending thresholds, engagement
 * is monotone — if step 3 is engaged then so are steps 2 and 1 — so the answer
 * is simply the highest engaged step.
 *
 * Disabled levels are never engaged and never consulted, which is exactly how
 * they are skipped without the steps above them moving down: the step number is
 * the level's position in the list, not a running count of the enabled ones.
 */
private fun AlertRule.stepFor(value: Float, previousStep: Int): Int {
    var step = 0
    for ((index, level) in levels.withIndex()) {
        if (!level.enabled) continue
        val position = index + 1
        val engaged = value >= level.thresholdValue ||
            (previousStep >= position && value >= level.thresholdValue - kind.releaseBand)
        if (engaged && position > step) step = position
    }
    return step
}

/**
 * How far [value] has climbed through the band it is in, 0..1 — the ramp that
 * lets Task 7's tone rise continuously instead of jumping at each threshold.
 *
 * The band runs from the active step's threshold to the next *enabled* step's
 * threshold, so urgency is 0 the moment a step engages and approaches 1 as the
 * next one comes into reach; crossing into that next step therefore starts again
 * from 0, and the ramp is continuous across the whole range rather than
 * sawtoothing at each boundary. A muted level is not an edge to ramp towards,
 * since nothing happens when the reading passes it.
 *
 * **In the final band there is nothing above to ramp towards, so urgency is 1.**
 * That keeps it continuous — the ramp reaches 1 just as the top step engages,
 * and the alarm is already as urgent as it can be.
 *
 * **A zero-width band answers 1 for the same reason, and the guard is not
 * decorative.** [AlertRule] permits two steps to share a threshold (it requires
 * non-decreasing, not strictly increasing). Within a single rule that tie is
 * unreachable here — both positions engage on the same attack, so the higher one
 * wins and `upper` comes back null on the line above instead. It becomes
 * reachable through a duplicated kind, where one rule can leave a kind holding
 * step 1 while a *different* rule supplies the tied pair: then `lower == upper`,
 * and without the guard the division yields ±Infinity (or, if the reading landed
 * exactly on the shared threshold, NaN — which would poison Task 7's tone the
 * way Task 2's NaN threshold would have poisoned this ramp). Pinned by
 * `a_zero_width_band_between_tied_thresholds_is_full_urgency`.
 *
 * Under hysteresis a held reading can sit *below* its own band, which would ramp
 * negative; that is clamped to 0.
 */
private fun AlertRule.urgencyAt(value: Float, step: Int): Float {
    if (step <= 0) return 0f
    val lower = levels[step - 1].thresholdValue
    val upper = levels.drop(step).firstOrNull { it.enabled }?.thresholdValue ?: return 1f
    if (upper <= lower) return 1f
    return ((value - lower) / (upper - lower)).coerceIn(0f, 1f)
}
