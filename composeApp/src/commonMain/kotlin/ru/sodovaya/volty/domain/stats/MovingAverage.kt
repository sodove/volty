package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsData
import kotlin.time.Duration

/**
 * Snapshot of a moving-window average over recent [BmsData] samples.
 *
 * Pure domain model — lives in the domain layer so repository interfaces and
 * use cases can depend on it without importing data-layer code.
 */
data class MovingAvg(
    /** Null when the window has no measured power samples. */
    val avgPowerW: Float?,
    /** Null when the window has no measured current samples. */
    val avgCurrentA: Float?,
    val window: Duration
)

object MovingAverage {
    fun over(samples: List<BmsData>, window: Duration): MovingAvg {
        if (samples.isEmpty()) return MovingAvg(null, null, window)
        var p = 0f; var c = 0f
        var powerCount = 0; var currentCount = 0
        for (s in samples) {
            if (s.hasPower) {
                p += s.power
                powerCount++
            }
            if (s.hasCurrent) {
                c += s.current
                currentCount++
            }
        }
        return MovingAvg(
            avgPowerW = if (powerCount == 0) null else p / powerCount,
            avgCurrentA = if (currentCount == 0) null else c / currentCount,
            window = window
        )
    }
}
