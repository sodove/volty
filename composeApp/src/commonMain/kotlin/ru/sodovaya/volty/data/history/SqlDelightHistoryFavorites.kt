package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.data.db.*
import ru.sodovaya.volty.domain.history.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SqlDelightHistoryFavorites(provider: VoltyDatabaseProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowEpochMillis: () -> Long) : HistoryFavoriteRepository {
    private val database = provider.database
    private val queries = database.historyFavoriteQueries
    override suspend fun save(id: String, source: HistoryFavoriteSource, range: HistoryRange,
        name: String, expectedRevision: Long?): ru.sodovaya.volty.domain.history.HistoryFavorite = withContext(dispatcher) {
        require(id.isNotBlank())
        val label = name.trim()
        if (label.isEmpty() || label.length > 120) fail(HistoryFavoriteFailure.INVALID_NAME)
        database.transactionWithResult {
            val existing = queries.getFavorite(id).executeAsOneOrNull()?.toFavorite()
            if (expectedRevision != null && (existing == null || existing.revision != expectedRevision))
                fail(HistoryFavoriteFailure.CONFLICT)
            if (existing != null && existing.source != source) fail(HistoryFavoriteFailure.SOURCE_CHANGED)
            val bounds = recordingBounds(source)
            val clamped = try { clampFavoriteRange(range, bounds) }
                catch (_: IllegalArgumentException) { fail(HistoryFavoriteFailure.INVALID_RANGE) }
            if (existing != null && expectedRevision == null) {
                if (existing.name == label && existing.range == clamped) return@transactionWithResult existing
                fail(HistoryFavoriteFailure.CONFLICT)
            }
            if (existing == null) {
                queries.insertFavorite(id, source.rideId, source.vehicleId, source.kind.name,
                    source.started.epochMillis, source.started.elapsedMillis, source.started.clockGeneration,
                    clamped.startEpochMillis, clamped.endEpochMillis, label, 1, nowEpochMillis())
            } else {
                if (existing.revision == Long.MAX_VALUE) fail(HistoryFavoriteFailure.CONFLICT)
                queries.editFavorite(clamped.startEpochMillis, clamped.endEpochMillis, label, existing.revision + 1, id)
            }
            queries.getFavorite(id).executeAsOne().toFavorite()
        }
    }
    override suspend fun get(id: String): ru.sodovaya.volty.domain.history.HistoryFavorite? = withContext(dispatcher) {
        queries.getFavorite(id).executeAsOneOrNull()?.toFavorite()
    }
    override suspend fun page(before: HistoryCursor?): HistoryPage<ru.sodovaya.volty.domain.history.HistoryFavorite> = withContext(dispatcher) {
        val rows = queries.pageFavorites(before?.epochMillis, before?.stableId.orEmpty(), 21).executeAsList()
        val selected = rows.take(20).map { it.toFavorite() }
        val more = rows.size > 20
        HistoryPage(selected, if (more) selected.last().let { HistoryCursor(it.createdEpochMillis, it.id) } else null, more)
    }
    override suspend fun delete(id: String, expectedRevision: Long): Unit = withContext(dispatcher) {
        database.transaction {
            val existing = queries.getFavorite(id).executeAsOneOrNull() ?: return@transaction
            if (existing.revision != expectedRevision) fail(HistoryFavoriteFailure.CONFLICT)
            queries.deleteFavorite(id)
        }
    }

    /** Runs inside the write transaction: source validation cannot race a deletion. */
    private fun recordingBounds(source: HistoryFavoriteSource): HistoryRange {
        val current = database.rideHistoryV2Queries.getRide(source.rideId).executeAsOneOrNull()
        if (current != null) {
            val ride = current.toHistoryRide()
            if (ride.favoriteSource() != source) fail(HistoryFavoriteFailure.SOURCE_CHANGED)
            val end = ride.ended?.let { historyAxisEpochMillis(ride.started, it) }
                ?: current.lastElapsedMillis?.let { elapsed ->
                    historyAxisEpochMillis(ride.started, HistoryTick(current.lastEpochMillis ?: ride.started.epochMillis,
                        elapsed, ride.started.clockGeneration))
                } ?: ride.started.epochMillis
            return HistoryRange(ride.started.epochMillis, end)
        }
        val old = database.rideRowQueries.selectById(source.rideId).executeAsOneOrNull()
            ?: fail(HistoryFavoriteFailure.SOURCE_UNAVAILABLE)
        val start = HistoryTick(Instant.parse(old.startedAt).toEpochMilliseconds(), 0, 0)
        if (HistoryFavoriteSource(old.id, old.vehicleId, HistoryStorageKind.LEGACY, start) != source)
            fail(HistoryFavoriteFailure.SOURCE_CHANGED)
        return old.endedAt?.let { HistoryRange(start.epochMillis, Instant.parse(it).toEpochMilliseconds()) }
            ?: legacyRecordedRange(database, old)
    }

    private fun fail(reason: HistoryFavoriteFailure): Nothing = throw HistoryFavoriteException(reason)
}

private fun ru.sodovaya.volty.data.db.HistoryFavorite.toFavorite() = ru.sodovaya.volty.domain.history.HistoryFavorite(
    id, HistoryFavoriteSource(rideId, vehicleId, HistoryStorageKind.valueOf(storageKind),
        HistoryTick(startedEpochMillis, startedElapsedMillis, clockGeneration)),
    HistoryRange(rangeStart, rangeEnd), name, revision, createdEpochMillis)

/** Caller owns a database transaction, shared by reads and favorite writes. */
@OptIn(ExperimentalTime::class)
internal fun legacyRecordedRange(database: VoltyDatabase, ride: RideRow): HistoryRange {
    val start = Instant.parse(ride.startedAt).toEpochMilliseconds()
    val bounds = database.ridePointRowQueries.selectLegacyRecordedRange(ride.id).executeAsOne()
    check(bounds.totalRows == bounds.parsedRows) { "Unparseable legacy recording timestamp" }
    if (bounds.totalRows == 0L) return HistoryRange(start, start)
    val first = Instant.parse(checkNotNull(bounds.firstTimestamp)).toEpochMilliseconds()
    val last = Instant.parse(checkNotNull(bounds.lastTimestamp)).toEpochMilliseconds()
    check(last >= first)
    return HistoryRange(minOf(start, first), maxOf(start, last))
}
