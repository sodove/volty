package ru.sodovaya.volty.domain.alert

/**
 * The motion metrics a rider can arm an escalating audible alarm on (F §10.2).
 *
 * Deliberately separate from [ru.sodovaya.volty.domain.usecase.AlertKind], which
 * is the battery/notification path: those are one-shot notifications, these drive
 * a continuous tone that escalates while the condition holds (F §2).
 *
 * A controller *fault* is not here on purpose — it is a one-shot with nothing to
 * threshold, so it carries no levels (F §3, Task 6).
 */
enum class MotionAlertKind { DUTY, SPEED, MOTOR_TEMP, ESC_TEMP }

/**
 * One rider-defined step of an alert. [thresholdValue] is in the metric's own
 * unit (percent for duty, km/h for speed, °C for temperatures) — the kind that
 * owns the level says which.
 *
 * [enabled] mutes a single step without the rider losing the number they tuned.
 * A disabled level is **skipped without shifting the ones above it** (plan Task
 * 5) — muting level 2 of 3 does not promote level 3 into its place, so the tone
 * a rider learned for the top step stays the top step. Muting *every* step is
 * still not how an alert is turned off: an empty level list is the only
 * representation of "off" (F §10.2 — the UI must not offer two ways to say the
 * same thing).
 *
 * [thresholdValue] must be finite. NaN is not "no threshold": `value >= NaN` and
 * `value < NaN - release` are both false for every reading, so a NaN level can
 * never activate and never release, while [AlertRule.isOff] stays false — the
 * settings screen would show the alert armed and it would be permanently,
 * silently dead. It also poisons the urgency ramp `(v - lower) / (upper - lower)`
 * that drives tone pitch. Rejecting it here is the only place that catches it,
 * because nothing downstream throws.
 */
data class AlertLevel(
    val thresholdValue: Float,
    val enabled: Boolean = true
) {
    init {
        require(thresholdValue.isFinite()) { "threshold must be finite, got $thresholdValue" }
    }
}

/**
 * A rider's configuration for one [MotionAlertKind]: 0..3 levels, ascending.
 *
 * Position implies escalation — level 1 is the mildest, the last is the most
 * urgent — so the order is load-bearing and the type refuses to hold a list that
 * contradicts it. An unsorted list is not a rule the engine may see; it is rider
 * input that has not been through [sortedLevels] yet.
 *
 * An empty [levels] means the rider turned this alert off entirely.
 */
data class AlertRule(
    val kind: MotionAlertKind,
    val levels: List<AlertLevel>
) {
    init {
        require(levels.size <= MAX_LEVELS) {
            "$kind: at most $MAX_LEVELS alert levels, got ${levels.size}"
        }
        require(levels.zipWithNext().all { (lower, upper) -> lower.thresholdValue <= upper.thresholdValue }) {
            "$kind: levels must ascend, got ${levels.map { it.thresholdValue }}"
        }
    }

    /** The rider turned this alert off — the one and only way to say that. */
    val isOff: Boolean get() = levels.isEmpty()

    companion object {
        const val MAX_LEVELS: Int = 3
    }
}

/**
 * The editor's normaliser. A rider typing 90 / 80 / 100 means 80 / 90 / 100 —
 * they mistyped an order, they did not ask for anything to be thrown away. So
 * this reorders and *never* refuses and never drops (F §10.2 left the choice
 * between reorder and refuse open; reorder is kinder and loses no input).
 *
 * The sort is stable, so two levels sharing a threshold keep the order the rider
 * typed them in — and with them their [AlertLevel.enabled] flags.
 *
 * Call this on the way *into* [AlertRule]; [AlertRule] itself only verifies. The
 * output always satisfies [AlertRule]'s order check — which is true only because
 * [AlertLevel] rejects NaN at construction: `sortedBy` sorts NaN to the *end*,
 * where the ascending `require` would then throw on a list this function had
 * just "normalised".
 */
fun sortedLevels(input: List<AlertLevel>): List<AlertLevel> =
    input.sortedBy { it.thresholdValue }
