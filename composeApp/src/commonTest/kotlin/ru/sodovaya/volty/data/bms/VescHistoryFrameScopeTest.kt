package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class VescHistoryFrameScopeTest {
    private val data = ControllerData(inputVoltageV = 50f, batteryCurrentA = 2f,
        motorCurrentA = 5f, powerW = 100f, hasInputVoltage = true, hasBatteryCurrent = true,
        hasPower = true, hasDistance = true, odometerKm = 999f, batteryLevelFraction = 0.8f)

    @Test fun setup_does_not_erase_local_currents_or_power_with_rows_from_another_scope() {
        val readings = observedVescController(data, setup = true, sessionTripKm = 40f)
        assertTrue(readings.none { it.metric in setOf(TelemetryMetric.MOTOR_CURRENT,
            TelemetryMetric.CONTROLLER_BATTERY_CURRENT, TelemetryMetric.CONTROLLER_POWER) })
        assertEquals(50.0, readings.single { it.metric == TelemetryMetric.INPUT_VOLTAGE }.value)
        assertEquals(999.0, readings.single { it.metric == TelemetryMetric.ODOMETER }.value)
    }

    @Test fun plain_query_cannot_restamp_setup_distance_even_if_a_cached_object_claims_it() {
        val readings = observedVescController(data, setup = false, sessionTripKm = 40f)
        assertTrue(readings.none { it.metric == TelemetryMetric.ODOMETER || it.metric == TelemetryMetric.TRIP })
        assertEquals(100.0, readings.single { it.metric == TelemetryMetric.CONTROLLER_POWER }.value)
    }

    @Test fun setup_virtual_battery_observes_only_its_gauge_and_voltage() {
        val readings = observedVescDerivedBattery(data, setup = true)
        assertEquals(setOf(TelemetryMetric.SOC, TelemetryMetric.VOLTAGE), readings.map { it.metric }.toSet())
        assertEquals(80.0, readings.single { it.metric == TelemetryMetric.SOC }.value)
    }

    @Test fun plain_virtual_rail_facts_do_not_erase_the_gauge_from_another_query_family() {
        val readings = observedVescDerivedBattery(data, setup = false)
        assertEquals(setOf(TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT, TelemetryMetric.POWER), readings.map { it.metric }.toSet())
        assertEquals(-2.0, readings.single { it.metric == TelemetryMetric.CURRENT }.value)
        assertEquals(-100.0, readings.single { it.metric == TelemetryMetric.POWER }.value)
    }

    @Test fun reported_current_scope_preserves_zero_and_sign_without_inventing_power_or_sensors() {
        val readings = observedVescCanCurrents(data.copy(motorCurrentA = 0f, batteryCurrentA = -7f,
            powerW = 4200f, hasPower = false))
        assertEquals(setOf(TelemetryMetric.MOTOR_CURRENT, TelemetryMetric.CONTROLLER_BATTERY_CURRENT), readings.map { it.metric }.toSet())
        assertEquals(0.0, readings.single { it.metric == TelemetryMetric.MOTOR_CURRENT }.value)
        assertEquals(-7.0, readings.single { it.metric == TelemetryMetric.CONTROLLER_BATTERY_CURRENT }.value)
    }

    @Test fun reported_input_current_requires_its_own_availability_even_with_a_stored_number() {
        val readings = observedVescCanCurrents(data.copy(batteryCurrentA = 4200f, hasBatteryCurrent = false))
        val missing = readings.single { it.metric == TelemetryMetric.CONTROLLER_BATTERY_CURRENT }
        assertNull(missing.value)
        assertEquals(TelemetryUnknown.UNAVAILABLE, missing.unknown)
    }

    @Test fun actual_plain_fields_still_qualify_missing_values_and_real_zero() {
        val readings = observedVescController(data.copy(dutyPercent = 0f, hasDuty = true,
            batteryCurrentA = 4200f, hasBatteryCurrent = false, powerW = 4200f, hasPower = false),
            setup = false, sessionTripKm = null)
        assertEquals(0.0, readings.single { it.metric == TelemetryMetric.DUTY }.value)
        for (metric in listOf(TelemetryMetric.CONTROLLER_BATTERY_CURRENT, TelemetryMetric.CONTROLLER_POWER)) {
            val missing = readings.single { it.metric == metric }
            assertNull(missing.value)
            assertEquals(TelemetryUnknown.UNAVAILABLE, missing.unknown)
        }
    }
}
