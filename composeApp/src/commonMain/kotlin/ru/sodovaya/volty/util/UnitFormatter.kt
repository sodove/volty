package ru.sodovaya.volty.util

import kotlin.math.roundToInt

enum class UnitSystem { METRIC, IMPERIAL }

/**
 * Display-only unit conversion. ControllerData stays canonical (km/h, km, °C) —
 * nothing in the domain or data layer ever sees imperial values.
 */
object UnitFormatter {
    private const val KM_PER_MILE = 1.609344f

    fun speed(kmh: Float, system: UnitSystem): String =
        (if (system == UnitSystem.IMPERIAL) kmh / KM_PER_MILE else kmh).roundToInt().toString()

    fun speedUnit(system: UnitSystem): String =
        if (system == UnitSystem.IMPERIAL) "mph" else "km/h"

    fun distance(km: Float, system: UnitSystem, decimals: Int = 1): String =
        formatFixed(if (system == UnitSystem.IMPERIAL) km / KM_PER_MILE else km, decimals)

    fun distanceUnit(system: UnitSystem): String =
        if (system == UnitSystem.IMPERIAL) "mi" else "km"
}
