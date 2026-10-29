package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

/**
 * Task 7 of the compact-history-storage plan: the export (logui CSV, GPX) and its shape check read a compact (V3) ride.
 * Every test records ONE stream into both layouts and compares what the export produces.
 *
 * What "storage order" means for V3: the logui encoder measures lateness over the order records arrive in. For V2 that
 * is `ORDER BY sequence` over the ride; V3 has no sequence index, so the source merges the ride's series on their
 * STORED sequences (first members and run ends only; interpolated interior sequences may coincide across series).
 */
class SqlDelightHistoryExportSourceCompactTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val origin = HistoryTick(1_700_000_000_000, 5_000, 1)
    private fun tick(ms: Long) = HistoryTick(origin.epochMillis + ms, origin.elapsedMillis + ms, origin.clockGeneration)
    private val voltage = HistorySeriesKey("pack", "voltage_v")
    private val dead = HistorySeriesKey("pack", "current_a")
    private val power = HistorySeriesKey("pack", "power_w")
    private val speed = HistorySeriesKey("ctl", "speed_kmh")
    private val sources = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack"),
        HistorySource("ctl", HistorySourceKind.CONTROLLER, "Wheel"))

    private class Fixture(val provider: VoltyDatabaseProvider, val store: SqlDelightHistoryStore)
    private fun fixture(compact: Boolean): Fixture {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        return Fixture(provider, SqlDelightHistoryStore(provider, recordCompact = compact))
    }
    @AfterTest fun close() { drivers.forEach { it.close() } }

    private class Stream(val batches: List<HistoryBatch>, val endMs: Long)

    /**
     * T4-shaped, an UNEVEN 279-323 ms step: a known voltage, a dead current (unknown runs, cut by a BLE_GAP of its own
     * source), a speed with PARTIAL_SOURCE blocks and lone unknowns, a power delivered LATE (eight ticks behind its
     * siblings, so the logui encoder measures lateness in storage order), GPS at 1 Hz with an outage, a source-scoped
     * gap of the controller, a global GPS_GAP and OVERFLOW.
     */
    private fun stream(endMs: Long = 856_000): Stream {
        val perTick = ArrayList<List<HistorySample>>()
        var ms = 0L; var i = 0
        while (ms <= endMs) {
            perTick += listOf(HistorySample(tick(ms), voltage, 60.0 + (i % 10) * 0.1),
                HistorySample(tick(ms), dead, null, HistoryUnknownReason.UNAVAILABLE),
                if ((i / 50) % 3 == 1 || i % 7 == 3) HistorySample(tick(ms), speed, null, HistoryUnknownReason.PARTIAL_SOURCE)
                    else HistorySample(tick(ms), speed, 10.0 + (i % 13)))
            ms += 279L + (i * 37) % 45
            i++
        }
        val late = 8
        val samples = perTick.flatMapIndexed { index, tickSamples ->
            tickSamples + (if (index >= late) listOf(HistorySample(perTick[index - late][0].tick, power, 500.0 + (index % 31)))
                else emptyList())
        }
        val gps = (0..endMs / 1000).map {
            HistoryGpsFix(tick(it * 1000), 56.0 + it * 1e-5, 60.0 + it * 2e-5, 3.0, if (it % 3 == 0L) null else 90.0 + it % 5)
        }.filterNot { it.tick.elapsedMillis - origin.elapsedMillis in 300_000L..302_000L }
        val markers = listOf(HistoryMarker(tick(200_050), HistoryMarkerKind.BLE_GAP, "ctl"),
            HistoryMarker(tick(300_500), HistoryMarkerKind.GPS_GAP),
            HistoryMarker(tick(400_123), HistoryMarkerKind.BLE_GAP, "pack"),
            HistoryMarker(tick(600_500), HistoryMarkerKind.OVERFLOW)).filter { it.tick.elapsedMillis - origin.elapsedMillis <= endMs }
        val chunks = samples.chunked(128)
        val batches = chunks.mapIndexed { index, chunk ->
            val from = if (index == 0) Long.MIN_VALUE else chunk.minOf { it.tick.elapsedMillis - origin.elapsedMillis }
            val to = if (index == chunks.lastIndex) Long.MAX_VALUE else chunks[index + 1].minOf { it.tick.elapsedMillis - origin.elapsedMillis }
            HistoryBatch("ride", chunk,
                gps.filter { it.tick.elapsedMillis - origin.elapsedMillis in from until to },
                markers.filter { it.tick.elapsedMillis - origin.elapsedMillis in from until to }, "r$index")
        }
        return Stream(batches, ms)
    }

    private suspend fun record(stream: Stream, compact: Boolean): Fixture {
        val f = fixture(compact)
        val ride = HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING)
        f.store.begin(ride, sources)
        var projection = HistoryRecordingProjection(ride, sources)
        stream.batches.forEachIndexed { index, batch ->
            val prepared = projection.prepare(batch, index == stream.batches.lastIndex)
            projection = prepared.next
            f.store.append(prepared.batch)
        }
        f.store.finish("ride", tick(stream.endMs), HistoryRideStatus.COMPLETED)
        return f
    }

    private class Exported(val text: String, val counts: HistoryExportCounts, val records: List<HistoryExportRecord>)

    private suspend fun request(f: Fixture, format: HistoryExportFormat): HistoryExportRequest {
        val ride = assertNotNull(f.store.ride("ride"))
        val end = checkNotNull(historyAxisEpochMillis(ride.started, checkNotNull(ride.ended)))
        return HistoryExportRequest(ride.favoriteSource(), HistoryRange(origin.epochMillis, end), format)
    }

    private suspend fun export(f: Fixture, format: HistoryExportFormat, pageSize: Int = 512): Exported {
        val input = SqlDelightHistoryExportSource(f.provider, pageSize = pageSize).open(request(f, format))
        val records = input.records.toList()
        val text = StringBuilder()
        val counts = StreamingHistoryExportEncoder(zone = { TimeZone.UTC })
            .write(format, input.metadata, flow { records.forEach { emit(it) } }) { text.append(it) }
        return Exported(text.toString(), counts, records)
    }

    @Test fun loguiBytesEqualBetweenV2AndV3() = runTest {
        val s = stream()
        val v2 = record(s, compact = false); val v3 = record(s, compact = true)
        val q = v3.provider.database.rideHistoryCompactQueries
        assertEquals(1L, q.maxRideNoV3().executeAsOne())
        assertTrue(q.seriesOfRideV3(1L).executeAsList().flatMap { q.samplesOfSeriesV3(it.seriesId).executeAsList() }
            .any { it.runCount != null && it.runCount!! > 100 }, "the dead series is stored as long runs")
        val expected = export(v2, HistoryExportFormat.LOGUI)
        assertTrue(expected.text.lines().size > 2500, "a real CSV")
        assertTrue("gnss_lat" in expected.text && expected.counts.locations > 700 && expected.counts.samples > 5000)
        for (pageSize in listOf(1, 7, 512, 1024)) {
            val actual = export(v3, HistoryExportFormat.LOGUI, pageSize)
            assertEquals(expected.text, actual.text, "logui bytes at page size $pageSize")
            assertEquals(expected.counts, actual.counts, "counts at page size $pageSize")
        }
        // A run is two records, not its thousand members.
        val v3Unknown = export(v3, HistoryExportFormat.LOGUI).records.count { it is HistoryExportRecord.Numeric && it.sample.value == null }
        val v2Unknown = expected.records.count { it is HistoryExportRecord.Numeric && it.sample.value == null }
        assertTrue(v3Unknown < v2Unknown / 4, "v3 $v3Unknown unknown records, v2 $v2Unknown")
    }

    @Test fun gpxEqual() = runTest {
        val s = stream(endMs = 120_000)
        val v2 = export(record(s, false), HistoryExportFormat.GPX)
        val v3 = export(record(s, true), HistoryExportFormat.GPX)
        assertEquals(v2.text, v3.text)
        assertTrue(v3.text.contains("<trkpt") && v3.counts.locations > 100)
        // GPX reads no samples at all: not one numeric record is streamed, on either layout.
        assertTrue(v3.records.none { it is HistoryExportRecord.Numeric } && v2.records.none { it is HistoryExportRecord.Numeric })
        assertEquals(v2.records, v3.records)
    }

    @Test fun mergeReproducesSequenceOrder() = runTest {
        // 3 series with interleaved sequences 0..299 (the pattern is irregular, so every page spans several series).
        val keys = listOf(voltage, speed, power)
        val samples = (0 until 300).map { n -> HistorySample(tick(n * 100L), keys[(n * 7 + n / 5) % 3], n.toDouble()) }
        val f = fixture(compact = true)
        f.store.begin(HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING), sources)
        f.store.append(HistoryBatch("ride", samples, emptyList(), emptyList()))
        f.store.finish("ride", tick(30_000), HistoryRideStatus.COMPLETED)
        val q = f.provider.database.rideHistoryCompactQueries
        val stored = q.seriesOfRideV3(1L).executeAsList().flatMap { q.samplesOfSeriesV3(it.seriesId).executeAsList() }
        assertEquals(300, stored.size)
        assertEquals(3, q.seriesOfRideV3(1L).executeAsList().size)
        assertEquals(stored.size, stored.map { it.sequence }.distinct().size, "sequences are unique across series")
        val bySequence = stored.sortedBy { it.sequence }.map { it.reading }
        assertEquals(samples.map { it.value }, bySequence, "the stored sequences are the append order")
        for (pageSize in listOf(1, 4, 50, 1024)) {
            val records = export(f, HistoryExportFormat.LOGUI, pageSize).records.filterIsInstance<HistoryExportRecord.Numeric>()
            assertEquals(bySequence, records.map { it.sample.value }, "page size $pageSize: the emitted order is the sequence order")
        }
    }

    @Test fun shapeCountMatchesEmittedAndACommittedChangeIsCaught() = runTest {
        val f = record(stream(endMs = 60_000), compact = true)
        val q = f.provider.database.rideHistoryCompactQueries
        val series = q.seriesOfRideV3(1L).executeAsList()
        val rows = series.flatMap { q.samplesOfSeriesV3(it.seriesId).executeAsList() }
        val expected = rows.sumOf { if (it.unknownCode != null && it.runCount != null) 2L else 1L }
        assertTrue(rows.any { it.runCount != null } && rows.any { it.runCount == null })
        val clean = export(f, HistoryExportFormat.LOGUI, pageSize = 5)
        assertEquals(expected, clean.records.count { it is HistoryExportRecord.Numeric }.toLong(), "records emitted = the shape's count")

        // The ride is not RECORDING, so a change is emulated by writing rows directly while the export streams.
        val request = request(f, HistoryExportFormat.LOGUI)
        var inserted = false
        val added = assertFailsWith<HistoryExportException> {
            SqlDelightHistoryExportSource(f.provider, pageSize = 5).open(request).records.collect {
                if (!inserted) {
                    inserted = true
                    q.insertSampleV3(series.first().seriesId, origin.elapsedMillis + 30_001, 9_999_999, 0, 0, 1.0, null, null, null, null, null)
                }
            }
        }
        assertTrue(inserted)
        assertEquals(HistoryExportFailure.SOURCE_CHANGED, added.reason)
        q.deleteSamplesOfSeriesV3(series.first().seriesId) // also removes the inserted row: the ride now lacks a series' rows
        var removed = false
        val gone = assertFailsWith<HistoryExportException> {
            SqlDelightHistoryExportSource(f.provider, pageSize = 5).open(request).records.collect {
                if (!removed) { removed = true; q.deleteSamplesOfSeriesV3(series.last().seriesId) }
            }
        }
        assertTrue(removed)
        assertEquals(HistoryExportFailure.SOURCE_CHANGED, gone.reason)
    }
}
