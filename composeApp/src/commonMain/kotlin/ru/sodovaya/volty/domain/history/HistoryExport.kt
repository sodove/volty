package ru.sodovaya.volty.domain.history

import kotlinx.coroutines.flow.Flow

/**
 * [LOGUI] is the VESC logui CSV (VESC Tool Log Analysis, telemetry-studio), one row per sample slot;
 * [GPX] is the recorded route.
 *
 * Retraction: two more entries were removed on 2026-10-29 at the rider's request. `JSON` (schema
 * v1, one object per record) cost ~400 B per value — an 18 MB file for a 14-minute ride — and
 * nothing in the app read it back. `CSV`, Volty's long table (one quoted row per stored record,
 * with units, source labels, unknown reasons and 60 s summaries), was about as large (~11 MB for
 * the same ride). Their shared per-record encoder (`StructuredHistoryExportEncoder`) and the
 * summary records only they carried (`Summary`, `RetainedSummary`) went with them.
 */
enum class HistoryExportFormat(val extension: String, val mime: String) {
    LOGUI("csv", "text/csv"), GPX("gpx", "application/gpx+xml")
}

data class HistoryExportRequest(val source: HistoryFavoriteSource, val range: HistoryRange,
    val format: HistoryExportFormat)

enum class HistoryExportFailure { NOT_FOUND, SOURCE_CHANGED, ACTIVE_RECORDING, NO_ROUTE, INVALID_DATA, UNSUPPORTED_FORMAT, LIMIT, STORAGE }
class HistoryExportException(val reason: HistoryExportFailure) : IllegalStateException(reason.name)

sealed interface HistoryExportResult {
    data class Success(val relativePath: String, val bytes: Long, val sha256: String, val mime: String) : HistoryExportResult
    data class Failed(val reason: HistoryExportFailure) : HistoryExportResult
}

interface HistoryExportRepository {
    suspend fun export(request: HistoryExportRequest): HistoryExportResult
}

data class HistoryExportInput(val metadata: HistoryExportMetadata, val records: Flow<HistoryExportRecord>)

/** A source-qualified stream of persisted facts. Normal completion verifies
 * the recording still exists unchanged before the file owner can publish. */
interface HistoryExportSource {
    suspend fun open(request: HistoryExportRequest): HistoryExportInput
}

/** Internal identities qualify reads. Encoded files use local aliases, never device addresses. */
data class HistoryExportMetadata(val ride: HistoryRide, val range: HistoryRange,
    val sources: List<HistorySource>, val title: String = "Volty ride",
    /** The rider's name for the vehicle, for the file name only; null when the vehicle is gone. */
    val vehicleName: String? = null)

/** Input must be paged by its producer; GPS/events are ordered by elapsed time,
 * with boundary events preceding locations at the same tick. Original wall time
 * stays on every fact. These are retained detail, not an acquisition log.
 * Summary records were removed with the long CSV/JSON exports, their only readers.
 */
sealed interface HistoryExportRecord {
    data class Numeric(val sample: HistorySample, val capturedTimestamp: String? = null,
        val legacyMetric: String? = null) : HistoryExportRecord
    data class LegacyUnmapped(val tick: HistoryTick, val capturedTimestamp: String, val metric: String,
        val cellIndex: Int?, val rawValue: Double?, val known: Boolean) : HistoryExportRecord
    data class Location(val fix: HistoryGpsFix) : HistoryExportRecord
    data class Event(val marker: HistoryMarker) : HistoryExportRecord
}

data class HistoryExportCounts(val samples: Long = 0, val locations: Long = 0,
    val events: Long = 0, val unmapped: Long = 0)

/** Writes fragments to an app-owned temporary sink. Only its file owner may
 * publish a finished, checksummed artifact after this method succeeds. */
interface HistoryExportEncoder {
    suspend fun write(format: HistoryExportFormat, metadata: HistoryExportMetadata,
        records: Flow<HistoryExportRecord>, write: suspend (String) -> Unit): HistoryExportCounts
}
