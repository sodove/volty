package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.stats.DutyBands
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.domain.stats.RideMetrics
import ru.sodovaya.volty.domain.stats.TempBands
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot
import ru.sodovaya.volty.presentation.ride.gauge.VescGaugeRange
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Everything one dial of the faithful VESC cluster needs, bound to its slot. */
data class VescDialSpec(
    val slot: VescClusterSlot,
    /** Angle sweep + value range, straight from `RtDataSetup.qml` (Speed's max is the exception). */
    val range: VescGaugeRange,
    /** The live reading, already in DISPLAY units wherever the dial has any. */
    val value: Float,
    /** The caption above the readout (`typeText`), localized by the caller. */
    val caption: String,
    /** The unit below the readout (`unitText`). */
    val unit: String,
    /**
     * Replaces the formatted readout when the reading is genuinely unknown (no sensor, no speed,
     * standing still), so a missing value never reads as a real `0`. Null means "print the number".
     */
    val valueTextOverride: String? = null,
    /** `CustomGauge.qml` :51 — decimal places for the readout. */
    val precision: Int = 0,
    /** `CustomGauge.qml` :57. False only for Battery, which overlays its own centre content. */
    val centerTextVisible: Boolean = true,
    /** `CustomGauge.qml` :48. */
    val tickmarkScale: Double = 1.0,
    /** `CustomGauge.qml` :47. */
    val tickmarkSuffix: String = "",
    /** What colours the needle (`nibColor`). [DutyLevel.NORMAL] means the plain fixed accent. */
    val severity: DutyLevel = DutyLevel.NORMAL
)

/**
 * The eight dial captions, resolved by the caller. Defaults are `RtDataSetup.qml`'s own
 * `typeText`s (:83, :99, :115, :142, :458, :474, :501; Battery declares none because :312 hides
 * its centre text) — they keep [ClassicDialSpecs] callable, and testable, without dragging Compose
 * or a string-resource resolver into this pure object. [ClassicRideCluster] is the one real caller
 * and passes `stringResource(...).uppercase()` for each field instead, reusing the exact same
 * string keys the Clean renderer's own labels resolve from, so a Russian rider reads Russian dial
 * faces on both styles for the same metric.
 */
data class ClassicDialLabels(
    val current: String = "CURRENT",
    val power: String = "POWER",
    val duty: String = "DUTY",
    val speed: String = "SPEED",
    val battery: String = "BATTERY",
    val esc: String = "TEMP ESC",
    val consumption: String = "CONSUMP.",
    val motor: String = "TEMP MOTOR"
)

/**
 * Turns live [ControllerData]/[BmsData] into the eight [VescDialSpec]s the Classic cluster
 * renders, using the per-gauge configuration VESC Tool's `mobile/RtDataSetup.qml` declares. Pure,
 * so the whole spec table and every threshold behind a needle's colour is unit tested without a
 * Compose/Canvas harness.
 *
 * ## Where the numbers come from
 *
 * Ranges, angles, label steps, units and captions are transcribed from `RtDataSetup.qml`'s
 * `GridLayout` (:58-537), with anything a gauge does not declare falling through to
 * `CustomGauge.qml`'s own property defaults (:31 `labelStep = 10`, :35-36 `minAngle = -140` /
 * `maxAngle = 140`, :51 `precision = 0`). Each constant below carries its line.
 *
 * The QML also *rewrites* several of these at runtime from the motor configuration
 * (`onValuesSetupReceived`, :664-763: current from `l_current_max`, power from `l_watt_max`,
 * temperatures from `l_temp_fet_end`/`l_temp_motor_end`). Volty does not read a VESC motor config,
 * so those adaptive maxima are deliberately NOT ported and every dial but Speed keeps the QML's
 * declared range. The conditional `labelStep` expressions ARE ported as functions of the maximum
 * even where the maximum is currently fixed, so the rule rather than its current answer is what
 * lives here.
 *
 * ## Whose thresholds colour the needle
 *
 * VESC's `nibColor` rules are per-gauge expressions in the QML. Where this project already owns a
 * threshold for the same quantity, OURS wins — [DutyBands] and [TempBands] are shared with the
 * Clean renderer and with Part F's audible alarms, and a dial that turns red at a different
 * temperature from the one that sounds the alarm is the exact drift those objects exist to
 * prevent. Where we own nothing (consumption, state of charge), VESC's numbers are kept. Every
 * case is spelled out at its constant below.
 */
object ClassicDialSpecs {

    // ---------------------------------------------------------------------------------------
    // Ranges and sweeps — RtDataSetup.qml, with CustomGauge.qml's defaults where it declares none
    // ---------------------------------------------------------------------------------------

    /** `CustomGauge.qml` :35-36 — the sweep a gauge gets when it declares no angles (Power). */
    private const val DEFAULT_MIN_ANGLE = -140.0
    private const val DEFAULT_MAX_ANGLE = 140.0

    /** `CustomGauge.qml` :31 — the label step a gauge gets when it declares none (Battery). */
    private const val DEFAULT_LABEL_STEP = 10.0

    /** QML :77-78. */
    private const val CURRENT_MAX_A = 60.0

    /** QML :92-93. */
    private const val DUTY_MAX_PERCENT = 100.0

    /** QML :108-109. */
    private const val POWER_MAX_W = 10000.0

    /** QML :441-442 and :467-468 — both temperature dials read 0..100 °C. */
    private const val TEMP_MAX_C = 100.0

    /** QML :494-495. */
    private const val CONSUMPTION_MAX = 50.0

    /** QML :80 / :139 / :498 — the same expression on three different gauges. */
    private fun tenOrTwentyLabelStep(maximumValue: Double): Double =
        if (maximumValue > 60.0) 20.0 else 10.0

    /** QML :112. */
    private fun powerLabelStep(maximumValue: Double): Double =
        if (maximumValue > 6000.0) 2000.0 else 1000.0

    // ---------------------------------------------------------------------------------------
    // The hero's runtime scale — the one thing here that is not a fixed constant
    // ---------------------------------------------------------------------------------------

    /** A hero scale is never allowed to collapse: a 0-max ring has no ticks and no needle travel. */
    private const val HERO_MIN_MAX_KMH = 10f

    /**
     * The hero's display max is snapped UP to a multiple of this before a label step is chosen.
     *
     * The snap is the whole reason imperial works. The km/h maximum is already round when it
     * arrives (`RideDashboardScreen` computes `max(70, ceil(sessionMax / 10) * 10)`), but that
     * roundness does not survive the `/1.609344` conversion to mph — 70 km/h is 43.496 mph. A
     * scale is only labelled in round numbers when its span divides EXACTLY by a round label step,
     * because [VescGaugeRange] spaces its majors by `(max - min) / (tickmarkCount - 1)` rather
     * than by `labelStep` itself: give it a 43.496 max and a step of 10 and it produces
     * `tickmarkCount = 5` and labels 0, 11, 22, 33, 43. Snapping the DISPLAY max (never the
     * reading) up to the next multiple of 10 makes a step of 10 always divide it exactly, and
     * leaves an already-round metric max untouched — which is why this needs no branch on the
     * unit system.
     */
    private const val HERO_SNAP_STEP = 10f

    /**
     * Round label steps to fall back on when the QML's own choice (:139) does not divide the
     * snapped span exactly — 70 with a step of 20 gives 3.5 intervals, i.e. the ragged ring above.
     * Coarsest first, so a big scale gets few labels rather than a crowded ring of small ones.
     */
    private val HERO_FALLBACK_LABEL_STEPS = listOf(50.0, 25.0, 20.0, 10.0)

    /**
     * The hero's scale maximum in DISPLAY units: the km/h maximum converted first, then snapped
     * up. Public because it is the thing worth pinning in a test — that the scale never shrinks
     * below the speed it was asked to show, in either unit system.
     */
    fun heroDisplayMax(maxSpeedKmh: Float, units: UnitSystem): Float {
        val display = UnitFormatter.speedValue(maxSpeedKmh.coerceAtLeast(HERO_MIN_MAX_KMH), units)
        return ceil(display / HERO_SNAP_STEP) * HERO_SNAP_STEP
    }

    /** The label step for a hero scale of [displayMax] — the QML's rule, then a divisor that works. */
    fun heroLabelStep(displayMax: Float): Double {
        val span = displayMax.toDouble()
        val preferred = tenOrTwentyLabelStep(span) // QML :139
        if (dividesEvenly(span, preferred)) return preferred
        return HERO_FALLBACK_LABEL_STEPS.firstOrNull { dividesEvenly(span, it) } ?: DEFAULT_LABEL_STEP
    }

    private fun dividesEvenly(span: Double, step: Double): Boolean {
        if (step <= 0.0) return false
        val intervals = span / step
        return abs(intervals - intervals.roundToInt()) < 1e-6
    }

    // ---------------------------------------------------------------------------------------
    // nibColor thresholds
    // ---------------------------------------------------------------------------------------

    /**
     * QML :505: `value > 45 ? red : value > 25 ? orange : blue`. VESC's own numbers, KEPT — this
     * project owns no consumption band for them to contradict.
     *
     * Two deliberate departures from the QML. (1) VESC compares against the gauge's DISPLAY value,
     * so an imperial rider's dial goes amber at 25 Wh/mi — 15.5 Wh/km, well under half the metric
     * trigger. A severity colour that moves when a display toggle moves is a defect, so these are
     * applied to the canonical Wh/km. (2) The comparisons stay strict (`>`), matching the QML,
     * rather than being bent to [TempBands]' `>=`; nothing hinges on the boundary value itself.
     */
    const val CONSUMPTION_WARN_WH_PER_KM = 25f
    const val CONSUMPTION_CRITICAL_WH_PER_KM = 45f

    /**
     * QML :316: `value > 50 ? green : value > 20 ? orange : red`. VESC's own numbers, KEPT for the
     * same reason as consumption's — this project has no shared state-of-charge BAND. It does have
     * a state-of-charge ALARM (`AlertConfig.socLowPercent`, default 15%), but that is a different
     * thing: per-vehicle, rider-editable, and about when to interrupt someone rather than what
     * colour a fuel gauge is. The two do not contradict each other — 15 < 20 means the dial is
     * already red before the alarm fires, which is the right order.
     *
     * This is the one dial where a HIGH reading is the safe one, so the mapping onto [DutyLevel] is
     * inverted relative to every other gauge.
     */
    const val BATTERY_OK_ABOVE_PERCENT = 50f
    const val BATTERY_WARN_ABOVE_PERCENT = 20f

    /** [DutyLevel.NORMAL] for an unknown consumption — an absent number is not an alarming one. */
    fun consumptionLevel(whPerKm: Float?): DutyLevel = when {
        whPerKm == null -> DutyLevel.NORMAL
        whPerKm > CONSUMPTION_CRITICAL_WH_PER_KM -> DutyLevel.CRITICAL
        whPerKm > CONSUMPTION_WARN_WH_PER_KM -> DutyLevel.WARN
        else -> DutyLevel.NORMAL
    }

    /** [DutyLevel.NORMAL] when the state of charge is unknown — never a fake empty battery. */
    fun batteryLevel(soc: Float, known: Boolean): DutyLevel = when {
        !known -> DutyLevel.NORMAL
        soc > BATTERY_OK_ABOVE_PERCENT -> DutyLevel.NORMAL
        soc > BATTERY_WARN_ABOVE_PERCENT -> DutyLevel.WARN
        else -> DutyLevel.CRITICAL
    }

    // ---------------------------------------------------------------------------------------

    private const val UNKNOWN = "—"

    /**
     * QML :458 / :474 write their temperature captions on two lines (`"TEMP\nESC"`) because one
     * long line would run out past the number ring at `0.66R`. Our localized strings arrive as a
     * single phrase ("ESC temp", "Темп. мотора"), so the same split is applied to them, at the
     * LAST space so a three-word label keeps its head together. A single word is left alone —
     * there is nothing to split, and hyphenating would be worse than a slightly wide caption.
     */
    private fun twoLineCaption(label: String): String {
        val lastSpace = label.trim().lastIndexOf(' ')
        if (lastSpace <= 0) return label
        val trimmed = label.trim()
        return trimmed.substring(0, lastSpace) + "\n" + trimmed.substring(lastSpace + 1)
    }

    fun build(
        motion: ControllerData,
        battery: BmsData,
        units: UnitSystem,
        maxSpeedKmh: Float,
        labels: ClassicDialLabels = ClassicDialLabels()
    ): List<VescDialSpec> {
        val heroMax = heroDisplayMax(maxSpeedKmh, units)

        // The canonical Wh/km drives the COLOUR (see CONSUMPTION_WARN_WH_PER_KM); the display
        // conversion drives the needle and the readout, exactly as the speed dial does.
        val whPerKm = RideMetrics.instantWhPerKm(motion.powerW, motion.speedKmh)
            ?: RideMetrics.sessionWhPerKm(motion.consumedWh, motion.tripKm)

        return listOf(
            // QML :70-85. `values.current_motor` feeds this gauge in VESC Tool; Volty shows the
            // BATTERY current instead, because that is what SecondaryGauge.CURRENT means
            // everywhere else in this app and one app-wide meaning for "current" beats matching
            // VESC's choice on one screen.
            VescDialSpec(
                slot = VescClusterSlot.CURRENT,
                range = VescGaugeRange(
                    minAngle = -210.0,               // QML :84
                    maxAngle = 15.0,                 // QML :85
                    minimumValue = -CURRENT_MAX_A,   // QML :77
                    maximumValue = CURRENT_MAX_A,    // QML :78
                    labelStep = tenOrTwentyLabelStep(CURRENT_MAX_A) // QML :80
                ),
                value = motion.batteryCurrentA,
                caption = labels.current,
                unit = "A"                            // QML :82
            ),
            // QML :86-100. The one dial whose scale runs counter-clockwise on purpose.
            VescDialSpec(
                slot = VescClusterSlot.DUTY,
                range = VescGaugeRange(
                    minAngle = 210.0,                 // QML :94
                    maxAngle = -15.0,                 // QML :95
                    minimumValue = -DUTY_MAX_PERCENT, // QML :93
                    maximumValue = DUTY_MAX_PERCENT,  // QML :92
                    labelStep = 25.0                  // QML :96
                ),
                value = motion.dutyPercent,
                caption = labels.duty,
                unit = "%",                           // QML :98
                // OURS WINS: VESC gives duty a fixed accent (:100) and no threshold at all.
                severity = DutyBands.level(motion.dutyPercent)
            ),
            // QML :101-117. Declares no angles, so it takes CustomGauge's own -140..140.
            VescDialSpec(
                slot = VescClusterSlot.POWER,
                range = VescGaugeRange(
                    minAngle = DEFAULT_MIN_ANGLE,
                    maxAngle = DEFAULT_MAX_ANGLE,
                    minimumValue = -POWER_MAX_W,      // QML :109
                    maximumValue = POWER_MAX_W,       // QML :108
                    labelStep = powerLabelStep(POWER_MAX_W) // QML :112
                ),
                value = motion.powerW,
                caption = labels.power,
                unit = "W",                           // QML :114
                tickmarkScale = 0.001,                // QML :110 — label the watts as kilowatts
                tickmarkSuffix = "k"                  // QML :111
            ),
            // QML :129-142. The only runtime scale: see heroDisplayMax / heroLabelStep.
            VescDialSpec(
                slot = VescClusterSlot.SPEED,
                range = VescGaugeRange(
                    minAngle = -225.0,                // QML :137
                    maxAngle = 45.0,                  // QML :138
                    minimumValue = 0.0,               // QML :135
                    maximumValue = heroMax.toDouble(),
                    labelStep = heroLabelStep(heroMax)
                ),
                // Value AND scale both in display units — the shipped defect was converting only
                // the readout, leaving an mph number over a km/h-labelled ring.
                value = if (motion.speedKnown) UnitFormatter.speedValue(motion.speedKmh, units) else 0f,
                caption = labels.speed,
                unit = UnitFormatter.speedUnit(units), // QML :141
                valueTextOverride = if (motion.speedKnown) null else UNKNOWN
            ),
            // QML :301-369. Hides its own centre text (:312) for the overlay ClassicRideCluster
            // draws in its place; declares no labelStep, so it takes CustomGauge's 10.
            VescDialSpec(
                slot = VescClusterSlot.BATTERY,
                range = VescGaugeRange(
                    minAngle = -225.0,                // QML :307
                    maxAngle = 45.0,                  // QML :308
                    minimumValue = 0.0,               // QML :309
                    maximumValue = 100.0,             // QML :310
                    labelStep = DEFAULT_LABEL_STEP
                ),
                value = if (battery.socKnown) battery.soc else 0f,
                caption = labels.battery,
                unit = "%",
                valueTextOverride = if (battery.socKnown) "${battery.soc.roundToInt()}%" else UNKNOWN,
                centerTextVisible = false,            // QML :312
                severity = batteryLevel(battery.soc, battery.socKnown)
            ),
            // QML :434-460.
            VescDialSpec(
                slot = VescClusterSlot.ESC_TEMP,
                range = VescGaugeRange(
                    minAngle = -195.0,                // QML :459
                    maxAngle = 30.0,                  // QML :460
                    minimumValue = 0.0,               // QML :441
                    maximumValue = TEMP_MAX_C,        // QML :442
                    labelStep = 20.0                  // QML :444
                ),
                value = if (motion.hasEscTemp) motion.escTempC else 0f,
                caption = twoLineCaption(labels.esc),
                unit = "°C",                          // QML :457
                valueTextOverride = if (motion.hasEscTemp) null else UNKNOWN,
                // OURS WINS: VESC reddens at 70 and ambers at 40 (:449); TempBands says 85/70.
                severity = if (motion.hasEscTemp) TempBands.escLevel(motion.escTempC) else DutyLevel.NORMAL
            ),
            // QML :461-486. Inverted sweep, like Duty.
            VescDialSpec(
                slot = VescClusterSlot.MOTOR_TEMP,
                range = VescGaugeRange(
                    minAngle = 195.0,                 // QML :469
                    maxAngle = -30.0,                 // QML :470
                    minimumValue = 0.0,               // QML :468
                    maximumValue = TEMP_MAX_C,        // QML :467
                    labelStep = 20.0                  // QML :471
                ),
                value = if (motion.hasMotorTemp) motion.motorTempC else 0f,
                caption = twoLineCaption(labels.motor),
                unit = "°C",                          // QML :473
                valueTextOverride = if (motion.hasMotorTemp) null else UNKNOWN,
                // OURS WINS: VESC reddens at 70 (:479); TempBands says 100/85 for a motor.
                severity = TempBands.motorLevel(motion.motorTempC, motion.hasMotorTemp)
            ),
            // QML :487-533 (`efficiencyGauge`).
            VescDialSpec(
                slot = VescClusterSlot.CONSUMPTION,
                range = VescGaugeRange(
                    minAngle = -127.0,                // QML :496
                    maxAngle = 127.0,                 // QML :497
                    minimumValue = -CONSUMPTION_MAX,  // QML :494
                    maximumValue = CONSUMPTION_MAX,   // QML :495
                    labelStep = tenOrTwentyLabelStep(CONSUMPTION_MAX) // QML :498
                ),
                value = whPerKm?.let { UnitFormatter.consumptionValue(it, units) } ?: 0f,
                caption = labels.consumption,
                unit = UnitFormatter.consumptionUnit(units), // QML :500
                valueTextOverride = if (whPerKm == null) UNKNOWN else null,
                severity = consumptionLevel(whPerKm)
            )
        )
    }
}
