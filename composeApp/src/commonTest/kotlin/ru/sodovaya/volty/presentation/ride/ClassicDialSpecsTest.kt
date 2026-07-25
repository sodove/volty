package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot
import ru.sodovaya.volty.presentation.ride.gauge.VescGaugeRange
import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The eight-gauge spec table as VESC Tool's own `mobile/RtDataSetup.qml` declares it, plus the
 * `nibColor` severity rules — every expected number below carries the QML line it came from, and
 * every place this project's own thresholds override VESC's says so explicitly.
 */
class ClassicDialSpecsTest {

    private val motion = ControllerData(
        speedKmh = 47f, speedSource = SpeedSource.REPORTED, dutyPercent = 76f,
        batteryCurrentA = 52.4f, inputVoltageV = 78.2f, powerW = 4098f,
        escTempC = 52f, motorTempC = 68f, hasMotorTemp = true,
        consumedWh = 980f, tripKm = 58f, isConnected = true
    )
    private val battery = BmsData(voltage = 78.2f, soc = 84f, socKnown = true, isConnected = true)

    private fun specs(
        m: ControllerData = motion,
        b: BmsData = battery,
        u: UnitSystem = UnitSystem.METRIC,
        maxKmh: Float = 70f,
        maxCurrentA: Float = 0f,
        maxPowerW: Float = 0f
    ) = ClassicDialSpecs.build(m, b, u, maxKmh, maxCurrentA = maxCurrentA, maxPowerW = maxPowerW)
        .associateBy { it.slot }

    private fun spec(slot: VescClusterSlot, u: UnitSystem = UnitSystem.METRIC) = specs(u = u).getValue(slot)

    // --- the table -----------------------------------------------------------------------------

    @Test fun all_eight_slots_are_filled_exactly_once() {
        val built = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, 70f)
        assertEquals(VescClusterSlot.entries.size, built.size)
        assertEquals(built.size, built.map { it.slot }.distinct().size)
    }

    /**
     * min/max value, min/max angle and labelStep for every gauge, transcribed from the QML with
     * the line number of each. The two gauges that declare no angles at all (Power, and nothing
     * else) fall through to `CustomGauge.qml`'s own defaults (:35-36, `-140`/`140`); Battery
     * likewise declares no `labelStep` and takes the default `10` (`CustomGauge.qml` :31).
     */
    @Test fun every_gauge_matches_the_range_and_sweep_RtDataSetup_declares() {
        data class Row(val min: Double, val max: Double, val minAngle: Double, val maxAngle: Double, val step: Double)

        val expected = mapOf(
            // QML :77-78 range, :84-85 angles, :80 labelStep (max is 60, so not > 60 -> 10)
            VescClusterSlot.CURRENT to Row(-60.0, 60.0, -210.0, 15.0, 10.0),
            // QML :92-93 range, :94-95 angles (INVERTED), :96 labelStep
            VescClusterSlot.DUTY to Row(-100.0, 100.0, 210.0, -15.0, 25.0),
            // QML :108-109 range, no angles declared -> CustomGauge.qml :35-36 defaults,
            // :112 labelStep (max 10000 > 6000 -> 2000)
            VescClusterSlot.POWER to Row(-10000.0, 10000.0, -140.0, 140.0, 2000.0),
            // QML :303-310 range/angles; no labelStep -> CustomGauge.qml :31 default 10
            VescClusterSlot.BATTERY to Row(0.0, 100.0, -225.0, 45.0, 10.0),
            // QML :441-442 range, :459-460 angles, :444 labelStep
            VescClusterSlot.ESC_TEMP to Row(0.0, 100.0, -195.0, 30.0, 20.0),
            // QML :467-468 range, :469-470 angles (INVERTED), :471 labelStep
            VescClusterSlot.MOTOR_TEMP to Row(0.0, 100.0, 195.0, -30.0, 20.0),
            // QML :494-495 range, :496-497 angles, :498 labelStep (max 50, not > 60 -> 10)
            VescClusterSlot.CONSUMPTION to Row(-50.0, 50.0, -127.0, 127.0, 10.0)
        )

        expected.forEach { (slot, row) ->
            val range = spec(slot).range
            assertEquals(row.min, range.minimumValue, 1e-9, "$slot minimumValue")
            assertEquals(row.max, range.maximumValue, 1e-9, "$slot maximumValue")
            assertEquals(row.minAngle, range.minAngle, 1e-9, "$slot minAngle")
            assertEquals(row.maxAngle, range.maxAngle, 1e-9, "$slot maxAngle")
            assertEquals(row.step, range.labelStep, 1e-9, "$slot labelStep")
        }

        // Speed's range is the one runtime scale (see the hero tests below); only its ANGLES are
        // fixed by the QML (:137-138).
        val speed = spec(VescClusterSlot.SPEED).range
        assertEquals(-225.0, speed.minAngle, 1e-9)
        assertEquals(45.0, speed.maxAngle, 1e-9)
        assertEquals(0.0, speed.minimumValue, 1e-9)
    }

    /**
     * QML :94-95 (Duty) and :469-470 (Motor temp) declare `minAngle > maxAngle`, so their scales
     * literally run counter-clockwise. Nothing else in the cluster does.
     */
    @Test fun only_duty_and_motor_temp_sweep_backwards() {
        val inverted = VescClusterSlot.entries.filter { spec(it).range.isInverted }.toSet()
        assertEquals(setOf(VescClusterSlot.DUTY, VescClusterSlot.MOTOR_TEMP), inverted)
    }

    @Test fun the_captions_and_units_match_the_QML() {
        assertEquals("A", spec(VescClusterSlot.CURRENT).unit)          // QML :82
        assertEquals("%", spec(VescClusterSlot.DUTY).unit)             // QML :98
        assertEquals("W", spec(VescClusterSlot.POWER).unit)            // QML :114
        assertEquals("km/h", spec(VescClusterSlot.SPEED).unit)         // QML :141
        assertEquals("mph", spec(VescClusterSlot.SPEED, UnitSystem.IMPERIAL).unit)
        assertEquals("°C", spec(VescClusterSlot.ESC_TEMP).unit)        // QML :457
        assertEquals("°C", spec(VescClusterSlot.MOTOR_TEMP).unit)      // QML :473
        assertEquals("Wh/km", spec(VescClusterSlot.CONSUMPTION).unit)  // QML :500
        assertEquals("Wh/mi", spec(VescClusterSlot.CONSUMPTION, UnitSystem.IMPERIAL).unit)
    }

    /**
     * QML :312 — the Battery gauge is the only one that hides `CustomGauge`'s own centre stack,
     * because :317-361 overlay four `Text` items of their own in its place.
     */
    @Test fun only_battery_hides_the_built_in_centre_text() {
        val hidden = VescClusterSlot.entries.filter { !spec(it).centerTextVisible }.toSet()
        assertEquals(setOf(VescClusterSlot.BATTERY), hidden)
    }

    /**
     * QML :110-111: the Power dial reads 0..10000 W but must be LABELLED 0..10k, so its tick
     * numbers are scaled by 0.001 and suffixed "k". Asserted through the actual tick values the
     * renderer prints rather than by reading the two properties back.
     */
    @Test fun power_labels_its_ten_kilowatt_scale_in_k() {
        val power = spec(VescClusterSlot.POWER)
        assertEquals(0.001, power.tickmarkScale, 1e-12)
        assertEquals("k", power.tickmarkSuffix)
        val printed = (0 until power.range.tickmarkCount).map { i ->
            (power.range.tickmarkValueFromIndex(i) * power.tickmarkScale).roundToInt().toString() + power.tickmarkSuffix
        }
        assertEquals(
            listOf("-10k", "-8k", "-6k", "-4k", "-2k", "0k", "2k", "4k", "6k", "8k", "10k"),
            printed
        )
    }

    @Test fun no_other_gauge_rescales_its_tick_numbers() {
        VescClusterSlot.entries.filter { it != VescClusterSlot.POWER }.forEach { slot ->
            assertEquals(1.0, spec(slot).tickmarkScale, 1e-12, "$slot tickmarkScale")
            assertEquals("", spec(slot).tickmarkSuffix, "$slot tickmarkSuffix")
        }
    }

    // --- the hero's runtime scale (carried forward from the renderer this replaces) ------------

    @Test fun the_hero_is_speed_and_honours_the_unit_setting() {
        assertEquals(47f, spec(VescClusterSlot.SPEED).value, 0.01f)
        assertEquals(29.204f, spec(VescClusterSlot.SPEED, UnitSystem.IMPERIAL).value, 0.01f)
        assertEquals("mph", spec(VescClusterSlot.SPEED, UnitSystem.IMPERIAL).unit)
    }

    /**
     * Regression for the shipped defect: `valueText`/`unit` went through the unit conversion but
     * the SCALE stayed in raw km/h, so an imperial rider read an "mph" number over a km/h-labelled
     * ring. Both the needle's own value AND the ring it moves against must convert together.
     */
    @Test fun the_hero_scale_converts_to_the_same_unit_as_the_readout() {
        val metric = spec(VescClusterSlot.SPEED).range
        assertEquals(70.0, metric.maximumValue, 1e-6)

        val imperial = spec(VescClusterSlot.SPEED, UnitSystem.IMPERIAL).range
        // 70 km/h = 43.496 mph, snapped UP to the next round display number (50) so a round
        // labelStep divides it exactly. The snap moves the ceiling, never the reading.
        assertEquals(50.0, imperial.maximumValue, 1e-6)
        assertTrue(imperial.maximumValue >= 43.49, "the scale must never shrink below the real max")
    }

    /**
     * The hero is the only dial with a runtime max, so it is the only one whose tick labels can
     * stop being round. The failure mode is subtle: `VescGaugeRange` spaces its majors by
     * `(max - min) / (tickmarkCount - 1)`, NOT by `labelStep` — so a labelStep that does not
     * divide the span exactly silently produces a ragged ring (a 70-max dial with labelStep 20
     * gives tickmarkCount 4 and labels 0, 23, 47, 70). Asserted on the printed values themselves.
     */
    @Test fun the_hero_tick_labels_stay_round_in_metric() {
        assertRoundTicks(UnitSystem.METRIC, listOf(70f, 80f, 100f, 120f, 150f))
    }

    /** Imperial's half of the same coverage — the unit where the km/h snap does NOT survive. */
    @Test fun the_hero_tick_labels_stay_round_in_imperial_too() {
        assertRoundTicks(UnitSystem.IMPERIAL, listOf(70f, 80f, 100f, 120f, 150f))
    }

    private fun assertRoundTicks(units: UnitSystem, maxima: List<Float>) {
        for (maxKmh in maxima) {
            val range = specs(u = units, maxKmh = maxKmh).getValue(VescClusterSlot.SPEED).range
            val values = (0 until range.tickmarkCount).map { range.tickmarkValueFromIndex(it) }
            assertTrue(values.size >= 4, "$units $maxKmh: only ${values.size} tick labels")
            // 1. every printed label is a whole number, not a rounded-off fraction
            values.forEach { v ->
                assertTrue(
                    abs(v - v.roundToInt()) < 1e-6,
                    "$units maxKmh=$maxKmh: tick $v is not a whole number (max=${range.maximumValue})"
                )
            }
            // 2. and consecutive labels step by the SAME whole number
            val steps = values.map { it.roundToInt() }.zipWithNext { a, b -> b - a }.distinct()
            assertEquals(1, steps.size, "$units maxKmh=$maxKmh: uneven tick steps $steps")
            // 3. the scale still covers the speed it was built for
            assertTrue(range.maximumValue + 1e-6 >= ClassicDialSpecs.heroDisplayMax(maxKmh, units))
        }
    }

    /** The km/h max is round by construction upstream; the snap must leave it alone. */
    @Test fun an_already_round_metric_max_is_not_snapped_upward() {
        listOf(70f, 80f, 100f, 120f).forEach { maxKmh ->
            assertEquals(
                maxKmh.toDouble(),
                specs(maxKmh = maxKmh).getValue(VescClusterSlot.SPEED).range.maximumValue,
                1e-6,
                "metric max $maxKmh was moved"
            )
        }
    }

    @Test fun the_hero_scale_never_collapses_to_zero() {
        val range = specs(maxKmh = 0f).getValue(VescClusterSlot.SPEED).range
        assertTrue(range.maximumValue > range.minimumValue)
        assertTrue(range.hasTickmarks)
    }

    // --- Current and Power's session auto-scale (§14's "Now" divergence from the port) ---------
    //
    // VESC derives these two ranges from the connected controller's motor config (`l_current_max`,
    // `l_watt_max`) times `num_vescs`; Volty cannot read that yet (needs COMM_GET_MCCONF, deferred
    // to Part C — B-vesc-dashboard.md §14), so it grows the scale from the largest ABSOLUTE reading
    // the session has actually shown instead, floored at VESC's own defaults so a quiet ride still
    // looks like the familiar port.

    /** A quiet ride (no args -> `0f`) must look exactly like the un-scaled port always has. */
    @Test fun current_and_power_floor_at_VESCs_own_defaults_when_nothing_has_been_seen() {
        val current = spec(VescClusterSlot.CURRENT).range
        assertEquals(-60.0, current.minimumValue, 1e-9)
        assertEquals(60.0, current.maximumValue, 1e-9)
        assertEquals(10.0, current.labelStep, 1e-9)

        val power = spec(VescClusterSlot.POWER).range
        assertEquals(-10000.0, power.minimumValue, 1e-9)
        assertEquals(10000.0, power.maximumValue, 1e-9)
        assertEquals(2000.0, power.labelStep, 1e-9)
    }

    /** A session reading well under the floor must not shrink the dial below it. */
    @Test fun current_and_power_never_shrink_below_VESCs_floor_for_a_small_session_reading() {
        val current = specs(maxCurrentA = 5f).getValue(VescClusterSlot.CURRENT).range
        assertEquals(60.0, current.maximumValue, 1e-9)

        val power = specs(maxPowerW = 500f).getValue(VescClusterSlot.POWER).range
        assertEquals(10000.0, power.maximumValue, 1e-9)
    }

    /**
     * The product owner's actual scooter: two uBox 250 A controllers. A session peak north of the
     * 60 A / 10000 W floor must grow the dial to cover it, not peg at the floor and stay there.
     */
    @Test fun current_and_power_grow_to_cover_a_reading_the_floor_does_not_reach() {
        val current = specs(maxCurrentA = 251f).getValue(VescClusterSlot.CURRENT).range
        assertEquals(260.0, current.maximumValue, 1e-9) // ceil(251/10)*10
        assertTrue(current.maximumValue >= 251.0, "the scale must cover the real session peak")

        val power = specs(maxPowerW = 40200f).getValue(VescClusterSlot.POWER).range
        assertEquals(41000.0, power.maximumValue, 1e-9) // ceil(40200/1000)*1000
        assertTrue(power.maximumValue >= 40200.0)
    }

    /** Both dials are bipolar: whatever the session grows the max to, the range stays symmetric. */
    @Test fun current_and_power_stay_symmetric_around_zero_at_every_auto_scaled_maximum() {
        listOf(0f, 55f, 251f, 505f).forEach { maxA ->
            val range = specs(maxCurrentA = maxA).getValue(VescClusterSlot.CURRENT).range
            assertEquals(-range.maximumValue, range.minimumValue, 1e-9, "current maxA=$maxA")
        }
        listOf(0f, 9500f, 40200f).forEach { maxW ->
            val range = specs(maxPowerW = maxW).getValue(VescClusterSlot.POWER).range
            assertEquals(-range.maximumValue, range.minimumValue, 1e-9, "power maxW=$maxW")
        }
    }

    /**
     * The real discriminating check: [tenOrTwentyLabelStep]/[powerLabelStep]'s chosen step must
     * divide the (symmetric) span `max - min` exactly at every auto-scaled maximum this session can
     * produce, or the ring goes ragged — the same shipped-twice defect `assertRoundTicks` above
     * guards for the hero. None of the maxima below are already round multiples of the label step
     * on their own, which is the point: the snapping in `currentDisplayMax`/`powerDisplayMax` is
     * what has to make them round, not luck.
     */
    @Test fun current_tick_labels_stay_round_at_several_auto_scaled_maxima() {
        listOf(0f, 5f, 55f, 65f, 251f, 505f).forEach { maxA ->
            assertRoundTickLabels(specs(maxCurrentA = maxA).getValue(VescClusterSlot.CURRENT).range, "current maxA=$maxA")
        }
    }

    @Test fun power_tick_labels_stay_round_at_several_auto_scaled_maxima() {
        listOf(0f, 500f, 9500f, 12500f, 40200f).forEach { maxW ->
            assertRoundTickLabels(specs(maxPowerW = maxW).getValue(VescClusterSlot.POWER).range, "power maxW=$maxW")
        }
    }

    /** Feeding a growing sequence of session peaks must never produce a smaller display max. */
    @Test fun the_current_and_power_maxima_never_decrease_as_the_session_reading_grows() {
        val currentMaxima = listOf(10f, 55f, 65f, 200f, 251f, 505f).map { ClassicDialSpecs.currentDisplayMax(it) }
        assertEquals(currentMaxima, currentMaxima.sorted())
        val powerMaxima = listOf(1000f, 9500f, 12500f, 40200f).map { ClassicDialSpecs.powerDisplayMax(it) }
        assertEquals(powerMaxima, powerMaxima.sorted())
    }

    /** Shared by the two round-label tests above: whole-number labels, one consistent step. */
    private fun assertRoundTickLabels(range: VescGaugeRange, context: String) {
        val values = (0 until range.tickmarkCount).map { range.tickmarkValueFromIndex(it) }
        assertTrue(values.size >= 4, "$context: only ${values.size} tick labels")
        values.forEach { v ->
            assertTrue(
                abs(v - v.roundToInt()) < 1e-6,
                "$context: tick $v is not a whole number (max=${range.maximumValue})"
            )
        }
        val steps = values.map { it.roundToInt() }.zipWithNext { a, b -> b - a }.distinct()
        assertEquals(1, steps.size, "$context: uneven tick steps $steps")
    }

    // --- unknown readings are never drawn as zero ----------------------------------------------

    @Test fun an_unknown_speed_reads_as_a_dash_and_rests_at_the_scale_minimum() {
        val stopped = motion.copy(speedSource = SpeedSource.NONE)
        val hero = specs(m = stopped).getValue(VescClusterSlot.SPEED)
        assertEquals("—", hero.valueTextOverride)
        assertEquals(hero.range.minimumValue.toFloat(), hero.value, 1e-6f)
    }

    @Test fun temperatures_dash_when_no_sensor_reports() {
        val noMotor = motion.copy(hasMotorTemp = false)
        assertEquals("—", specs(m = noMotor).getValue(VescClusterSlot.MOTOR_TEMP).valueTextOverride)
        val noEsc = motion.copy(escTempC = -99f)
        assertEquals("—", specs(m = noEsc).getValue(VescClusterSlot.ESC_TEMP).valueTextOverride)
        // ...and a sensor that IS reporting prints its own number, not a dash.
        assertNull(spec(VescClusterSlot.MOTOR_TEMP).valueTextOverride)
        assertNull(spec(VescClusterSlot.ESC_TEMP).valueTextOverride)
    }

    @Test fun consumption_dashes_while_standing_still() {
        val stopped = motion.copy(speedKmh = 0f, consumedWh = 0f, tripKm = 0f)
        assertEquals("—", specs(m = stopped).getValue(VescClusterSlot.CONSUMPTION).valueTextOverride)
    }

    @Test fun battery_dashes_when_the_state_of_charge_is_unknown() {
        val unknown = battery.copy(socKnown = false)
        assertEquals("—", specs(b = unknown).getValue(VescClusterSlot.BATTERY).valueTextOverride)
        assertEquals("84%", spec(VescClusterSlot.BATTERY).valueTextOverride)
    }

    @Test fun consumption_converts_to_watt_hours_per_mile_for_an_imperial_rider() {
        val metric = spec(VescClusterSlot.CONSUMPTION).value
        val imperial = spec(VescClusterSlot.CONSUMPTION, UnitSystem.IMPERIAL).value
        assertTrue(metric > 0f, "fixture should be consuming something")
        assertEquals(metric * 1.609344f, imperial, 0.01f)
    }

    // --- nibColor severity: whose thresholds won ------------------------------------------------

    /**
     * VESC gives Duty a FIXED accent (QML :100, `tertiary3`) — it has no duty threshold at all.
     * This project does, in [ru.sodovaya.volty.domain.stats.DutyBands], and it is shared with the
     * Clean renderer and Part F's audible alarm, so OURS wins: 75 warns, 90 is critical.
     */
    @Test fun duty_carries_our_own_DutyBands_severity_not_VESCs_fixed_accent() {
        assertEquals(DutyLevel.NORMAL, specs(m = motion.copy(dutyPercent = 40f)).getValue(VescClusterSlot.DUTY).severity)
        assertEquals(DutyLevel.WARN, specs(m = motion.copy(dutyPercent = 76f)).getValue(VescClusterSlot.DUTY).severity)
        assertEquals(DutyLevel.CRITICAL, specs(m = motion.copy(dutyPercent = 95f)).getValue(VescClusterSlot.DUTY).severity)
    }

    /**
     * VESC's temp nibs go orange above 40 °C and red above 70 °C (QML :449, :479). Ours are
     * [ru.sodovaya.volty.domain.stats.TempBands] — ESC 70/85, motor 85/100 — and OURS win, because
     * the same numbers drive the Clean cluster's severity dot and Part F's alarm. A dial that went
     * red at 70 °C while the alarm stayed silent until 85 is exactly the drift TempBands prevents.
     */
    @Test fun temperatures_carry_our_own_TempBands_not_VESCs_forty_and_seventy() {
        // 50 °C: orange under VESC's rule, still normal under ours.
        assertEquals(DutyLevel.NORMAL, specs(m = motion.copy(escTempC = 50f)).getValue(VescClusterSlot.ESC_TEMP).severity)
        assertEquals(DutyLevel.WARN, specs(m = motion.copy(escTempC = 72f)).getValue(VescClusterSlot.ESC_TEMP).severity)
        assertEquals(DutyLevel.CRITICAL, specs(m = motion.copy(escTempC = 90f)).getValue(VescClusterSlot.ESC_TEMP).severity)

        // 75 °C on the motor: RED under VESC's rule, still normal under ours (motor warns at 85).
        val warmMotor = motion.copy(motorTempC = 75f, hasMotorTemp = true)
        assertEquals(DutyLevel.NORMAL, specs(m = warmMotor).getValue(VescClusterSlot.MOTOR_TEMP).severity)
        val hotMotor = motion.copy(motorTempC = 105f, hasMotorTemp = true)
        assertEquals(DutyLevel.CRITICAL, specs(m = hotMotor).getValue(VescClusterSlot.MOTOR_TEMP).severity)
    }

    /** A motor with no sensor is never alarming, whatever the stale field holds. */
    @Test fun an_absent_motor_sensor_never_colours_the_nib() {
        val noSensor = motion.copy(motorTempC = 200f, hasMotorTemp = false)
        assertEquals(DutyLevel.NORMAL, specs(m = noSensor).getValue(VescClusterSlot.MOTOR_TEMP).severity)
    }

    /**
     * QML :505 — VESC's own 25/45 thresholds, kept because this project has no shared consumption
     * band for them to disagree with. Applied to the CANONICAL Wh/km, never the display value:
     * VESC compares against its display-unit reading, which makes an imperial rider's dial go red
     * at 45 Wh/mi (= 28 Wh/km). A severity colour that moves when the units toggle is a defect.
     */
    @Test fun consumption_uses_VESCs_thresholds_on_the_canonical_wh_per_km() {
        fun at(whPerKm: Float, u: UnitSystem): DutyLevel {
            // powerW / speedKmh == Wh/km, so this pins an exact canonical consumption.
            val m = motion.copy(speedKmh = 10f, speedSource = SpeedSource.REPORTED, powerW = whPerKm * 10f)
            return specs(m = m, u = u).getValue(VescClusterSlot.CONSUMPTION).severity
        }
        assertEquals(DutyLevel.NORMAL, at(20f, UnitSystem.METRIC))
        assertEquals(DutyLevel.WARN, at(30f, UnitSystem.METRIC))
        assertEquals(DutyLevel.CRITICAL, at(48f, UnitSystem.METRIC))
        // The same physical consumption reads the same colour in either unit system.
        listOf(20f, 30f, 48f).forEach { wh ->
            assertEquals(at(wh, UnitSystem.METRIC), at(wh, UnitSystem.IMPERIAL), "$wh Wh/km changed colour with units")
        }
    }

    /** QML :316: `value > 50 ? green : value > 20 ? orange : red` — VESC's, inverted vs the rest. */
    @Test fun battery_is_green_above_fifty_amber_above_twenty_and_red_below() {
        fun at(soc: Float) = specs(b = battery.copy(soc = soc, socKnown = true))
            .getValue(VescClusterSlot.BATTERY).severity
        assertEquals(DutyLevel.NORMAL, at(84f))
        assertEquals(DutyLevel.NORMAL, at(50.1f))
        assertEquals(DutyLevel.WARN, at(50f))   // strict `>`, per the QML
        assertEquals(DutyLevel.WARN, at(21f))
        assertEquals(DutyLevel.CRITICAL, at(20f))
        assertEquals(DutyLevel.CRITICAL, at(3f))
    }

    @Test fun an_unknown_state_of_charge_never_reads_as_an_empty_battery() {
        val unknown = battery.copy(soc = 0f, socKnown = false)
        assertEquals(DutyLevel.NORMAL, specs(b = unknown).getValue(VescClusterSlot.BATTERY).severity)
    }

    /**
     * QML :81, :116 and CustomGauge.qml :49 give Current, Power and Speed fixed accents with no
     * threshold behind them. Nothing may colour them, at any reading — severity colour is spent
     * only where it means something.
     */
    @Test fun current_power_and_speed_never_carry_severity() {
        val extreme = motion.copy(batteryCurrentA = 400f, powerW = 30000f, speedKmh = 300f)
        listOf(VescClusterSlot.CURRENT, VescClusterSlot.POWER, VescClusterSlot.SPEED).forEach { slot ->
            assertEquals(DutyLevel.NORMAL, specs(m = extreme).getValue(slot).severity, "$slot")
        }
    }

    // --- localized dial faces -------------------------------------------------------------------

    @Test fun each_supplied_label_lands_on_its_own_slot() {
        val labels = ClassicDialLabels(
            current = "TOK", power = "MOSHCH", duty = "SHIM", speed = "SKOR",
            battery = "BAT", esc = "ESK", consumption = "RASHOD", motor = "MOTR"
        )
        val built = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, 70f, labels).associateBy { it.slot }
        assertEquals("TOK", built.getValue(VescClusterSlot.CURRENT).caption)
        assertEquals("MOSHCH", built.getValue(VescClusterSlot.POWER).caption)
        assertEquals("SHIM", built.getValue(VescClusterSlot.DUTY).caption)
        assertEquals("SKOR", built.getValue(VescClusterSlot.SPEED).caption)
        assertEquals("BAT", built.getValue(VescClusterSlot.BATTERY).caption)
        assertEquals("ESK", built.getValue(VescClusterSlot.ESC_TEMP).caption)
        assertEquals("RASHOD", built.getValue(VescClusterSlot.CONSUMPTION).caption)
        assertEquals("MOTR", built.getValue(VescClusterSlot.MOTOR_TEMP).caption)
    }

    /**
     * QML :458 and :474 write their temperature captions as two lines (`"TEMP\nESC"`) because one
     * long line would run out past the number ring. Our localized strings arrive as one phrase
     * ("ESC temp", "Темп. мотора"), so the same split is applied to them — at the LAST space, so a
     * three-word label keeps its head together on the first line.
     */
    @Test fun a_multi_word_temperature_caption_is_split_onto_two_lines_like_the_QML() {
        val labels = ClassicDialLabels(esc = "ESC TEMP", motor = "ТЕМП. МОТОРА")
        val built = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, 70f, labels).associateBy { it.slot }
        assertEquals("ESC\nTEMP", built.getValue(VescClusterSlot.ESC_TEMP).caption)
        assertEquals("ТЕМП.\nМОТОРА", built.getValue(VescClusterSlot.MOTOR_TEMP).caption)
    }

    @Test fun a_single_word_caption_is_left_alone() {
        val labels = ClassicDialLabels(esc = "ESC", motor = "МОТОР")
        val built = ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, 70f, labels).associateBy { it.slot }
        assertEquals("ESC", built.getValue(VescClusterSlot.ESC_TEMP).caption)
        assertEquals("МОТОР", built.getValue(VescClusterSlot.MOTOR_TEMP).caption)
        assertFalse(built.getValue(VescClusterSlot.CONSUMPTION).caption.contains('\n'))
    }
}
