package ru.sodovaya.volty.domain.alert

/**
 * One kind that is currently holding the alarm up (F §10.2).
 *
 * [level] is the *step number* the kind is at — 1 is the mildest configured
 * step, 3 the most urgent — and it is a position in the rider's level list, not
 * a count of levels that fired. A disabled middle level leaves a gap: a rule
 * with steps 1/2/3 whose step 2 is muted can report `level = 3` while nothing
 * ever reports 2, because the tone a rider learned for the top step must stay
 * the top step ([AlertLevel.enabled]).
 *
 * [value] is the reading that put it there, in the metric's own unit, so a UI
 * or a log can say *why* the alarm is sounding without re-reading the sample.
 */
data class AlarmContributor(
    val kind: MotionAlertKind,
    val level: Int,
    val value: Float
)

/**
 * What the alarm should be doing right now — the whole output of
 * [AlarmController] and the whole input of Task 7's `AudibleAlarm`.
 *
 * [level] is 0 (silent) or the highest step any armed kind has reached; duty at
 * step 2 with motor temperature at step 1 is step 2, because the rider is owed
 * the most urgent thing that is true, not an average of the true things.
 *
 * [urgency] is a 0..1 ramp *within* [level]'s band, so a tone can climb
 * continuously between the discrete steps instead of jumping at each threshold:
 * 0 at the bottom edge of the active band, 1 at the top edge (the next
 * configured step). See [AlarmController] for what it does in the final band,
 * where there is no next step to ramp towards. At `level = 0` it is 0.
 *
 * [contributors] lists every kind at step >= 1, most urgent first and enum order
 * within a step, so the ordering is deterministic and a UI can render "why" in a
 * stable order. A kind below its first step is absent rather than present at 0 —
 * "not sounding" is not a degree of sounding.
 */
data class AlarmState(
    val level: Int = 0,
    val urgency: Float = 0f,
    val contributors: List<AlarmContributor> = emptyList()
) {
    /** Whether anything should be audible at all. */
    val isSounding: Boolean get() = level > 0

    companion object {
        /** Nothing is wrong, or nothing is known — the state the engine starts and recovers to. */
        val SILENT: AlarmState = AlarmState()
    }
}

/**
 * How far a reading must fall *below* a threshold before that step lets go
 * (F §10.2, §8). Attack is immediate; only the release is damped, because a
 * rider is owed the warning the instant the condition is true and owed calm only
 * once it is properly over.
 *
 * These are the only numbers this task introduces, and they live here rather
 * than beside [AlarmDefaults] because they are not thresholds a rider edits:
 * a release band is a property of the *measurement's* noise, not of the rider's
 * tolerance. Each is roughly the jitter a steady reading shows on real hardware
 * — big enough to stop chatter, small enough that the alarm does not linger
 * noticeably after the condition clears.
 */
object AlarmHysteresis {
    /** Duty, percentage points. Duty is computed per control loop and is the twitchiest of the four. */
    const val DUTY_RELEASE_PERCENT: Float = 3f

    /** Both temperatures, °C. Thermistor readings step in fractions of a degree; 3 °C is well clear of that. */
    const val TEMP_RELEASE_C: Float = 3f

    /** Speed, km/h. Tighter than the rest because a speed limit is a number the rider set deliberately. */
    const val SPEED_RELEASE_KMH: Float = 2f
}

/** The release band for a kind — the amount [AlarmController] subtracts to decide a step has let go. */
internal val MotionAlertKind.releaseBand: Float
    get() = when (this) {
        MotionAlertKind.DUTY -> AlarmHysteresis.DUTY_RELEASE_PERCENT
        MotionAlertKind.SPEED -> AlarmHysteresis.SPEED_RELEASE_KMH
        MotionAlertKind.MOTOR_TEMP -> AlarmHysteresis.TEMP_RELEASE_C
        MotionAlertKind.ESC_TEMP -> AlarmHysteresis.TEMP_RELEASE_C
    }
