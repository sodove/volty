package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsCurrentSource
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import kotlin.time.ExperimentalTime
import kotlin.time.Clock

data class BatteryInputCurrent(val dischargePositiveAmps: Float, val sampleEpochMillis: Long)

/** Real BMS first, then controller, then its mirrored battery sample. Zero never means missing. */
@OptIn(ExperimentalTime::class)
fun batteryInputCurrent(
    motion: ControllerData,
    battery: BmsData,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    motionAvailable: Boolean = true,
    batteryAvailable: Boolean = true,
): BatteryInputCurrent? {
    val oldest = if (nowEpochMillis < Long.MIN_VALUE + 2_000L) Long.MIN_VALUE else nowEpochMillis - 2_000L
    fun fresh(stamp: Long) = stamp <= nowEpochMillis && stamp >= oldest
    fun normalize(value: Float) = if (value == 0f) 0f else -value
    val batteryStamp = battery.timestamp.toEpochMilliseconds()
    val batteryCurrent = BmsReadings.current(battery)?.takeIf {
        batteryAvailable && battery.isConnected && it.isFinite() && fresh(batteryStamp)
    }
    if (battery.currentSource == BmsCurrentSource.BMS && batteryCurrent != null) {
        return BatteryInputCurrent(normalize(batteryCurrent), batteryStamp)
    }
    val motionStamp = motion.timestamp.toEpochMilliseconds()
    val controllerCurrent = MotionReadings.batteryCurrentA(motion)?.takeIf {
        motionAvailable && motion.isConnected && it.isFinite() && fresh(motionStamp)
    }
    if (controllerCurrent != null) return BatteryInputCurrent(controllerCurrent, motionStamp)
    return batteryCurrent?.takeIf { battery.currentSource == BmsCurrentSource.CONTROLLER }
        ?.let { BatteryInputCurrent(normalize(it), batteryStamp) }
}
