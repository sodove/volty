package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.stats.DutyBands
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.domain.stats.RideMetrics
import ru.sodovaya.volty.domain.stats.TempBands
import ru.sodovaya.volty.presentation.ride.gauge.ClusterSlot
import ru.sodovaya.volty.presentation.ride.gauge.DialGeometry
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
 * The eight dial labels, resolved by the caller. Defaults are the plain-English labels this
 * renderer shipped with before spec item 5 (Classic dial faces are English-only while Clean's
 * are localized) — they keep [ClassicDialSpecs] callable, and testable, without dragging Compose
 * or a string-resource resolver into this pure object. [ClassicRideCluster] is the one real
 * caller and passes `stringResource(...).uppercase()` for each field instead of relying on these
 * defaults, reusing the exact same string keys Clean's own labels resolve from so a Russian
 * rider reads Russian on both renderers for the same dial.
 */
data class ClassicDialLabels(
    val current: String = "CURRENT",
    val power: String = "POWER",
    val duty: String = "DUTY",
    val speed: String = "SPEED",
    val battery: String = "BATTERY",
    val esc: String = "ESC",
    val consumption: String = "CONSUMPTION",
    val motor: String = "MOTOR"
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

    fun build(
        motion: ControllerData,
        battery: BmsData,
        units: UnitSystem,
        maxSpeedKmh: Float,
        labels: ClassicDialLabels = ClassicDialLabels()
    ): List<DialSpec> {
        val powerKw = motion.powerW / 1000f
        val instantWhPerKm = RideMetrics.instantWhPerKm(motion.powerW, motion.speedKmh)
            ?: RideMetrics.sessionWhPerKm(motion.consumedWh, motion.tripKm)

        // The hero's max is the only RUNTIME scale in the cluster (every other dial's span is a
        // fixed design constant above) — resolved in km/h first so majorTicks divides the actual
        // riding range evenly, THEN converted to the display unit. Picking ticks after converting
        // to mph would chase two moving targets at once for no benefit: the "keep ticks round"
        // requirement only ever showed up against the km/h range riders actually see.
        val heroMaxKmh = maxSpeedKmh.coerceAtLeast(HERO_SCALE_MIN_SPAN)
        val heroMajorTicks = DialGeometry.pickMajorTicks(heroMaxKmh)
        val heroScaleMax = UnitFormatter.speedValue(heroMaxKmh, units)

        return listOf(
            DialSpec(
                slot = ClusterSlot.TOP_LEFT,
                label = labels.current,
                valueText = motion.batteryCurrentA.roundToInt().toString(),
                unit = "A",
                value = motion.batteryCurrentA,
                scale = DialScale(min = -CURRENT_SCALE_MAX, max = CURRENT_SCALE_MAX, majorTicks = 6),
                dangerFrom = null,
                severity = DutyLevel.NORMAL
            ),
            DialSpec(
                slot = ClusterSlot.TOP_CENTRE,
                label = labels.power,
                valueText = formatFixed(powerKw, 1),
                unit = "kW",
                value = powerKw,
                scale = DialScale(min = -2f, max = POWER_SCALE_MAX, majorTicks = 5),
                dangerFrom = null,
                severity = DutyLevel.NORMAL
            ),
            DialSpec(
                slot = ClusterSlot.TOP_RIGHT,
                label = labels.duty,
                valueText = motion.dutyPercent.roundToInt().toString(),
                unit = "%",
                value = motion.dutyPercent,
                scale = DialScale(min = 0f, max = 100f, majorTicks = 5),
                dangerFrom = DutyBands.DEFAULT_CRITICAL_PERCENT,
                severity = DutyBands.level(motion.dutyPercent)
            ),
            DialSpec(
                slot = ClusterSlot.HERO,
                label = labels.speed,
                // Both the readout AND the scale go through the same unit conversion now — the
                // bug this fixes was exactly that `valueText`/`unit` were converted to mph while
                // `value`/`scale` stayed in raw km/h, so an imperial rider saw an "mph" number
                // over a km/h-labelled ring with the needle sitting between the wrong ticks.
                valueText = if (motion.speedKnown) UnitFormatter.speed(motion.speedKmh, units) else "—",
                unit = UnitFormatter.speedUnit(units),
                value = if (motion.speedKnown) UnitFormatter.speedValue(motion.speedKmh, units) else 0f,
                scale = DialScale(min = 0f, max = heroScaleMax, majorTicks = heroMajorTicks),
                dangerFrom = null,
                severity = DutyLevel.NORMAL
            ),
            DialSpec(
                slot = ClusterSlot.HERO_INSET,
                label = labels.battery,
                valueText = if (battery.socKnown) battery.soc.roundToInt().toString() else "—",
                unit = "%",
                value = if (battery.socKnown) battery.soc else 0f,
                scale = DialScale(min = 0f, max = 100f, majorTicks = 5),
                dangerFrom = null,
                severity = DutyLevel.NORMAL
            ),
            DialSpec(
                slot = ClusterSlot.BOTTOM_LEFT,
                label = labels.esc,
                valueText = if (motion.hasEscTemp) motion.escTempC.roundToInt().toString() else "—",
                unit = "°C",
                value = if (motion.hasEscTemp) motion.escTempC else 0f,
                scale = DialScale(min = 0f, max = ESC_SCALE_MAX, majorTicks = 6),
                dangerFrom = TempBands.ESC_CRITICAL_C,
                severity = TempBands.escLevel(motion.escTempC)
            ),
            DialSpec(
                slot = ClusterSlot.BOTTOM_CENTRE,
                label = labels.consumption,
                valueText = instantWhPerKm?.let { formatFixed(it, 1) } ?: "—",
                unit = "Wh/km",
                value = instantWhPerKm ?: 0f,
                scale = DialScale(min = 0f, max = CONSUMPTION_SCALE_MAX, majorTicks = 5),
                dangerFrom = null,
                severity = DutyLevel.NORMAL
            ),
            DialSpec(
                slot = ClusterSlot.BOTTOM_RIGHT,
                label = labels.motor,
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
