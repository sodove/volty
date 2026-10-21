package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SqlDelightHistoryRideDeletion(provider: VoltyDatabaseProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cleanup: suspend () -> HistoryMediaRecovery = { HistoryMediaRecovery() }) : HistoryRideDeletionRepository {
    private val database = provider.database
    private val queries = database.historyRideDeletionQueries
    private val issuer = Any()
    private val previewPurge = HistoryPreviewPurgeJournal(provider)

    override suspend fun prepare(ride: HistoryRide): HistoryRideDeletionRequest = transaction {
        val source = ride.favoriteSource()
        val actual = current(source) ?: fail(HistoryRideDeletionFailure.NOT_FOUND)
        if (actual.favoriteSource() != source) fail(HistoryRideDeletionFailure.SOURCE_CHANGED)
        requireClosed(actual)
        val impact = impact(source)
        HistoryRideDeletionRequest(actual, impact.favoriteCount, impact.mediaCount, issuer)
    }

    override suspend fun confirm(request: HistoryRideDeletionRequest): HistoryRideDeletionResult {
        if (request.issuer !== issuer) fail(HistoryRideDeletionFailure.CONFLICT)
        val source = request.ride.favoriteSource()
        transaction {
            val actual = current(source) ?: return@transaction
            if (actual.favoriteSource() != source) fail(HistoryRideDeletionFailure.SOURCE_CHANGED)
            requireClosed(actual)
            if (actual != request.ride) fail(HistoryRideDeletionFailure.CONFLICT)
            val impact = impact(source)
            if (impact.favoriteCount != request.favoriteCount || impact.mediaCount != request.mediaCount)
                fail(HistoryRideDeletionFailure.CONFLICT)
            queries.retireRecordingMedia(source.rideId, source.kind.name, source.vehicleId,
                source.started.epochMillis, source.started.elapsedMillis, source.started.clockGeneration)
            queries.deleteRecordingFavorites(source.rideId, source.kind.name, source.vehicleId,
                source.started.epochMillis, source.started.elapsedMillis, source.started.clockGeneration)
            // Retraction: originally only V2 had derived preview files. Legacy
            // CSV/JSON exports now share the same durable derived-file purge.
            previewPurge.request()
            if (source.kind == HistoryStorageKind.LEGACY) {
                database.ridePointRowQueries.deleteByRide(source.rideId)
                database.rideRowQueries.delete(source.rideId)
            } else {
                // Explicit children, including receipt/bucket state, are required
                // even on SQLite connections with foreign_keys disabled.
                database.rideHistorySampleQueries.deleteSamples(source.rideId)
                database.rideHistoryGpsQueries.deleteGps(source.rideId)
                database.rideHistoryMarkerQueries.deleteMarkers(source.rideId)
                database.rideHistoryBucketQueries.deleteBuckets(source.rideId)
                database.rideHistoryReceiptQueries.deleteReceipts(source.rideId)
                database.rideHistorySourceQueries.deleteSources(source.rideId)
                database.rideHistoryV2Queries.deleteRide(source.rideId)
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
        return HistoryRideDeletionResult(source, pending || retainedFiles)
    }

    private fun current(source: HistoryFavoriteSource): HistoryRide? = when (source.kind) {
        HistoryStorageKind.V2 -> database.rideHistoryV2Queries.getRide(source.rideId).executeAsOneOrNull()?.toHistoryRide()
        HistoryStorageKind.LEGACY -> database.rideRowQueries.selectById(source.rideId).executeAsOneOrNull()?.let { row ->
            fun tick(text: String) = HistoryTick(Instant.parse(text).toEpochMilliseconds(), 0, 0)
            HistoryRide(row.id, row.vehicleId, tick(row.startedAt), row.endedAt?.let(::tick), HistoryRideStatus.LEGACY)
        }
    }
    private fun requireClosed(ride: HistoryRide) {
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
