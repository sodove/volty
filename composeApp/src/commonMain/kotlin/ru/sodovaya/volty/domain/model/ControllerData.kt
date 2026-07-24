package ru.sodovaya.volty.domain.model

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class SpeedSource { REPORTED, DERIVED, NONE }

@OptIn(ExperimentalTime::class)
data class ControllerData(
    val speedKmh: Float = 0f,
    val speedSource: SpeedSource = SpeedSource.NONE,
    val dutyPercent: Float = 0f,
    val motorCurrentA: Float = 0f,
    val batteryCurrentA: Float = 0f,
    val inputVoltageV: Float = 0f,
    val powerW: Float = 0f,
    val eRpm: Float = 0f,
    val escTempC: Float = 0f,
    val motorTempC: Float = 0f,
    val hasMotorTemp: Boolean = false,
    val odometerKm: Float = 0f,
    val tripKm: Float = 0f,
    val consumedAh: Float = 0f,
    val consumedWh: Float = 0f,
    val regenAh: Float = 0f,
    val regenWh: Float = 0f,
    val faults: List<String> = emptyList(),
    /**
     * Controller-computed battery level 0..1 from COMM_GET_VALUES_SETUP, or null
     * when the frame carries none (plain GET_VALUES, non-VESC controllers). Used
     * only to seed a derived battery's SoC — the real fuel gauge, when a smart
     * BMS is present, always wins.
     */
    val batteryLevelFraction: Float? = null,
    val isConnected: Boolean = false,
    val timestamp: Instant = Clock.System.now()
) {
    val speedKnown: Boolean get() = speedSource != SpeedSource.NONE
}
