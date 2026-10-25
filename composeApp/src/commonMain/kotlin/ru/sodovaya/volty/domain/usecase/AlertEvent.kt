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
 *
 * [BMS_FAULT], [SPEED_HIGH] and [DUTY_HIGH] arrived with the per-kind notify switches
 * (2026-10-25): a BMS fault had no notification at all, and speed and duty had none by design
 * until the rider asked for an opt-in one. The last two are only ever posted when switched on.
 */
enum class AlertKind {
    CELL_HIGH, CELL_LOW, CELL_DELTA, TEMPERATURE_WARN, TEMPERATURE_HIGH,
    SOC_LOW, SOC_CUTOFF, DISCONNECT, CHARGE_COMPLETE,
    CONTROLLER_FAULT, MOTOR_TEMP_HIGH, ESC_TEMP_HIGH,
    BMS_FAULT, SPEED_HIGH, DUTY_HIGH
}

/**
 * What the engine tells the notifier: a kind and the numbers behind it, **not** words.
 *
 * Text is produced at the platform edge from string resources (rider-approved decision 7,
 * 2026-10-25), because the engine used to build English titles inline for a Russian UI. Keeping
 * the message structured also lets tests assert on what was *decided* rather than on a rendering.
 *
 * Units are canonical, as everywhere in the domain: [value] is volts for the cell kinds, millivolts
 * for [AlertKind.CELL_DELTA], �C for temperatures, percent for state of charge and duty, km/h for
 * speed. The formatter converts for display. [details] carries the fault names for the two fault
 * kinds (decoder strings, shown as the decoder wrote them).
 */
data class AlertMessage(
    val kind: AlertKind,
    val vehicleName: String,
    val value: Float? = null,
    val details: List<String> = emptyList()
)

// `AlertEvent`, a data class carrying (kind, severity, title, text, vehicleId),
// lived here and was constructed and consumed by nothing in the whole source
// tree: AlertEngine builds its title/text inline and hands them straight to
// Notifier.showAlert. Removed with Task 6 rather than extended along with
// AlertKind — an unused shape that looks like the engine's event model invites
// the next reader to route through it and find that nothing does.
