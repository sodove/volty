package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.stats.DutyBands
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The test that spans the three renderers.** `G §9`'s deliverable is a
 * contract, not three patches, and a contract with three implementers is only
 * a contract if something checks that they agree — Part G2 Task 5 shipped a
 * button that could never succeed because two layers were each pinned by their
 * own tests and no fixture spanned both.
 *
 * The three implementers are Classic's dial cluster ([ClassicDialSpecs]),
 * Clean's metric cards ([CleanMetricMapper]) and the hero's secondary gauge
 * ([SecondaryGaugeMapper]). Every test below asks all of them about the SAME
 * sample.
 *
 * Both directions are asserted throughout: an unobserved value must dash, and a
 * genuinely measured zero must still print a number. A renderer that dashed
 * everything, or one that dashed nothing, fails here.
 */
class UnknownMotionRenderingTest {

    private val battery = BmsData(voltage = 78f, soc = 61f, socKnown = true, isConnected = true)

    /**
     * A Begode at speed with no cell count and an open truePWM latch: moving,
     * so nothing is hidden behind the standing-still guard, and every one of the
     * three §9 quantities is a placeholder rather than a reading.
     */
    private val wheel = ControllerData(
        speedKmh = 34f,
        speedSource = SpeedSource.REPORTED,
        dutyPercent = 0f, hasDuty = false,
        batteryCurrentA = 6.2f,
        inputVoltageV = 0f, hasInputVoltage = false,
        powerW = 0f, hasPower = false,
        escTempC = 44f,
        motorTempC = 51f, hasMotorTemp = true,
        odometerKm = 8565f,
        tripKm = 12.75f,
        consumedWh = 0f, hasEnergyCounters = false,
        isConnected = true
    )

    /**
     * The same ride on hardware that measures all three — and measures them as
     * ZERO. This is the reading the contract must never hide: a wheel coasting
     * at 0 % duty with regen balancing the draw.
     */
    private val measuredZero = wheel.copy(
        hasDuty = true,
        inputVoltageV = 0f, hasInputVoltage = true,
        powerW = 0f, hasPower = true,
        consumedWh = 0f, hasEnergyCounters = true
    )

    private fun classic(motion: ControllerData, slot: VescClusterSlot) =
        ClassicDialSpecs.build(motion, battery, UnitSystem.METRIC, maxSpeedKmh = 70f)
            .single { it.slot == slot }

    private fun secondary(motion: ControllerData, gauge: SecondaryGauge) =
        SecondaryGaugeMapper.map(gauge, motion, battery, UnitSystem.METRIC)

    // -----------------------------------------------------------------------------
    // The marker itself
    // -----------------------------------------------------------------------------

    @Test fun the_unknown_marker_is_an_em_dash_and_nothing_else() {
        // Pinned at its own value, not merely "not a digit": the rule rests on this
        // string being one specific glyph that no formatter can produce, and every
        // renderer below compares against it rather than against a literal.
        assertEquals("—", UNKNOWN_READOUT)
        assertNotEquals("", UNKNOWN_READOUT, "a blank reads as a rendering bug, not a statement")
        assertNotEquals("0", UNKNOWN_READOUT)
    }

    @Test fun the_shared_helper_dashes_only_a_null() {
        assertEquals(UNKNOWN_READOUT, (null as Float?).readoutOr { "x" })
        assertEquals("0.0", 0f.readoutOr { "0.0" })
    }

    // -----------------------------------------------------------------------------
    // Duty — §9's first instance
    // -----------------------------------------------------------------------------

    @Test fun every_renderer_dashes_a_duty_no_firmware_has_ever_reported() {
        assertNull(MotionReadings.dutyPercent(wheel))

        val dial = classic(wheel, VescClusterSlot.DUTY)
        assertEquals(UNKNOWN_READOUT, dial.valueTextOverride, "Classic's duty dial")
        assertEquals(0f, dial.value, "…with the needle parked, as the temperature dials do")
        assertEquals(DutyLevel.NORMAL, dial.severity, "an unobserved duty gets no severity band")

        assertEquals(UNKNOWN_READOUT, secondary(wheel, SecondaryGauge.DUTY).value, "the secondary gauge")
        assertEquals(0f, secondary(wheel, SecondaryGauge.DUTY).fraction)
        assertEquals(DutyLevel.NORMAL, secondary(wheel, SecondaryGauge.DUTY).severity)
    }

    @Test fun every_renderer_prints_a_duty_that_was_actually_measured_as_zero() {
        val dial = classic(measuredZero, VescClusterSlot.DUTY)
        assertNull(dial.valueTextOverride, "Classic must print the number, not the dash")
        assertEquals(0f, dial.value)

        assertEquals("0", secondary(measuredZero, SecondaryGauge.DUTY).value)
    }

    @Test fun an_unobserved_duty_gets_no_severity_band_however_high_the_placeholder_reads() {
        // The number is 92 % and the flag says nobody measured it — the HAZARD
        // MotionAlertAvailability's DUTY branch already documents for a decoder that
        // writes into a field its protocol does not report. Without this fixture the
        // "unknown ⇒ NORMAL" assertions are unfalsifiable: DutyBands.level(0f) is
        // NORMAL too, so the naive `level(motion.dutyPercent)` passes them.
        val bogus = wheel.copy(dutyPercent = 92f, hasDuty = false)
        assertEquals(DutyLevel.CRITICAL, DutyBands.level(92f), "precondition")

        val dial = classic(bogus, VescClusterSlot.DUTY)
        assertEquals(DutyLevel.NORMAL, dial.severity, "Classic must not redden a needle nobody measured")
        assertEquals(UNKNOWN_READOUT, dial.valueTextOverride)
        assertEquals(0f, dial.value, "…nor swing it to 92 %")

        val gauge = secondary(bogus, SecondaryGauge.DUTY)
        assertEquals(DutyLevel.NORMAL, gauge.severity, "the secondary ring, likewise")
        assertEquals(UNKNOWN_READOUT, gauge.value)
        assertEquals(0f, gauge.fraction, "…and its ring stays empty rather than 92 % full")
    }

    @Test fun a_high_duty_still_colours_the_needle_on_both_renderers() {
        // The band must survive the null-plumbing: a contract that quietly dropped
        // DutyBands would pass every dash assertion in this file.
        val pushing = measuredZero.copy(dutyPercent = 92f)
        assertEquals(DutyLevel.CRITICAL, classic(pushing, VescClusterSlot.DUTY).severity)
        assertEquals(DutyLevel.CRITICAL, secondary(pushing, SecondaryGauge.DUTY).severity)
    }

    // -----------------------------------------------------------------------------
    // Power — §9's second instance
    // -----------------------------------------------------------------------------

    @Test fun every_renderer_dashes_a_power_with_no_voltage_scale_behind_it() {
        assertNull(MotionReadings.powerW(wheel))

        val dial = classic(wheel, VescClusterSlot.POWER)
        assertEquals(UNKNOWN_READOUT, dial.valueTextOverride, "Classic's power dial")
        assertEquals(0f, dial.value)

        assertEquals(UNKNOWN_READOUT, secondary(wheel, SecondaryGauge.POWER).value, "the secondary gauge")
        assertEquals(0f, secondary(wheel, SecondaryGauge.POWER).fraction)

        assertEquals(UNKNOWN_READOUT, CleanMetricMapper.powerValue(wheel), "Clean's POWER card")
    }

    @Test fun an_unobserved_power_parks_the_needle_however_high_the_placeholder_reads() {
        // The twin of the duty case above, and it exists because the first sweep of
        // this task found the needle assertion UNFALSIFIABLE without it: every
        // producer that sets hasPower false also writes powerW = 0f, so
        // `value = power ?: 0f` and `value = motion.powerW` are the same number on a
        // realistic fixture. The contract must not lean on that producer invariant —
        // it is the flag, not the value, that says whether we measured anything.
        val bogus = wheel.copy(powerW = 4200f, hasPower = false)

        val dial = classic(bogus, VescClusterSlot.POWER)
        assertEquals(0f, dial.value, "the needle reads the contract, not the placeholder")
        assertEquals(UNKNOWN_READOUT, dial.valueTextOverride)

        val gauge = secondary(bogus, SecondaryGauge.POWER)
        assertEquals(UNKNOWN_READOUT, gauge.value)
        assertEquals(0f, gauge.fraction, "…and the ring stays empty rather than half full")

        assertEquals(UNKNOWN_READOUT, CleanMetricMapper.powerValue(bogus))
        assertEquals(UNKNOWN_READOUT, CleanMetricMapper.instantConsumptionValue(bogus))
    }

    @Test fun clean_keeps_the_current_sub_line_when_the_power_is_unknown() {
        // The missing cell count costs the volts, not the amps — a card that went
        // wordless would be a worse answer than the confident zero it replaces.
        assertEquals("+6.2 A", CleanMetricMapper.powerSub(wheel))
    }

    @Test fun every_renderer_prints_a_power_that_was_actually_measured_as_zero() {
        assertNull(classic(measuredZero, VescClusterSlot.POWER).valueTextOverride)
        assertEquals("0.0", secondary(measuredZero, SecondaryGauge.POWER).value)
        assertEquals("0.0 kW", CleanMetricMapper.powerValue(measuredZero))
    }

    @Test fun a_real_power_reading_reaches_every_renderer_unchanged() {
        val hard = measuredZero.copy(inputVoltageV = 78f, powerW = 4200f)
        assertEquals(4200f, classic(hard, VescClusterSlot.POWER).value)
        assertEquals("4.2", secondary(hard, SecondaryGauge.POWER).value)
        assertEquals("4.2 kW", CleanMetricMapper.powerValue(hard))
    }

    // -----------------------------------------------------------------------------
    // Consumption — §9.1's instance
    // -----------------------------------------------------------------------------

    @Test fun every_renderer_dashes_the_consumption_of_a_counterless_wheel_that_has_moved() {
        // tripKm is 12.75, so the old session fallback answered a well-formed 0.0 —
        // and Classic's `—` override existed but was never reached.
        assertTrue(wheel.tripKm > 0f, "the trip counter has moved; the old fallback was live")
        assertNull(MotionReadings.whPerKm(wheel))

        val dial = classic(wheel, VescClusterSlot.CONSUMPTION)
        assertEquals(UNKNOWN_READOUT, dial.valueTextOverride, "Classic's consumption dial")
        assertEquals(0f, dial.value)
        assertEquals(DutyLevel.NORMAL, dial.severity)

        assertEquals(UNKNOWN_READOUT, secondary(wheel, SecondaryGauge.CONSUMPTION).value)
        assertEquals(UNKNOWN_READOUT, CleanMetricMapper.instantConsumptionValue(wheel))
        assertNull(
            CleanMetricMapper.sessionConsumptionValue(
                MotionReadings.sessionWhPerKm(wheel), synthesised = false
            ),
            "Clean's `avg … Wh/km` chip is hidden rather than dashed — see its doc"
        )
    }

    @Test fun a_measured_consumption_reaches_every_renderer() {
        val vesc = measuredZero.copy(inputVoltageV = 78f, powerW = 3400f, consumedWh = 255f)
        val expected = 100f // 3400 W / 34 km/h
        assertEquals(expected, MotionReadings.whPerKm(vesc))
        assertEquals(expected, classic(vesc, VescClusterSlot.CONSUMPTION).value)
        assertNull(classic(vesc, VescClusterSlot.CONSUMPTION).valueTextOverride)
        assertEquals("100.0", secondary(vesc, SecondaryGauge.CONSUMPTION).value)
        assertEquals("100.0", CleanMetricMapper.instantConsumptionValue(vesc))
        assertEquals(
            "20.0",
            CleanMetricMapper.sessionConsumptionValue(
                MotionReadings.sessionWhPerKm(vesc), synthesised = false
            )
        )
    }

    // -----------------------------------------------------------------------------
    // Speed — Part I Task 6's instance: the controller nobody measured a wheel for
    // -----------------------------------------------------------------------------

    /**
     * **Deliberately incoherent**: 42 km/h in the field, [SpeedSource.NONE] beside
     * it. No producer emits that pair — `VescValues.decodeValues` writes
     * `speedKmh = derived ?: 0f`, so every real unknown-speed sample carries a
     * `0f` — and that is precisely why the fixture has to. On a realistic sample
     * `speed ?: 0f` and `motion.speedKmh` are the same number, so every assertion
     * below would pass against the unguarded `motion.speedKmh / vehicleMaxSpeed`
     * this task replaced. The contract is about the FLAG, not about a producer's
     * habit of zeroing the field beside it.
     */
    private val unmeasuredWheel = wheel.copy(speedKmh = 42f, speedSource = SpeedSource.NONE)

    @Test fun a_controller_with_no_wheel_diameter_reports_its_speed_as_unknown() {
        assertFalse(unmeasuredWheel.speedKnown, "SpeedSource.NONE is the whole of `not observed`")
        assertNull(MotionReadings.speedKmh(unmeasuredWheel))
        // The producer's own path to that state, so the contract is anchored to a
        // real decoder outcome and not only to a hand-built sample: an unconfigured
        // wheel makes the eRPM fallback unavailable.
        assertNull(VescValues.derivedSpeedKmh(3000f, MotorConfig(wheelDiameterMm = 0)))
    }

    @Test fun every_renderer_dashes_the_speed_of_a_controller_with_no_wheel_diameter() {
        val dial = classic(unmeasuredWheel, VescClusterSlot.SPEED)
        assertEquals(UNKNOWN_READOUT, dial.valueTextOverride, "Classic's speed dial")
        assertEquals(0f, dial.value, "…with the needle parked rather than swung to 42")

        assertEquals(
            UNKNOWN_READOUT,
            CleanMetricMapper.heroSpeedValue(unmeasuredWheel, UnitSystem.METRIC),
            "the Clean hero's readout"
        )
        assertEquals(
            0f,
            CleanMetricMapper.heroSpeedFraction(unmeasuredWheel, 70f),
            "…and its ring stays empty rather than 60 % full beside that dash"
        )
    }

    @Test fun every_renderer_prints_a_speed_that_was_actually_reported_as_zero() {
        // The standstill this contract must never hide: stopped at a light, and the
        // firmware said so. A renderer that dashed every zero fails here.
        val stopped = wheel.copy(speedKmh = 0f)
        assertNull(classic(stopped, VescClusterSlot.SPEED).valueTextOverride, "Classic prints the number")
        assertEquals("0", CleanMetricMapper.heroSpeedValue(stopped, UnitSystem.METRIC))
        assertEquals(0f, CleanMetricMapper.heroSpeedFraction(stopped, 70f))
    }

    @Test fun a_real_speed_reaches_both_renderers_unchanged() {
        assertEquals(34f, classic(wheel, VescClusterSlot.SPEED).value)
        assertNull(classic(wheel, VescClusterSlot.SPEED).valueTextOverride)
        assertEquals("34", CleanMetricMapper.heroSpeedValue(wheel, UnitSystem.METRIC))
        assertEquals(34f / 70f, CleanMetricMapper.heroSpeedFraction(wheel, 70f))
    }

    // -----------------------------------------------------------------------------
    // The precedent this contract had to match
    // -----------------------------------------------------------------------------

    @Test fun the_new_cases_render_exactly_the_way_the_temperature_gauges_already_did() {
        // hasMotorTemp/hasEscTemp were honoured before this task; §9's whole argument
        // is that duty, power and consumption should look the same to a rider. If a
        // future change gives one family a different marker, this fails.
        val noSensors = wheel.copy(motorTempC = -60f, hasMotorTemp = false, escTempC = -60f)
        val temperatureMarker = classic(noSensors, VescClusterSlot.MOTOR_TEMP).valueTextOverride
        assertEquals(UNKNOWN_READOUT, temperatureMarker)
        assertEquals(temperatureMarker, classic(noSensors, VescClusterSlot.ESC_TEMP).valueTextOverride)
        assertEquals(temperatureMarker, classic(noSensors, VescClusterSlot.DUTY).valueTextOverride)
        assertEquals(temperatureMarker, classic(noSensors, VescClusterSlot.POWER).valueTextOverride)
        assertEquals(temperatureMarker, classic(noSensors, VescClusterSlot.CONSUMPTION).valueTextOverride)

        assertEquals(
            secondary(noSensors, SecondaryGauge.MOTOR_TEMP).value,
            secondary(noSensors, SecondaryGauge.DUTY).value
        )
        assertEquals(
            secondary(noSensors, SecondaryGauge.ESC_TEMP).value,
            secondary(noSensors, SecondaryGauge.POWER).value
        )
    }

    @Test fun an_unwired_temperature_sensor_gets_no_severity_band_on_either_renderer() {
        // The temperature gauges were the precedent this contract copied, but their
        // severity was the one part still computed from a value-and-flag pair rather
        // than from the nullable. Behaviour is unchanged — TempBands.motorLevel's own
        // `known` branch already answered NORMAL — so this asserts the CONTRACT-level
        // claim the refactor makes: an unobserved sensor never colours anything, and a
        // renderer that defaulted the other way would be caught here rather than on a
        // rider's dashboard.
        val noSensors = wheel.copy(motorTempC = -60f, hasMotorTemp = false, escTempC = -60f)

        assertEquals(DutyLevel.NORMAL, classic(noSensors, VescClusterSlot.MOTOR_TEMP).severity)
        assertEquals(DutyLevel.NORMAL, classic(noSensors, VescClusterSlot.ESC_TEMP).severity)
        assertEquals(DutyLevel.NORMAL, secondary(noSensors, SecondaryGauge.MOTOR_TEMP).severity)
        assertEquals(DutyLevel.NORMAL, secondary(noSensors, SecondaryGauge.ESC_TEMP).severity)

        // …and the positive half, so "never colours anything" cannot be satisfied by a
        // renderer that colours nothing at all.
        val hot = wheel.copy(motorTempC = 105f, hasMotorTemp = true, escTempC = 90f)
        assertEquals(DutyLevel.CRITICAL, classic(hot, VescClusterSlot.MOTOR_TEMP).severity)
        assertEquals(DutyLevel.CRITICAL, classic(hot, VescClusterSlot.ESC_TEMP).severity)
        assertEquals(DutyLevel.CRITICAL, secondary(hot, SecondaryGauge.MOTOR_TEMP).severity)
        assertEquals(DutyLevel.CRITICAL, secondary(hot, SecondaryGauge.ESC_TEMP).severity)
    }

    @Test fun the_battery_gauges_are_untouched_by_this_contract() {
        // BmsData.socKnown said the same thing years earlier and is pinned by tests
        // predating this part — it stays where it is, and it keeps working.
        val noSoc = BmsData(voltage = 78f, soc = 0f, socKnown = false, isConnected = true)
        val dial = ClassicDialSpecs.build(wheel, noSoc, UnitSystem.METRIC, maxSpeedKmh = 70f)
            .single { it.slot == VescClusterSlot.BATTERY }
        assertEquals(UNKNOWN_READOUT, dial.valueTextOverride)
        assertEquals(DutyLevel.NORMAL, dial.severity)
        assertEquals(
            "61%",
            ClassicDialSpecs.build(wheel, battery, UnitSystem.METRIC, maxSpeedKmh = 70f)
                .single { it.slot == VescClusterSlot.BATTERY }.valueTextOverride
        )
    }
}
