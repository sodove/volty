package ru.sodovaya.volty.util

import kotlin.math.roundToInt

enum class UnitSystem { METRIC, IMPERIAL }

/**
 * Display-only unit conversion. ControllerData stays canonical (km/h, km, °C) —
 * nothing in the domain or data layer ever sees imperial values.
 */
object UnitFormatter {
    private const val KM_PER_MILE = 1.609344f

    /**
     * The raw numeric speed in display units — km/h passed through unchanged, or converted to
     * mph. Exists alongside [speed] (which formats it to a rounded string) so a caller that needs
     * the NUMBER rather than a label — e.g. a gauge's scale maximum — never has to reconstruct
     * this conversion itself. [speed] used to be the only place this division lived, which is how
     * a Classic hero dial once ended up showing an "mph" readout over a km/h-scaled ring: the
     * ticks never went through this conversion at all.
     */
    fun speedValue(kmh: Float, system: UnitSystem): Float =
        if (system == UnitSystem.IMPERIAL) kmh / KM_PER_MILE else kmh

    fun speed(kmh: Float, system: UnitSystem): String =
        speedValue(kmh, system).roundToInt().toString()

    fun speedUnit(system: UnitSystem): String =
        if (system == UnitSystem.IMPERIAL) "mph" else "km/h"

    fun distance(km: Float, system: UnitSystem, decimals: Int = 1): String =
        formatFixed(if (system == UnitSystem.IMPERIAL) km / KM_PER_MILE else km, decimals)

    fun distanceUnit(system: UnitSystem): String =
        if (system == UnitSystem.IMPERIAL) "mi" else "km"
}
