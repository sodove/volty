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
 * alert thresholds when set, otherwise the chemistry's usable range
 * ([Chemistry.emptyCellV]..[Chemistry.defaultHighV]).
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
     * [Chemistry.emptyCellV]..[Chemistry.defaultHighV]. Clamped to 0..100.
     * An empty cell list (or a degenerate bounds window) yields 0, never NaN.
     *
     * The fallback floor is [Chemistry.emptyCellV] — the usable-range floor —
     * NOT [Chemistry.defaultLowV], the cell-damage threshold behind the
     * low-cell alert. The resulting ordering is deliberate: the SoC estimate
     * reaches 0 % (Li-ion: 3.30 V) before the low-cell alert fires (2.80 V),
     * so the rider hears "you are out of range" before "the cells are being
     * damaged". A user-configured [AlertConfig.cellLowV] still wins when set:
     * the bounds the user configured ARE their 0 % and 100 %.
     */
    fun estimateSocPercent(
        cellVoltages: List<Float>,
        chemistry: Chemistry,
        alertConfig: AlertConfig
    ): Float {
        if (cellVoltages.isEmpty()) return 0f
        return estimateFromAverageCellVoltage(cellVoltages.average().toFloat(), chemistry, alertConfig)
    }

    /** The linear map itself, for callers that already hold an average cell voltage. */
    private fun estimateFromAverageCellVoltage(
        averageCellV: Float,
        chemistry: Chemistry,
        alertConfig: AlertConfig
    ): Float {
        val highV = alertConfig.cellHighV ?: chemistry.defaultHighV
        val lowV = alertConfig.cellLowV ?: chemistry.emptyCellV
        val span = highV - lowV
        if (span <= 0f) return 0f
        return ((averageCellV - lowV) / span * 100f).coerceIn(0f, 100f)
    }

    /**
     * Fill in an estimated SoC ONLY for a pack whose BMS type does not report
     * one itself ([ru.sodovaya.volty.domain.model.BmsType.reportsStateOfCharge]
     * is false). The guard is by device identity, not by value: a JK or ANT
     * sample carries a real, coulomb-counted SoC that must pass through
     * untouched even when it is a genuine 0 % on a flat pack — a `soc == 0`
     * check cannot tell that apart from "no SoC reported" and would overwrite
     * the true zero with a reassuring voltage estimate at the exact moment
     * the rider needs to stop.
     *
     * [packIndex] selects the pack (by its [ru.sodovaya.volty.domain.model.Pack.index],
     * matching VehicleConnection.submit) — the per-pack type, not the
     * vehicle-level shortcut, because a future BMS group can mix types. An
     * unknown index, a null vehicle, or a sample with no cells passes through
     * unchanged: there is nothing honest to compute.
     */
    fun withEstimatedSoc(sample: BmsData, vehicle: Vehicle?, packIndex: Int): BmsData {
        if (vehicle == null) return sample
        val pack = vehicle.packs.firstOrNull { it.index == packIndex } ?: return sample
        if (pack.bmsType.reportsStateOfCharge) return sample
        if (sample.cellVoltages.isNotEmpty()) {
            return sample.copy(
                soc = estimateSocPercent(sample.cellVoltages, vehicle.chemistry, vehicle.alertConfig),
                // An estimate IS the device's fuel gauge — mark the SoC known
                // so the SoC alerts work off it (a no-op for producers that
                // already default to true).
                socKnown = true
            )
        }
        // No cells at all: a Begode without a smart BMS streams only a pack
        // voltage (scaled upstream from the live frame). When the profile
        // knows how many cells divide it, the average cell voltage is
        // voltage / cellCount and the same linear map applies. A zero
        // voltage is the "unscaled, cell count unknown" sentinel from that
        // path — dividing it would proclaim an exact 0 % on a wheel whose
        // charge is simply unknown, so it passes through untouched.
        val cellCount = pack.cellCount ?: return sample
        if (cellCount <= 0 || sample.voltage <= 0f) return sample
        return sample.copy(
            soc = estimateFromAverageCellVoltage(
                sample.voltage / cellCount, vehicle.chemistry, vehicle.alertConfig
            ),
            // Same as the cell path: once estimated, the SoC is known. The
            // pass-throughs above are identities on purpose — the synthetic
            // no-BMS pack's socKnown = false survives them and keeps the SoC
            // alerts silent on a wheel whose charge nobody can know.
            socKnown = true
        )
    }
}
