package ru.sodovaya.volty.domain.energy

import ru.sodovaya.volty.domain.navigation.ConsumptionEvidence

/**
 * [km] of range left, built from [whPerKm] - the blended consumption, which the battery page shows
 * as the rider's average consumption.
 */
data class RangeEstimate(val km: Float, val whPerKm: Double)

/** Why [RangeEstimator] withholds a range; the first failing check in this order. */
enum class RangeUnearnedReason { NO_CAPACITY, TOO_SHORT, TOO_FEW_SAMPLES, SOC_UNKNOWN, NO_CONSUMPTION }

/**
 * The figures behind a range, for the vehicle-info screen. Figures are null when their source is
 * absent - never zero. [unearnedReason] is null exactly when a range is published.
 */
data class RangeDetail(
    val tripWhPerKm: Double?,
    val recentWhPerKm: Double?,
    val blendedWhPerKm: Double?,
    val tripKm: Double?,
    val unearnedReason: RangeUnearnedReason?,
)

/** [estimate] and its [detail], from one computation. */
data class RangeAssessment(val estimate: RangeEstimate?, val detail: RangeDetail)

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

    fun estimate(energy: EnergyState?, consumption: ConsumptionEvidence?, recentWhPerKm: Double?): RangeEstimate? =
        assess(energy, consumption, recentWhPerKm).estimate

    /**
     * [estimate] plus the reason when there is none. Checks run capacity (usable Ah), SoC,
     * remaining energy, consumption, then distance and sample count; the first to fail names the
     * reason. Consumption figures are reported whenever consumption exists, earned or not.
     */
    fun assess(energy: EnergyState?, consumption: ConsumptionEvidence?, recentWhPerKm: Double?): RangeAssessment {
        val consumptionUsable = consumption != null && consumption.whPerKm.isFinite() && consumption.whPerKm > 0.0
        val blended = if (consumption != null && consumptionUsable) {
            blendedWhPerKm(consumption.whPerKm, consumption.distanceKm, recentWhPerKm)
        } else null
        val recent = recentWhPerKm?.takeIf { it.isFinite() }

        val remaining = energy?.remainingWh
        val reason: RangeUnearnedReason? = when {
            energy == null -> RangeUnearnedReason.NO_CAPACITY
            remaining == null && !(energy.usableAh != null && energy.usableAh > 0f) -> RangeUnearnedReason.NO_CAPACITY
            remaining == null || !remaining.isFinite() || remaining < 0f -> RangeUnearnedReason.SOC_UNKNOWN
            consumption == null || !consumptionUsable -> RangeUnearnedReason.NO_CONSUMPTION
            !consumption.distanceKm.isFinite() || consumption.distanceKm < MIN_KM -> RangeUnearnedReason.TOO_SHORT
            consumption.measuredSampleCount < MIN_SAMPLES -> RangeUnearnedReason.TOO_FEW_SAMPLES
            else -> null
        }
        val estimate = if (reason == null && remaining != null && blended != null) {
            (remaining / blended).toFloat().takeIf { it.isFinite() }?.let { RangeEstimate(km = it, whPerKm = blended) }
        } else null
        val detail = RangeDetail(
            tripWhPerKm = consumption?.whPerKm?.takeIf { it.isFinite() },
            recentWhPerKm = recent,
            blendedWhPerKm = blended,
            tripKm = consumption?.distanceKm?.takeIf { it.isFinite() },
            unearnedReason = reason ?: if (estimate == null) RangeUnearnedReason.NO_CONSUMPTION else null,
        )
        return RangeAssessment(estimate, detail)
    }
}
