package com.volty.app.domain.stats

import com.volty.app.domain.model.BmsData
import kotlin.time.Duration

/**
 * Snapshot of a moving-window average over recent [BmsData] samples.
 *
 * Pure domain model — lives in the domain layer so repository interfaces and
 * use cases can depend on it without importing data-layer code.
 */
data class MovingAvg(
    val avgPowerW: Float,
    val avgCurrentA: Float,
    val window: Duration
)

object MovingAverage {
    fun over(samples: List<BmsData>, window: Duration): MovingAvg {
        if (samples.isEmpty()) return MovingAvg(0f, 0f, window)
        var p = 0f; var c = 0f
        for (s in samples) { p += s.power; c += s.current }
        return MovingAvg(
            avgPowerW = p / samples.size,
            avgCurrentA = c / samples.size,
            window = window
        )
    }
}
