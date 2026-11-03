package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.*
import ru.sodovaya.volty.domain.history.*
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.test.*

class HistoryExportSourceTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val origin = HistoryTick(1000, 10, 1)
    private val key = HistorySeriesKey("pack", "power_w")
    private fun tick(offset: Long) = origin.copy(epochMillis = 1000 + offset, elapsedMillis = 10 + offset)
    private fun fixture(): Pair<VoltyDatabaseProvider, SqlDelightHistoryStore> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers += it }
        VoltyDatabase.Schema.create(driver)
        val provider = VoltyDatabaseProvider(driver)
        return provider to SqlDelightHistoryStore(provider)
    }
    private suspend fun SqlDelightHistoryStore.beginRide() {
        begin(HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING),
            listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Батарея")))
    }
    private suspend fun SqlDelightHistoryStore.request(format: HistoryExportFormat): HistoryExportRequest {
        finish("ride", tick(4000), HistoryRideStatus.COMPLETED)
        return HistoryExportRequest(assertNotNull(ride("ride")).favoriteSource(), HistoryRange(1000, 5000), format)
    }
    @AfterTest fun close() { drivers.forEach { it.close() } }

    @Test fun pagedStoredDetailRetainsEqualTimeRowsAndExplicitUnknownRatherThanOverviewOrZero() = runTest {
        val (provider, store) = fixture(); store.beginRide()
        val samples = (1..4).map { HistorySample(origin, key, it.toDouble()) } +
            HistorySample(origin, key, null, HistoryUnknownReason.PARTIAL_SOURCE)
        store.append(HistoryBatch("ride", samples, emptyList(), emptyList()))
        val request = store.request(HistoryExportFormat.LOGUI)
        val input = SqlDelightHistoryExportSource(provider, pageSize = 2).open(request)
        assertEquals(request.source, input.metadata.ride.favoriteSource())
        val output = input.records.toList().filterIsInstance<HistoryExportRecord.Numeric>().map { it.sample }
        assertEquals(samples, output)
        assertEquals(5, output.size); assertEquals(1, output.count { it.value == null })
        assertEquals(HistoryUnknownReason.PARTIAL_SOURCE, output.last().unknown)
    }

    @Test fun pagedRouteMergesBoundaryEventsBetweenFixesWithoutBridgingGpsOutage() = runTest {
        val (provider, store) = fixture(); store.beginRide()
        val fixes = (0..3).map { HistoryGpsFix(tick(it * 1000L), 56.0, 60.0 + it / 10000.0, 3.0, null) }
        store.append(HistoryBatch("ride", emptyList(), fixes,
            listOf(HistoryMarker(tick(1500), HistoryMarkerKind.GPS_GAP))))
        val request = store.request(HistoryExportFormat.GPX)
        val input = SqlDelightHistoryExportSource(provider, pageSize = 2).open(request)
        val text = StringBuilder()
        StreamingHistoryExportEncoder().write(request.format, input.metadata, input.records) { text.append(it) }
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(text.toString())))
        assertEquals(4, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
        val points = document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt")
        assertEquals(List(4) { 56.0 }, (0 until points.length).map { points.item(it).attributes.getNamedItem("lat").nodeValue.toDouble() })
        assertEquals(2, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkseg").length)
    }

    @Test fun sourceIdentityCannotFallbackToAnotherRecordingAndDeletionDuringReadCannotCompleteStream() = runTest {
        val (provider, store) = fixture(); store.beginRide()
        store.append(HistoryBatch("ride", (1..5).map { HistorySample(tick(it * 100L), key, it.toDouble()) }, emptyList(), emptyList()))
        val request = store.request(HistoryExportFormat.LOGUI)
        val reader = SqlDelightHistoryExportSource(provider, pageSize = 2)
        val mismatch = assertFailsWith<HistoryExportException> { reader.open(request.copy(source = request.source.copy(vehicleId = "other"))) }
        assertEquals(HistoryExportFailure.SOURCE_CHANGED, mismatch.reason)
        val input = reader.open(request); var issuedDeletion = false
        val failure = assertFailsWith<HistoryExportException> {
            input.records.collect {
                if (!issuedDeletion) { issuedDeletion = true; store.delete("ride") }
            }
        }
        assertTrue(issuedDeletion); assertNull(store.ride("ride"))
        assertEquals(HistoryExportFailure.SOURCE_CHANGED, failure.reason)
    }

    private suspend fun legacyRequest(provider: VoltyDatabaseProvider, format: HistoryExportFormat = HistoryExportFormat.LOGUI,
        range: HistoryRange = HistoryRange(1000, 3000)) = HistoryExportRequest(
        assertNotNull(LegacyHistoryReader(provider).ride("old")).favoriteSource(), range, format)

    @Test fun legacyPagingKeepsSubmillisecondTimestampIdentityCanonicalSignsAndUnknownInsteadOfGarbageValue() = runTest {
        val (provider, _) = fixture(); val db = provider.database
        db.rideRowQueries.insert("old", "old-bike", "1970-01-01T00:00:01Z", "1970-01-01T00:00:03Z")
        val first = "1970-01-01T00:00:01.000100Z"; val second = "1970-01-01T00:00:01.000900Z"
        db.ridePointRowQueries.insert("old", "POWER", first, -1, 50.0, 1)
        db.ridePointRowQueries.insert("old", "POWER", second, -1, 60.0, 1)
        db.ridePointRowQueries.insert("old", "CURRENT", "1970-01-01T00:00:02Z", -1, 4200.0, 0)
        val input = SqlDelightHistoryExportSource(provider, pageSize = 1).open(legacyRequest(provider))
        // Retraction: this read the long CSV's per-record objects; that export is gone, so the
        // source's own records are checked, then the logui file that is written from them.
        val rows = input.records.toList().map { assertIs<HistoryExportRecord.Numeric>(it) }
        assertEquals(3, rows.size)
        val power = rows.filter { it.sample.key.metricId == "power_w" }
        assertEquals(setOf(first, second), power.map { it.capturedTimestamp }.toSet())
        assertEquals(setOf(-50.0, -60.0), power.map { it.sample.value }.toSet())
        val unknown = rows.single { it.sample.key.metricId == "current_a" }
        assertNull(unknown.sample.value); assertEquals(HistoryUnknownReason.LEGACY, unknown.sample.unknown)
        val text = StringBuilder()
        StreamingHistoryExportEncoder().write(HistoryExportFormat.LOGUI, input.metadata, input.records) { text.append(it) }
        assertFalse("4200" in text)
        assertFalse("bms_current" in text, "a series with only unknown samples earns no column")
    }

    @Test fun legacyUnmappedMetricsAreRetainedAndTwinV2IdCannotReplaceLegacySourceOrSupplyGps() = runTest {
        val (provider, store) = fixture(); val db = provider.database
        db.rideRowQueries.insert("old", "old-bike", "1970-01-01T00:00:01Z", "1970-01-01T00:00:03Z")
        db.ridePointRowQueries.insert("old", "FUTURE_VALUE", "1970-01-01T00:00:01Z", -1, 321.0, 1)
        store.begin(HistoryRide("old", "new-bike", origin, null, HistoryRideStatus.RECORDING), emptyList())
        val reader = SqlDelightHistoryExportSource(provider, pageSize = 1)
        val request = legacyRequest(provider)
        val input = reader.open(request)
        assertEquals(HistoryStorageKind.LEGACY, input.metadata.ride.favoriteSource().kind)
        val raw = assertIs<HistoryExportRecord.LegacyUnmapped>(input.records.toList().single())
        assertEquals("FUTURE_VALUE", raw.metric); assertEquals(321.0, raw.rawValue)
        assertEquals("new-bike", store.ride("old")?.vehicleId)
        val noRoute = assertFailsWith<HistoryExportException> { reader.open(request.copy(format = HistoryExportFormat.GPX)) }
        assertEquals(HistoryExportFailure.NO_ROUTE, noRoute.reason)
        val mismatch = assertFailsWith<HistoryExportException> { reader.open(request.copy(source = request.source.copy(vehicleId = "wrong"))) }
        assertEquals(HistoryExportFailure.SOURCE_CHANGED, mismatch.reason)
    }

    @Test fun unclosedLegacyRequiresExplicitInactiveAuthorityAndUsesOnlyPersistedBoundsWithoutInventingEnd() = runTest {
        val (provider, _) = fixture(); val db = provider.database
        db.rideRowQueries.insert("old", "old-bike", "1970-01-01T00:00:01Z", null)
        db.ridePointRowQueries.insert("old", "POWER", "1970-01-01T00:00:01Z", -1, 50.0, 1)
        db.ridePointRowQueries.insert("old", "POWER", "1970-01-01T00:00:02Z", -1, 60.0, 1)
        val request = legacyRequest(provider, range = HistoryRange(1000, 2000))
        for (authority in listOf<(String) -> Boolean?>({ null }, { true })) {
            val error = assertFailsWith<HistoryExportException> {
                SqlDelightHistoryExportSource(provider, isLegacyActive = authority).open(request)
            }
            assertEquals(HistoryExportFailure.ACTIVE_RECORDING, error.reason)
        }
        val reader = SqlDelightHistoryExportSource(provider, pageSize = 1, isLegacyActive = { false })
        val input = reader.open(request)
        assertNull(input.metadata.ride.ended); assertEquals(2, input.records.toList().size)
        assertNull(db.rideRowQueries.selectById("old").executeAsOne().endedAt)
        val tooWide = assertFailsWith<HistoryExportException> { reader.open(request.copy(range = HistoryRange(1000, 1000000))) }
        assertEquals(HistoryExportFailure.INVALID_DATA, tooWide.reason)
    }
    @Test fun multiHourRetainedDetailBeyondTwentyThousandRowsStreamsFullyIncludingUnknowns() = runTest {
        val (provider, store) = fixture(); store.beginRide()
        val total = 21005
        for (batchStart in 0 until total step 500) {
            val samples = (batchStart until minOf(total, batchStart + 500)).map { index ->
                val unknown = index % 1000 == 0
                HistorySample(tick(index * 500L), key, if (unknown) null else -index.toDouble(),
                    if (unknown) HistoryUnknownReason.GAP else null)
            }
            store.append(HistoryBatch("ride", samples, emptyList(), emptyList()))
        }
        val end = tick(total * 500L)
        store.finish("ride", end, HistoryRideStatus.COMPLETED)
        val request = HistoryExportRequest(assertNotNull(store.ride("ride")).favoriteSource(), HistoryRange(1000, end.epochMillis), HistoryExportFormat.LOGUI)
        val input = SqlDelightHistoryExportSource(provider, pageSize = 127).open(request)
        var count = 0; var unknowns = 0; var last: HistorySample? = null
        input.records.collect { record ->
            val sample = assertIs<HistoryExportRecord.Numeric>(record).sample
            count++; if (sample.unknown != null) unknowns++; last = sample
        }
        assertEquals(total, count); assertEquals(22, unknowns)
        assertEquals(-(total - 1).toDouble(), last?.value)
        assertEquals(tick((total - 1) * 500L), last?.tick)
    }

    @Test fun summariesAreNotReadForAnyExportFormat() = runTest {
        // Retraction: two tests here proved the long CSV/JSON paged summaries (retained ones without
        // captured times, and buckets crossing the window). Both exports were removed on 2026-10-29.
        val (provider, store) = fixture(); store.beginRide()
        provider.database.rideHistoryBucketQueries.putBucket("ride", "pack", "power_w", -1,
            10, 2010, -100.0, 20.0, 4.5, 100, 1, null, null, null, null, null, null, null, null, null)
        val request = store.request(HistoryExportFormat.LOGUI)
        for (format in HistoryExportFormat.entries) {
            assertTrue(SqlDelightHistoryExportSource(provider, pageSize = 1).open(request.copy(format = format)).records.toList().isEmpty(), format.name)
        }
    }
}
