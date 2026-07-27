package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Result of folding a vehicle's [ControllerState]s into one aggregate
 * [ControllerData], mirroring [ru.sodovaya.volty.domain.stats.PackAggregator]'s
 * [ru.sodovaya.volty.domain.model.VehicleData] on the battery side.
 */
data class MotionResult(val aggregate: ControllerData, val partial: Boolean)

/**
 * Derives a vehicle-level [ControllerData] from its controllers. Pure — no
 * BLE, no state, only a fallback clock read for the timestamp. All
 * multi-controller maths lives here so it can be tested without a radio.
 */
@OptIn(ExperimentalTime::class)
object MotionAggregator {

    fun build(controllers: List<ControllerState>): MotionResult = MotionResult(
        aggregate = aggregate(controllers),
        partial = controllers.isNotEmpty() && controllers.any { !it.isOnline }
    )

    fun aggregate(controllers: List<ControllerState>): ControllerData {
        val online = controllers.filter { it.isOnline }
        if (online.isEmpty()) return ControllerData(isConnected = false)

        val d = online.map { it.data }
        val labelled = online.size > 1

        val speedSource = when {
            d.any { it.speedSource == SpeedSource.REPORTED } -> SpeedSource.REPORTED
            d.any { it.speedSource == SpeedSource.DERIVED } -> SpeedSource.DERIVED
            else -> SpeedSource.NONE
        }

        return ControllerData(
            speedKmh = d.maxOf { it.speedKmh },
            speedSource = speedSource,
            dutyPercent = d.maxOf { it.dutyPercent },
            // `any`, exactly like hasMotorTemp below: one controller that
            // MEASURES duty is enough for the vehicle, because the maxOf fold
            // above already carries that controller's real reading. Folding
            // with `all` (or dropping the fold and inheriting the default)
            // would cost a mixed VESC + Begode vehicle its duty availability
            // outright the moment the wheel's truePWM latch is still open —
            // a worse bug than the unmeasured-duty one the flag exists for.
            hasDuty = d.any { it.hasDuty },
            motorCurrentA = d.sumOf { it.motorCurrentA.toDouble() }.toFloat(),
            batteryCurrentA = d.sumOf { it.batteryCurrentA.toDouble() }.toFloat(),
            inputVoltageV = d.map { it.inputVoltageV }.average().toFloat(),
            powerW = d.sumOf { it.powerW.toDouble() }.toFloat(),
            eRpm = d.maxOf { it.eRpm },
            escTempC = d.maxOf { it.escTempC },
            motorTempC = d.maxOf { it.motorTempC },
            hasMotorTemp = d.any { it.hasMotorTemp },
            odometerKm = d.maxOf { it.odometerKm },
            tripKm = d.maxOf { it.tripKm },
            consumedAh = d.sumOf { it.consumedAh.toDouble() }.toFloat(),
            consumedWh = d.sumOf { it.consumedWh.toDouble() }.toFloat(),
            regenAh = d.sumOf { it.regenAh.toDouble() }.toFloat(),
            regenWh = d.sumOf { it.regenWh.toDouble() }.toFloat(),
            faults = online.flatMap { s ->
                s.data.faults.map { if (labelled) "${s.controller.label}: $it" else it }
            },
            isConnected = true,
            timestamp = d.maxOfOrNull { it.timestamp } ?: Clock.System.now()
        )
    }
}
