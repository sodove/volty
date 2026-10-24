package ru.sodovaya.volty.domain.energy

import ru.sodovaya.volty.domain.navigation.ConsumptionEvidence

/**
 * [km] of range left, built from [whPerKm] - the blended consumption, which the battery page shows
 * as the rider's average consumption.
 */
data class RangeEstimate(val km: Float, val whPerKm: Double)

/**
 * "~42 km left": remaining energy over a blended consumption, hidden until earned.
 *
 * Remaining energy is [EnergyState.remainingWh] (SoC x usable Ah x NOMINAL pack voltage) - never the
 * live aggregate voltage, which sags under load. Consumption blends the trip average with a recent
 * window so ten gentle kilometres do not hide a climb.
 */
object RangeEstimator {
    const val MIN_KM = 1.0
    const val MIN_SAMPLES = 20
    const val RECENT_KM = 3.0

    /**
     * Trip average alone while the trip is shorter than [RECENT_KM] (the recent window would be the
     * trip itself) or when no usable recent figure exists; otherwise half trip, half recent.
     * A non-finite or non-positive recent figure (a regen stretch) counts as absent: blending it in
     * would promise range from energy the pack did not give back.
     */
    fun blendedWhPerKm(tripWhPerKm: Double, tripKm: Double, recentWhPerKm: Double?): Double {
        if (tripKm < RECENT_KM || recentWhPerKm == null || !recentWhPerKm.isFinite() || recentWhPerKm <= 0.0) {
            return tripWhPerKm
        }
        return 0.5 * tripWhPerKm + 0.5 * recentWhPerKm
    }

    fun estimate(energy: EnergyState?, consumption: ConsumptionEvidence?, recentWhPerKm: Double?): RangeEstimate? {
        val remaining = energy?.remainingWh ?: return null
        if (!remaining.isFinite() || remaining < 0f) return null
        if (consumption == null ||
            !consumption.whPerKm.isFinite() || consumption.whPerKm <= 0.0 ||
            !consumption.distanceKm.isFinite() || consumption.distanceKm < MIN_KM ||
            consumption.measuredSampleCount < MIN_SAMPLES
        ) {
            return null
        }
        val blended = blendedWhPerKm(consumption.whPerKm, consumption.distanceKm, recentWhPerKm)
        val km = (remaining / blended).toFloat()
        if (!km.isFinite()) return null
        return RangeEstimate(km = km, whPerKm = blended)
    }
}
