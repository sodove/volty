package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem

/** Pure distance-strip readouts; Compose only renders these already-earned strings. */
object RideDistanceMapper {
    fun odometerValue(motion: ControllerData, units: UnitSystem): String =
        MotionReadings.odometerKm(motion).readoutOr {
            "${UnitFormatter.distance(it, units)} ${UnitFormatter.distanceUnit(units)}"
        }

    fun tripValue(motion: ControllerData, units: UnitSystem): String =
        MotionReadings.tripKm(motion).readoutOr {
            "${UnitFormatter.distance(it, units)} ${UnitFormatter.distanceUnit(units)}"
        }
}
