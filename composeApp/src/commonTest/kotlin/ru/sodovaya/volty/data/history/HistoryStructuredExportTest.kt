package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryStructuredExportTest {
    private val start = HistoryTick(1000, 10, 1)
    private val end = HistoryTick(2000, 1010, 1)
    private val address = "AA:BB:CC:DD:EE:FF"
    private val label = "=Мой \"аккум\",\nпак"
    private val key = HistorySeriesKey(address, "power_w")
    private fun metadata(sourceLabel: String = label) = HistoryExportMetadata(
        HistoryRide("private-record-id", "C:/Users/private/vehicle", start, end, HistoryRideStatus.COMPLETED),
        HistoryRange(1000, 2000), listOf(HistorySource(address, HistorySourceKind.BATTERY, sourceLabel)), "Поездка")
    private val known get() = HistoryExportRecord.Numeric(HistorySample(start, key, -50.0))
    private val unknown get() = HistoryExportRecord.Numeric(HistorySample(end, key, null, HistoryUnknownReason.PARTIAL_SOURCE))

    @Test fun jsonHasVersionQualityUnitsSignsAndExplicitUnknownWithoutPrivateStorageIdentities() = runTest {
        val output = StringBuilder()
        val counts = StreamingHistoryExportEncoder().write(HistoryExportFormat.JSON, metadata(), flowOf(known, unknown)) { output.append(it) }
        val document = Json.parseToJsonElement(output.toString()).jsonObject
        assertEquals(1, document.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("RETAINED_DETAIL_AND_SUMMARIES", document.getValue("dataQuality").jsonPrimitive.content)
        assertEquals("ORIGIN_PLUS_ELAPSED", document.getValue("range").jsonObject.getValue("axis").jsonPrimitive.content)
        val source = document.getValue("sources").jsonArray.single().jsonObject
        assertEquals("source-1", source.getValue("source").jsonPrimitive.content)
        assertEquals("BATTERY", source.getValue("kind").jsonPrimitive.content)
        assertEquals(label, source.getValue("label").jsonPrimitive.content)
        val rows = document.getValue("records").jsonArray.map { it.jsonObject }
        assertEquals(2, rows.size); assertEquals(2L, counts.samples)
        assertEquals("source-1", rows[0].getValue("source").jsonPrimitive.content)
        assertEquals("WATT", rows[0].getValue("unit").jsonPrimitive.content)
        assertEquals(-50.0, rows[0].getValue("value").jsonPrimitive.double)
        assertEquals(-1.0, rows[0].getValue("displayMultiplier").jsonPrimitive.double)
        assertEquals(-1.0, rows[0].getValue("consumptionMultiplier").jsonPrimitive.double)
        assertEquals(1000L, rows[0].getValue("epochMillis").jsonPrimitive.long)
        assertEquals(10L, rows[0].getValue("elapsedMillis").jsonPrimitive.long)
        assertEquals(JsonNull, rows[1].getValue("value"))
        assertEquals("PARTIAL_SOURCE", rows[1].getValue("unknownReason").jsonPrimitive.content)
        assertFalse(address in output); assertFalse("private-record-id" in output); assertFalse("C:/Users/private" in output)
    }

    @Test fun csvEscapesMultilineUnicodeLabelsAndFormulaPrefixesButKeepsNumericSigns() = runTest {
        val output = StringBuilder()
        StreamingHistoryExportEncoder().write(HistoryExportFormat.CSV, metadata(), flowOf(known, unknown)) { output.append(it) }
        val rows = readCsv(output.toString()); val header = rows.first()
        assertEquals(3, rows.size)
        fun value(row: Int, name: String) = rows[row][header.indexOf(name).also { assertTrue(it >= 0, name) }]
        assertEquals("'" + label, value(1, "source_label"))
        assertEquals("source-1", value(1, "source")); assertEquals("WATT", value(1, "unit"))
        assertEquals("ORIGIN_PLUS_ELAPSED", value(1, "range_axis"))
        assertEquals(-50.0, value(1, "value").toDouble())
        assertEquals("", value(2, "value")); assertEquals("PARTIAL_SOURCE", value(2, "unknown_reason"))
        assertFalse(address in output); assertFalse("C:/Users/private" in output)
    }

    @Test fun formattedSourceLabelsDoNotLeakAnEmbeddedDeviceAddress() = runTest {
        for (format in listOf(HistoryExportFormat.JSON, HistoryExportFormat.CSV)) {
            val output = StringBuilder()
            StreamingHistoryExportEncoder().write(format, metadata("BMS $address"), flowOf(known)) { output.append(it) }
            assertFalse(address in output, format.name)
            assertTrue("BMS [device]" in output, format.name)
        }
    }

    @Test fun structuredSinkCancellationStopsBeforeTheNextInputRecord() = runTest {
        for (format in listOf(HistoryExportFormat.CSV, HistoryExportFormat.JSON)) {
            var secondRead = false; var writtenSample = false
            val records = flow { emit(known); secondRead = true; emit(unknown) }
            assertFailsWith<CancellationException> {
                StreamingHistoryExportEncoder().write(format, metadata(), records) {
                    if ("-50.0" in it) { writtenSample = true; throw CancellationException("explicit export cancel") }
                }
            }
            assertTrue(writtenSample); assertFalse(secondRead)
        }
    }

    @Test fun unresolvedSourceCannotBeRelabeledAsAnotherBatteryOrExportedAsZero() = runTest {
        val missing = HistoryExportRecord.Numeric(HistorySample(start, key.copy(sourceId = "missing"), null, HistoryUnknownReason.UNAVAILABLE))
        for (format in listOf(HistoryExportFormat.CSV, HistoryExportFormat.JSON)) {
            val error = assertFailsWith<HistoryExportException> {
                StreamingHistoryExportEncoder().write(format, metadata(), flowOf(missing)) {}
            }
            assertEquals(HistoryExportFailure.INVALID_DATA, error.reason)
        }
    }

    @Test fun fullyContainedIncompleteSummaryKeepsOriginalCoverageAndUnknownCompleteness() = runTest {
        val minimum = HistorySample(start, key, -100.0)
        val maximum = HistorySample(end, key, 20.0)
        val bucket = HistoryBucket(key, 0, start, end, minimum, maximum, 4.5, 100, false)
        val output = StringBuilder()
        val selected = metadata()
        StreamingHistoryExportEncoder().write(HistoryExportFormat.JSON, selected,
            flowOf(HistoryExportRecord.Summary(bucket))) { output.append(it) }
        val record = Json.parseToJsonElement(output.toString()).jsonObject.getValue("records").jsonArray.single().jsonObject
        assertEquals("ORIGINAL_BUCKET", record.getValue("coverage").jsonPrimitive.content)
        assertFalse(record.getValue("overlapsRangeBoundary").jsonPrimitive.boolean)
        assertFalse(record.getValue("complete").jsonPrimitive.boolean)
        assertEquals(100L, record.getValue("sampleCount").jsonPrimitive.long)
        assertEquals(4.5, record.getValue("energyWh").jsonPrimitive.double)
        assertEquals(1000L, record.getValue("first").jsonObject.getValue("epochMillis").jsonPrimitive.long)
        assertEquals(2000L, record.getValue("last").jsonObject.getValue("epochMillis").jsonPrimitive.long)
        assertEquals(-100.0, record.getValue("minimum").jsonObject.getValue("value").jsonPrimitive.double)
        assertEquals(20.0, record.getValue("maximum").jsonObject.getValue("value").jsonPrimitive.double)
    }
    @Test fun summaryCrossingSelectedWindowCannotLeakOutsideFactsOrBeProportionallyRescaled() = runTest {
        val bucket = HistoryBucket(key, 0, start, end, HistorySample(start, key, -100.0),
            HistorySample(end, key, 20.0), 4.5, 100, false)
        for (format in listOf(HistoryExportFormat.JSON, HistoryExportFormat.CSV)) {
            val error = assertFailsWith<HistoryExportException> {
                StreamingHistoryExportEncoder().write(format, metadata().copy(range = HistoryRange(1250, 1750)),
                    flowOf(HistoryExportRecord.Summary(bucket))) {}
            }
            assertEquals(HistoryExportFailure.INVALID_DATA, error.reason)
        }
    }

    @Test fun privateAbsolutePathsEmbeddedInDisplayLabelsAreRedactedForBothStructuredFormats() = runTest {
        for (format in listOf(HistoryExportFormat.JSON, HistoryExportFormat.CSV)) {
            val output = StringBuilder()
            val selected = metadata("Pack /data/user/0/ru.sodovaya.volty/files/private.bin")
                .copy(title = "Ride C:\\Users\\private\\source.json")
            StreamingHistoryExportEncoder().write(format, selected, flowOf(known)) { output.append(it) }
            assertFalse("/data/user/0/" in output, format.name)
            assertFalse("Users" in output, format.name)
            assertTrue("Pack [path]" in output, format.name)
        }
    }
    @Test fun unmappedLegacyFactsRetainRawEvidenceWithoutInventingSourceUnitOrCanonicalValue() = runTest {
        val selected = metadata().copy(ride = metadata().ride.copy(status = HistoryRideStatus.LEGACY), sources = emptyList())
        val raw = HistoryExportRecord.LegacyUnmapped(HistoryTick(1000, 0, 0), "1970-01-01T00:00:01.000100Z",
            "=FUTURE_VALUE", null, 321.0, true)
        val output = StringBuilder()
        val counts = StreamingHistoryExportEncoder().write(HistoryExportFormat.JSON, selected, flowOf(raw)) { output.append(it) }
        val record = Json.parseToJsonElement(output.toString()).jsonObject.getValue("records").jsonArray.single().jsonObject
        assertEquals("legacy_unmapped", record.getValue("type").jsonPrimitive.content)
        assertEquals(JsonNull, record.getValue("source")); assertEquals(JsonNull, record.getValue("unit"))
        assertEquals(JsonNull, record.getValue("value")); assertEquals(JsonNull, record.getValue("elapsedMillis"))
        assertEquals("LEGACY_UNMAPPED", record.getValue("unknownReason").jsonPrimitive.content)
        assertEquals(321.0, record.getValue("legacyRawValue").jsonPrimitive.double)
        assertEquals(raw.capturedTimestamp, record.getValue("capturedTimestamp").jsonPrimitive.content)
        assertEquals(1L, counts.unmapped)
        val csv = StringBuilder()
        StreamingHistoryExportEncoder().write(HistoryExportFormat.CSV, selected, flowOf(raw.copy(known = false))) { csv.append(it) }
        val rows = readCsv(csv.toString()); val header = rows.first()
        assertEquals("", rows[1][header.indexOf("value")]); assertEquals("", rows[1][header.indexOf("legacy_raw_value")])
        assertEquals("'=FUTURE_VALUE", rows[1][header.indexOf("legacy_metric")])
    }

    /** Independent RFC4180 reader for the small synthetic fixtures, including embedded CR/LF. */
    private fun readCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val field = StringBuilder()
        var quoted = false; var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char == '"') {
                if (quoted && text.getOrNull(index) == '"') { field.append('"'); index++ } else quoted = !quoted
            } else if (!quoted && char == ',') { row += field.toString(); field.clear() }
            else if (!quoted && char == '\r' && text.getOrNull(index) == '\n') {
                index++; row += field.toString(); field.clear(); rows += row.toList(); row.clear()
            } else if (!quoted && char == '\n') {
                row += field.toString(); field.clear(); rows += row.toList(); row.clear()
            } else field.append(char)
        }
        assertFalse(quoted)
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row.toList() }
        return rows
    }
}
