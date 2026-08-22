package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class LightDashboardMapperTest {

    @Test
    fun layout_adapts_from_compact_to_medium_to_wide() {
        assertEquals(LightLayoutMode.COMPACT, lightLayoutMode(320f, 568f))
        assertEquals(LightLayoutMode.MEDIUM, lightLayoutMode(411f, 915f))
        assertEquals(LightLayoutMode.WIDE, lightLayoutMode(800f, 600f))
    }

    @Test
    fun absent_live_telemetry_is_a_dash_and_never_a_zero() {
        val readouts = LightDashboardMapper.map(
            motion = ControllerData(
                isConnected = true,
                speedSource = SpeedSource.NONE,
                hasDuty = false,
                hasBatteryCurrent = false,
                hasMotorTemp = false,
                escTempC = -60f,
                hasInputVoltage = false,
                hasPower = false
            ),
            battery = BmsData(isConnected = false, soc = 0f, voltage = 0f),
            units = UnitSystem.METRIC
        )

        assertEquals("—", readouts.speed.value)
        assertEquals("—", readouts.duty.value)
        assertEquals("—", readouts.motorTemperature.value)
        assertEquals("—", readouts.controllerTemperature.value)
        assertEquals("—", readouts.batteryCurrent.value)
        assertEquals("—", readouts.batterySoc.value)
        assertEquals("—", readouts.batteryVoltage.value)
        assertEquals(0f, LightDashboardMapper.speedFraction(ControllerData(isConnected = false), 70f))
        assertEquals(0f, LightDashboardMapper.dutyFraction(ControllerData(isConnected = false)), 0f)
        assertEquals(0f, LightDashboardMapper.batteryFraction(BmsData(isConnected = false)), 0f)
    }

    @Test
    fun connected_unearned_motor_current_is_a_dash() {
        val readouts = LightDashboardMapper.map(
            motion = ControllerData(
                isConnected = true,
                motorCurrentA = 0f,
                hasPower = false
            ),
            battery = BmsData(isConnected = false),
            units = UnitSystem.METRIC
        )

        assertEquals("—", readouts.motorCurrent.value)
        assertEquals("", readouts.motorCurrent.unit)
    }

    @Test
    fun known_live_telemetry_keeps_real_zeroes_and_units() {
        val readouts = LightDashboardMapper.map(
            motion = ControllerData(
                speedKmh = 0f,
                speedSource = SpeedSource.REPORTED,
                dutyPercent = 0f,
                hasDuty = true,
                motorCurrentA = 0f,
                hasPower = true,
                batteryCurrentA = -12.4f,
                hasBatteryCurrent = true,
                escTempC = 42f,
                motorTempC = 38f,
                hasMotorTemp = true,
                isConnected = true
            ),
            battery = BmsData(
                voltage = 82.4f,
                soc = 76f,
                socKnown = true,
                isConnected = true
            ),
            units = UnitSystem.METRIC
        )

        assertEquals("0", readouts.speed.value)
        assertEquals("km/h", readouts.speed.unit)
        assertEquals("0", readouts.duty.value)
        assertEquals("0", readouts.motorCurrent.value)
        assertEquals("-12.4", readouts.batteryCurrent.value)
        assertEquals("42", readouts.controllerTemperature.value)
        assertEquals("38", readouts.motorTemperature.value)
        assertEquals("76", readouts.batterySoc.value)
        assertEquals("82.4", readouts.batteryVoltage.value)
        assertEquals(0f, LightDashboardMapper.speedFraction(
            ControllerData(speedSource = SpeedSource.REPORTED, isConnected = true), 70f
        ))
        assertEquals(0f, LightDashboardMapper.dutyFraction(
            ControllerData(hasDuty = true, dutyPercent = 0f, isConnected = true)
        ))
    }

    @Test
    fun pure_bms_uses_gps_speed_and_bms_current() {
        val readouts = LightDashboardMapper.map(
            motion = ControllerData(),
            battery = BmsData(
                current = -12.4f,
                hasCurrent = true,
                isConnected = true,
            ),
            units = UnitSystem.METRIC,
            gpsSpeedKmh = 23.5f,
        )

        assertEquals("24", readouts.speed.value)
        assertEquals("km/h", readouts.speed.unit)
        assertEquals("-12.4", readouts.batteryCurrent.value)
        assertEquals("A", readouts.batteryCurrent.unit)
    }

    @Test
    fun controller_speed_and_current_win_over_gps_and_bms_fallbacks() {
        val readouts = LightDashboardMapper.map(
            motion = ControllerData(
                speedKmh = 11f,
                speedSource = SpeedSource.REPORTED,
                batteryCurrentA = -4f,
                hasBatteryCurrent = true,
                isConnected = true,
            ),
            battery = BmsData(current = -12.4f, isConnected = true),
            units = UnitSystem.METRIC,
            gpsSpeedKmh = 23.5f,
        )

        assertEquals("11", readouts.speed.value)
        assertEquals("-4.0", readouts.batteryCurrent.value)
    }
}
