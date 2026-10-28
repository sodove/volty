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
import kotlinx.serialization.json.*
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
        val request = store.request(HistoryExportFormat.CSV)
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
        val request = store.request(HistoryExportFormat.CSV)
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

    private suspend fun legacyRequest(provider: VoltyDatabaseProvider, format: HistoryExportFormat = HistoryExportFormat.CSV,
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
        val text = StringBuilder()
        StreamingHistoryExportEncoder().write(HistoryExportFormat.CSV, input.metadata, input.records) { text.append(it) }
        val rows = structuredRecords(input.metadata, input.records)
        assertEquals(3, rows.size)
        val power = rows.filter { it.getValue("metric").jsonPrimitive.content == "power_w" }
        assertEquals(setOf(first, second), power.map { it.getValue("capturedTimestamp").jsonPrimitive.content }.toSet())
        assertEquals(setOf(-50.0, -60.0), power.map { it.getValue("value").jsonPrimitive.double }.toSet())
        assertTrue(rows.all { it.getValue("elapsedMillis") == JsonNull && it.getValue("clockGeneration") == JsonNull })
        val unknown = rows.single { it.getValue("metric").jsonPrimitive.content == "current_a" }
        assertEquals(JsonNull, unknown.getValue("value")); assertEquals("LEGACY", unknown.getValue("unknownReason").jsonPrimitive.content)
        assertFalse("4200" in text)
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
        val request = HistoryExportRequest(assertNotNull(store.ride("ride")).favoriteSource(), HistoryRange(1000, end.epochMillis), HistoryExportFormat.CSV)
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

    @Test fun retainedSummariesWithoutCapturedTimesAreExportedWithoutInventingSampleTimes() = runTest {
        val (provider, store) = fixture(); store.beginRide()
        val db = provider.database
        // Deliberately incoherent with current writers: migrated retained facts
        // have earned aggregates but none of the later captured-time columns.
        db.rideHistoryBucketQueries.putBucket("ride", "pack", "power_w", -1,
            10, 2010, -100.0, 20.0, 4.5, 100, 1, null, null, null, null, null, null, null, null, null)
        val request = store.request(HistoryExportFormat.CSV)
        val reader = SqlDelightHistoryExportSource(provider, pageSize = 1)
        for (structured in listOf(true, false)) {
            val input = reader.open(request); val text = StringBuilder()
            val counts = StreamingHistoryExportEncoder().write(HistoryExportFormat.CSV, input.metadata, input.records) { text.append(it) }
            assertEquals(1, counts.summaries, "retained facts must not silently disappear")
            if (structured) {
                val row = structuredRecords(input.metadata, input.records).single()
                assertEquals("summary", row.getValue("type").jsonPrimitive.content)
                assertEquals("RETAINED_SUMMARY_TIME_UNAVAILABLE", row.getValue("dataQuality").jsonPrimitive.content)
                assertEquals(JsonNull, row.getValue("epochMillis"))
                assertEquals(JsonNull, row.getValue("clockGeneration"))
                assertEquals(JsonNull, row.getValue("first").jsonObject.getValue("epochMillis"))
                assertEquals(JsonNull, row.getValue("minimum").jsonObject.getValue("epochMillis"))
                assertEquals(-100.0, row.getValue("minimum").jsonObject.getValue("value").jsonPrimitive.double)
                assertEquals(4.5, row.getValue("energyWh").jsonPrimitive.double)
                assertEquals(100, row.getValue("sampleCount").jsonPrimitive.int)
                assertEquals(10, row.getValue("coverageStartElapsedMillis").jsonPrimitive.int)
                assertEquals(2010, row.getValue("coverageEndElapsedMillis").jsonPrimitive.int)
            } else assertTrue("RETAINED_SUMMARY_TIME_UNAVAILABLE" in text)
        }
        val cropped = reader.open(request.copy(range = HistoryRange(1500, 4000)))
        assertTrue(cropped.records.toList().isEmpty(), "a retained bucket crossing the selected window cannot be cropped by guessing")
    }

    @Test fun summaryCompositePagingKeepsIncompleteEarnedFactsAndExcludesBucketsCrossingSelectedWindow() = runTest {
        val (provider, store) = fixture(); store.beginRide(); val db = provider.database
        for (source in listOf("pack2", "pack3")) db.rideHistorySourceQueries.insertSource("ride", source, "BATTERY", "Same label")
        fun bucket(source: String, first: Long, last: Long) {
            db.rideHistoryBucketQueries.putBucket("ride", source, "power_w", -1, 10, 10 + last,
                -100.0, 20.0, 4.5, 100, 0, 1000 + first, 10 + first, 1000 + last,
                1000 + first, 10 + first, 1000 + last, 10 + last, null, null)
        }
        bucket("pack", 1000, 2000); bucket("pack2", 1000, 2500); bucket("pack3", 0, 2000)
        val request = store.request(HistoryExportFormat.CSV).copy(range = HistoryRange(2000, 4000))
        val input = SqlDelightHistoryExportSource(provider, pageSize = 1).open(request)
        val rows = input.records.toList().map { assertIs<HistoryExportRecord.Summary>(it).bucket }
        assertEquals(listOf("pack", "pack2"), rows.map { it.key.sourceId })
        assertTrue(rows.all { !it.complete && it.sampleCount == 100L && it.energyWh == 4.5 })
        assertEquals(listOf(2000L, 2000L), rows.map { it.first.epochMillis })
        assertEquals(listOf(3000L, 3500L), rows.map { it.last.epochMillis })
        assertEquals(2, structuredRecords(input.metadata, input.records).size)
    }
}
