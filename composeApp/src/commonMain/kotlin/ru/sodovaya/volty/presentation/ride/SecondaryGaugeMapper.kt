package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.stats.DutyBands
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.domain.stats.TempBands
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
 * The seven secondary-gauge labels, resolved by the caller. Mirrors [ClassicDialLabels]:
 * defaults are the plain-English labels this mapper shipped with before localization (the
 * literal single-language strings [SecondaryGaugeMapper] used to hardcode, including a plain
 * "DUTY" rather than the old bilingual "DUTY · ШИМ") — they keep [SecondaryGaugeMapper] callable,
 * and testable, without dragging Compose or a string-resource resolver into this pure object.
 * [RideDashboardScreen] is the one real caller and passes `stringResource(...).uppercase()` for
 * each field instead of relying on these defaults, reusing the exact same string keys Classic's
 * own dial labels resolve from (`secondary_gauge_duty`, `secondary_gauge_battery`, …) so a
 * Russian rider reads Russian on both renderers for the same metric.
 */
data class SecondaryGaugeLabels(
    val duty: String = "DUTY",
    val battery: String = "BATTERY",
    val power: String = "POWER",
    val current: String = "CURRENT",
    val motorTemp: String = "MOTOR",
    val escTemp: String = "ESC",
    val consumption: String = "CONSUMPTION"
)

/**
 * Turns the rider's chosen secondary metric into everything the gauge needs.
 * Pure, so the choice logic is tested without a screen. Severity is what colors
 * the ring: duty uses the shared [DutyBands], temperatures their own ceilings,
 * everything else is neutral — semantic color is spent only on safety.
 *
 * Every motion reading is taken through [MotionReadings], never off
 * [ControllerData] directly: an unobserved value arrives here as null and
 * renders as [UNKNOWN_READOUT] with a zero ring and a [DutyLevel.NORMAL]
 * severity — the same three things the temperature gauges have always done, now
 * reaching duty, power and consumption too (`G §9`).
 */
object SecondaryGaugeMapper {

    private const val MAX_CURRENT_A = 150f
    private const val MAX_POWER_W = 8000f
    private const val MAX_WH_PER_KM = 50f

    /**
     * [units] is accepted for a fixed cross-task contract (later screens call this with the
     * app's unit setting) but is a deliberate no-op here: the imperial toggle only converts
     * speed and distance, and none of the seven [SecondaryGauge] metrics (%, kW, A, °C, Wh/km)
     * has an imperial form under the locked design — so this is not a forgotten conversion.
     */
    fun map(
        gauge: SecondaryGauge,
        motion: ControllerData,
        battery: BmsData,
        units: UnitSystem,
        labels: SecondaryGaugeLabels = SecondaryGaugeLabels()
    ): SecondaryReadout = when (gauge) {
        SecondaryGauge.DUTY -> {
            // G §9: hasDuty used to be read nowhere in presentation/, so a wheel
            // whose truePWM latch has never closed — the exact case Part F's
            // alarm refuses to arm on — showed a confident 0 %.
            val duty = MotionReadings.dutyPercent(motion)
            SecondaryReadout(
                labels.duty, duty.readoutOr { it.roundToInt().toString() }, "%",
                duty?.let { frac(it, 100f) } ?: 0f,
                duty?.let { DutyBands.level(it) } ?: DutyLevel.NORMAL
            )
        }
        SecondaryGauge.BATTERY -> SecondaryReadout(
            labels.battery,
            if (battery.socKnown) battery.soc.roundToInt().toString() else "—", "%",
            if (battery.socKnown) frac(battery.soc, 100f) else 0f,
            DutyLevel.NORMAL
        )
        SecondaryGauge.POWER -> {
            val power = MotionReadings.powerW(motion)
            SecondaryReadout(
                labels.power, power.readoutOr { formatFixed(it / 1000f, 1) }, "kW",
                power?.let { frac(abs(it), MAX_POWER_W) } ?: 0f, DutyLevel.NORMAL
            )
        }
        SecondaryGauge.CURRENT -> SecondaryReadout(
            labels.current, motion.batteryCurrentA.roundToInt().toString(), "A",
            frac(abs(motion.batteryCurrentA), MAX_CURRENT_A), DutyLevel.NORMAL
        )
        SecondaryGauge.MOTOR_TEMP -> {
            val temp = MotionReadings.motorTempC(motion)
            SecondaryReadout(
                labels.motorTemp,
                temp.readoutOr { it.roundToInt().toString() }, "°C",
                temp?.let { frac(it, TempBands.MOTOR_CRITICAL_C + 20f) } ?: 0f,
                TempBands.motorLevel(motion.motorTempC, motion.hasMotorTemp)
            )
        }
        SecondaryGauge.ESC_TEMP -> {
            val temp = MotionReadings.escTempC(motion)
            SecondaryReadout(
                labels.escTemp,
                temp.readoutOr { it.roundToInt().toString() }, "°C",
                temp?.let { frac(it, TempBands.ESC_CRITICAL_C + 20f) } ?: 0f,
                TempBands.escLevel(motion.escTempC)
            )
        }
        SecondaryGauge.CONSUMPTION -> {
            val wh = MotionReadings.whPerKm(motion)
            SecondaryReadout(
                labels.consumption, wh.readoutOr { formatFixed(it, 1) }, "Wh/km",
                wh?.let { frac(it, MAX_WH_PER_KM) } ?: 0f, DutyLevel.NORMAL
            )
        }
    }

    private fun frac(value: Float, max: Float): Float =
        if (max <= 0f) 0f else (value / max).coerceIn(0f, 1f)
}
