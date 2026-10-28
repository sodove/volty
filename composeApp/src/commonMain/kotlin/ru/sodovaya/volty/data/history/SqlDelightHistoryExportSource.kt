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
    private val legacy = SqlDelightLegacyHistoryExportSource(provider, dispatcher, pageSize, isLegacyActive)
    init { require(pageSize in 1..1024) }
    private data class Snapshot(val request: HistoryExportRequest, val metadata: HistoryExportMetadata,
        val from: Long, val through: Long, val shape: ExportV2Shape)

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
            Snapshot(request, HistoryExportMetadata(ride, request.range, sources(ride.id),
                vehicleName = queries.exportVehicleName(ride.vehicleId).executeAsOneOrNull()), from, through,
                queries.exportV2Shape(ride.id, from, through).executeAsOne())
        }
        return HistoryExportInput(snapshot.metadata, flow {
            // Every page is its own short SQLite snapshot. No database
            // transaction is held while encoding or waiting for the file sink.
            val id = snapshot.metadata.ride.id
            var sampleRows = 0L
            if (request.format != HistoryExportFormat.GPX) {
                var cursor = -1L
                while (true) {
                    val page = read { unchanged(snapshot); queries.exportSamples(id, snapshot.from, snapshot.through, cursor, pageSize.toLong()).executeAsList() }
                    if (page.isEmpty()) break
                    sampleRows += page.size
                    if (sampleRows > snapshot.shape.sampleCount) fail(HistoryExportFailure.SOURCE_CHANGED)
                    for (row in page) {
                        if (row.sequence <= cursor) fail(HistoryExportFailure.INVALID_DATA)
                        emit(HistoryExportRecord.Numeric(HistorySample(HistoryTick(row.epochMillis, row.elapsedMillis, row.clockGeneration),
                            HistorySeriesKey(row.sourceId, row.metricId, cell(row.cellIndex)), row.reading,
                            row.unknownReason?.let(HistoryUnknownReason::valueOf))))
                        cursor = row.sequence
                    }
                    if (page.size < pageSize) break
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
                if (queries.exportV2Shape(id, snapshot.from, snapshot.through).executeAsOne() != snapshot.shape ||
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

    private suspend fun openLegacy(request: HistoryExportRequest): HistoryExportInput = legacy.open(request)

    private fun unchanged(snapshot: Snapshot) {
        val actual = database.rideHistoryV2Queries.getRide(snapshot.metadata.ride.id).executeAsOneOrNull()?.toHistoryRide()
        if (actual != snapshot.metadata.ride) fail(HistoryExportFailure.SOURCE_CHANGED)
    }
    private fun sources(id: String): List<HistorySource> {
        val rows = queries.exportSources(id).executeAsList()
        if (rows.size > 2048) fail(HistoryExportFailure.LIMIT)
        return rows.map { HistorySource(it.id, HistorySourceKind.valueOf(it.kind), it.label) }
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
