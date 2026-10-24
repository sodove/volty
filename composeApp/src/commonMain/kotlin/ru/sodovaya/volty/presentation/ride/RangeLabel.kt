package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.energy.RangeEstimate
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem

/**
 * The rider-facing range readout: "~42 км" / "~26 mi", or null when no range has been earned.
 *
 * Null in, null out: the dashboards render nothing for it (no "--" placeholder), because a
 * placeholder would claim a range slot the vehicle has not earned. The distance goes through
 * [UnitFormatter] like [RideDistanceMapper], at zero decimals - an estimate is not a measurement.
 * [kmUnit] / [miUnit] are the localized unit words, resolved by the caller ([UnitFormatter]'s own
 * unit strings are language-neutral "km"); the defaults are the Russian UI's.
 */
fun rangeLabel(
    range: RangeEstimate?,
    units: UnitSystem,
    kmUnit: String = "км",
    miUnit: String = "mi",
): String? {
    if (range == null) return null
    val unit = if (units == UnitSystem.IMPERIAL) miUnit else kmUnit
    return "~${UnitFormatter.distance(range.km, units, decimals = 0)} $unit"
}
