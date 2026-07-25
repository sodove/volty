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
        assertEquals("76", r.value)
        assertEquals("%", r.unit)
        assertTrue(abs(r.fraction - 0.76f) < 0.01f)
        assertEquals(DutyLevel.WARN, r.severity)
    }

    @Test fun battery_maps_to_state_of_charge() {
        val r = SecondaryGaugeMapper.map(SecondaryGauge.BATTERY, motion, battery, UnitSystem.METRIC)
        assertEquals("84", r.value)
        assertEquals("%", r.unit)
        assertTrue(abs(r.fraction - 0.84f) < 0.01f)
        assertEquals(DutyLevel.NORMAL, r.severity)
    }

    @Test fun power_is_shown_in_kilowatts() {
        val r = SecondaryGaugeMapper.map(SecondaryGauge.POWER, motion, battery, UnitSystem.METRIC)
        assertEquals("4.1", r.value)
        assertEquals("kW", r.unit)
    }

    @Test fun temperatures_carry_severity_from_their_own_ceiling() {
        val hot = motion.copy(motorTempC = 105f)
        val r = SecondaryGaugeMapper.map(SecondaryGauge.MOTOR_TEMP, hot, battery, UnitSystem.METRIC)
        assertEquals("105", r.value)
        assertEquals("°C", r.unit)
        assertEquals(DutyLevel.CRITICAL, r.severity)
    }

    @Test fun consumption_falls_back_to_a_dash_when_standing_still() {
        val stopped = motion.copy(speedKmh = 0f, consumedWh = 0f, tripKm = 0f)
        val r = SecondaryGaugeMapper.map(SecondaryGauge.CONSUMPTION, stopped, battery, UnitSystem.METRIC)
        assertEquals("—", r.value)
        assertEquals(0f, r.fraction)
    }

    @Test fun fractions_never_leave_zero_to_one() {
        val over = motion.copy(dutyPercent = 140f)
        assertEquals(1f, SecondaryGaugeMapper.map(SecondaryGauge.DUTY, over, battery, UnitSystem.METRIC).fraction)
        val under = motion.copy(dutyPercent = -20f)
        assertEquals(0f, SecondaryGaugeMapper.map(SecondaryGauge.DUTY, under, battery, UnitSystem.METRIC).fraction)
    }
}
