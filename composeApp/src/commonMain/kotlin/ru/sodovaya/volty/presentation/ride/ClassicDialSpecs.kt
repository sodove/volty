package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.stats.DutyBands
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.domain.stats.RideMetrics
import ru.sodovaya.volty.domain.stats.TempBands
import ru.sodovaya.volty.presentation.ride.gauge.ClusterSlot
import ru.sodovaya.volty.presentation.ride.gauge.DialScale
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import ru.sodovaya.volty.util.formatFixed
import kotlin.math.roundToInt

/** Everything one Classic dial needs to draw itself, bound to a slot in the cluster. */
data class DialSpec(
    val slot: ClusterSlot,
    val label: String,
    val valueText: String,
    val unit: String,
    val value: Float,
    val scale: DialScale,
    val dangerFrom: Float?,
    val severity: DutyLevel
)

/**
 * Turns live [ControllerData]/[BmsData] into the eight [DialSpec]s the Classic cluster renders.
 * Pure, so the binding logic (unknown-value dashes, severity, which threshold colors which dial)
 * is fully unit tested without a Compose/Canvas harness.
 *
 * Mirrors [SecondaryGaugeMapper]'s conventions on purpose: both renderers read the same
 * [ControllerData]/[BmsData] and must never disagree on a number or a severity for the same
 * telemetry, so this reuses the exact same shared bands ([DutyBands], [TempBands]) and the same
 * unknown-value handling (dash the text, needle rests at the scale minimum) rather than
 * introducing any Classic-only threshold.
 */
object ClassicDialSpecs {

    private const val CURRENT_SCALE_MAX = 150f
    private const val POWER_SCALE_MAX = 8f
    private const val CONSUMPTION_SCALE_MAX = 50f
    private const val ESC_SCALE_MAX = 120f
    private const val MOTOR_SCALE_MAX = 140f
    private const val HERO_SCALE_MIN_SPAN = 10f

    fun build(motion: ControllerData, battery: BmsData, units: UnitSystem, maxSpeedKmh: Float): List<DialSpec> {
        val powerKw = motion.powerW / 1000f
        val instantWhPerKm = RideMetrics.instantWhPerKm(motion.powerW, motion.speedKmh)
            ?: RideMetrics.sessionWhPerKm(motion.consumedWh, motion.tripKm)

        return listOf(
            DialSpec(
                slot = ClusterSlot.TOP_LEFT,
                label = "CURRENT",
                valueText = motion.batteryCurrentA.roundToInt().toString(),
                unit = "A",
                value = motion.batteryCurrentA,
                scale = DialScale(min = -CURRENT_SCALE_MAX, max = CURRENT_SCALE_MAX, majorTicks = 6),
                dangerFrom = null,
                severity = DutyLevel.NORMAL
            ),
            DialSpec(
                slot = ClusterSlot.TOP_CENTRE,
                label = "POWER",
                valueText = formatFixed(powerKw, 1),
                unit = "kW",
                value = powerKw,
                scale = DialScale(min = -2f, max = POWER_SCALE_MAX, majorTicks = 5),
                dangerFrom = null,
                severity = DutyLevel.NORMAL
            ),
            DialSpec(
                slot = ClusterSlot.TOP_RIGHT,
                label = "DUTY",
                valueText = motion.dutyPercent.roundToInt().toString(),
                unit = "%",
                value = motion.dutyPercent,
                scale = DialScale(min = 0f, max = 100f, majorTicks = 5),
                dangerFrom = DutyBands.DEFAULT_CRITICAL_PERCENT,
                severity = DutyBands.level(motion.dutyPercent)
            ),
            DialSpec(
                slot = ClusterSlot.HERO,
                label = "SPEED",
                valueText = if (motion.speedKnown) UnitFormatter.speed(motion.speedKmh, units) else "—",
                unit = UnitFormatter.speedUnit(units),
                value = if (motion.speedKnown) motion.speedKmh else 0f,
                scale = DialScale(min = 0f, max = maxSpeedKmh.coerceAtLeast(HERO_SCALE_MIN_SPAN), majorTicks = 7),
                dangerFrom = null,
                severity = DutyLevel.NORMAL
            ),
            DialSpec(
                slot = ClusterSlot.HERO_INSET,
                label = "BATTERY",
                valueText = if (battery.socKnown) battery.soc.roundToInt().toString() else "—",
                unit = "%",
                value = if (battery.socKnown) battery.soc else 0f,
                scale = DialScale(min = 0f, max = 100f, majorTicks = 5),
                dangerFrom = null,
                severity = DutyLevel.NORMAL
            ),
            DialSpec(
                slot = ClusterSlot.BOTTOM_LEFT,
                label = "ESC",
                valueText = if (motion.hasEscTemp) motion.escTempC.roundToInt().toString() else "—",
                unit = "°C",
                value = if (motion.hasEscTemp) motion.escTempC else 0f,
                scale = DialScale(min = 0f, max = ESC_SCALE_MAX, majorTicks = 6),
                dangerFrom = TempBands.ESC_CRITICAL_C,
                severity = TempBands.escLevel(motion.escTempC)
            ),
            DialSpec(
                slot = ClusterSlot.BOTTOM_CENTRE,
                label = "CONSUMPTION",
                valueText = instantWhPerKm?.let { formatFixed(it, 1) } ?: "—",
                unit = "Wh/km",
                value = instantWhPerKm ?: 0f,
                scale = DialScale(min = 0f, max = CONSUMPTION_SCALE_MAX, majorTicks = 5),
                dangerFrom = null,
                severity = DutyLevel.NORMAL
            ),
            DialSpec(
                slot = ClusterSlot.BOTTOM_RIGHT,
                label = "MOTOR",
                valueText = if (motion.hasMotorTemp) motion.motorTempC.roundToInt().toString() else "—",
                unit = "°C",
                value = if (motion.hasMotorTemp) motion.motorTempC else 0f,
                scale = DialScale(min = 0f, max = MOTOR_SCALE_MAX, majorTicks = 7),
                dangerFrom = TempBands.MOTOR_CRITICAL_C,
                severity = TempBands.motorLevel(motion.motorTempC, motion.hasMotorTemp)
            )
        )
    }
}
