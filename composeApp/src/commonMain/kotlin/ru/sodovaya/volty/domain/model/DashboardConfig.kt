package ru.sodovaya.volty.domain.model

/** Which Ride renderer a vehicle uses. Null on a Vehicle = follow the app default. */
enum class DashboardStyle {
    CLEAN,
    CLASSIC,
    LIGHT;

    companion object {
        /** Reads the old persisted name without exposing the old product name in the UI. */
        fun fromPersistedName(value: String?): DashboardStyle? = when (value) {
            "VESCAPE" -> LIGHT
            else -> value?.let { runCatching { valueOf(it) }.getOrNull() }
        }
    }
}

/**
 * What Clean's hero ring shows inside the speed arc. A wheel rider wants DUTY in
 * the middle, a scooter rider BATTERY, so this is stored per vehicle rather than
 * app-wide.
 *
 * [DashboardStyle.CLEAN] only: Classic renders all eight VESC dials at once, and
 * Light has its own fixed dual gauge, so neither reads this setting. Kept per-vehicle regardless of
 * the vehicle's current style, because the style is itself a per-vehicle setting a
 * rider can switch back and forth.
 */
enum class SecondaryGauge { DUTY, BATTERY, POWER, CURRENT, MOTOR_TEMP, ESC_TEMP, CONSUMPTION }
