package ru.sodovaya.volty.domain.energy

import kotlin.math.abs

/** Usable pack capacity learned from rides. [weight] is the summed SoC drop (percent) behind it. */
data class LearnedCapacity(val usableAh: Float, val weight: Float, val rides: Int)

/**
 * One rest-to-rest stretch of riding. [dischargedAh] is discharge-positive;
 * [gapMillis] is the time within [durationMillis] during which charge was not integrated.
 */
data class LearnSegment(
    val restSocStart: Float,
    val restSocEnd: Float,
    val dischargedAh: Double,
    val durationMillis: Long,
    val gapMillis: Long,
)

enum class CapacityLearnDecision {
    ACCEPTED,
    RECOVERED,
    NEED_MORE_DROP,
    SOC_NOT_FALLING,
    NO_NET_DISCHARGE,
    TELEMETRY_GAPS,
    INVALID,
    DIVERGENT,
    INTERRUPTED,
}

/** Pending estimates must agree across their full spread, even if their weighted mean drifts. */
data class CapacityCandidate(val estimate: LearnedCapacity, val minAh: Float, val maxAh: Float)

data class CapacityLearnUpdate(
    val learned: LearnedCapacity?,
    val candidate: CapacityCandidate?,
    val decision: CapacityLearnDecision,
)

/** Pure: usable Ah = charge drawn / fraction of SoC consumed between two rest points. */
object CapacityLearner {
    const val MIN_DROP_PCT = 20f
    const val MAX_GAP_FRACTION = 0.05
    const val RECOVERY_MEASUREMENTS = 3
    private const val OUTLIER_FRACTION = 0.40f
    private const val ESTABLISHED_RIDES = 2
    private const val MAX_CANDIDATE_SPREAD_RATIO = 1.15f

    fun evaluate(
        current: LearnedCapacity?,
        candidate: CapacityCandidate?,
        segment: LearnSegment,
    ): CapacityLearnUpdate {
        val drop = segment.restSocStart - segment.restSocEnd
        val usableAh = (segment.dischargedAh / (drop / 100.0)).toFloat()
        val rejection = rejection(segment, drop, usableAh)
        if (rejection != null) return CapacityLearnUpdate(current, candidate, rejection)

        val measurement = LearnedCapacity(usableAh, drop, 1)
        val divergent = current != null && current.rides >= ESTABLISHED_RIDES &&
            abs(usableAh - current.usableAh) > OUTLIER_FRACTION * current.usableAh
        if (!divergent) {
            return CapacityLearnUpdate(current?.let { weightedMean(it, measurement) } ?: measurement,
                null, CapacityLearnDecision.ACCEPTED)
        }

        val pending = accumulate(candidate, measurement)
        return if (pending.estimate.rides >= RECOVERY_MEASUREMENTS) {
            CapacityLearnUpdate(pending.estimate, null, CapacityLearnDecision.RECOVERED)
        } else {
            CapacityLearnUpdate(current, pending, CapacityLearnDecision.DIVERGENT)
        }
    }

    private fun rejection(segment: LearnSegment, drop: Float, usableAh: Float): CapacityLearnDecision? = when {
        segment.restSocStart !in 0f..100f || segment.restSocEnd !in 0f..100f ||
            !segment.dischargedAh.isFinite() || segment.durationMillis <= 0 ||
            segment.gapMillis !in 0L..segment.durationMillis -> CapacityLearnDecision.INVALID
        drop <= 0f -> CapacityLearnDecision.SOC_NOT_FALLING
        segment.gapMillis.toDouble() / segment.durationMillis > MAX_GAP_FRACTION ->
            CapacityLearnDecision.TELEMETRY_GAPS
        drop < MIN_DROP_PCT -> CapacityLearnDecision.NEED_MORE_DROP
        segment.dischargedAh <= 0.0 -> CapacityLearnDecision.NO_NET_DISCHARGE
        !usableAh.isFinite() || usableAh <= 0f -> CapacityLearnDecision.INVALID
        else -> null
    }

    private fun accumulate(candidate: CapacityCandidate?, measurement: LearnedCapacity): CapacityCandidate {
        val ah = measurement.usableAh
        val min = minOf(candidate?.minAh ?: ah, ah)
        val max = maxOf(candidate?.maxAh ?: ah, ah)
        return if (candidate != null && max <= min * MAX_CANDIDATE_SPREAD_RATIO) {
            CapacityCandidate(weightedMean(candidate.estimate, measurement), min, max)
        } else {
            CapacityCandidate(measurement, ah, ah)
        }
    }

    private fun weightedMean(first: LearnedCapacity, second: LearnedCapacity): LearnedCapacity {
        val total = first.weight.toDouble() + second.weight
        val mean = (first.usableAh.toDouble() * first.weight + second.usableAh.toDouble() * second.weight) / total
        return LearnedCapacity(mean.toFloat(), total.toFloat(), first.rides + second.rides)
    }

    /** Usable Ah implied by [segment], or null when the segment is not trustworthy. */
    fun accept(segment: LearnSegment): Float? = evaluate(null, null, segment).learned?.usableAh

    /** A longer segment from the same start could still become valid after more discharge. */
    fun isStillGrowing(segment: LearnSegment): Boolean =
        evaluate(null, null, segment).decision == CapacityLearnDecision.NEED_MORE_DROP

    fun merge(current: LearnedCapacity?, segment: LearnSegment): LearnedCapacity? =
        evaluate(current, null, segment).learned
}
