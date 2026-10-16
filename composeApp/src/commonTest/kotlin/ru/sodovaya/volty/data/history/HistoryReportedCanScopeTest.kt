package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class HistoryReportedCanScopeTest {
    private val tick = HistoryTick(10000, 100, 1)
    private val reporter = HistorySource("node-a-report", HistorySourceKind.REPORTED_CAN, "Node A · CAN")

    @Test fun reported_network_scope_is_addressed_by_its_reporting_controller() {
        val observation = TelemetryObservation(TelemetryOrigin.CONTROLLER, 3,
            listOf(TelemetryReading.measured(TelemetryMetric.MOTOR_CURRENT, 0.0)), TelemetryRole.REPORTED_CAN)
        assertEquals(3, observation.localIndex)
        assertEquals(TelemetryRole.REPORTED_CAN, observation.role)
        assertEquals(0.0, observation.readings.single().value)
        assertEquals(HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, 3, TelemetryRole.REPORTED_CAN),
            HistoryTelemetrySource(observation.origin, observation.localIndex, observation.role))
    }

    @Test fun reported_can_scope_cannot_be_a_battery_or_battery_assembly() {
        assertFailsWith<IllegalArgumentException> {
            TelemetryObservation(TelemetryOrigin.BATTERY, 0,
                listOf(TelemetryReading.measured(TelemetryMetric.CURRENT, 7.0)), TelemetryRole.REPORTED_CAN)
        }
        assertFailsWith<IllegalArgumentException> {
            HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, 0, TelemetryRole.REPORTED_CAN, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, -1, TelemetryRole.REPORTED_CAN)
        }
    }

    @Test fun reporting_scope_maps_to_a_distinct_kind_instead_of_a_physical_or_vehicle_contributor() {
        val kind = historySourceKindForTelemetry(TelemetryOrigin.CONTROLLER, TelemetryRole.REPORTED_CAN)
        assertEquals(HistorySourceKind.REPORTED_CAN, kind)
        assertNotEquals(HistorySourceKind.CONTROLLER, kind)
        assertNotEquals(HistorySourceKind.VEHICLE_MOTION, kind)
        assertFailsWith<IllegalArgumentException> {
            historySourceKindForTelemetry(TelemetryOrigin.BATTERY, TelemetryRole.REPORTED_CAN)
        }
    }

    @Test fun reported_scope_permits_only_the_two_reported_current_quantities() {
        assertEquals(setOf("motor_current_a", "battery_current_a"),
            HistoryMetricRegistry.forSource(HistorySourceKind.REPORTED_CAN).map { it.id }.toSet())
        assertTrue(HistoryMetricRegistry.forSource(HistorySourceKind.REPORTED_CAN).all {
            it.unit == HistoryUnit.AMP && it.consumptionMultiplier == null && !it.indexedByCell
        })
    }

    @Test fun signed_and_zero_reported_currents_survive_without_becoming_physical_measurements() {
        val report = TelemetryObservation(TelemetryOrigin.CONTROLLER, 3, listOf(
            TelemetryReading.measured(TelemetryMetric.MOTOR_CURRENT, 0.0),
            TelemetryReading.measured(TelemetryMetric.CONTROLLER_BATTERY_CURRENT, -7.0)), TelemetryRole.REPORTED_CAN)
        val samples = historyTelemetrySamples(reporter, tick, report)
        assertEquals(listOf(0.0, -7.0), samples.map { it.value })
        assertTrue(samples.all { it.key.sourceId == reporter.id && it.tick == tick && it.unknown == null })
        for (kind in listOf(HistorySourceKind.CONTROLLER, HistorySourceKind.VEHICLE_MOTION, HistorySourceKind.DERIVED_BATTERY)) {
            assertFailsWith<IllegalArgumentException> { historyTelemetrySamples(reporter.copy(kind = kind), tick, report) }
        }
    }

    @Test fun network_current_report_cannot_claim_power_local_voltage_speed_or_physical_sensors() {
        for (metric in listOf(TelemetryMetric.CONTROLLER_POWER, TelemetryMetric.INPUT_VOLTAGE,
            TelemetryMetric.SPEED, TelemetryMetric.ESC_TEMPERATURE, TelemetryMetric.ODOMETER)) {
            val report = TelemetryObservation(TelemetryOrigin.CONTROLLER, 3,
                listOf(TelemetryReading.measured(metric, 4200.0)), TelemetryRole.REPORTED_CAN)
            assertFailsWith<IllegalArgumentException> { historyTelemetrySamples(reporter, tick, report) }
        }
    }
}
