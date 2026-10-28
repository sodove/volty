package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import ru.sodovaya.volty.data.db.*
import ru.sodovaya.volty.domain.history.*
import kotlin.time.Instant

/** Legacy timestamps remain wall-time evidence, never a manufactured v2 clock. */
@OptIn(kotlin.time.ExperimentalTime::class)
internal class SqlDelightLegacyHistoryExportSource(provider: VoltyDatabaseProvider,
    private val dispatcher: CoroutineDispatcher, private val pageSize: Int,
    private val isActive: (String) -> Boolean?) : HistoryExportSource {
    private val database = provider.database
    private val queries = database.historyExportReadQueries
    private data class Snapshot(val row: RideRow, val metadata: HistoryExportMetadata, val count: Long)

    override suspend fun open(request: HistoryExportRequest): HistoryExportInput {
        val snapshot = read {
            val row = database.rideRowQueries.selectById(request.source.rideId).executeAsOneOrNull()
                ?: fail(HistoryExportFailure.NOT_FOUND)
            val ride = HistoryRide(row.id, row.vehicleId, tick(row.startedAt), row.endedAt?.let(::tick), HistoryRideStatus.LEGACY)
            if (ride.favoriteSource() != request.source) fail(HistoryExportFailure.SOURCE_CHANGED)
            if (unsafe(row)) fail(HistoryExportFailure.ACTIVE_RECORDING)
            val recorded = legacyRecordedRange(database, row)
            val bounds = HistoryRange(recorded.startEpochMillis, maxOf(recorded.endEpochMillis, ride.ended?.epochMillis ?: recorded.endEpochMillis))
            if (request.range.startEpochMillis < bounds.startEpochMillis || request.range.endEpochMillis > bounds.endEpochMillis)
                fail(HistoryExportFailure.INVALID_DATA)
            if (request.format == HistoryExportFormat.GPX) fail(HistoryExportFailure.NO_ROUTE)
            Snapshot(row, HistoryExportMetadata(ride, request.range, sources(row.id),
                vehicleName = database.historyExportReadQueries.exportVehicleName(row.vehicleId).executeAsOneOrNull()),
                count(row.id, request.range))
        }
        return HistoryExportInput(snapshot.metadata, flow {
            var timestamp: String? = null; var metric = ""; var index = -1L; var emitted = 0L
            while (true) {
                val page = read {
                    unchanged(snapshot)
                    queries.exportLegacyRows(snapshot.row.id, request.range.startEpochMillis, request.range.endEpochMillis,
                        timestamp, metric, index, pageSize.toLong()).executeAsList()
                }
                if (page.isEmpty()) break
                emitted += page.size
                if (emitted > snapshot.count) fail(HistoryExportFailure.SOURCE_CHANGED)
                for (row in page) {
                    val captured = tick(row.timestamp)
                    if (captured.epochMillis !in request.range.startEpochMillis..request.range.endEpochMillis)
                        fail(HistoryExportFailure.INVALID_DATA)
                    val cell = cell(row.cellIndex)
                    val mapping = legacyMetric(row.metricKey)
                    val descriptor = mapping?.let { HistoryMetricRegistry.descriptor(it.id) }
                    val record = if (mapping != null && descriptor != null && descriptor.indexedByCell == (cell != null)) {
                        val value = (row.value_ * mapping.canonicalMultiplier).takeIf { row.isKnown != 0L && it.isFinite() }
                        val unknown = if (value != null) null else if (row.isKnown == 0L) HistoryUnknownReason.LEGACY else HistoryUnknownReason.INVALID_VALUE
                        HistoryExportRecord.Numeric(HistorySample(captured, HistorySeriesKey(mapping.sourceId, mapping.id, cell), value, unknown),
                            row.timestamp, row.metricKey)
                    } else HistoryExportRecord.LegacyUnmapped(captured, row.timestamp, row.metricKey, cell, row.value_, row.isKnown != 0L)
                    emit(record)
                    timestamp = row.timestamp; metric = row.metricKey; index = row.cellIndex
                }
                if (page.size < pageSize) break
            }
            read {
                unchanged(snapshot)
                if (emitted != snapshot.count || count(snapshot.row.id, request.range) != snapshot.count ||
                    sources(snapshot.row.id) != snapshot.metadata.sources) fail(HistoryExportFailure.SOURCE_CHANGED)
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

    private fun unsafe(row: RideRow): Boolean {
        val active = isActive(row.id)
        return active == true || (row.endedAt == null && active != false)
    }
    private fun unchanged(snapshot: Snapshot) {
        val row = database.rideRowQueries.selectById(snapshot.row.id).executeAsOneOrNull()
        if (row != snapshot.row || row == null || unsafe(row)) fail(HistoryExportFailure.SOURCE_CHANGED)
    }
    private fun count(id: String, range: HistoryRange) = queries.exportLegacyCount(id, range.startEpochMillis, range.endEpochMillis).executeAsOne()
    private fun sources(id: String): List<HistorySource> = queries.exportLegacyKnownKeys(id, legacyMetrics.keys).executeAsList()
        .mapNotNull(::legacyMetric).map { it.sourceId }.distinct().sorted().map {
            HistorySource(it, if (it == "legacy-battery") HistorySourceKind.VEHICLE_BATTERY else HistorySourceKind.VEHICLE_MOTION, it)
        }
    private fun tick(text: String) = HistoryTick(Instant.parse(text).toEpochMilliseconds(), 0, 0)
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
        catch (_: IllegalStateException) { fail(HistoryExportFailure.INVALID_DATA) }
        catch (_: Exception) { fail(HistoryExportFailure.STORAGE) }
    }
    private fun fail(reason: HistoryExportFailure): Nothing = throw HistoryExportException(reason)
}
