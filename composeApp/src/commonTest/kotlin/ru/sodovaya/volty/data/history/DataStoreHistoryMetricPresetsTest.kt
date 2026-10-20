package ru.sodovaya.volty.data.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class DataStoreHistoryMetricPresetsTest {
    private class Disk(var value: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = flowOf(value)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(value).also { value = it }
    }
    private fun preset(id: String = "a", vehicle: String = "bike") = HistoryMetricPreset(id,"Поездка · \"я\"",vehicle,
        listOf(HistoryPresetMetric("pack:a",HistorySourceKind.BATTERY,"cell_v",1000),
            HistoryPresetMetric("controller",HistorySourceKind.CONTROLLER,"speed_kmh")))

    @Test fun roundTripRecreationPreservesOrderExactIdentityAndUnicode() = runTest {
        val disk=Disk(); val first=DataStoreHistoryMetricPresets(disk)
        assertTrue(first.read().isEmpty())
        first.save(preset()); first.save(preset("b","wheel"))
        assertEquals(listOf(preset(),preset("b","wheel")),DataStoreHistoryMetricPresets(disk).read())
    }
    @Test fun replacingOneIdAndDeletingItKeepsOtherVehiclesUntouched() = runTest {
        val disk=Disk(); val store=DataStoreHistoryMetricPresets(disk)
        store.save(preset());store.save(preset("b","wheel"))
        val changed=preset().copy(name="New",metrics=preset().metrics.reversed())
        assertEquals(listOf(changed,preset("b","wheel")),store.save(changed))
        assertEquals(listOf(preset("b","wheel")),store.delete("a"))
        assertEquals(listOf(preset("b","wheel")),store.delete("absent"))
    }
    @Test fun corruptOrFutureEnvelopeIsReportedAndNeverSilentlyOverwritten() = runTest {
        for(raw in listOf("not-json","{\"version\":99,\"presets\":[]}")) {
            val disk=Disk(mutablePreferencesOf(stringPreferencesKey("history_metric_presets_v1") to raw))
            val store=DataStoreHistoryMetricPresets(disk); val before=disk.value
            assertFails { store.read() };assertFails { store.save(preset()) };assertFails {store.delete("a")}
            assertEquals(before,disk.value)
        }
    }
    @Test fun unknownMetricSurvivesStorageForExplicitUnavailablePresentation() = runTest {
        val disk=Disk();val future=preset().copy(metrics=listOf(HistoryPresetMetric("pack",HistorySourceKind.BATTERY,"future_metric")))
        DataStoreHistoryMetricPresets(disk).save(future)
        assertEquals(listOf(future),DataStoreHistoryMetricPresets(disk).read())
    }
    @Test fun boundedLayoutsRejectExcessWithoutChangingSavedFacts() = runTest {
        val disk=Disk();val store=DataStoreHistoryMetricPresets(disk)
        repeat(24){store.save(preset("p$it"))};val before=disk.value
        assertFailsWith<IllegalArgumentException>{store.save(preset("overflow"))}
        assertEquals(before,disk.value)
        assertFailsWith<IllegalArgumentException>{store.save(preset("p0").copy(metrics=List(129){ HistoryPresetMetric("pack$it",HistorySourceKind.BATTERY,"voltage_v") }))}
        assertEquals(before,disk.value)
        assertEquals(24,store.save(preset("p0").copy(name="Updated")).size)
    }
}
