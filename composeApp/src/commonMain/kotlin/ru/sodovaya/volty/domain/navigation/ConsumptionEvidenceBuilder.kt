package ru.sodovaya.volty.domain.navigation

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.domain.stats.RideEnergy
import kotlin.time.ExperimentalTime

/**
 * How much energy a kilometre of this ride has cost, as evidence: controller counters first,
 * integrated power second. Extracted from `BmsNavigationEnergySource` so navigation's arrival
 * estimate and the range estimate read **one** definition of consumption instead of two copies.
 */
@OptIn(ExperimentalTime::class)
object ConsumptionEvidenceBuilder {

    /** The whole-session figure; [samples] must already be bounded to the current session. */
    fun build(motion: ControllerData, samples: List<ControllerData>): ConsumptionEvidence? {
        val measuredCounterConsumption = MotionReadings.sessionWhPerKm(motion)
        if (motion.hasEnergyCounters && measuredCounterConsumption != null) {
            val counterSamples = samples.filter { it.hasEnergyCounters }
            return ConsumptionEvidence(
                whPerKm = measuredCounterConsumption.toDouble(),
                distanceKm = motion.tripKm.toDouble(),
                durationMillis = durationMillis(counterSamples),
                measuredSampleCount = counterSamples.size,
                provenance = ConsumptionProvenance.CONTROLLER_COUNTERS,
            )
        }

        val measuredPowerSamples = samples.filter { MotionReadings.powerW(it) != null }
        val ride = RideEnergy.windowedRide(measuredPowerSamples, since = null) ?: return null
        val whPerKm = if (ride.km > 0f) ride.wh.toDouble() / ride.km.toDouble() else return null
        if (!whPerKm.isFinite()) return null
        return ConsumptionEvidence(
            whPerKm = whPerKm,
            distanceKm = ride.km.toDouble(),
            durationMillis = durationMillis(measuredPowerSamples),
            measuredSampleCount = measuredPowerSamples.size,
            provenance = ConsumptionProvenance.POWER_INTEGRAL,
        )
    }

    /**
     * Consumption over the last [recentKm] of distance travelled, from the same samples and the
     * same two sources as [build] (counter deltas when the samples carry counters, else the power
     * integral over the measured-power samples). The window is anchored on the newest sample's
     * `tripKm`: it starts at the first sample at or after `newest - recentKm`, so it covers at most
     * [recentKm] and at least one sample interval. Null when that window holds no positive
     * distance, fewer than two samples, or a non-finite quotient - "no recent figure" is never a zero.
     * [samples] must be in arrival order and bounded to the current session.
     */
    fun recentWhPerKm(samples: List<ControllerData>, recentKm: Double): Double? {
        val counterSamples = samples.filter { it.hasEnergyCounters }
        if (counterSamples.size >= 2) {
            val tail = tailWithin(counterSamples, recentKm)
            val km = (tail.last().tripKm - tail.first().tripKm).toDouble()
            if (tail.size >= 2 && km > 0.0) {
                return (tail.last().consumedWh - tail.first().consumedWh).toDouble() / km
            }
            return null
        }
        val measured = samples.filter { MotionReadings.powerW(it) != null }
        if (measured.size < 2) return null
        val ride = RideEnergy.windowedRide(tailWithin(measured, recentKm), since = null) ?: return null
        if (ride.km <= 0f) return null
        return (ride.wh.toDouble() / ride.km.toDouble()).takeIf { it.isFinite() }
    }

    private fun tailWithin(samples: List<ControllerData>, recentKm: Double): List<ControllerData> {
        val from = samples.last().tripKm - recentKm
        return samples.dropWhile { it.tripKm < from }
    }

    private fun durationMillis(samples: List<ControllerData>): Long {
        val first = samples.minOfOrNull { it.timestamp } ?: return 0L
        val last = samples.maxOfOrNull { it.timestamp } ?: return 0L
        return (last - first).inWholeMilliseconds.coerceAtLeast(0L)
    }
}
