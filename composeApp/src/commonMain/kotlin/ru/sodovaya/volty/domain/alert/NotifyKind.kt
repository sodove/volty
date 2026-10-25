package ru.sodovaya.volty.domain.alert

/**
 * The alert kinds whose **notification** the rider can switch per vehicle (rider-approved
 * decision 6, 2026-10-25).
 *
 * The switch gates the notification and nothing else: the live sound alarm and the rules that
 * drive it are untouched, and the battery alerts keep their own enable switches. Speed and duty
 * are notification-optional because they are the alarm's metrics, and a duty excursion is
 * ordinary riding.
 */
enum class NotifyKind {
    MOTOR_TEMP, ESC_TEMP, SPEED, DUTY, CONTROLLER_FAULT, BMS_FAULT;

    companion object {
        /**
         * What a vehicle that was never asked does: today's behaviour. Temperatures and faults
         * notify; speed and duty do not.
         */
        val DEFAULTS: Set<NotifyKind> = setOf(MOTOR_TEMP, ESC_TEMP, CONTROLLER_FAULT, BMS_FAULT)
    }
}
