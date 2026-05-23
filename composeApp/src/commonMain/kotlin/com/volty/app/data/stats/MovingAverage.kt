package com.volty.app.data.stats

import com.volty.app.domain.model.BmsData
import kotlin.time.Duration

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
