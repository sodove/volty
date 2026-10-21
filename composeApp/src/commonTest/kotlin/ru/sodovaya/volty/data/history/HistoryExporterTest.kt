package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.history.*
import java.io.StringReader
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.test.*

class HistoryExporterTest {
    private val origin = HistoryTick(1_000, 10, 1)
    private fun tick(offset: Long) = origin.copy(epochMillis = origin.epochMillis + offset, elapsedMillis = origin.elapsedMillis + offset)
    private fun fix(offset: Long) = HistoryExportRecord.Location(HistoryGpsFix(tick(offset), 56.0 + offset / 1e6, 60.0, 3.0, null))
    private fun metadata(status: HistoryRideStatus = HistoryRideStatus.COMPLETED) = HistoryExportMetadata(
        HistoryRide("private-record-id", "AA:BB:CC:DD:EE:FF", origin, tick(20_000), status),
        HistoryRange(1_000, 21_000), emptyList(), "Поездка <&> \"домой\"")
    private fun parse(text: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(InputSource(StringReader(text)))

    @Test fun gpxOutageProducesTwoParsedSegmentsAndRetainsBothSidesWithoutPrivateIds() = runTest {
        val text = StringBuilder()
        val rows = listOf(fix(0), fix(1000), HistoryExportRecord.Event(HistoryMarker(tick(1500), HistoryMarkerKind.GPS_GAP)),
            fix(2000), fix(3000))
        val counts = StreamingHistoryExportEncoder().write(HistoryExportFormat.GPX, metadata(), rows.asFlow()) { text.append(it) }
        val document = parse(text.toString())
        assertEquals(2, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkseg").length)
        assertEquals(4, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
        assertEquals(metadata().title, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "name").item(0).textContent)
        assertEquals("1970-01-01T00:00:01Z", document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "time").item(0).textContent)
        assertEquals(4L, counts.locations)
        assertFalse("private-record-id" in text); assertFalse("AA:BB:CC:DD:EE:FF" in text)
    }

    @Test fun legacyWithoutRecordedGpsReturnsNoRouteInsteadOfPublishingEmptyOrLiveRoute() = runTest {
        val emitted = mutableListOf<String>()
        val failure = assertFailsWith<HistoryExportException> {
            StreamingHistoryExportEncoder().write(HistoryExportFormat.GPX, metadata(HistoryRideStatus.LEGACY),
                flowOf(HistoryExportRecord.Numeric(HistorySample(origin, HistorySeriesKey("old", "voltage_v"), null, HistoryUnknownReason.LEGACY)))) { emitted += it }
        }
        assertEquals(HistoryExportFailure.NO_ROUTE, failure.reason)
        assertFalse(emitted.any { "<trkpt" in it })
    }

    @Test fun gpsSilenceBreaksTrackButEqualTimeDistinctFixesAreNotDropped() = runTest {
        val output = StringBuilder()
        StreamingHistoryExportEncoder(2_000).write(HistoryExportFormat.GPX, metadata(),
            flowOf(fix(0), fix(0), fix(1000), fix(9000))) { output.append(it) }
        val document = parse(output.toString())
        assertEquals(2, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkseg").length)
        assertEquals(4, document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
    }

    @Test fun encoderStreamsAndSinkCancellationStopsBeforeRequestingNextPage() = runTest {
        var secondPageRequested = false; var pointFragments = 0
        val rows = flow {
            emit(fix(0)); secondPageRequested = true; emit(fix(1000))
        }
        assertFailsWith<CancellationException> {
            StreamingHistoryExportEncoder().write(HistoryExportFormat.GPX, metadata(), rows) { fragment ->
                if ("<trkpt" in fragment) { pointFragments++; throw CancellationException("cancel explicit export") }
            }
        }
        assertEquals(1, pointFragments); assertFalse(secondPageRequested)
    }

    @Test fun gpxSmallCoordinatesUsePlainDecimalsRatherThanScientificNotation() = runTest {
        val output = StringBuilder()
        val fix = HistoryGpsFix(origin, 1e-7, -1e-7, 3.0, null)
        StreamingHistoryExportEncoder().write(HistoryExportFormat.GPX, metadata(),
            flowOf(HistoryExportRecord.Location(fix))) { output.append(it) }
        val point = parse(output.toString()).getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").item(0)
        assertEquals("0.0000001", point.attributes.getNamedItem("lat").nodeValue)
        assertEquals("-0.0000001", point.attributes.getNamedItem("lon").nodeValue)
        // Synthetic format fixture for the independent official-XSD acceptance check.
        val artifact = File("build/reports/history-export/gpx-small-coordinates.gpx")
        artifact.parentFile.mkdirs(); artifact.writeText(output.toString())
    }

    @Test fun positiveDatelineUsesEquivalentGpxLongitudeAndPreservesOriginalInExtension() = runTest {
        val output = StringBuilder()
        StreamingHistoryExportEncoder().write(HistoryExportFormat.GPX, metadata(),
            flowOf(HistoryExportRecord.Location(HistoryGpsFix(origin, 90.0, 180.0, 0.0, null)))) { output.append(it) }
        val document = parse(output.toString())
        val point = document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").item(0)
        assertEquals(-180.0, point.attributes.getNamedItem("lon").nodeValue.toDouble())
        assertEquals("180.0", document.getElementsByTagNameNS("urn:ru.sodovaya.volty:history:1", "originalLongitude").item(0).textContent)
        val artifact = File("build/reports/history-export/gpx-dateline.gpx")
        artifact.parentFile.mkdirs(); artifact.writeText(output.toString())
    }
    @Test fun gpxTitleCannotLeakADeviceAddressOrPrivateStoragePath() = runTest {
        val output = StringBuilder()
        val selected = metadata().copy(title = "AA:BB:CC:DD:EE:FF /data/user/0/ru.sodovaya.volty/files/private.bin")
        StreamingHistoryExportEncoder().write(HistoryExportFormat.GPX, selected, flowOf(fix(0))) { output.append(it) }
        val title = parse(output.toString()).getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "name").item(0).textContent
        assertEquals("[device] [path]", title)
    }
    @Test fun gpxCarriesSelectedMonotonicAxisOriginWithoutRewritingCapturedWallTime() = runTest {
        val output = StringBuilder()
        val captured = HistoryGpsFix(tick(1000).copy(epochMillis = 6000), 56.0, 60.0, 3.0, null)
        StreamingHistoryExportEncoder().write(HistoryExportFormat.GPX, metadata().copy(range = HistoryRange(1000, 2000)),
            flowOf(HistoryExportRecord.Location(captured))) { output.append(it) }
        val document = parse(output.toString())
        fun extension(name: String) = assertNotNull(document.getElementsByTagNameNS("urn:ru.sodovaya.volty:history:1", name).item(0)).textContent
        assertEquals("ORIGIN_PLUS_ELAPSED", extension("rangeAxis"))
        assertEquals("1000", extension("originEpochMillis")); assertEquals("10", extension("originElapsedMillis"))
        assertEquals("1000", extension("rangeStartAxisEpochMillis")); assertEquals("2000", extension("rangeEndAxisEpochMillis"))
        assertEquals("1970-01-01T00:00:06Z", document.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "time").item(0).textContent)
    }
    @Test fun unfinishedRecordingCannotBeExportedThroughADifferentFormatToBypassSnapshotSafety() = runTest {
        val active = metadata().let { it.copy(ride = it.ride.copy(status = HistoryRideStatus.RECORDING, ended = null)) }
        for (format in HistoryExportFormat.entries) {
            val error = assertFailsWith<HistoryExportException> {
                StreamingHistoryExportEncoder().write(format, active, flowOf(fix(0))) {}
            }
            assertEquals(HistoryExportFailure.ACTIVE_RECORDING, error.reason, format.name)
        }
    }
}
