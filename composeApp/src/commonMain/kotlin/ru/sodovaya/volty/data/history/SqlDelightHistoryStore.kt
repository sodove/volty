package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.data.db.HistoryRideV2
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.data.db.hasLegacyHistory
import ru.sodovaya.volty.domain.history.*

/**
 * Borrows the application database; mutations commit atomically away from the UI thread.
 *
 * Samples live in the compact tables only (compact-history-storage plan). [begin] gives every ride a `HistoryRideKeyV3`,
 * and 28.sqm gave one to every ride recorded before them, so a ride without a key does not exist.
 *
 * Retraction: a `recordCompact` flag chose the layout while V2 lived (Tasks 5-8), and writer, deletion and readers
 * dispatched on the key. 28.sqm dropped `HistorySampleV2`, and the flag and the V2 branches went with it.
 */
class SqlDelightHistoryStore(
    provider: VoltyDatabaseProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : HistoryStore {
    private val driver = provider.driver
    private val database = provider.database
    private val rides = database.rideHistoryV2Queries
    private val sourceQueries = database.rideHistorySourceQueries
    private val gpsQueries = database.rideHistoryGpsQueries
    private val markerQueries = database.rideHistoryMarkerQueries
    private val reader = HistoryStoreReader(database)
    private val receipts = HistoryBatchReceipts(database)
    private val bucketStorage = HistoryBucketStorage(database)
    private val compact = HistoryCompactSampleWriter(database)

    override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys = withContext(dispatcher) {
        database.transactionWithResult { reader.recordedKeys(rideId, sourceId, limit) }
    }

    override suspend fun begin(ride: HistoryRide, sources: List<HistorySource>) = withContext(dispatcher) {
        require(ride.status == HistoryRideStatus.RECORDING && ride.ended == null)
        require(sources.map { it.id }.distinct().size == sources.size)
        database.transaction {
            rides.insertRide(ride.id, ride.vehicleId, ride.started.epochMillis,
                ride.started.elapsedMillis, ride.started.clockGeneration, ride.status.name)
            sources.forEach { sourceQueries.insertSource(ride.id, it.id, it.kind.name, it.label) }
            compact.begin(ride.id)
        }
    }

    override suspend fun ride(rideId: String): HistoryRide? = withContext(dispatcher) {
        rides.getRide(rideId).executeAsOneOrNull()?.toHistoryRide()
    }

    override suspend fun append(batch: HistoryBatch) = withContext(dispatcher) {
        database.transaction {
            if (receipts.verifyCommitted(batch)) return@transaction
            val row = checkNotNull(rides.getRide(batch.rideId).executeAsOneOrNull()) { "Ride not found" }
            val rideNo = checkNotNull(compact.rideNo(batch.rideId)) { "A ride without a compact key" }
            check(row.status == HistoryRideStatus.RECORDING.name) { "Ride is closed" }
            val origin = row.toHistoryRide().started
            val sources = sourceQueries.sourcesForRide(batch.rideId).executeAsList().map { it.id }.toSet()
            var sequence = row.nextSequence
            var last = HistoryTick(row.lastEpochMillis ?: origin.epochMillis,
                row.lastElapsedMillis ?: origin.elapsedMillis, origin.clockGeneration)
            fun admit(tick: HistoryTick): Long {
                require(historyAxisEpochMillis(origin, tick) != null) { "Incomparable recording clock" }
                check(sequence < Long.MAX_VALUE) { "Sequence exhausted" }
                if (tick.elapsedMillis >= last.elapsedMillis) last = tick
                return sequence++
            }
            val session = compact.session(batch.rideId, rideNo, origin, batch.markers)
            batch.samples.forEach { sample ->
                require(sample.key.sourceId in sources) { "Unregistered source" }
                session.write(sample, admit(sample.tick))
            }
            batch.gps.forEach { fix ->
                val t = fix.tick
                gpsQueries.insertGps(batch.rideId, admit(t), t.epochMillis, t.elapsedMillis,
                    t.clockGeneration, fix.lat, fix.lon, fix.accuracyMeters, fix.heading, fix.provider?.name)
            }
            batch.markers.forEach { marker ->
                require(marker.sourceId == null || marker.sourceId in sources) { "Unregistered marker source" }
                val t = marker.tick
                markerQueries.insertMarker(batch.rideId, admit(t), t.epochMillis,
                    t.elapsedMillis, t.clockGeneration, marker.kind.name, marker.sourceId)
                // A gap marker committed after a run already grew past its tick cuts that run (writer KDoc).
                compact.splitRunsAt(rideNo, origin, marker)
            }
            bucketStorage.append(batch, origin, sources)?.let {
                if (it.elapsedMillis >= last.elapsedMillis) last = it
            }
            rides.advanceSequence(sequence, last.epochMillis, last.elapsedMillis, batch.rideId)
            receipts.record(batch, row.nextSequence, sequence)
        }
    }

    override suspend fun finish(rideId: String, ended: HistoryTick, status: HistoryRideStatus) = withContext(dispatcher) {
        require(status == HistoryRideStatus.COMPLETED || status == HistoryRideStatus.INTERRUPTED)
        database.transaction {
            val row = checkNotNull(rides.getRide(rideId).executeAsOneOrNull()) { "Ride not found" }
            val ride = row.toHistoryRide()
            require(historyAxisEpochMillis(ride.started, ended) != null)
            require(ended.elapsedMillis >= (row.lastElapsedMillis ?: ride.started.elapsedMillis))
            if (ride.status == HistoryRideStatus.RECORDING) {
                rides.finishRide(ended.epochMillis, ended.elapsedMillis, ended.clockGeneration, status.name, rideId)
            } else {
                check(ride.status == status && ride.ended == ended) { "Conflicting finish receipt" }
            }
        }
    }

    /** Bootstrap only: the caller exclusively owns the new recorder and has not started a writer.
     * All persisted facts use their ride origin's generation. Unused generations need no row:
     * retry before begin can reuse one, but any surviving previous ride forces a different clock.
     */
    internal suspend fun prepareRecordingClock(): Long = withContext(dispatcher) {
        database.transactionWithResult {
            val previous = rides.latestClockGeneration { it ?: 0L }.executeAsOne()
            check(previous >= 0L && previous < Long.MAX_VALUE) { "Recording clock generation exhausted or invalid" }
            recoverInterruptedRows()
            previous + 1L
        }
    }

    override suspend fun recoverInterrupted(): Int = withContext(dispatcher) {
        database.transactionWithResult { recoverInterruptedRows() }
    }

    /** Must run inside the caller's database transaction. */
    private fun recoverInterruptedRows(): Int {
        val open = rides.openRides().executeAsList()
        open.forEach { row ->
            val epoch = row.lastEpochMillis ?: row.startedEpochMillis
            val elapsed = row.lastElapsedMillis ?: row.startedElapsedMillis
            markerQueries.insertMarker(row.id, row.nextSequence, epoch, elapsed,
                row.clockGeneration, HistoryMarkerKind.INTERRUPTED.name, null)
            compact.rideNo(row.id)?.let {
                compact.splitRunsAt(it, row.toHistoryRide().started,
                    HistoryMarker(HistoryTick(epoch, elapsed, row.clockGeneration), HistoryMarkerKind.INTERRUPTED))
            }
            rides.finishRide(epoch, elapsed, row.clockGeneration, HistoryRideStatus.INTERRUPTED.name, row.id)
        }
        return open.size
    }

    override suspend fun rides(before: HistoryCursor?, limit: Int): HistoryPage<HistoryRide> = withContext(dispatcher) {
        reader.rides(before, limit)
    }
    override suspend fun sources(rideId: String): List<HistorySource> = withContext(dispatcher) {
        // Review I1: a kind written by a newer build is skipped (and logged), so the ride still opens; was `valueOf`.
        historyKnownSources(sourceQueries.sourcesForRide(rideId).executeAsList(), { it.id }, { it.kind }, { it.label }, "store")
    }
    override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistorySample> = withContext(dispatcher) {
        // A page reads a carried run and the rows after it: one snapshot, so a concurrent append cannot land between.
        // On Android this begins IMMEDIATE (write lock) and serialises with the recorder's append for one page (ms-scale,
        // no suspension inside, as `stats` and `recordedKeys` already do); review M4 accepted it.
        database.transactionWithResult { reader.samples(rideId, key, range, after, limit) }
    }
    override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistoryGpsFix> = withContext(dispatcher) {
        reader.gps(rideId, range, after, limit)
    }
    override suspend fun lastSampleElapsedOfOtherSources(rideId: String, exceptSourceId: String, toElapsed: Long): Long? =
        withContext(dispatcher) {
            // One snapshot: this seeks every other series (exact; V2 approximated, see the reader's retraction).
            database.transactionWithResult { reader.lastSampleElapsedOfOtherSources(rideId, exceptSourceId, toElapsed) }
        }
    override suspend fun markers(rideId: String, range: HistoryRange): List<HistoryMarker> = withContext(dispatcher) {
        reader.markers(rideId, range)
    }
    override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryRoutePage = withContext(dispatcher) {
        database.transactionWithResult {
            val origin = rides.getRide(rideId).executeAsOneOrNull()?.toHistoryRide()?.started
            HistoryRoutePage(origin, reader.gps(rideId, range, after, limit), reader.markers(rideId, range))
        }
    }
    override suspend fun buckets(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistoryBucket> = withContext(dispatcher) {
        reader.buckets(rideId, key, range, after, limit)
    }
    override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats = withContext(dispatcher) {
        database.transactionWithResult { reader.stats(rideId, key, range) }
    }

    override suspend fun delete(rideId: String) = withContext(dispatcher) {
        database.transaction {
            check(database.historyFavoriteQueries.sourceReferences(rideId, "V2").executeAsOne() == 0L) {
                "Recording is referenced by favorites"
            }
            // Application foreign_keys are OFF. Explicit child deletion must work on that path too.
            if (hasLegacyHistory(driver)) {
                driver.execute(null, "DELETE FROM HistorySampleV2 WHERE rideId = ?", 1) { bindString(0, rideId) }
            }
            compact.delete(rideId)
            gpsQueries.deleteGps(rideId)
            markerQueries.deleteMarkers(rideId)
            database.rideHistoryBucketQueries.deleteBuckets(rideId)
            database.rideHistoryReceiptQueries.deleteReceipts(rideId)
            sourceQueries.deleteSources(rideId)
            rides.deleteRide(rideId)
        }
    }
}

internal fun HistoryRideV2.toHistoryRide() = HistoryRide(id, vehicleId,
    HistoryTick(startedEpochMillis, startedElapsedMillis, clockGeneration),
    endedEpochMillis?.let { HistoryTick(it, checkNotNull(endedElapsedMillis), checkNotNull(endedClockGeneration)) },
    HistoryRideStatus.valueOf(status))
