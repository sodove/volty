package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.domain.stats.VoltageSocEstimator

/**
 * The vehicle's energy-model charge as history needs it: [percent] is the [ru.sodovaya.volty.domain.energy.ChargeTracker]'s
 * displayed SoC when the model owns the vehicle's charge (no online pack reports one) AND it has seeded; null otherwise
 * (not yet seeded, re-seeding after a long gap, mixed or reporting packs, no tracker). Read non-blockingly at
 * observation time, like [HistoryPwmView].
 */
internal class HistorySocView(val vehicleId: String, val percent: Float?)

/**
 * The view the repository publishes after each snapshot. Pure so its gate is testable: only a modelled snapshot
 * carries the tracker's charge (a reported or mixed vehicle's charge is the BMS's, never the model's), and only a
 * finite value in 0..100.
 */
internal fun historySocView(vehicleId: String, energy: ru.sodovaya.volty.domain.energy.EnergyState?): HistorySocView =
    HistorySocView(vehicleId, energy?.takeIf { it.modelled }?.socPercent?.takeIf { it.isFinite() && it in 0f..100f })

/**
 * The dashboard's charge, recorded with the number it came from.
 *
 * **Tracker first (0.7.55 ride fixes).** Since 0.7.50 the dashboard's charge is the coulomb counter of
 * `ChargeTracker`, while history kept recording the instantaneous-voltage estimate below; a ride's SoC chart then
 * showed rest at 85 % and load-sag dips to 51 % on a pack that ended at 79 %, and the "battery used" tile read -39 %.
 * An earlier plan listed "recording the ChargeTracker SoC" as out of scope; that was wrong because the recorded series
 * is what every ride tile and chart is computed from. When [tracked] has a charge for this vehicle the reading becomes
 * that charge. Before the tracker seeds (or while it re-seeds) [tracked] has none and the voltage estimate below is
 * recorded as before. UNMARKED, as the estimate was: a sample has no per-reading flag and a marker needs a schema
 * migration, which this change does not take.
 *
 * Voltage estimate:
 * History records what the decoder read, and a wheel without a smart BMS reads no charge: the
 * estimate lived only in the live repository, so every Begode ride stored volts and an empty
 * charge, and the vehicle card showed "—" beside the 54 % the dashboard had just shown.
 *
 * Fills only a SOC the decoder left unknown, on a battery whose type never reports one, from a
 * VOLTAGE measured in the same observation. A BMS that reports a charge — even a true 0 % — passes
 * through untouched, as in [VoltageSocEstimator.withEstimatedSoc].
 */
internal fun withHistoryEstimatedSoc(observation: TelemetryObservation, vehicle: Vehicle?, fallbackType: BmsType?,
    typedDerivedCellCount: Int? = null, tracked: () -> HistorySocView? = { null }, learnedCellCount: () -> Int?): TelemetryObservation {
    if (vehicle == null || observation.origin != TelemetryOrigin.BATTERY || observation.role != TelemetryRole.CONTRIBUTOR) return observation
    val soc = observation.readings.firstOrNull { it.metric == TelemetryMetric.SOC } ?: return observation
    if (soc.value != null) return observation
    val pack = vehicle.packs.firstOrNull { it.index == observation.localIndex }
    if ((pack?.bmsType ?: fallbackType ?: return observation).reportsStateOfCharge) return observation
    val earned = tracked()?.takeIf { it.vehicleId == vehicle.id }?.percent?.takeIf { it.isFinite() }
    val estimate = earned ?: run {
        val voltage = observation.readings.firstOrNull { it.metric == TelemetryMetric.VOLTAGE }?.value ?: return observation
        VoltageSocEstimator.estimateFromPackVoltage(voltage.toFloat(), pack?.cellCount ?: typedDerivedCellCount ?: learnedCellCount(),
            vehicle.chemistry, vehicle.alertConfig) ?: return observation
    }
    return TelemetryObservation(observation.origin, observation.localIndex,
        observation.readings.map { if (it === soc) TelemetryReading.measured(TelemetryMetric.SOC, estimate.toDouble()) else it },
        observation.role, observation.assemblyIndex)
}
