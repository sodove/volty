package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.stats.DutyBands
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.domain.stats.RideMetrics
import ru.sodovaya.volty.util.UnitSystem
import ru.sodovaya.volty.util.formatFixed
import kotlin.math.abs
import kotlin.math.roundToInt

data class SecondaryReadout(
    val label: String,
    val value: String,
    val unit: String,
    /** 0..1 for the ring; 0 when the value is unknown. */
    val fraction: Float,
    val severity: DutyLevel
)

/**
 * Turns the rider's chosen secondary metric into everything the gauge needs.
 * Pure, so the choice logic is tested without a screen. Severity is what colors
 * the ring: duty uses the shared [DutyBands], temperatures their own ceilings,
 * everything else is neutral — semantic color is spent only on safety.
 */
object SecondaryGaugeMapper {

    private const val ESC_WARN_C = 70f
    private const val ESC_CRITICAL_C = 85f
    private const val MOTOR_WARN_C = 85f
    private const val MOTOR_CRITICAL_C = 100f
    private const val MAX_CURRENT_A = 150f
    private const val MAX_POWER_W = 8000f
    private const val MAX_WH_PER_KM = 50f

    fun map(
        gauge: SecondaryGauge,
        motion: ControllerData,
        battery: BmsData,
        units: UnitSystem
    ): SecondaryReadout = when (gauge) {
        SecondaryGauge.DUTY -> SecondaryReadout(
            "DUTY · ШИМ", motion.dutyPercent.roundToInt().toString(), "%",
            frac(motion.dutyPercent, 100f), DutyBands.level(motion.dutyPercent)
        )
        SecondaryGauge.BATTERY -> SecondaryReadout(
            "BATTERY",
            if (battery.socKnown) battery.soc.roundToInt().toString() else "—", "%",
            if (battery.socKnown) frac(battery.soc, 100f) else 0f,
            DutyLevel.NORMAL
        )
        SecondaryGauge.POWER -> SecondaryReadout(
            "POWER", formatFixed(motion.powerW / 1000f, 1), "kW",
            frac(abs(motion.powerW), MAX_POWER_W), DutyLevel.NORMAL
        )
        SecondaryGauge.CURRENT -> SecondaryReadout(
            "CURRENT", motion.batteryCurrentA.roundToInt().toString(), "A",
            frac(abs(motion.batteryCurrentA), MAX_CURRENT_A), DutyLevel.NORMAL
        )
        SecondaryGauge.MOTOR_TEMP -> SecondaryReadout(
            "MOTOR",
            if (motion.hasMotorTemp) motion.motorTempC.roundToInt().toString() else "—", "°C",
            if (motion.hasMotorTemp) frac(motion.motorTempC, MOTOR_CRITICAL_C + 20f) else 0f,
            tempLevel(motion.motorTempC, MOTOR_WARN_C, MOTOR_CRITICAL_C, motion.hasMotorTemp)
        )
        SecondaryGauge.ESC_TEMP -> SecondaryReadout(
            "ESC", motion.escTempC.roundToInt().toString(), "°C",
            frac(motion.escTempC, ESC_CRITICAL_C + 20f),
            tempLevel(motion.escTempC, ESC_WARN_C, ESC_CRITICAL_C, true)
        )
        SecondaryGauge.CONSUMPTION -> {
            val wh = RideMetrics.instantWhPerKm(motion.powerW, motion.speedKmh)
                ?: RideMetrics.sessionWhPerKm(motion.consumedWh, motion.tripKm)
            SecondaryReadout(
                "CONSUMPTION", wh?.let { formatFixed(it, 1) } ?: "—", "Wh/km",
                wh?.let { frac(it, MAX_WH_PER_KM) } ?: 0f, DutyLevel.NORMAL
            )
        }
    }

    private fun frac(value: Float, max: Float): Float =
        if (max <= 0f) 0f else (value / max).coerceIn(0f, 1f)

    private fun tempLevel(c: Float, warn: Float, critical: Float, known: Boolean): DutyLevel = when {
        !known -> DutyLevel.NORMAL
        c >= critical -> DutyLevel.CRITICAL
        c >= warn -> DutyLevel.WARN
        else -> DutyLevel.NORMAL
    }
}
