package ru.sodovaya.volty.domain.usecase

enum class AlertSeverity { CRITICAL, WARNING, INFO }

/**
 * The **one-shot notification** kinds (F §2): a discrete event crossed a line,
 * debounced, arm/recover, posts a notification and is then quiet until the
 * condition clears.
 *
 * Not to be confused with [ru.sodovaya.volty.domain.alert.MotionAlertKind],
 * which is the *continuous* alarm's axis — a live graded signal that sounds
 * while the condition holds. The two overlap on temperature deliberately: the
 * alarm tells the rider *now*, the notification leaves a record of the episode.
 *
 * The last three are motion kinds (Task 6). There is **one** notification kind
 * per motion metric, not one per rider-defined level: three levels on one metric
 * would otherwise mean three notifications for a single continuous climb, which
 * is the alarm-fatigue failure F §10 exists to prevent. The level reached is
 * carried by the [AlertSeverity] instead.
 */
enum class AlertKind {
    CELL_HIGH, CELL_LOW, CELL_DELTA, TEMPERATURE_WARN, TEMPERATURE_HIGH,
    SOC_LOW, SOC_CUTOFF, DISCONNECT, CHARGE_COMPLETE,
    CONTROLLER_FAULT, MOTOR_TEMP_HIGH, ESC_TEMP_HIGH
}

data class AlertEvent(
    val kind: AlertKind,
    val severity: AlertSeverity,
    val title: String,
    val text: String,
    val vehicleId: String
)
