package ru.sodovaya.volty.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.TimeZone
import ru.sodovaya.volty.domain.history.*
import kotlin.time.Instant

class StreamingHistoryExportEncoder(private val maxGpsGapMillis: Long = 5_000,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val loguiGridMillis: Long = LOGUI_ROW_GRID_MILLIS) : HistoryExportEncoder {
    init { require(maxGpsGapMillis > 0) }
    override suspend fun write(format: HistoryExportFormat, metadata: HistoryExportMetadata,
        records: Flow<HistoryExportRecord>, write: suspend (String) -> Unit): HistoryExportCounts {
        currentCoroutineContext().ensureActive()
        if (metadata.ride.status == HistoryRideStatus.RECORDING) fail(HistoryExportFailure.ACTIVE_RECORDING)
        if (format == HistoryExportFormat.LOGUI) return LoguiHistoryExportEncoder(metadata, zone(), loguiGridMillis).write(records, write)
        if (format != HistoryExportFormat.GPX) return StructuredHistoryExportEncoder(metadata).write(format, records, write)
        if (metadata.ride.status == HistoryRideStatus.LEGACY) fail(HistoryExportFailure.NO_ROUTE)
        return gpx(metadata, records, write)
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private suspend fun gpx(metadata: HistoryExportMetadata, records: Flow<HistoryExportRecord>,
        write: suspend (String) -> Unit): HistoryExportCounts {
        write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:v=\"urn:ru.sodovaya.volty:history:1\" version=\"1.1\" creator=\"Volty\"><trk><name>" +
            xml(historyExportLabel(metadata.title)) + "</name><extensions>" +
            "<v:rangeAxis>ORIGIN_PLUS_ELAPSED</v:rangeAxis>" +
            "<v:originEpochMillis>${metadata.ride.started.epochMillis}</v:originEpochMillis>" +
            "<v:originElapsedMillis>${metadata.ride.started.elapsedMillis}</v:originElapsedMillis>" +
            "<v:rangeStartAxisEpochMillis>${metadata.range.startEpochMillis}</v:rangeStartAxisEpochMillis>" +
            "<v:rangeEndAxisEpochMillis>${metadata.range.endEpochMillis}</v:rangeEndAxisEpochMillis>" +
            "</extensions>\n")
        var previous: HistoryGpsFix? = null
        var lastElapsed: Long? = null
        var segmentOpen = false
        var boundary = false
        var points = 0L
        var events = 0L
        fun validate(tick: HistoryTick) {
            val axis = historyAxisEpochMillis(metadata.ride.started, tick) ?: fail(HistoryExportFailure.INVALID_DATA)
            if (axis !in metadata.range.startEpochMillis..metadata.range.endEpochMillis ||
                lastElapsed?.let { tick.elapsedMillis < it } == true) fail(HistoryExportFailure.INVALID_DATA)
            lastElapsed = tick.elapsedMillis
        }
        records.collect { record ->
            currentCoroutineContext().ensureActive()
            when (record) {
                is HistoryExportRecord.Event -> {
                    validate(record.marker.tick)
                    if (record.marker.kind in routeBoundaries) boundary = true
                    events++
                }
                is HistoryExportRecord.Location -> {
                    val fix = record.fix
                    validate(fix.tick)
                    val old = previous
                    val interrupted = old != null && (boundary || clockBoundary(old.tick, fix.tick) ||
                        fix.tick.elapsedMillis - old.tick.elapsedMillis > maxGpsGapMillis)
                    if (interrupted && segmentOpen) { write("</trkseg>\n"); segmentOpen = false }
                    if (!segmentOpen) { write("<trkseg>\n"); segmentOpen = true }
                    val time = try { Instant.fromEpochMilliseconds(fix.tick.epochMillis).toString() }
                        catch (_: IllegalArgumentException) { fail(HistoryExportFailure.INVALID_DATA) }
                    // GPX 1.1 uses xsd:decimal (no exponent) and longitude <180.
                    // +180 and -180 are the same meridian; retain the captured
                    // representation in an extension when canonicalizing it.
                    val longitude = if (fix.lon == 180.0) -180.0 else fix.lon
                    val originalLongitude = if (fix.lon == 180.0) "<v:originalLongitude>180.0</v:originalLongitude>" else ""
                    write("<trkpt lat=\"${plainDecimal(fix.lat)}\" lon=\"${plainDecimal(longitude)}\"><time>$time</time><extensions>" +
                        "<v:elapsedMillis>${fix.tick.elapsedMillis}</v:elapsedMillis>" +
                        "<v:clockGeneration>${fix.tick.clockGeneration}</v:clockGeneration>" +
                        "<v:accuracyMeters>${fix.accuracyMeters}</v:accuracyMeters>" +
                        originalLongitude + "</extensions></trkpt>\n")
                    previous = fix; boundary = false; points++
                }
                is HistoryExportRecord.Numeric, is HistoryExportRecord.Summary, is HistoryExportRecord.RetainedSummary, is HistoryExportRecord.LegacyUnmapped -> Unit
            }
        }
        if (points == 0L) fail(HistoryExportFailure.NO_ROUTE)
        if (segmentOpen) write("</trkseg>\n")
        write("</trk></gpx>\n")
        return HistoryExportCounts(locations = points, events = events)
    }

    private fun xml(value: String): String {
        if (value.length > 200 || value.any { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' })
            fail(HistoryExportFailure.INVALID_DATA)
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
    }
    private fun plainDecimal(value: Double): String {
        val text = value.toString()
        val exponentAt = text.indexOf('E')
        if (exponentAt < 0) return text
        val sign = if (text.startsWith('-')) "-" else ""
        val mantissa = text.substring(sign.length, exponentAt)
        val digits = mantissa.replace(".", "")
        val point = (mantissa.indexOf('.').takeIf { it >= 0 } ?: mantissa.length) + text.substring(exponentAt + 1).toInt()
        val expanded = when {
            point <= 0 -> "0." + "0".repeat(-point) + digits
            point >= digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits.substring(0, point) + "." + digits.substring(point)
        }
        return sign + if ('.' in expanded) expanded.trimEnd('0').trimEnd('.') else expanded
    }
    private fun fail(reason: HistoryExportFailure): Nothing = throw HistoryExportException(reason)
    private companion object {
        val routeBoundaries = setOf(HistoryMarkerKind.GPS_GAP, HistoryMarkerKind.CLOCK_CHANGE,
            HistoryMarkerKind.OVERFLOW, HistoryMarkerKind.INTERRUPTED, HistoryMarkerKind.MANUAL_STOP,
            HistoryMarkerKind.VEHICLE_SWITCH)
    }
}
