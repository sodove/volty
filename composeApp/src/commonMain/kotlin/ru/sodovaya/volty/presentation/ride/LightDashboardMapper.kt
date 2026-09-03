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
    ): LightTelemetryReadouts = map(values(motion, battery, gpsSpeedKmh), units)

    internal fun values(
        motion: ControllerData,
        battery: BmsData,
        gpsSpeedKmh: Float? = null,
    ): LightTelemetryValues {
        val connected = motion.isConnected
        return LightTelemetryValues(
            speedKmh = speedKmh(motion, gpsSpeedKmh),
            dutyPercent = MotionReadings.dutyPercent(motion)?.takeIf { connected },
            motorCurrentA = motion.motorCurrentA.takeIf { connected && (it != 0f || motion.hasPower) },
            batteryCurrentA = batteryCurrentA(motion, battery),
            controllerTemperatureC = MotionReadings.escTempC(motion)?.takeIf { connected },
            motorTemperatureC = MotionReadings.motorTempC(motion)?.takeIf { connected },
            batterySocPercent = if (battery.isConnected && battery.socKnown) battery.soc else null,
            batteryVoltageV = if (battery.isConnected && battery.voltage > 0f) battery.voltage else null,
        )
    }

    internal fun map(values: LightTelemetryValues, units: UnitSystem): LightTelemetryReadouts =
        LightTelemetryReadouts(
            speed = values.speedKmh?.let { LightReadout(UnitFormatter.speed(it, units), UnitFormatter.speedUnit(units)) }
                ?: LightReadout(UNKNOWN_READOUT),
            duty = values.dutyPercent?.let { LightReadout(it.roundToInt().toString(), "%") }
                ?: LightReadout(UNKNOWN_READOUT),
            motorCurrent = values.motorCurrentA?.let { LightReadout(formatFixed(it, 0), "A") }
                ?: LightReadout(UNKNOWN_READOUT),
            batteryCurrent = values.batteryCurrentA?.let { LightReadout(formatSigned(it, 1), "A") }
                ?: LightReadout(UNKNOWN_READOUT),
            controllerTemperature = values.controllerTemperatureC?.let { LightReadout(formatFixed(it, 0), "°C") }
                ?: LightReadout(UNKNOWN_READOUT),
            motorTemperature = values.motorTemperatureC?.let { LightReadout(formatFixed(it, 0), "°C") }
                ?: LightReadout(UNKNOWN_READOUT),
            batterySoc = values.batterySocPercent?.let { LightReadout(it.roundToInt().toString(), "%") }
                ?: LightReadout(UNKNOWN_READOUT),
            batteryVoltage = values.batteryVoltageV?.let { LightReadout(formatFixed(it, 1), "V") }
                ?: LightReadout(UNKNOWN_READOUT),
        )

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
