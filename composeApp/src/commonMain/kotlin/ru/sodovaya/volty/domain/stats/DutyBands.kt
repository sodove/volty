package ru.sodovaya.volty.domain.stats

enum class DutyLevel { NORMAL, WARN, CRITICAL }

/**
 * Duty-cycle (ШИМ) severity bands. THE single source of truth: the Ride
 * dashboard colors its gauge from this and the Part F audible alarm escalates
 * from this, so the color a rider sees and the tone they hear can never
 * disagree. Thresholds are overridable because Part F makes them per-vehicle.
 */
object DutyBands {
    const val DEFAULT_WARN_PERCENT: Float = 75f
    const val DEFAULT_CRITICAL_PERCENT: Float = 90f

    fun level(
        dutyPercent: Float,
        warnPercent: Float = DEFAULT_WARN_PERCENT,
        criticalPercent: Float = DEFAULT_CRITICAL_PERCENT
    ): DutyLevel = when {
        dutyPercent >= criticalPercent -> DutyLevel.CRITICAL
        dutyPercent >= warnPercent -> DutyLevel.WARN
        else -> DutyLevel.NORMAL
    }
}
