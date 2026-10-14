package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

class HistoryTelemetryAdapterTest {
    @Test fun assemblyAndDerivedRolesCannotMasqueradeAsPhysicalBatteryContributors() {
        for (role in listOf(TelemetryRole.ASSEMBLY, TelemetryRole.DERIVED)) {
            val observation = TelemetryObservation(TelemetryOrigin.BATTERY, 0,
                listOf(TelemetryReading.measured(TelemetryMetric.VOLTAGE, 42.0)), role,
                if (role == TelemetryRole.ASSEMBLY) 0 else null)
            assertFailsWith<IllegalArgumentException> { historyTelemetrySamples(source, tick, observation) }
        }
    }

    @Test fun explicitAssemblyAndVirtualSourcesRetainCanonicalValuesWithoutPhysicalSensorClaims() {
        val assembly = TelemetryObservation(TelemetryOrigin.BATTERY, 0,
            listOf(TelemetryReading.measured(TelemetryMetric.BATTERY_TEMPERATURE, 0.0)), TelemetryRole.ASSEMBLY, 1)
        val sectionSource = source.copy(id = "section-a", kind = HistorySourceKind.BATTERY_ASSEMBLY)
        assertEquals(0.0, historyTelemetrySamples(sectionSource, tick, assembly).single().value)
        val virtual = TelemetryObservation(TelemetryOrigin.BATTERY, 0,
            listOf(TelemetryReading.measured(TelemetryMetric.POWER, -100.0)), TelemetryRole.DERIVED)
        val virtualSource = source.copy(id = "virtual-a", kind = HistorySourceKind.DERIVED_BATTERY)
        assertEquals(-100.0, historyTelemetrySamples(virtualSource, tick, virtual).single().value)
        assertFailsWith<IllegalArgumentException> { historyTelemetrySamples(virtualSource, tick, assembly) }
        assertFailsWith<IllegalArgumentException> { historyTelemetrySamples(sectionSource, tick, virtual) }
    }

    @Test fun assemblyCurrentAndVirtualSensorsCannotBeRecordedAsEarnedFacts() {
        val assembly = TelemetryObservation(TelemetryOrigin.BATTERY, 0,
            listOf(TelemetryReading.measured(TelemetryMetric.CURRENT, 42.0)), TelemetryRole.ASSEMBLY, 0)
        assertFailsWith<IllegalArgumentException> {
            historyTelemetrySamples(source.copy(kind = HistorySourceKind.BATTERY_ASSEMBLY), tick, assembly)
        }
        for (metric in listOf(TelemetryMetric.BATTERY_TEMPERATURE, TelemetryMetric.CELL_MIN, TelemetryMetric.CELL)) {
            val virtual = TelemetryObservation(TelemetryOrigin.BATTERY, 0,
                listOf(TelemetryReading.measured(metric, 42.0, cellIndex = if (metric.indexed) 0 else null)), TelemetryRole.DERIVED)
            assertFailsWith<IllegalArgumentException> {
                historyTelemetrySamples(source.copy(kind = HistorySourceKind.DERIVED_BATTERY), tick, virtual)
            }
        }
    }
    private val tick = HistoryTick(10000, 100, 1)
    private val source = HistorySource("pack-a", HistorySourceKind.BATTERY, "A")
    private fun battery(vararg readings: TelemetryReading) = TelemetryObservation(TelemetryOrigin.BATTERY, 0, readings.toList())

    @Test fun equalLocalCellIndicesRemainSeparateAcrossPhysicalSources() {
        val a = historyTelemetrySamples(source, tick, battery(TelemetryReading.measured(TelemetryMetric.CELL, 4.0, cellIndex = 0))).single()
        val b = historyTelemetrySamples(source.copy(id = "pack-b"), tick, battery(TelemetryReading.measured(TelemetryMetric.CELL, 3.9, cellIndex = 0))).single()
        assertEquals(HistorySeriesKey("pack-a", "cell_v", 0), a.key)
        assertEquals(HistorySeriesKey("pack-b", "cell_v", 0), b.key)
        assertEquals(4.0, a.value); assertEquals(3.9, b.value)
    }

    @Test fun incoherentAbsentPowerKeepsUnknownAndNeverRecordsItsStoredNumber() {
        val observation = TelemetryObservation(TelemetryOrigin.CONTROLLER, 0,
            listOf(TelemetryReading.measured(TelemetryMetric.CONTROLLER_POWER, 4200.0, known = false)))
        val point = historyTelemetrySamples(source.copy(id = "esc", kind = HistorySourceKind.CONTROLLER), tick, observation).single()
        assertNull(point.value); assertEquals(HistoryUnknownReason.UNAVAILABLE, point.unknown)
        assertEquals("controller_power_w", point.key.metricId)
    }

    @Test fun omittedCachedFieldsAreNotRestampedAndTypedGapKeepsPhysicalIndex() {
        val observation = battery(TelemetryReading.measured(TelemetryMetric.CURRENT, -2.0),
            TelemetryReading(TelemetryMetric.CELL, null, TelemetryUnknown.INVALID_VALUE, 3))
        val points = historyTelemetrySamples(source, tick, observation)
        assertEquals(listOf("current_a", "cell_v"), points.map { it.key.metricId })
        assertEquals(-2.0, points.first().value)
        assertEquals(3, points.last().key.cellIndex)
        assertEquals(HistoryUnknownReason.INVALID_VALUE, points.last().unknown)
        assertTrue(points.all { it.tick == tick })
    }

    @Test fun aggregateOrMismatchedSourceCannotDuplicateContributorMeasurements() {
        val observation = battery(TelemetryReading.measured(TelemetryMetric.POWER, -100.0))
        assertFailsWith<IllegalArgumentException> { historyTelemetrySamples(source.copy(kind = HistorySourceKind.VEHICLE_BATTERY), tick, observation) }
        assertFailsWith<IllegalArgumentException> { historyTelemetrySamples(source.copy(kind = HistorySourceKind.CONTROLLER), tick, observation) }
    }

    @Test fun canonicalVocabularyCoversRegistryWithoutSignOrUnitConversion() {
        assertEquals(HistoryMetricRegistry.descriptors.map { it.id }.toSet(), TelemetryMetric.entries.map { it.id }.toSet())
        val readings = listOf(TelemetryReading.measured(TelemetryMetric.POWER, -100.0),
            TelemetryReading.measured(TelemetryMetric.CELL_DELTA, 12.0),
            TelemetryReading(TelemetryMetric.CELL_MIN, null, TelemetryUnknown.PARTIAL_SOURCE))
        val points = historyTelemetrySamples(source, tick, battery(*readings.toTypedArray()))
        assertEquals(-100.0, points[0].value); assertEquals(12.0, points[1].value)
        assertEquals(HistoryUnknownReason.PARTIAL_SOURCE, points[2].unknown)
    }
}
