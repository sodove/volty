package ru.sodovaya.volty.data.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

/**
 * Review I1 (2026-11-04): names written by a NEWER build (a source kind "FUTURE", a metric "x_y") are skipped and
 * logged; the ride, its export and the preset store still open. Deliberately incoherent rows a current writer never
 * produces, inserted straight into storage.
 */
class HistoryTolerantParsingTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val logged = mutableListOf<String>()
    private val previousSink = HistorySkippedLog.sink
    @BeforeTest fun capture() { HistorySkippedLog.sink = { logged += it } }
    @AfterTest fun close() { HistorySkippedLog.sink = previousSink; drivers.forEach { it.close() } }

    private val pack = HistorySource("pack", HistorySourceKind.BATTERY, "Pack")
    private val origin = HistoryTick(1_700_000_000_000, 100, 1)
    private fun tick(ms: Long) = HistoryTick(origin.epochMillis + ms, origin.elapsedMillis + ms, 1)

    private class Rig(val provider: VoltyDatabaseProvider, val store: SqlDelightHistoryStore)
    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        return Rig(provider, SqlDelightHistoryStore(provider))
    }

    /** A finished ride with a pack, plus a "FUTURE" source and an "x_y" metric on the pack, each with readings. */
    private suspend fun Rig.futureRide(sources: List<HistorySource> = listOf(pack)) {
        store.begin(HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING), sources)
        provider.database.rideHistorySourceQueries.insertSource("ride", "future-src", "FUTURE", "From a newer build")
        val samples = (0L until 3L).flatMap { i ->
            listOfNotNull(
                HistorySample(tick(i * 500), HistorySeriesKey("future-src", "future_metric"), 7.0 + i),
                if (sources.isNotEmpty()) HistorySample(tick(i * 500), HistorySeriesKey("pack", "voltage_v"), 60.0 + i) else null,
                if (sources.isNotEmpty()) HistorySample(tick(i * 500), HistorySeriesKey("pack", "x_y"), 1.0) else null,
            )
        }
        store.append(HistoryBatch("ride", samples, emptyList(), emptyList()))
        store.finish("ride", tick(2_000), HistoryRideStatus.COMPLETED)
    }

    private suspend fun Rig.export(): String {
        val ride = assertNotNull(store.ride("ride"))
        val input = SqlDelightHistoryExportSource(provider).open(HistoryExportRequest(ride.favoriteSource(),
            HistoryRange(tick(0).epochMillis, tick(2_000).epochMillis), HistoryExportFormat.LOGUI))
        val records = input.records.toList()
        val text = StringBuilder()
        StreamingHistoryExportEncoder(zone = { TimeZone.UTC })
            .write(HistoryExportFormat.LOGUI, input.metadata, flow { records.forEach { emit(it) } }) { text.append(it) }
        return text.toString()
    }

    @Test fun a_source_of_unknown_kind_is_skipped_and_the_ride_still_opens() = runTest {
        val r = rig(); r.futureRide()
        assertEquals(listOf(pack), r.store.sources("ride"))
        assertNotNull(r.store.ride("ride"))
        val range = HistoryRange(tick(0).epochMillis, tick(2_000).epochMillis)
        assertEquals(listOf(60.0, 61.0, 62.0), r.store.samples("ride", HistorySeriesKey("pack", "voltage_v"), range, null).items.map { it.value })
        assertTrue(r.store.recordedKeys("ride", "future-src", 10).keys.isEmpty(), "a kind this build cannot show has no keys")
        // An unregistered metric of a known kind stays invisible too (the recorded-keys query only asks for registered ones).
        assertEquals(listOf(HistorySeriesKey("pack", "voltage_v")), r.store.recordedKeys("ride", "pack", 10).keys)
        assertTrue(logged.any { "FUTURE" in it }, logged.toString())
    }

    @Test fun a_ride_whose_only_source_is_of_unknown_kind_still_opens_and_exports() = runTest {
        val r = rig(); r.futureRide(sources = emptyList())
        assertTrue(r.store.sources("ride").isEmpty())
        assertNotNull(r.store.ride("ride"))
        // The export opens and reads; with nothing this build can show it reports NO_DATA, the honest answer,
        // instead of INVALID_DATA or a storage error.
        val failure = assertFailsWith<HistoryExportException> { r.export() }
        assertEquals(HistoryExportFailure.NO_DATA, failure.reason)
    }

    @Test fun the_export_leaves_out_unknown_sources_and_metrics_instead_of_failing() = runTest {
        val r = rig(); r.futureRide()
        val text = r.export()
        val header = text.lineSequence().first()
        assertTrue(header.startsWith("bms_v_tot:"), header)
        assertFalse("future_metric" in text, "a series of an unknown source is not written")
        assertFalse("x_y" in text, "an unregistered metric is not written")
        assertTrue(logged.any { "x_y" in it } && logged.any { "future_metric" in it }, logged.toString())
    }

    // ---- presets ----

    private class Disk(var value: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = flowOf(value)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(value).also { value = it }
    }
    private val presetKey = stringPreferencesKey("history_metric_presets_v1")
    private val futureEnvelope = """{"version":1,"presets":[{"id":"a","name":"Ride","vehicleId":"bike","metrics":[""" +
        """{"sourceId":"gps-x","sourceKind":"FUTURE","metricId":"future_speed"},""" +
        """{"sourceId":"pack","sourceKind":"BATTERY","metricId":"x_y"},""" +
        """{"sourceId":"pack","sourceKind":"BATTERY","metricId":"voltage_v"}]}]}"""

    @Test fun a_preset_metric_of_unknown_kind_is_dropped_without_making_the_store_unreadable() = runTest {
        val disk = Disk(mutablePreferencesOf(presetKey to futureEnvelope))
        val store = DataStoreHistoryMetricPresets(disk)
        val read = store.read().single()
        // The unknown KIND is dropped; an unknown metric of a known kind is kept and shown as unavailable (existing contract).
        assertEquals(listOf(HistoryPresetMetric("pack", HistorySourceKind.BATTERY, "x_y"),
            HistoryPresetMetric("pack", HistorySourceKind.BATTERY, "voltage_v")), read.metrics)
        assertTrue(logged.any { "FUTURE" in it }, logged.toString())
    }

    @Test fun saving_through_this_build_keeps_the_entries_it_could_not_read() = runTest {
        val disk = Disk(mutablePreferencesOf(presetKey to futureEnvelope))
        val store = DataStoreHistoryMetricPresets(disk)
        val other = HistoryMetricPreset("b", "Other", "wheel", listOf(HistoryPresetMetric("esc", HistorySourceKind.CONTROLLER, "speed_kmh")))
        assertEquals(2, store.save(other).size)
        store.save(store.read().first { it.id == "a" }.copy(name = "Renamed"))
        val raw = checkNotNull(disk.value[presetKey])
        assertTrue("\"FUTURE\"" in raw && "future_speed" in raw, "the newer build's metric survives a round trip: $raw")
        assertEquals(listOf("Renamed", "Other"), store.read().map { it.name })
        // Deleting the preset deletes what it carried.
        store.delete("a")
        assertFalse("FUTURE" in checkNotNull(disk.value[presetKey]))
    }
}
