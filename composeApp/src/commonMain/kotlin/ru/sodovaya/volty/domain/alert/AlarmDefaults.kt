package ru.sodovaya.volty.domain.alert

/**
 * The opening position for every motion alert (F §10.1/§10.2) — the *only* place
 * motion-alarm thresholds are written down. Every one of these is a starting
 * point the rider edits per vehicle, not a decision the app keeps.
 *
 * These deliberately differ from the dashboard's
 * [ru.sodovaya.volty.domain.stats.DutyBands] / [ru.sodovaya.volty.domain.stats.TempBands]
 * colours, and the difference is the design (F §10.1): the dial's colour is a
 * *glanceable* warning that should turn amber early while the rider still has
 * cheap options; the alarm *interrupts*, so it must come later — otherwise it
 * fires while the dial is merely amber and the rider learns to dismiss it.
 *
 * **The invariant anyone editing either set must preserve** (F §11.1), per metric
 * that has a dashboard band:
 *
 * ```
 * first(levels).threshold >= band.warn   // the alarm never precedes the amber dial
 * last(levels).threshold  >= band.red    // the dial always reddens before the alarm sounds
 * ```
 *
 * F §10.1 states this as a flat `alarm >= band-red`, which duty's own defaults
 * violate (the first level, 80, is under the dial's red at 90); §11.1 restates it
 * over the *ends* of the list, which is what the reasoning actually requires.
 * `MotionAlarmInvariantTest` enforces it against the live band constants, so
 * editing either object here or there fails loudly.
 */
object AlarmDefaults {
    /** Duty %, two steps — 80 is wheel-rider convention, 90 is the dial's red. */
    const val DUTY_LEVEL_1_PERCENT: Float = 80f
    const val DUTY_LEVEL_2_PERCENT: Float = 90f

    /** ESC °C, one step — FET derating typically begins ~85 °C; alarm just past it. */
    const val ESC_TEMP_LEVEL_1_C: Float = 90f

    /**
     * Motor °C, one step — windings tolerate far more than FETs. 90 would nag
     * continuously on ordinary hardware, and an alarm a rider learns to ignore is
     * worse than no alarm.
     */
    const val MOTOR_TEMP_LEVEL_1_C: Float = 110f

    /** Speed ships off: a speed limit is meaningless without a rider-chosen number. */
    fun rule(kind: MotionAlertKind): AlertRule = AlertRule(
        kind = kind,
        levels = when (kind) {
            MotionAlertKind.DUTY -> listOf(
                AlertLevel(DUTY_LEVEL_1_PERCENT),
                AlertLevel(DUTY_LEVEL_2_PERCENT)
            )
            MotionAlertKind.ESC_TEMP -> listOf(AlertLevel(ESC_TEMP_LEVEL_1_C))
            MotionAlertKind.MOTOR_TEMP -> listOf(AlertLevel(MOTOR_TEMP_LEVEL_1_C))
            MotionAlertKind.SPEED -> emptyList()
        }
    )

    /** Every kind's default rule, in enum order. */
    fun all(): List<AlertRule> = MotionAlertKind.entries.map(::rule)
}
