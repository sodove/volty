package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.stats.BmsReadings
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import ru.sodovaya.volty.util.formatFixed
import ru.sodovaya.volty.util.formatSigned
import kotlin.math.roundToInt

enum class LightLayoutMode { COMPACT, MEDIUM, WIDE }

fun lightLayoutMode(widthDp: Float, heightDp: Float): LightLayoutMode = when {
    widthDp >= 600f || widthDp > heightDp * 1.25f -> LightLayoutMode.WIDE
    widthDp < 360f || heightDp < 640f -> LightLayoutMode.COMPACT
    else -> LightLayoutMode.MEDIUM
}

data class LightReadout(val value: String, val unit: String = "")

data class LightTelemetryReadouts(
    val speed: LightReadout,
    val duty: LightReadout,
    val motorCurrent: LightReadout,
    val batteryCurrent: LightReadout,
    val controllerTemperature: LightReadout,
    val motorTemperature: LightReadout,
    val batterySoc: LightReadout,
    val batteryVoltage: LightReadout,
)

/** Pure controller/BMS/GPS-to-HUD mapping; unknown values stay unknown. */
object LightDashboardMapper {
    fun map(
        motion: ControllerData,
        battery: BmsData,
        units: UnitSystem,
        gpsSpeedKmh: Float? = null,
    ): LightTelemetryReadouts {
        val connected = motion.isConnected
        val speed = speedKmh(motion, gpsSpeedKmh)
        val duty = MotionReadings.dutyPercent(motion)?.takeIf { connected }
        val motorCurrent = motion.motorCurrentA.takeIf { connected && (it != 0f || motion.hasPower) }
        val batteryCurrent = batteryCurrentA(motion, battery)
        val controllerTemperature = MotionReadings.escTempC(motion)?.takeIf { connected }
        val motorTemperature = MotionReadings.motorTempC(motion)?.takeIf { connected }
        return LightTelemetryReadouts(
            speed = speed?.let { LightReadout(UnitFormatter.speed(it, units), UnitFormatter.speedUnit(units)) }
                ?: LightReadout(UNKNOWN_READOUT),
            duty = duty?.let { LightReadout(it.roundToInt().toString(), "%") }
                ?: LightReadout(UNKNOWN_READOUT),
            motorCurrent = motorCurrent?.let { LightReadout(formatFixed(it, 0), "A") }
                ?: LightReadout(UNKNOWN_READOUT),
            batteryCurrent = batteryCurrent?.let { LightReadout(formatSigned(it, 1), "A") }
                ?: LightReadout(UNKNOWN_READOUT),
            controllerTemperature = controllerTemperature?.let { LightReadout(formatFixed(it, 0), "°C") }
                ?: LightReadout(UNKNOWN_READOUT),
            motorTemperature = motorTemperature?.let { LightReadout(formatFixed(it, 0), "°C") }
                ?: LightReadout(UNKNOWN_READOUT),
            batterySoc = if (battery.isConnected && battery.socKnown) {
                LightReadout(battery.soc.roundToInt().toString(), "%")
            } else LightReadout(UNKNOWN_READOUT),
            batteryVoltage = if (battery.isConnected && battery.voltage > 0f) {
                LightReadout(formatFixed(battery.voltage, 1), "V")
            } else LightReadout(UNKNOWN_READOUT),
        )
    }

    fun speedKmh(motion: ControllerData, gpsSpeedKmh: Float?): Float? =
        MotionReadings.speedKmh(motion)?.takeIf { motion.isConnected }
            ?: gpsSpeedKmh?.takeIf { it.isFinite() && it >= 0f }

    fun batteryCurrentA(motion: ControllerData, battery: BmsData): Float? =
        MotionReadings.batteryCurrentA(motion)?.takeIf { motion.isConnected }
            ?: BmsReadings.current(battery)?.takeIf { battery.isConnected }

    fun speedFraction(motion: ControllerData, maxSpeedKmh: Float, gpsSpeedKmh: Float? = null): Float {
        val speed = speedKmh(motion, gpsSpeedKmh) ?: return 0f
        return if (maxSpeedKmh > 0f) (speed / maxSpeedKmh).coerceIn(0f, 1f) else 0f
    }

    fun dutyFraction(motion: ControllerData): Float =
        (MotionReadings.dutyPercent(motion)?.takeIf { motion.isConnected }?.div(100f) ?: 0f)
            .coerceIn(0f, 1f)

    fun batteryFraction(battery: BmsData): Float =
        if (battery.isConnected && battery.socKnown) (battery.soc / 100f).coerceIn(0f, 1f) else 0f
}
