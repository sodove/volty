package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.domain.stats.VoltageSocEstimator

/**
 * The dashboard's voltage-based charge, recorded with the voltage it came from.
 *
 * History records what the decoder read, and a wheel without a smart BMS reads no charge: the
 * estimate lived only in the live repository, so every Begode ride stored volts and an empty
 * charge, and the vehicle card showed "—" beside the 54 % the dashboard had just shown.
 *
 * Fills only a SOC the decoder left unknown, on a battery whose type never reports one, from a
 * VOLTAGE measured in the same observation. A BMS that reports a charge — even a true 0 % — passes
 * through untouched, as in [VoltageSocEstimator.withEstimatedSoc].
 */
internal fun withHistoryEstimatedSoc(observation: TelemetryObservation, vehicle: Vehicle?, fallbackType: BmsType?,
    learnedCellCount: () -> Int?): TelemetryObservation {
    if (vehicle == null || observation.origin != TelemetryOrigin.BATTERY || observation.role != TelemetryRole.CONTRIBUTOR) return observation
    val soc = observation.readings.firstOrNull { it.metric == TelemetryMetric.SOC } ?: return observation
    if (soc.value != null) return observation
    val voltage = observation.readings.firstOrNull { it.metric == TelemetryMetric.VOLTAGE }?.value ?: return observation
    val pack = vehicle.packs.firstOrNull { it.index == observation.localIndex }
    if ((pack?.bmsType ?: fallbackType ?: return observation).reportsStateOfCharge) return observation
    val estimate = VoltageSocEstimator.estimateFromPackVoltage(voltage.toFloat(), pack?.cellCount ?: learnedCellCount(),
        vehicle.chemistry, vehicle.alertConfig) ?: return observation
    return TelemetryObservation(observation.origin, observation.localIndex,
        observation.readings.map { if (it === soc) TelemetryReading.measured(TelemetryMetric.SOC, estimate.toDouble()) else it },
        observation.role, observation.assemblyIndex)
}
