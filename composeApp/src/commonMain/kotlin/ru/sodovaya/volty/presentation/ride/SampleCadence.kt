package ru.sodovaya.volty.presentation.ride

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class SampleCadencePhase { NO_SAMPLES, WARMUP, STEADY }

internal data class SampleCadence(
    val rateHz: Float?,
    val phase: SampleCadencePhase,
)

/**
 * Derives a displayable sample rate from arrival timestamps. A missing rate is
 * intentional: one arrival has not earned a frequency, and must not become a
 * misleading zero. The phase is based on elapsed observed time, not on the
 * configured poll interval.
 */
@OptIn(ExperimentalTime::class)
internal fun sampleCadence(
    timestamps: List<Instant>,
    warmup: Duration = WARMUP_DURATION,
    warmupStartedAt: Instant? = null,
): SampleCadence {
    if (timestamps.isEmpty()) {
        return SampleCadence(rateHz = null, phase = SampleCadencePhase.NO_SAMPLES)
    }

    // The rate uses only the bounded history, but the phase must use the
    // connection's full observation window. At a fast cadence the last eight
    // arrivals span less than six seconds forever, which would leave a live
    // link labelled WARMUP indefinitely.
    val elapsed = timestamps.last() - (warmupStartedAt ?: timestamps.first())
    val intervals = timestamps.zipWithNext()
        .map { (previous, next) -> next - previous }
        .filter { it > Duration.ZERO }
    val totalSeconds = intervals.sumOf { it.inWholeNanoseconds.toDouble() / NANOS_PER_SECOND }
    val rateHz = if (totalSeconds > 0.0) intervals.size / totalSeconds else null
    val phase = if (elapsed >= warmup) SampleCadencePhase.STEADY else SampleCadencePhase.WARMUP
    return SampleCadence(rateHz = rateHz?.toFloat(), phase = phase)
}

internal const val SAMPLE_CADENCE_HISTORY = 8
internal val WARMUP_DURATION: Duration = 6.seconds

private const val NANOS_PER_SECOND = 1_000_000_000.0
