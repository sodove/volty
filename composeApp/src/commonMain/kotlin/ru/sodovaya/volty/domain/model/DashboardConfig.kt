package ru.sodovaya.volty.domain.model

/** Which Ride renderer a vehicle uses. Null on a Vehicle = follow the app default. */
enum class DashboardStyle { CLEAN, CLASSIC }

/**
 * What Clean's hero ring shows inside the speed arc. A wheel rider wants DUTY in
 * the middle, a scooter rider BATTERY, so this is stored per vehicle rather than
 * app-wide.
 *
 * [DashboardStyle.CLEAN] only: Classic renders all eight VESC dials at once, so it
 * has nothing to select and reads this not at all. Kept per-vehicle regardless of
 * the vehicle's current style, because the style is itself a per-vehicle setting a
 * rider can switch back and forth.
 */
enum class SecondaryGauge { DUTY, BATTERY, POWER, CURRENT, MOTOR_TEMP, ESC_TEMP, CONSUMPTION }
