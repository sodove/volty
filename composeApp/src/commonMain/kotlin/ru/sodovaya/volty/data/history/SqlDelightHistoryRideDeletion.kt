package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.data.db.hasLegacyHistory
import ru.sodovaya.volty.domain.history.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SqlDelightHistoryRideDeletion(provider: VoltyDatabaseProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val isLegacyActive: (String) -> Boolean? = { null },
    private val cleanup: suspend () -> HistoryMediaRecovery = { HistoryMediaRecovery() }) : HistoryRideDeletionRepository {
    private val database = provider.database
    private val driver = provider.driver
    private val queries = database.historyRideDeletionQueries
    private val issuer = Any()
    private val previewPurge = HistoryPreviewPurgeJournal(provider)

    override suspend fun prepare(rides: List<HistoryRide>): HistoryRideDeletionRequest {
        val selected = rides.distinctBy { it.favoriteSource() }
        if (selected.isEmpty()) fail(HistoryRideDeletionFailure.NOT_FOUND)
        return transaction {
            HistoryRideDeletionRequest(selected.map { ride ->
                val source = ride.favoriteSource()
                val actual = current(source) ?: fail(HistoryRideDeletionFailure.NOT_FOUND)
                if (actual.favoriteSource() != source) fail(HistoryRideDeletionFailure.SOURCE_CHANGED)
                requireClosed(actual)
                val impact = impact(source)
                HistoryRideDeletionEntry(actual, impact.favoriteCount, impact.mediaCount)
            }, issuer)
        }
    }

    override suspend fun confirm(request: HistoryRideDeletionRequest): HistoryRideDeletionResult {
        if (request.issuer !== issuer) fail(HistoryRideDeletionFailure.CONFLICT)
        transaction {
            val existing = request.entries.mapNotNull { entry ->
                val source = entry.ride.favoriteSource()
                val actual = current(source) ?: return@mapNotNull null
                if (actual.favoriteSource() != source) fail(HistoryRideDeletionFailure.SOURCE_CHANGED)
                requireClosed(actual)
                if (actual != entry.ride) fail(HistoryRideDeletionFailure.CONFLICT)
                val impact = impact(source)
                if (impact.favoriteCount != entry.favoriteCount || impact.mediaCount != entry.mediaCount)
                    fail(HistoryRideDeletionFailure.CONFLICT)
                source
            }
            if (existing.isNotEmpty()) {
                val staged = hasLegacyHistory(driver)
                val compact = HistoryCompactSampleWriter(database)
                existing.forEach { delete(it, compact, staged) }
                previewPurge.request()
            }
        }
        // The database commit is final. Optional file cleanup can be waiting for
        // an in-flight picker stream; do not strand its acknowledgement forever.
        val pending = try { withTimeout(MEDIA_CLEANUP_DEADLINE_MILLIS) { cleanup().pendingCleanup > 0 } }
            catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); true }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { true }
        val retainedFiles = try { withContext(dispatcher) { database.transactionWithResult {
            previewPurge.pendingGeneration() != null || queries.pendingRetiredMediaCount().executeAsOne() > 0
        } } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { true } // A post-commit read failure cannot undo deletion.
        return HistoryRideDeletionResult(request.rides.mapTo(mutableSetOf()) { it.favoriteSource() }, pending || retainedFiles)
    }

    private fun delete(source: HistoryFavoriteSource, compact: HistoryCompactSampleWriter, staged: Boolean) {
        val id = source.rideId
        queries.retireRecordingMedia(id, source.kind.name, source.vehicleId,
            source.started.epochMillis, source.started.elapsedMillis, source.started.clockGeneration)
        queries.deleteRecordingFavorites(id, source.kind.name, source.vehicleId,
            source.started.epochMillis, source.started.elapsedMillis, source.started.clockGeneration)
        if (source.kind == HistoryStorageKind.LEGACY) {
            database.ridePointRowQueries.deleteByRide(id)
            database.rideRowQueries.delete(id)
            return
        }
        // Android upgrades can leave these rows in the staging table until conversion.
        if (staged) driver.execute(null, "DELETE FROM HistorySampleV2 WHERE rideId = ?", 1) { bindString(0, id) }
        // Explicitly delete children even with SQLite foreign keys disabled.
        compact.delete(id)
        database.rideHistoryGpsQueries.deleteGps(id)
        database.rideHistoryMarkerQueries.deleteMarkers(id)
        database.rideHistoryBucketQueries.deleteBuckets(id)
        database.rideHistoryReceiptQueries.deleteReceipts(id)
        database.rideHistorySourceQueries.deleteSources(id)
        database.rideHistoryV2Queries.deleteRide(id)
    }

    private fun current(source: HistoryFavoriteSource): HistoryRide? = when (source.kind) {
        HistoryStorageKind.V2 -> database.rideHistoryV2Queries.getRide(source.rideId).executeAsOneOrNull()?.toHistoryRide()
        HistoryStorageKind.LEGACY -> database.rideRowQueries.selectById(source.rideId).executeAsOneOrNull()?.let { row ->
            fun tick(text: String) = HistoryTick(Instant.parse(text).toEpochMilliseconds(), 0, 0)
            HistoryRide(row.id, row.vehicleId, tick(row.startedAt), row.endedAt?.let(::tick), HistoryRideStatus.LEGACY)
        }
    }
    private fun requireClosed(ride: HistoryRide) {
        if (ride.status == HistoryRideStatus.LEGACY) {
            val active = isLegacyActive(ride.id)
            if (active == true || (ride.ended == null && active != false))
                fail(HistoryRideDeletionFailure.ACTIVE_RECORDING)
            return
        }
        if (ride.status == HistoryRideStatus.RECORDING || ride.ended == null) fail(HistoryRideDeletionFailure.ACTIVE_RECORDING)
    }
    private fun impact(source: HistoryFavoriteSource) = queries.deletionImpact(source.rideId, source.kind.name, source.vehicleId,
        source.started.epochMillis, source.started.elapsedMillis, source.started.clockGeneration).executeAsOne()
    private suspend fun <T> transaction(block: () -> T): T = withContext(dispatcher) {
        currentCoroutineContext().ensureActive()
        try { database.transactionWithResult { block() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: HistoryRideDeletionException) { throw failure }
        catch (_: Exception) { fail(HistoryRideDeletionFailure.STORAGE) }
    }
    private fun fail(reason: HistoryRideDeletionFailure): Nothing = throw HistoryRideDeletionException(reason)
}

private const val MEDIA_CLEANUP_DEADLINE_MILLIS = 5_000L
