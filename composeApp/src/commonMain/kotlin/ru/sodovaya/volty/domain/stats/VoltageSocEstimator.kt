package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Vehicle

/**
 * Voltage-based state-of-charge ESTIMATE for devices that report none.
 *
 * Smart BMS units (JK, JBD, ANT, Daly) coulomb-count and report a real SoC.
 * A Begode wheel reports voltage, current, cells and temperatures — nothing
 * else — so its samples arrive with `soc = 0` and the dashboard showed "0 %"
 * on a nearly full wheel. This maps the average cell voltage linearly onto
 * 0..100 % between the vehicle's configured cell-voltage bounds: the user's
 * alert thresholds when set, the chemistry defaults otherwise.
 *
 * Pure and vehicle-aware, so it lives in the domain layer next to
 * [PackAggregator] — a protocol cannot do this, it has no idea what chemistry
 * or thresholds the vehicle is configured with. It is applied in
 * KableBmsRepository's onSample callback, the one place where both a sample
 * and its vehicle are in scope.
 *
 * A linear voltage-to-percent map is an approximation (real discharge curves
 * sag in the middle, and voltage dips under load), but it follows the user's
 * own mental model: the bounds they configured ARE their 0 % and 100 %.
 */
object VoltageSocEstimator {

    /**
     * Average cell voltage mapped linearly onto 0..100 % between
     * [AlertConfig.cellLowV]..[AlertConfig.cellHighV], falling back to
     * [Chemistry.defaultLowV]..[Chemistry.defaultHighV]. Clamped to 0..100.
     * An empty cell list (or a degenerate bounds window) yields 0, never NaN.
     */
    fun estimateSocPercent(
        cellVoltages: List<Float>,
        chemistry: Chemistry,
        alertConfig: AlertConfig
    ): Float {
        if (cellVoltages.isEmpty()) return 0f
        val highV = alertConfig.cellHighV ?: chemistry.defaultHighV
        val lowV = alertConfig.cellLowV ?: chemistry.defaultLowV
        val span = highV - lowV
        if (span <= 0f) return 0f
        val avg = cellVoltages.average().toFloat()
        return ((avg - lowV) / span * 100f).coerceIn(0f, 100f)
    }

    /**
     * Fill in an estimated SoC ONLY when the device did not report one itself:
     * `soc == 0` AND there are cell voltages to estimate from. A JK or ANT
     * sample already carries a real, coulomb-counted SoC — overwriting it with
     * a voltage estimate would be a regression — and with no cells (or no
     * vehicle to take bounds from) there is nothing honest to compute.
     */
    fun withEstimatedSoc(sample: BmsData, vehicle: Vehicle?): BmsData {
        if (vehicle == null) return sample
        if (sample.soc != 0f || sample.cellVoltages.isEmpty()) return sample
        return sample.copy(
            soc = estimateSocPercent(sample.cellVoltages, vehicle.chemistry, vehicle.alertConfig)
        )
    }
}
