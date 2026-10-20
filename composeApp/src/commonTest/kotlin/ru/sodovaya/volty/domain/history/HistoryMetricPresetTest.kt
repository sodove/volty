package ru.sodovaya.volty.domain.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HistoryMetricPresetTest {
    @Test
    fun absentSourceDoesNotRebindToAnotherSourceWithTheSameName() {
        val presetMetric = presetMetric(sourceId = "ble:AA:01", metricId = "soc_percent")
        val result = resolveHistoryMetricPreset(
            preset(metrics = listOf(presetMetric)),
            vehicleId = VEHICLE,
            sources = listOf(source(id = "ble:BB:02", kind = HistorySourceKind.BATTERY, label = "Battery")),
        )

        assertEquals(emptyList(), result.keys)
        assertEquals(listOf(presetMetric), result.unavailable)
    }

    @Test
    fun renamedSourceWithTheSameQualifiedIdentityStillResolves() {
        val result = resolveHistoryMetricPreset(
            preset(metrics = listOf(presetMetric(sourceId = "ble:AA:01", metricId = "soc_percent"))),
            vehicleId = VEHICLE,
            sources = listOf(source(id = "ble:AA:01", kind = HistorySourceKind.BATTERY, label = "Rear pack")),
        )

        assertEquals(listOf(HistorySeriesKey("ble:AA:01", "soc_percent")), result.keys)
        assertEquals(emptyList(), result.unavailable)
    }

    @Test
    fun vehicleMismatchMakesEvenLegacyGenericSourceIdsUnavailable() {
        val legacyMetric = presetMetric(sourceId = "battery", metricId = "soc_percent")
        val result = resolveHistoryMetricPreset(
            preset(vehicleId = "vehicle-a", metrics = listOf(legacyMetric)),
            vehicleId = "vehicle-b",
            sources = listOf(source(id = "battery", kind = HistorySourceKind.BATTERY, label = "Battery")),
        )

        assertEquals(emptyList(), result.keys)
        assertEquals(listOf(legacyMetric), result.unavailable)
    }

    @Test
    fun sourceKindMustMatchEvenWhenTheMetricSupportsBothKinds() {
        val presetMetric = presetMetric(
            sourceId = "pack:AA",
            sourceKind = HistorySourceKind.BATTERY,
            metricId = "soc_percent",
        )
        val result = resolveHistoryMetricPreset(
            preset(metrics = listOf(presetMetric)),
            vehicleId = VEHICLE,
            sources = listOf(source(id = "pack:AA", kind = HistorySourceKind.VEHICLE_BATTERY, label = "Pack")),
        )

        assertEquals(emptyList(), result.keys)
        assertEquals(listOf(presetMetric), result.unavailable)
    }

    @Test
    fun sparseCellIndexIsPreservedExactly() {
        val presetMetric = presetMetric(
            sourceId = "pack:AA",
            metricId = "cell_v",
            cellIndex = 7,
        )
        val result = resolveHistoryMetricPreset(
            preset(metrics = listOf(presetMetric)),
            vehicleId = VEHICLE,
            sources = listOf(source(id = "pack:AA", kind = HistorySourceKind.BATTERY, label = "Pack")),
        )

        assertEquals(listOf(HistorySeriesKey("pack:AA", "cell_v", 7)), result.keys)
        assertEquals(emptyList(), result.unavailable)
    }

    @Test
    fun unknownMetricsAndWrongCellShapesRemainUnavailable() {
        val unavailable = listOf(
            presetMetric(sourceId = "pack:AA", metricId = "future_metric"),
            presetMetric(sourceId = "pack:AA", metricId = "cell_v"),
            presetMetric(sourceId = "pack:AA", metricId = "soc_percent", cellIndex = 0),
        )
        val result = resolveHistoryMetricPreset(
            preset(metrics = unavailable),
            vehicleId = VEHICLE,
            sources = listOf(source(id = "pack:AA", kind = HistorySourceKind.BATTERY, label = "Pack")),
        )

        assertEquals(emptyList(), result.keys)
        assertEquals(unavailable, result.unavailable)
    }

    @Test
    fun duplicateSourceIdsAreAmbiguousRegardlessOfKind() {
        val presetMetric = presetMetric(sourceId = "shared-id", metricId = "soc_percent")
        val result = resolveHistoryMetricPreset(
            preset(metrics = listOf(presetMetric)),
            vehicleId = VEHICLE,
            sources = listOf(
                source(id = "shared-id", kind = HistorySourceKind.BATTERY, label = "Pack"),
                source(id = "shared-id", kind = HistorySourceKind.CONTROLLER, label = "Controller"),
            ),
        )

        assertEquals(emptyList(), result.keys)
        assertEquals(listOf(presetMetric), result.unavailable)
    }

    @Test
    fun resolutionPreservesOrderAndDeduplicatesBothResolvedAndUnavailableEntries() {
        val first = presetMetric(sourceId = "pack:AA", metricId = "soc_percent")
        val future = presetMetric(sourceId = "pack:AA", metricId = "future_metric")
        val second = presetMetric(sourceId = "pack:AA", metricId = "voltage_v")
        val result = resolveHistoryMetricPreset(
            preset(metrics = listOf(first, future, second, first, future)),
            vehicleId = VEHICLE,
            sources = listOf(source(id = "pack:AA", kind = HistorySourceKind.BATTERY, label = "Pack")),
        )

        assertEquals(
            listOf(
                HistorySeriesKey("pack:AA", "soc_percent"),
                HistorySeriesKey("pack:AA", "voltage_v"),
            ),
            result.keys,
        )
        assertEquals(listOf(future), result.unavailable)
    }

    @Test
    fun captureDeduplicatesKeysAndSnapshotsItsInputs() {
        val expected = HistoryPresetMetric("pack:AA", HistorySourceKind.BATTERY, "soc_percent")
        val key = HistorySeriesKey("pack:AA", "soc_percent")
        val mutableKeys = mutableListOf(key, key)
        val mutableSources = mutableListOf(source(id = "pack:AA", kind = HistorySourceKind.BATTERY, label = "Pack"))

        val captured = captureHistoryMetricPreset("preset-1", "Battery", VEHICLE, mutableKeys, mutableSources)
        mutableKeys.clear()
        mutableSources.clear()

        assertEquals(listOf(expected), captured.metrics)
    }

    @Test
    fun captureRejectsUnknownMetricsAndInvalidIndexedShape() {
        val battery = source(id = "pack:AA", kind = HistorySourceKind.BATTERY, label = "Pack")

        assertFailsWith<IllegalArgumentException> {
            captureHistoryMetricPreset(
                "preset-1", "Battery", VEHICLE,
                listOf(HistorySeriesKey("pack:AA", "future_metric")),
                listOf(battery),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            captureHistoryMetricPreset(
                "preset-1", "Battery", VEHICLE,
                listOf(HistorySeriesKey("pack:AA", "cell_v")),
                listOf(battery),
            )
        }
    }

    private fun preset(
        vehicleId: String = VEHICLE,
        metrics: List<HistoryPresetMetric>,
    ) = HistoryMetricPreset("preset-1", "Battery", vehicleId, metrics)

    private fun presetMetric(
        sourceId: String,
        metricId: String,
        sourceKind: HistorySourceKind = HistorySourceKind.BATTERY,
        cellIndex: Int? = null,
    ) = HistoryPresetMetric(sourceId, sourceKind, metricId, cellIndex)

    private fun source(id: String, kind: HistorySourceKind, label: String) = HistorySource(id, kind, label)

    private companion object {
        const val VEHICLE = "vehicle-1"
    }
}
