package ru.sodovaya.volty.data.history

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

/**
 * The GPS speed series of a battery-only ride, end to end through the real recorder, writer, compact (V3) store,
 * readers and logui export (2026-11-04). Every assertion is on what was STORED or EXPORTED, never on an intermediate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryGpsSpeedRecordingTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    @AfterTest fun close() { drivers.forEach { it.close() } }

    private val pack = HistorySource("pack", HistorySourceKind.BATTERY, "Pack")
    private val esc = HistorySource("esc", HistorySourceKind.CONTROLLER, "ESC")
    private val gpsKey = HistorySeriesKey(HISTORY_GPS_SPEED_SOURCE_ID, HISTORY_GPS_SPEED_METRIC)
    private fun tick(offset: Long) = HistoryTick(1_700_000_000_000 + offset, 100 + offset, 1)
    private fun fix(offset: Long) = HistoryGpsFix(tick(offset), 56.0 + offset * 1e-6, 60.0, 4.0, null, HistoryGpsProvider.GPS)

    private class Rig(val driver: JdbcSqliteDriver, val provider: VoltyDatabaseProvider, val store: SqlDelightHistoryStore,
        val writer: BufferedHistoryWriter, val controller: RideRecordingController) {
        fun count(sql: String): Long = driver.executeQuery(null, sql, { c ->
            check(c.next().value); QueryResult.Value(checkNotNull(c.getLong(0)))
        }, 0).value
    }

    private fun TestScope.rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = SqlDelightHistoryStore(provider, dispatcher)
        val writer = BufferedHistoryWriter(store, dispatcher, emptyFlow(), projectRecording = true)
        val controller = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride" }, { store.recoverInterrupted() })
        return Rig(driver, provider, store, writer, controller)
    }

    /** 1 Hz fixes: 5, 6 m/s, three fixes that reported no speed, 7.5, then a measured standstill. */
    private val speeds = listOf(5.0, 6.0, null, null, null, 7.5, 0.0)

    private suspend fun Rig.ride(sources: List<HistorySource>) {
        controller.execute(RecordingInput.Connected("bike", 7, tick(0)), sources)
        speeds.forEachIndexed { i, speed ->
            assertEquals(HistoryOffer.ACCEPTED, controller.offerGps("ride", fix(1_000L * (i + 1)), speed))
        }
        controller.execute(RecordingInput.Stop(tick(8_000), 7, "ride"))
        writer.close()
    }

    @Test fun a_battery_only_ride_stores_its_gps_speed_as_its_own_series_with_honest_unknowns() = runTest {
        val r = rig(); r.ride(listOf(pack))
        assertEquals(setOf(pack, historyGpsSpeedSource), r.store.sources("ride").toSet())
        val range = HistoryRange(tick(0).epochMillis, tick(8_000).epochMillis)
        val samples = r.store.samples("ride", gpsKey, range, null).items
        assertEquals(listOf(1_100L, 2_100L, 3_100L, 4_100L, 5_100L, 6_100L, 7_100L), samples.map { it.tick.elapsedMillis })
        assertEquals(listOf(18.0, 21.6, null, null, null, 27.0, 0.0), samples.map { it.value?.let { v -> Math.round(v * 1e6) / 1e6 } })
        assertEquals(List(3) { HistoryUnknownReason.UNAVAILABLE }, samples.filter { it.value == null }.map { it.unknown })
        // V3: the three unknowns are ONE run row, the four readings four rows.
        assertEquals(5L, r.count("SELECT COUNT(*) FROM HistorySampleV3 s JOIN HistorySeriesV3 r ON s.seriesId = r.seriesId " +
            "WHERE r.metricId = '$HISTORY_GPS_SPEED_METRIC'"))
        assertEquals(1L, r.count("SELECT COUNT(*) FROM HistorySampleV3 WHERE runCount = 3"))
        val stats = r.store.stats("ride", gpsKey, range)
        assertEquals(27.0, assertNotNull(stats.maximum), 1e-9)
        assertEquals(0.0, assertNotNull(stats.minimum), 1e-9)
        assertNull(stats.energyWh, "a speed has no energy")
        // The track itself is unchanged: seven fixes.
        assertEquals(7, r.store.gps("ride", range, null, 100).items.size)
        // The controller series does not exist: nothing was mixed into `speed_kmh`.
        assertEquals(0L, r.count("SELECT COUNT(*) FROM HistorySeriesV3 WHERE metricId = 'speed_kmh'"))
        assertEquals(listOf(gpsKey), r.store.recordedKeys("ride", HISTORY_GPS_SPEED_SOURCE_ID, 10).keys)
    }

    @Test fun a_ride_with_a_controller_records_no_gps_speed_even_when_the_fixes_carry_one() = runTest {
        val r = rig(); r.ride(listOf(pack, esc))
        assertEquals(setOf(pack, esc), r.store.sources("ride").toSet())
        assertEquals(0L, r.count("SELECT COUNT(*) FROM HistorySeriesV3"))
        assertEquals(7, r.store.gps("ride", HistoryRange(tick(0).epochMillis, tick(8_000).epochMillis), null, 100).items.size)
    }

    @Test fun the_logui_export_writes_vesc_tools_gnss_speed_column_and_never_the_controller_one() = runTest {
        val r = rig(); r.ride(listOf(pack))
        val ride = assertNotNull(r.store.ride("ride"))
        val request = HistoryExportRequest(ride.favoriteSource(),
            HistoryRange(tick(0).epochMillis, tick(8_000).epochMillis), HistoryExportFormat.LOGUI)
        val input = SqlDelightHistoryExportSource(r.provider).open(request)
        val records = input.records.toList()
        val text = StringBuilder()
        StreamingHistoryExportEncoder(zone = { TimeZone.UTC })
            .write(HistoryExportFormat.LOGUI, input.metadata, flow { records.forEach { emit(it) } }) { text.append(it) }
        val lines = text.toString().trimEnd('\n').split('\n')
        val keys = lines.first().split(';').map { it.substringBefore(':') }
        assertTrue("kmh_gnss" in keys, lines.first())
        assertTrue(lines.first().contains("kmh_gnss:Speed GNSS:km/h:2:0:0"), lines.first())
        assertFalse("kmh_vesc" in keys, "a GPS speed is not the controller's")
        val column = keys.indexOf("kmh_gnss")
        val values = lines.drop(1).map { it.split(';')[column] }.filter { it.isNotEmpty() }
        assertEquals(listOf("18", "21.6", "27", "0"), values, "unknowns are empty cells, never a held or zero value")
    }

    @Test fun gnss_clock_jitter_fragments_the_unknown_run_but_loses_no_sample() = runTest {
        // Review M1: on a device a fix's epoch is GNSS time and its elapsed is the phone's monotonic clock, so their
        // difference jitters by more than the 15 ms run tolerance. Deliberately incoherent clocks, +-20 ms per fix.
        // Pinned behaviour: the three speedless fixes are stored as three rows (no run), every value reads back.
        val r = rig()
        val jitter = listOf(0L, 20L, -20L, 20L, -20L, 0L, 20L)
        r.controller.execute(RecordingInput.Connected("bike", 7, tick(0)), listOf(pack))
        speeds.forEachIndexed { i, speed ->
            val t = tick(1_000L * (i + 1))
            val f = HistoryGpsFix(t.copy(epochMillis = t.epochMillis + jitter[i]), 56.0, 60.0, 4.0, null, HistoryGpsProvider.GPS)
            assertEquals(HistoryOffer.ACCEPTED, r.controller.offerGps("ride", f, speed))
        }
        r.controller.execute(RecordingInput.Stop(tick(8_000), 7, "ride"))
        r.writer.close()
        val range = HistoryRange(tick(0).epochMillis, tick(8_100).epochMillis)
        val samples = r.store.samples("ride", gpsKey, range, null).items
        assertEquals(listOf(18.0, 21.6, null, null, null, 27.0, 0.0), samples.map { it.value?.let { v -> Math.round(v * 1e6) / 1e6 } })
        assertEquals(jitter.mapIndexed { i, j -> tick(1_000L * (i + 1)).epochMillis + j }, samples.map { it.tick.epochMillis },
            "each fix keeps its own GNSS epoch exactly")
        assertEquals(0L, r.count("SELECT COUNT(*) FROM HistorySampleV3 WHERE runCount IS NOT NULL"))
        assertEquals(7L, r.count("SELECT COUNT(*) FROM HistorySampleV3"))
        assertEquals(27.0, assertNotNull(r.store.stats("ride", gpsKey, range).maximum), 1e-9)
    }
}
