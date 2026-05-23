package com.volty.app.domain.model

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class BmsData(
    val voltage: Float = 0f,
    val current: Float = 0f,
    val power: Float = 0f,
    val soc: Float = 0f,
    val charge: Float = 0f,
    val capacity: Float = 0f,
    val numCycles: Int = 0,
    val cellVoltages: List<Float> = emptyList(),
    val temperatures: List<Float> = emptyList(),
    val chargeEnabled: Boolean = false,
    val dischargeEnabled: Boolean = false,
    val isConnected: Boolean = false,
    val timestamp: Instant = Clock.System.now()
)
