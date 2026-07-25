package ru.sodovaya.volty.domain.stats

/**
 * ESC/motor temperature severity bands — the same shape as [DutyBands] (green
 * until warn, amber to critical, red above), generalized here since the ESC
 * and the motor each need their own ceiling. THE single source of truth for
 * what counts as "hot" on the Ride dashboard: [ru.sodovaya.volty.presentation.ride.SecondaryGaugeMapper]'s
 * secondary-gauge ring and the Clean dashboard's 2x2 cluster severity dot must
 * never disagree on this, and Part F's audible alarm will escalate from these
 * same ceilings — exactly the drift [DutyBands] was created to prevent for duty.
 */
object TempBands {
    const val ESC_WARN_C: Float = 70f
    const val ESC_CRITICAL_C: Float = 85f
    const val MOTOR_WARN_C: Float = 85f
    const val MOTOR_CRITICAL_C: Float = 100f

    fun escLevel(tempC: Float): DutyLevel = level(tempC, ESC_WARN_C, ESC_CRITICAL_C)

    /** [known] false (no motor-temp sensor reporting) is never alarming. */
    fun motorLevel(tempC: Float, known: Boolean): DutyLevel =
        if (!known) DutyLevel.NORMAL else level(tempC, MOTOR_WARN_C, MOTOR_CRITICAL_C)

    private fun level(tempC: Float, warnC: Float, criticalC: Float): DutyLevel = when {
        tempC >= criticalC -> DutyLevel.CRITICAL
        tempC >= warnC -> DutyLevel.WARN
        else -> DutyLevel.NORMAL
    }
}
