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

/** Pure: usable Ah = charge drawn / fraction of SoC consumed between two rest points. */
object CapacityLearner {
    const val MIN_DROP_PCT = 20f
    const val MAX_GAP_FRACTION = 0.05f
    private const val OUTLIER_FRACTION = 0.40f
    private const val ESTABLISHED_RIDES = 2

    /** Usable Ah implied by [segment], or null when the segment is not trustworthy. */
    fun accept(segment: LearnSegment): Float? {
        val drop = segment.restSocStart - segment.restSocEnd
        if (drop < MIN_DROP_PCT) return null
        if (segment.dischargedAh <= 0.0) return null
        if (segment.durationMillis <= 0L) return null
        if (segment.gapMillis.toDouble() / segment.durationMillis > MAX_GAP_FRACTION) return null
        return (segment.dischargedAh / (drop / 100.0)).toFloat()
    }

    /** Folds [segment] into [current]; returns [current] unchanged when the segment is rejected. */
    fun merge(current: LearnedCapacity?, segment: LearnSegment): LearnedCapacity? {
        val estimate = accept(segment) ?: return current
        val weight = segment.restSocStart - segment.restSocEnd
        if (current == null) return LearnedCapacity(estimate, weight, 1)
        if (current.rides >= ESTABLISHED_RIDES &&
            abs(estimate - current.usableAh) > OUTLIER_FRACTION * current.usableAh
        ) return current
        val total = current.weight + weight
        val mean = (current.usableAh * current.weight + estimate * weight) / total
        return LearnedCapacity(mean, total, current.rides + 1)
    }
}
