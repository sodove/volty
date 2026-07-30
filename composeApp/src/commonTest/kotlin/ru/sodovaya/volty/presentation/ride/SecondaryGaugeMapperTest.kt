package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecondaryGaugeMapperTest {

    private val motion = ControllerData(
        speedKmh = 47f, dutyPercent = 76f, motorCurrentA = -82.5f, batteryCurrentA = 52.4f,
        inputVoltageV = 78.2f, powerW = 4098f, escTempC = 52f, motorTempC = 68f, hasMotorTemp = true,
        consumedWh = 980f, tripKm = 58f, isConnected = true
    )
    private val battery = BmsData(voltage = 78.2f, soc = 84f, socKnown = true, isConnected = true)

    @Test fun duty_maps_to_percent_and_carries_its_severity() {
        val r = SecondaryGaugeMapper.map(SecondaryGauge.DUTY, motion, battery, UnitSystem.METRIC)
        assertEquals("DUTY", r.label)
        assertEquals("76", r.value)
        assertEquals("%", r.unit)
        assertTrue(abs(r.fraction - 0.76f) < 0.01f)
        assertEquals(DutyLevel.WARN, r.severity)
    }

    @Test fun battery_maps_to_state_of_charge() {
        val r = SecondaryGaugeMapper.map(SecondaryGauge.BATTERY, motion, battery, UnitSystem.METRIC)
        assertEquals("BATTERY", r.label)
        assertEquals("84", r.value)
        assertEquals("%", r.unit)
        assertTrue(abs(r.fraction - 0.84f) < 0.01f)
        assertEquals(DutyLevel.NORMAL, r.severity)
    }

    @Test fun power_is_shown_in_kilowatts() {
        val r = SecondaryGaugeMapper.map(SecondaryGauge.POWER, motion, battery, UnitSystem.METRIC)
        assertEquals("POWER", r.label)
        assertEquals("4.1", r.value)
        assertEquals("kW", r.unit)
    }

    @Test fun current_maps_to_battery_current() {
        val r = SecondaryGaugeMapper.map(SecondaryGauge.CURRENT, motion, battery, UnitSystem.METRIC)
        assertEquals("CURRENT", r.label)
        assertEquals("52", r.value)
        assertEquals("A", r.unit)
    }

    @Test fun temperatures_carry_severity_from_their_own_ceiling() {
        val hot = motion.copy(motorTempC = 105f)
        val r = SecondaryGaugeMapper.map(SecondaryGauge.MOTOR_TEMP, hot, battery, UnitSystem.METRIC)
        assertEquals("MOTOR", r.label)
        assertEquals("105", r.value)
        assertEquals("°C", r.unit)
        assertEquals(DutyLevel.CRITICAL, r.severity)
    }

    @Test fun esc_temp_dashes_when_the_sensor_is_unwired() {
        val noSensor = motion.copy(escTempC = -60f)
        val r = SecondaryGaugeMapper.map(SecondaryGauge.ESC_TEMP, noSensor, battery, UnitSystem.METRIC)
        assertEquals("ESC", r.label)
        assertEquals("—", r.value)
        assertEquals(0f, r.fraction)
    }

    @Test fun consumption_falls_back_to_a_dash_when_standing_still() {
        val stopped = motion.copy(speedKmh = 0f, consumedWh = 0f, tripKm = 0f)
        val r = SecondaryGaugeMapper.map(SecondaryGauge.CONSUMPTION, stopped, battery, UnitSystem.METRIC)
        assertEquals("CONSUMPTION", r.label)
        assertEquals("—", r.value)
        assertEquals(0f, r.fraction)
    }

    // --- G §9.2: the CURRENT and POWER rings follow the vehicle, on THIS renderer too -------------

    /**
     * **"A range that only Classic honours is half a fix."** The Clean hero's inner ring divided
     * `abs(current)` by a hardcoded 150 A and `abs(power)` by 8000 W, which put a wheel's 6 A / 571 W
     * cruise at 4 % and 7 % of the ring — the same unreadable state Classic's ±60 A / ±10 kW dials
     * were reported for.
     *
     * Both halves are asserted: the old hardcoded divisors would fail the first two assertions, and
     * an implementation that ignored the parameter entirely would fail all four.
     */
    @Test fun the_current_and_power_rings_divide_by_the_learned_range() {
        val wheel = motion.copy(batteryCurrentA = 6f, powerW = 571f)

        val current = SecondaryGaugeMapper.map(
            SecondaryGauge.CURRENT, wheel, battery, UnitSystem.METRIC, currentRangeA = 10f
        )
        assertTrue(abs(current.fraction - 0.6f) < 0.01f, "current ring read ${current.fraction}")
        assertTrue(6f / 150f < 0.05f, "what the hardcoded 150 A divisor used to give")

        val power = SecondaryGaugeMapper.map(
            SecondaryGauge.POWER, wheel, battery, UnitSystem.METRIC, powerRangeW = 1000f
        )
        assertTrue(abs(power.fraction - 0.571f) < 0.01f, "power ring read ${power.fraction}")
        assertTrue(571f / 8000f < 0.1f, "what the hardcoded 8000 W divisor used to give")
    }

    /** A scooter's wide range must widen the divisor too, not just narrow it for wheels. */
    @Test fun a_wide_learned_range_shrinks_the_same_reading_on_the_ring() {
        val narrow = SecondaryGaugeMapper.map(
            SecondaryGauge.CURRENT, motion, battery, UnitSystem.METRIC, currentRangeA = 60f
        )
        val wide = SecondaryGaugeMapper.map(
            SecondaryGauge.CURRENT, motion, battery, UnitSystem.METRIC, currentRangeA = 500f
        )
        assertTrue(narrow.fraction > wide.fraction)
        assertTrue(abs(narrow.fraction - 52.4f / 60f) < 0.01f)
        assertTrue(abs(wide.fraction - 52.4f / 500f) < 0.01f)
    }

    /** Regen is a negative current on a bipolar dial, so the ring follows the magnitude. */
    @Test fun the_current_ring_follows_the_magnitude_of_a_regen_reading() {
        val regen = motion.copy(batteryCurrentA = -30f)
        val r = SecondaryGaugeMapper.map(
            SecondaryGauge.CURRENT, regen, battery, UnitSystem.METRIC, currentRangeA = 60f
        )
        assertTrue(abs(r.fraction - 0.5f) < 0.01f)
    }

    @Test fun fractions_never_leave_zero_to_one() {
        val over = motion.copy(dutyPercent = 140f)
        assertEquals(1f, SecondaryGaugeMapper.map(SecondaryGauge.DUTY, over, battery, UnitSystem.METRIC).fraction)
        val under = motion.copy(dutyPercent = -20f)
        assertEquals(0f, SecondaryGaugeMapper.map(SecondaryGauge.DUTY, under, battery, UnitSystem.METRIC).fraction)
    }

    // Defect 1: the label used to be hardcoded per-gauge inside the mapper, including a bilingual
    // literal ("DUTY · ШИМ") that always showed up regardless of device locale. The mapper now
    // takes a caller-supplied SecondaryGaugeLabels (mirrors ClassicDialLabels/ClassicDialSpecs) so
    // it stays pure and testable without Compose, while RideDashboardScreen resolves the real
    // string_gauge_* resources and passes them in. These two tests cover both halves of that
    // contract: a supplied label always wins, and the un-supplied default is single-language.
    @Test fun a_supplied_label_replaces_the_default_for_every_gauge() {
        val labels = SecondaryGaugeLabels(
            duty = "SHIM", battery = "BAT", power = "MOSHCH", current = "TOK",
            motorTemp = "MOTR", escTemp = "ESK", consumption = "RASHOD"
        )
        assertEquals("SHIM", SecondaryGaugeMapper.map(SecondaryGauge.DUTY, motion, battery, UnitSystem.METRIC, labels).label)
        assertEquals("BAT", SecondaryGaugeMapper.map(SecondaryGauge.BATTERY, motion, battery, UnitSystem.METRIC, labels).label)
        assertEquals("MOSHCH", SecondaryGaugeMapper.map(SecondaryGauge.POWER, motion, battery, UnitSystem.METRIC, labels).label)
        assertEquals("TOK", SecondaryGaugeMapper.map(SecondaryGauge.CURRENT, motion, battery, UnitSystem.METRIC, labels).label)
        assertEquals("MOTR", SecondaryGaugeMapper.map(SecondaryGauge.MOTOR_TEMP, motion, battery, UnitSystem.METRIC, labels).label)
        assertEquals("ESK", SecondaryGaugeMapper.map(SecondaryGauge.ESC_TEMP, motion, battery, UnitSystem.METRIC, labels).label)
        assertEquals("RASHOD", SecondaryGaugeMapper.map(SecondaryGauge.CONSUMPTION, motion, battery, UnitSystem.METRIC, labels).label)
    }

    @Test fun default_labels_are_plain_english_and_never_bilingual() {
        // The regression this guards: DUTY's default used to be the literal "DUTY · ШИМ",
        // hardcoded English-and-Russian in the same string regardless of the device's locale.
        assertEquals("DUTY", SecondaryGaugeMapper.map(SecondaryGauge.DUTY, motion, battery, UnitSystem.METRIC).label)
        assertEquals("BATTERY", SecondaryGaugeMapper.map(SecondaryGauge.BATTERY, motion, battery, UnitSystem.METRIC).label)
        assertEquals("POWER", SecondaryGaugeMapper.map(SecondaryGauge.POWER, motion, battery, UnitSystem.METRIC).label)
        assertEquals("CURRENT", SecondaryGaugeMapper.map(SecondaryGauge.CURRENT, motion, battery, UnitSystem.METRIC).label)
        assertEquals("MOTOR", SecondaryGaugeMapper.map(SecondaryGauge.MOTOR_TEMP, motion, battery, UnitSystem.METRIC).label)
        assertEquals("ESC", SecondaryGaugeMapper.map(SecondaryGauge.ESC_TEMP, motion, battery, UnitSystem.METRIC).label)
        assertEquals("CONSUMPTION", SecondaryGaugeMapper.map(SecondaryGauge.CONSUMPTION, motion, battery, UnitSystem.METRIC).label)
    }
}
