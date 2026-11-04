package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import ru.sodovaya.volty.data.db.*
import ru.sodovaya.volty.domain.history.*

class SqlDelightHistoryExportSource(provider: VoltyDatabaseProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pageSize: Int = 512,
    private val isLegacyActive: (String) -> Boolean? = { null }) : HistoryExportSource {
    private val database = provider.database
    private val queries = database.historyExportReadQueries
    private val compact = database.rideHistoryCompactQueries
    private val legacy = SqlDelightLegacyHistoryExportSource(provider, dispatcher, pageSize, isLegacyActive)
    init { require(pageSize in 1..1024) }
    private data class Shape(val sampleCount: Long, val gpsCount: Long, val markerCount: Long)
    private data class Snapshot(val request: HistoryExportRequest, val metadata: HistoryExportMetadata,
        val from: Long, val through: Long, val shape: Shape, val rideNo: Long)

    private fun shape(id: String, rideNo: Long, from: Long, through: Long): Shape =
        // exportV3Shape takes its parameters in the order they first appear in the SQL: toElapsed, rideNo, fromElapsed, rideId.
        queries.exportV3Shape(through, rideNo, from, id).executeAsOne().let { Shape(it.sampleCount, it.gpsCount, it.markerCount) }

    override suspend fun open(request: HistoryExportRequest): HistoryExportInput {
        if (request.source.kind == HistoryStorageKind.LEGACY) return openLegacy(request)
        val snapshot = read {
            if (request.source.kind != HistoryStorageKind.V2) fail(HistoryExportFailure.UNSUPPORTED_FORMAT)
            val ride = database.rideHistoryV2Queries.getRide(request.source.rideId).executeAsOneOrNull()?.toHistoryRide()
                ?: fail(HistoryExportFailure.NOT_FOUND)
            if (ride.favoriteSource() != request.source) fail(HistoryExportFailure.SOURCE_CHANGED)
            if (ride.status == HistoryRideStatus.RECORDING) fail(HistoryExportFailure.ACTIVE_RECORDING)
            val end = ride.ended?.let { historyAxisEpochMillis(ride.started, it) } ?: fail(HistoryExportFailure.INVALID_DATA)
            if (request.range.startEpochMillis < ride.started.epochMillis || request.range.endEpochMillis > end)
                fail(HistoryExportFailure.INVALID_DATA)
            val from = ride.started.elapsedMillis + (request.range.startEpochMillis - ride.started.epochMillis)
            val through = ride.started.elapsedMillis + (request.range.endEpochMillis - ride.started.epochMillis)
            val rideNo = compact.rideNoV3(ride.id).executeAsOneOrNull() ?: fail(HistoryExportFailure.INVALID_DATA)
            Snapshot(request, HistoryExportMetadata(ride, request.range, sources(ride.id),
                vehicleName = queries.exportVehicleName(ride.vehicleId).executeAsOneOrNull()), from, through,
                shape(ride.id, rideNo, from, through), rideNo)
        }
        return HistoryExportInput(snapshot.metadata, flow {
            // Every page is its own short SQLite snapshot. No database
            // transaction is held while encoding or waiting for the file sink.
            val id = snapshot.metadata.ride.id
            var sampleRows = 0L
            val visibleKinds = snapshot.metadata.sources.associate { it.id to it.kind }
            val skippedSeries = HashSet<HistorySeriesKey>()
            if (request.format != HistoryExportFormat.GPX) {
                emitCompactSamples(snapshot, snapshot.rideNo) { record ->
                    sampleRows++
                    if (sampleRows > snapshot.shape.sampleCount) fail(HistoryExportFailure.SOURCE_CHANGED)
                    // Counted for the shape check, but a series of a skipped source or of a metric this build does not
                    // register for that kind is not written (review I1): the encoders would refuse it as INVALID_DATA.
                    val kind = visibleKinds[record.sample.key.sourceId]
                    if (kind != null && historyMetricKnownFor(kind, record.sample.key)) emit(record)
                    else if (skippedSeries.add(record.sample.key)) historySkipped("export: series '${record.sample.key.metricId}'")
                }
            }
            var gpsRows = 0L; var markerRows = 0L
            var elapsed: Long? = null; var order = 0L; var sequence = -1L
            while (true) {
                val page = read { unchanged(snapshot); queries.exportRouteFacts(id, snapshot.from, snapshot.through,
                    elapsed, order, sequence, pageSize.toLong()).executeAsList() }
                if (page.isEmpty()) break
                for (row in page) {
                    val tick = HistoryTick(row.epochMillis, row.elapsedMillis, row.clockGeneration)
                    val record = if (row.routeOrder == 0L) {
                        markerRows++
                        HistoryExportRecord.Event(HistoryMarker(tick, HistoryMarkerKind.valueOf(row.markerKind),
                            row.sourceId.takeIf { row.sourcePresent != 0L }))
                    } else {
                        if (row.routeOrder != 1L) fail(HistoryExportFailure.INVALID_DATA)
                        gpsRows++
                        HistoryExportRecord.Location(HistoryGpsFix(tick, checkNotNull(row.latitude), checkNotNull(row.longitude),
                            checkNotNull(row.accuracyMeters), row.headingDegrees, row.provider?.let(HistoryGpsProvider::valueOf)))
                    }
                    if (gpsRows > snapshot.shape.gpsCount || markerRows > snapshot.shape.markerCount) fail(HistoryExportFailure.SOURCE_CHANGED)
                    emit(record)
                    elapsed = row.elapsedMillis; order = row.routeOrder; sequence = row.sequence
                }
                if (page.size < pageSize) break
            }
            // Retraction: summaries (HistoryBucketV2) were paged here for the long CSV and JSON
            // exports. Both were removed on 2026-10-29; no export reads summaries now.
            read {
                unchanged(snapshot)
                if (shape(id, snapshot.rideNo, snapshot.from, snapshot.through) != snapshot.shape ||
                    sources(id) != snapshot.metadata.sources || gpsRows != snapshot.shape.gpsCount || markerRows != snapshot.shape.markerCount ||
                    (request.format != HistoryExportFormat.GPX && sampleRows != snapshot.shape.sampleCount)) fail(HistoryExportFailure.SOURCE_CHANGED)
            }
        }.catch { failure ->
            when (failure) {
                is CancellationException -> throw failure
                is HistoryExportException -> throw failure
                is IllegalArgumentException, is IllegalStateException -> fail(HistoryExportFailure.INVALID_DATA)
                else -> fail(HistoryExportFailure.STORAGE)
            }
        })
    }

    /** One series of a compact ride, paged on its primary key; [head] is the next record to merge. */
    private class SeriesStream(val seriesId: Long, val key: HistorySeriesKey) {
        val buffer = ArrayDeque<Pair<Long, HistoryExportRecord.Numeric>>()
        var afterElapsed: Long? = null
        var afterSequence = 0L
        var exhausted = false
        /** The highest stored sequence of the rows paged so far (a run counts at its end): the series must climb strictly. */
        var lastSequence = Long.MIN_VALUE
    }

    /**
     * The sample records of a ride in STORAGE ORDER, which for V3 means ascending stored sequence across series.
     *
     * V2 paged `ORDER BY sequence` over the ride. V3 has no sequence index, so this is a k-way merge of the ride's series,
     * each paged on its primary key `(elapsedMillis, sequence)` at [pageSize] rows (one short transaction per page, as
     * for V2), merged on the stored sequence of the row's first member and then on the series id. Within a series PK order
     * equals sequence order iff its elapsed never decreases in sequence order, which the recorder guarantees for projected
     * recordings. Retraction: this said Task 9's conversion enforces it; a conversion cannot reorder what was recorded, it
     * only keeps such a chain out of a run, so a converted ride holding such a series stops exporting. Measured: no series
     * of the rider's database does (0 of 26 rides, 1.3 M rows). A series that breaks it is REFUSED with INVALID_DATA instead
     * of merged: an earlier revision merged it in PK order and claimed `droppedLate` could only fall; that was unproven (heads would
     * not be monotone, so the cross-series permutation differs from both V2 and time order). Cross-series order uses the
     * stored sequences of first members and run ends. Retraction: this said "never an interpolated interior one"; a row cut
     * by a back-dated marker (`splitStoredRunAt`) stores its tail's first member with the sequence `HistoryRunMembers`
     * interpolated for it, and that stored value is what this merges on (final review M5). Only unknown records can move,
     * ties fall to the series id, and every encoder ignores unknowns, so no exported byte changes.
     *
     * A run that starts in range is emitted as its first member and, only if the stored end lies at or before
     * `snapshot.through`, its end member (exact stored ticks). A run that ends after `through` emits its first member alone:
     * the encoders validate every record's tick against the range, and V2 never had a member past it. A run that starts
     * before `from` and ends inside is not emitted at all (V2 emitted its in-range interior members). Every encoder
     * ignores unknowns beyond validating the key, so none of this changes a byte of the CSV or GPX. The records emitted
     * are what `exportV3Shape` counts.
     */
    private suspend fun emitCompactSamples(snapshot: Snapshot, rideNo: Long, emit: suspend (HistoryExportRecord.Numeric) -> Unit) {
        val id = snapshot.metadata.ride.id
        val origin = snapshot.metadata.ride.started
        val streams = read { unchanged(snapshot)
            compact.seriesOfRideV3(rideNo).executeAsList().map {
                SeriesStream(it.seriesId, HistorySeriesKey(it.sourceId, it.metricId, cell(it.cellIndex)))
            } }
        suspend fun fill(stream: SeriesStream) {
            while (stream.buffer.isEmpty() && !stream.exhausted) {
                val rows = read { unchanged(snapshot)
                    compact.pageSamplesV3(stream.seriesId, maxOf(snapshot.from, stream.afterElapsed ?: Long.MIN_VALUE), snapshot.through, stream.afterElapsed,
                        stream.afterSequence, pageSize.toLong()).executeAsList() }
                for (row in rows) {
                    if (row.sequence <= stream.lastSequence) fail(HistoryExportFailure.INVALID_DATA)
                    stream.lastSequence = row.runEndSequence ?: row.sequence
                    val members = HistoryRunMembers(HistoryCompactCodec.decode(row, stream.key, origin))
                    stream.buffer.addLast(row.sequence to HistoryExportRecord.Numeric(members.sample(0)))
                    if (members.size >= 2 && checkNotNull(row.runEndElapsed) <= snapshot.through) stream.buffer.addLast(checkNotNull(row.runEndSequence) to
                        HistoryExportRecord.Numeric(members.sample(members.size - 1)))
                }
                rows.lastOrNull()?.let { stream.afterElapsed = it.elapsedMillis; stream.afterSequence = it.sequence }
                if (rows.size < pageSize) stream.exhausted = true
            }
        }
        streams.forEach { fill(it) }
        val live = streams.filterTo(ArrayList()) { it.buffer.isNotEmpty() }
        while (live.isNotEmpty()) {
            var best = live[0]
            for (candidate in live) {
                val a = candidate.buffer.first().first; val b = best.buffer.first().first
                if (a < b || (a == b && candidate.seriesId < best.seriesId)) best = candidate
            }
            emit(best.buffer.removeFirst().second)
            if (best.buffer.isEmpty()) { fill(best); if (best.buffer.isEmpty()) live.remove(best) }
        }
    }

    private suspend fun openLegacy(request: HistoryExportRequest): HistoryExportInput = legacy.open(request)

    private fun unchanged(snapshot: Snapshot) {
        val actual = database.rideHistoryV2Queries.getRide(snapshot.metadata.ride.id).executeAsOneOrNull()?.toHistoryRide()
        if (actual != snapshot.metadata.ride) fail(HistoryExportFailure.SOURCE_CHANGED)
    }
    private fun sources(id: String): List<HistorySource> {
        val rows = queries.exportSources(id).executeAsList()
        if (rows.size > 2048) fail(HistoryExportFailure.LIMIT)
        // Review I1: a source of a kind this build does not know is left out of the export (logged), not a failure.
        return historyKnownSources(rows, { it.id }, { it.kind }, { it.label }, "export")
    }
    private fun cell(value: Long): Int? {
        if (value !in -1L..Int.MAX_VALUE.toLong()) fail(HistoryExportFailure.INVALID_DATA)
        return value.takeIf { it >= 0 }?.toInt()
    }
    private suspend fun <T> read(block: () -> T): T = withContext(dispatcher) {
        currentCoroutineContext().ensureActive()
        try { database.transactionWithResult { block() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: HistoryExportException) { throw failure }
        catch (_: IllegalArgumentException) { fail(HistoryExportFailure.INVALID_DATA) }
        catch (_: Exception) { fail(HistoryExportFailure.STORAGE) }
    }
    private fun fail(reason: HistoryExportFailure): Nothing = throw HistoryExportException(reason)
}
