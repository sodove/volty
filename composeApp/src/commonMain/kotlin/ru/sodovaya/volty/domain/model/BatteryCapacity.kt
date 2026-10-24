package ru.sodovaya.volty.domain.model

enum class CapacityUnit { WH, AH }

/**
 * The pack capacity the rider typed from the nameplate, in the unit they read it in.
 * **Nameplate**, not usable: the usable share is applied downstream
 * (`OcvCurve.usableFraction`). Null on a [Vehicle] means nothing was typed.
 */
data class BatteryCapacity(val value: Float, val unit: CapacityUnit)

/**
 * Nameplate amp-hours. AH is returned as typed; WH divides by the nominal pack voltage
 * (series cells x [Chemistry.nominalCellV]) and so needs a series count: null when
 * [seriesCells] <= 0, and null for a non-finite or non-positive typed value.
 */
fun BatteryCapacity.nameplateAh(seriesCells: Int, chemistry: Chemistry): Float? {
    if (!value.isFinite() || value <= 0f) return null
    return when (unit) {
        CapacityUnit.AH -> value
        CapacityUnit.WH -> if (seriesCells <= 0) null else value / (seriesCells * chemistry.nominalCellV)
    }
}
