package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.stats.DutyBands
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.domain.stats.TempBands
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot
import ru.sodovaya.volty.presentation.ride.gauge.VescGaugeRange
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Everything one dial of the faithful VESC cluster needs, bound to its slot. */
data class VescDialSpec(
    val slot: VescClusterSlot,
    /**
     * Angle sweep + value range, straight from `RtDataSetup.qml` — Speed, Current and Power's
     * maxima are the exceptions, grown from what the session has shown (see [ClassicDialSpecs]'s
     * class doc and §14).
     */
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
    val motor: String = "TEMP MOTOR",
    /**
     * The consumption dial's UNIT, resolved by the caller because — alone among the eight units on
     * this cluster — it is spelled rather than symbolic, and Russian spells it `Вт·ч/км`. (`A`,
     * `%`, `W`, `°C` are the same in every locale; `km/h`/`mph` is the unit system's own business
     * and [UnitFormatter] owns it.) Null falls back to [UnitFormatter.consumptionUnit], which is
     * English-only and exists so this object stays callable, and testable, without a resource
     * resolver.
     */
    val consumptionUnit: String? = null
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
 * temperatures from `l_temp_fet_end`/`l_temp_motor_end`). Volty does not read a VESC motor config
 * yet (that needs `COMM_GET_MCCONF`, deferred to Part C — see
 * `docs/superpowers/specs/2026-07-24-vehicle-platform/B-vesc-dashboard.md` §14), so those adaptive
 * maxima are NOT ported faithfully. Three of the eight dials instead grow from what the vehicle has
 * actually shown this session — Speed ([heroDisplayMax], the original runtime scale), and, per §14's
 * interim decision, Current and Power too ([currentDisplayMax]/[powerDisplayMax]): a rider on a
 * 2×250 A scooter gets a dial that is wrong in a way that matters (pegged at the end of its scale)
 * rather than merely imprecise. The two temperature dials keep the QML's fixed `0..100` range —
 * §14 leaves their threshold question (`l_temp_fet_start` vs. this project's own `TempBands`) open
 * for when `GET_MCCONF` lands, and does not touch their range. The conditional `labelStep`
 * expressions ARE ported as functions of the maximum even where the maximum is currently fixed, so
 * the rule rather than its current answer is what lives here.
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
    // The tick-count ceiling every runtime scale shares — Hero, Current and Power all grow from a
    // session reading with no natural upper bound, and VescGaugeRange.tickmarkCount's own
    // `min(MAX_TICKMARK_COUNT, naturalCount)` caps every one of them the same way once they grow
    // too far, so all three derive the same ceiling from it below rather than each hardcoding one.
    // ---------------------------------------------------------------------------------------

    /**
     * The largest a runtime-scaled max is allowed to snap to, for a scale whose FINEST possible
     * label step is [snapStep].
     *
     * Derived from [VescGaugeRange.MAX_TICKMARK_COUNT] rather than a hardcoded number, because that
     * is what actually produces the ragged ring this exists to prevent: [VescGaugeRange.tickmarkCount]
     * is `min(MAX_TICKMARK_COUNT, naturalCount)`, and the label-step arithmetic elsewhere in this
     * object only stays round because [snapStep] makes `naturalCount = span/labelStep + 1` an exact
     * integer — the `min()` has never had anything to clip. The INSTANT `naturalCount` exceeds
     * [VescGaugeRange.MAX_TICKMARK_COUNT], the displayed count is pinned at the cap instead, and
     * `(cap - 1)` has no reason to divide a span that was only ever snapped to be a multiple of the
     * label step — e.g. a 1000 A max: span 2000, labelStep 20, natural count 101, capped to 100,
     * `tickmarkSectionValue = 2000 / 99 = 20.2…`, not round.
     *
     * So the ceiling is the largest max whose NATURAL count still equals the cap exactly:
     * `naturalCount <= MAX_TICKMARK_COUNT`. Current and Power are bipolar (`span = 2 * max`) with a
     * label step that is always exactly `2 * snapStep` once past their own floor, so those two
     * factors of 2 cancel, giving `max <= (MAX_TICKMARK_COUNT - 1) * snapStep` exactly. The hero is
     * unipolar (`span = max`, not `2 * max`) and [heroLabelStep]'s own fallback list bottoms out at
     * [HERO_SNAP_STEP] itself when nothing coarser divides the snapped span evenly — the identical
     * worst case (the label step chosen equal to `snapStep` itself) that drives the Current/Power
     * bound, so the same formula, `max <= (MAX_TICKMARK_COUNT - 1) * HERO_SNAP_STEP`, holds for it
     * too even though its range is not bipolar. At exactly the ceiling the natural count IS the cap
     * (100 for 990 A, 100 for 99000 W, 100 for 990 km/h-or-mph) and the ring is still perfectly
     * round — one step past it, for whichever of the three lands on the case where its label step is
     * this fine, `naturalCount` silently becomes 101-capped-to-100 and the ring ragges.
     */
    private fun tickCapCeiling(snapStep: Double): Double =
        (VescGaugeRange.MAX_TICKMARK_COUNT - 1) * snapStep

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
     * up, then clamped to [tickCapCeiling] of [HERO_SNAP_STEP] — 990 (km/h or mph) — for exactly
     * the reason [tickCapCeiling]'s own doc gives: a session's speed has no upper bound short of a
     * decode error, and past that ceiling the ring goes ragged the same way Current/Power's did
     * before they got this same clamp (e.g. a display max of 2000 picks label step 20, natural
     * count 101, capped to 100, `tickmarkSectionValue = 2000 / 99 = 20.2…`) — on the hero, the
     * largest dial in the cluster, from a single sample, permanent for the rest of the session.
     * `ceil()`/`min()` stay non-decreasing in the reading, so the scale still only grows, it just
     * stops growing at the ceiling instead of forever, same as Current/Power.
     *
     * Public because it is the thing worth pinning in a test — that the scale never shrinks below
     * the speed it was asked to show, in either unit system, up to that ceiling.
     */
    fun heroDisplayMax(maxSpeedKmh: Float, units: UnitSystem): Float {
        val display = UnitFormatter.speedValue(maxSpeedKmh.coerceAtLeast(HERO_MIN_MAX_KMH), units)
        val snapped = ceil(display / HERO_SNAP_STEP) * HERO_SNAP_STEP
        return min(snapped.toDouble(), tickCapCeiling(HERO_SNAP_STEP.toDouble())).toFloat()
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
    // Current + Power's runtime scale — §14's "Now" divergence from the port
    //
    // VESC derives BOTH of these from the connected controller's motor configuration
    // (`RtDataSetup.qml:665-728`):
    //   currentMaxRound = ceil(l_current_max / 5) * 5 * values.num_vescs
    //   powerMax        = min(v_in * min(l_in_current_max, l_current_max), l_watt_max) * num_vescs
    // Volty cannot reproduce that — it needs `COMM_GET_MCCONF`, which is deferred to Part C, where
    // `num_vescs` first becomes meaningful (see B-vesc-dashboard.md §14). Until then, THIS project
    // scales from what the vehicle has actually shown this session instead, reusing the exact
    // floor-then-snap-then-grow-only shape [heroDisplayMax] already established for Speed: a fixed
    // floor (today's constant, so a quiet ride still shows VESC's familiar scale), snapped up to a
    // step that keeps the tick labels round, and never allowed to shrink. `GET_MCCONF` supersedes
    // this the day Part C lands.
    //
    // Like the hero, this scale is given a CEILING too — [tickCapCeiling], shared by all three (see
    // its doc, above). What Current and Power do NOT share with the hero is a debounce:
    // `SessionPeakTracker`, upstream of these two functions, withholds a rising reading from ever
    // reaching [sessionScaledMax] until several consecutive samples corroborate it, so a single bad
    // decode never reaches the ceiling in the first place. Speed's own tracker
    // (`RideDashboardScreen.sessionMaxSpeedKmh`) has no such debounce — see `B-vesc-dashboard.md`
    // §15.3 item 1 for what that leaves open now that the ceiling itself is no longer one of them.
    // ---------------------------------------------------------------------------------------

    /**
     * The shared shape behind [currentDisplayMax] and [powerDisplayMax]: floor at VESC's own
     * default, snap UP to [snapStep] so the label-step functions below always divide the resulting
     * (symmetric, `-max..+max`) span evenly, then clamp to [tickCapCeiling] so the snap can never
     * carry the max PAST the point where [VescGaugeRange]'s own tick-count cap would make that
     * division ragged again (see [tickCapCeiling]'s doc). Monotonic — `max`, `ceil` and `min` are
     * all non-decreasing in [sessionMaxAbs] up to the ceiling and constant beyond it — so a caller
     * that only ever grows its own high-water mark (see `RideDashboardScreen`'s session trackers)
     * still gets a scale that only grows, it just stops growing at the ceiling instead of forever.
     *
     * The ceiling does not, by itself, stop ONE spurious sample from pinning the scale there for
     * the rest of the ride — merely capping how far wrong it can go. That is
     * [SessionPeakTracker]'s job, upstream of this function: it withholds a rising reading from
     * ever reaching [sessionMaxAbs] until several consecutive samples corroborate it, so a single
     * bad decode never gets this far in the first place.
     */
    private fun sessionScaledMax(sessionMaxAbs: Float, floor: Double, snapStep: Double): Double {
        val floored = max(floor, sessionMaxAbs.toDouble())
        val snapped = ceil(floored / snapStep) * snapStep
        return min(snapped, tickCapCeiling(snapStep))
    }

    /**
     * Amps ten apart. [tenOrTwentyLabelStep] picks either 10 or 20 A per label; snapping the max to
     * a multiple of 10 makes the symmetric span `2 * max` a multiple of 20, which both candidate
     * steps divide exactly for every integer multiple — the same reasoning [HERO_SNAP_STEP] documents.
     */
    private const val CURRENT_SNAP_STEP = 10.0

    /**
     * Watts a thousand apart, for the same reason as [CURRENT_SNAP_STEP]: [powerLabelStep] picks
     * 1000 or 2000 W, and a max snapped to a multiple of 1000 makes the span a multiple of 2000,
     * which both divide exactly.
     */
    private const val POWER_SNAP_STEP = 1000.0

    /**
     * Current's bipolar scale maximum in AMPS — see the divergence note above and §14. Bipolar
     * because the dial is `-max..+max` (QML :77-78); the caller therefore tracks the largest
     * ABSOLUTE battery current seen, not the largest signed one, so regen braking grows the scale
     * exactly as much as acceleration does. Ceilinged at 990 A ([tickCapCeiling] of
     * [CURRENT_SNAP_STEP]) — see [sessionScaledMax].
     */
    fun currentDisplayMax(sessionMaxAbsCurrentA: Float): Double =
        sessionScaledMax(sessionMaxAbsCurrentA, CURRENT_MAX_A, CURRENT_SNAP_STEP)

    /**
     * Power's bipolar scale maximum in WATTS — see the divergence note above and §14. Ceilinged at
     * 99000 W ([tickCapCeiling] of [POWER_SNAP_STEP]) — see [sessionScaledMax].
     */
    fun powerDisplayMax(sessionMaxAbsPowerW: Float): Double =
        sessionScaledMax(sessionMaxAbsPowerW, POWER_MAX_W, POWER_SNAP_STEP)

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

    /**
     * The shared marker, not a private copy: `G §9`'s whole point is that all
     * three renderers say "unknown" the same way, and two literals that happen
     * to match today are exactly how the Clean and Classic gauges would drift.
     */
    private const val UNKNOWN = UNKNOWN_READOUT

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
        labels: ClassicDialLabels = ClassicDialLabels(),
        /**
         * The largest ABSOLUTE battery current seen this session; drives [currentDisplayMax].
         * Defaults to `0f` — nothing seen yet — which floors at VESC's own [CURRENT_MAX_A].
         */
        maxCurrentA: Float = 0f,
        /**
         * The largest ABSOLUTE power seen this session; drives [powerDisplayMax]. Defaults to
         * `0f`, which floors at VESC's own [POWER_MAX_W].
         */
        maxPowerW: Float = 0f
    ): List<VescDialSpec> {
        val heroMax = heroDisplayMax(maxSpeedKmh, units)
        val currentMax = currentDisplayMax(maxCurrentA)
        val powerMax = powerDisplayMax(maxPowerW)

        // The canonical Wh/km drives the COLOUR (see CONSUMPTION_WARN_WH_PER_KM); the display
        // conversion drives the needle and the readout, exactly as the speed dial does.
        // Through MotionReadings, so the `—` override below is finally reachable: this fallback
        // used to be spelled out here and returned a well-formed 0.0 on every Begode (G §9.1).
        val whPerKm = MotionReadings.whPerKm(motion)
        val duty = MotionReadings.dutyPercent(motion)
        val power = MotionReadings.powerW(motion)
        val speed = MotionReadings.speedKmh(motion)
        val escTemp = MotionReadings.escTempC(motion)
        val motorTemp = MotionReadings.motorTempC(motion)

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
                    // §14: session-scaled, floored at the QML's own -60/+60 (:77-78) — see
                    // currentDisplayMax's doc.
                    minimumValue = -currentMax,
                    maximumValue = currentMax,
                    labelStep = tenOrTwentyLabelStep(currentMax) // QML :80
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
                value = duty ?: 0f,
                caption = labels.duty,
                unit = "%",                           // QML :98
                // G §9: a wheel whose firmware has never reported a PWM used to show a confident
                // 0 % here while the alarm correctly refused to arm on the same sample.
                valueTextOverride = if (duty == null) UNKNOWN else null,
                // OURS WINS: VESC gives duty a fixed accent (:100) and no threshold at all.
                // An unobserved duty gets no band: an absent number is not an alarming one, the
                // same call consumptionLevel and batteryLevel already make.
                severity = duty?.let { DutyBands.level(it) } ?: DutyLevel.NORMAL
            ),
            // QML :101-117. Declares no angles, so it takes CustomGauge's own -140..140.
            VescDialSpec(
                slot = VescClusterSlot.POWER,
                range = VescGaugeRange(
                    minAngle = DEFAULT_MIN_ANGLE,
                    maxAngle = DEFAULT_MAX_ANGLE,
                    // §14: session-scaled, floored at the QML's own -10000/+10000 (:108-109) — see
                    // powerDisplayMax's doc.
                    minimumValue = -powerMax,
                    maximumValue = powerMax,
                    labelStep = powerLabelStep(powerMax) // QML :112
                ),
                value = power ?: 0f,
                caption = labels.power,
                unit = "W",                           // QML :114
                // G §9: an unavailable voltage scale used to render as a confident 0 W.
                valueTextOverride = if (power == null) UNKNOWN else null,
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
                value = speed?.let { UnitFormatter.speedValue(it, units) } ?: 0f,
                caption = labels.speed,
                unit = UnitFormatter.speedUnit(units), // QML :141
                valueTextOverride = if (speed == null) UNKNOWN else null
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
                value = escTemp ?: 0f,
                caption = twoLineCaption(labels.esc),
                unit = "°C",                          // QML :457
                valueTextOverride = if (escTemp == null) UNKNOWN else null,
                // OURS WINS: VESC reddens at 70 and ambers at 40 (:449); TempBands says 85/70.
                severity = escTemp?.let { TempBands.escLevel(it) } ?: DutyLevel.NORMAL
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
                value = motorTemp ?: 0f,
                caption = twoLineCaption(labels.motor),
                unit = "°C",                          // QML :473
                valueTextOverride = if (motorTemp == null) UNKNOWN else null,
                // OURS WINS: VESC reddens at 70 (:479); TempBands says 100/85 for a motor.
                //
                // Through the nullable local like every other gauge, rather than passing
                // `motion.motorTempC` beside `motion.hasMotorTemp`. Identical behaviour —
                // TempBands.motorLevel's own `known` branch answers NORMAL for exactly the
                // samples this `?:` does — but it keeps "every renderer reads motion through
                // MotionReadings" literally true, so the next reader does not find one gauge
                // reaching past the contract and take it for permission.
                severity = motorTemp?.let { TempBands.motorLevel(it, known = true) }
                    ?: DutyLevel.NORMAL
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
                // QML :500. Localized by the caller when it has a resolver; see ClassicDialLabels.
                unit = labels.consumptionUnit ?: UnitFormatter.consumptionUnit(units),
                valueTextOverride = if (whPerKm == null) UNKNOWN else null,
                severity = consumptionLevel(whPerKm)
            )
        )
    }
}
