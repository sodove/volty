package ru.sodovaya.volty.domain.model

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class BmsData(
    val voltage: Float = 0f,
    val current: Float = 0f,
    val power: Float = 0f,
    val soc: Float = 0f,
    /**
     * False when [soc] is a placeholder for a value nobody knows: a Begode
     * without a smart BMS and with no configured cell count has no fuel gauge
     * and nothing to estimate from, so its samples carry `soc = 0` that means
     * "unknown", not "empty". A genuinely flat pack reads `soc = 0` WITH
     * `socKnown = true` — the value alone cannot express the difference, this
     * flag can. SoC-based alerts are skipped while it is false. Defaults to
     * true: every device that reports or estimates a SoC produces a known one.
     */
    val socKnown: Boolean = true,
    val charge: Float = 0f,
    val capacity: Float = 0f,
    /**
     * Charge/discharge cycle count as an integer, reported by count-based BMS
     * (JK/JBD/Daly). ANT has no such counter — see [cycleCapacityAh].
     */
    val numCycles: Int = 0,
    /**
     * ANT-only: cumulative amp-hours cycled through the pack (its "total Ah
     * cycle"), in Ah. 0 on BMS that report an integer [numCycles] instead.
     */
    val cycleCapacityAh: Float = 0f,
    val cellVoltages: List<Float> = emptyList(),
    val temperatures: List<Float> = emptyList(),
    val chargeEnabled: Boolean = false,
    val dischargeEnabled: Boolean = false,
    val bmsFaults: List<String> = emptyList(),
    val isConnected: Boolean = false,
    val timestamp: Instant = Clock.System.now()
)
