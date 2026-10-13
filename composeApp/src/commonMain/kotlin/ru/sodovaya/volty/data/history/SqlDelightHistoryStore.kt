package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.data.db.HistoryRideV2
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*

/** Borrows the application database; mutations commit atomically away from the UI thread. */
class SqlDelightHistoryStore(
    provider: VoltyDatabaseProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : HistoryStore {
    private val database = provider.database
    private val rides = database.rideHistoryV2Queries
    private val sourceQueries = database.rideHistorySourceQueries
    private val sampleQueries = database.rideHistorySampleQueries
    private val gpsQueries = database.rideHistoryGpsQueries
    private val markerQueries = database.rideHistoryMarkerQueries
    private val reader = HistoryStoreReader(database)

    override suspend fun begin(ride: HistoryRide, sources: List<HistorySource>) = withContext(dispatcher) {
        require(ride.status == HistoryRideStatus.RECORDING && ride.ended == null)
        require(sources.map { it.id }.distinct().size == sources.size)
        database.transaction {
            rides.insertRide(ride.id, ride.vehicleId, ride.started.epochMillis,
                ride.started.elapsedMillis, ride.started.clockGeneration, ride.status.name)
            sources.forEach { sourceQueries.insertSource(ride.id, it.id, it.kind.name, it.label) }
        }
    }

    override suspend fun ride(rideId: String): HistoryRide? = withContext(dispatcher) {
        rides.getRide(rideId).executeAsOneOrNull()?.toHistoryRide()
    }

    override suspend fun append(batch: HistoryBatch) = withContext(dispatcher) {
        database.transaction {
            val row = checkNotNull(rides.getRide(batch.rideId).executeAsOneOrNull()) { "Ride not found" }
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
            batch.samples.forEach { sample ->
                require(sample.key.sourceId in sources) { "Unregistered source" }
                val t = sample.tick
                sampleQueries.insertSample(batch.rideId, admit(t), sample.key.sourceId,
                    sample.key.metricId, sample.key.cellIndex?.toLong() ?: -1L, t.epochMillis,
                    t.elapsedMillis, t.clockGeneration, sample.value, sample.unknown?.name)
            }
            batch.gps.forEach { fix ->
                val t = fix.tick
                gpsQueries.insertGps(batch.rideId, admit(t), t.epochMillis, t.elapsedMillis,
                    t.clockGeneration, fix.lat, fix.lon, fix.accuracyMeters, fix.heading)
            }
            batch.markers.forEach { marker ->
                val t = marker.tick
                markerQueries.insertMarker(batch.rideId, admit(t), t.epochMillis,
                    t.elapsedMillis, t.clockGeneration, marker.kind.name)
            }
            rides.advanceSequence(sequence, last.epochMillis, last.elapsedMillis, batch.rideId)
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

    override suspend fun recoverInterrupted(): Int = withContext(dispatcher) {
        database.transactionWithResult {
            val open = rides.openRides().executeAsList()
            open.forEach { row ->
                val epoch = row.lastEpochMillis ?: row.startedEpochMillis
                val elapsed = row.lastElapsedMillis ?: row.startedElapsedMillis
                markerQueries.insertMarker(row.id, row.nextSequence, epoch, elapsed,
                    row.clockGeneration, HistoryMarkerKind.INTERRUPTED.name)
                rides.finishRide(epoch, elapsed, row.clockGeneration, HistoryRideStatus.INTERRUPTED.name, row.id)
            }
            open.size
        }
    }

    override suspend fun rides(before: HistoryCursor?, limit: Int): HistoryPage<HistoryRide> = withContext(dispatcher) {
        reader.rides(before, limit)
    }
    override suspend fun sources(rideId: String): List<HistorySource> = withContext(dispatcher) {
        sourceQueries.sourcesForRide(rideId).executeAsList().map {
            HistorySource(it.id, HistorySourceKind.valueOf(it.kind), it.label)
        }
    }
    override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistorySample> = withContext(dispatcher) {
        reader.samples(rideId, key, range, after, limit)
    }
    override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistoryGpsFix> = withContext(dispatcher) {
        reader.gps(rideId, range, after, limit)
    }
    override suspend fun markers(rideId: String, range: HistoryRange): List<HistoryMarker> = withContext(dispatcher) {
        reader.markers(rideId, range)
    }
    // Exact summaries require full-stream buckets, not extrema reconstructed from thinned detail.
    override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats = HistoryStats(null, null, null, false)

    override suspend fun delete(rideId: String) = withContext(dispatcher) {
        database.transaction {
            // Application foreign_keys are OFF. Explicit child deletion must work on that path too.
            sampleQueries.deleteSamples(rideId)
            gpsQueries.deleteGps(rideId)
            markerQueries.deleteMarkers(rideId)
            database.rideHistoryBucketQueries.deleteBuckets(rideId)
            sourceQueries.deleteSources(rideId)
            rides.deleteRide(rideId)
        }
    }
}

internal fun HistoryRideV2.toHistoryRide() = HistoryRide(id, vehicleId,
    HistoryTick(startedEpochMillis, startedElapsedMillis, clockGeneration),
    endedEpochMillis?.let { HistoryTick(it, checkNotNull(endedElapsedMillis), checkNotNull(endedClockGeneration)) },
    HistoryRideStatus.valueOf(status))
