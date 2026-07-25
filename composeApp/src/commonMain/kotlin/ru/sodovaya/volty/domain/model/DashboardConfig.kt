package ru.sodovaya.volty.domain.model

/** Which Ride renderer a vehicle uses. Null on a Vehicle = follow the app default. */
enum class DashboardStyle { CLEAN, CLASSIC }

/**
 * What the secondary gauge shows — the inner ring in Clean, the emphasised dial
 * in Classic. A wheel rider wants DUTY in the middle, a scooter rider BATTERY,
 * so this is stored per vehicle rather than app-wide.
 */
enum class SecondaryGauge { DUTY, BATTERY, POWER, CURRENT, MOTOR_TEMP, ESC_TEMP, CONSUMPTION }
