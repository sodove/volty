package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One recorded catalog; each ride is read exclusively through its storage format. */
@OptIn(ExperimentalTime::class)
class SqlDelightHistoryReadRepository(
    provider: VoltyDatabaseProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    maxReadPages: Int = 8,
) : HistoryReadRepository {
    private val database = provider.database
    private val current = DefaultHistoryReadRepository(SqlDelightHistoryStore(provider, dispatcher), maxReadPages)
    private val legacy = LegacyHistoryReader(provider, dispatcher)

    override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys {
        require(limit in 1..20000)
        return reader(rideId).recordedKeys(rideId, sourceId, limit)
    }

    override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> = withContext(dispatcher) {
        val rows = database.rideHistoryCatalogQueries.pageHistoryCatalog(before?.epochMillis, before?.stableId.orEmpty(), 21) {
            id, vehicleId, epoch, elapsed, generation, endedEpoch, endedElapsed, endedGeneration, status, oldStarted, oldEnded ->
            if (oldStarted != null) {
                val started = wallTick(oldStarted)
                check(started.epochMillis == epoch) { "Legacy catalog timestamp is not comparable" }
                HistoryRide(id, vehicleId, started, oldEnded?.let(::wallTick), HistoryRideStatus.LEGACY)
            } else {
                HistoryRide(id, vehicleId, HistoryTick(checkNotNull(epoch), elapsed, generation),
                    endedEpoch?.let { HistoryTick(it, checkNotNull(endedElapsed), checkNotNull(endedGeneration)) },
                    HistoryRideStatus.valueOf(status))
            }
        }.executeAsList()
        val selected = rows.take(20)
        val more = rows.size > 20
        HistoryPage(selected, if (more) selected.last().let { HistoryCursor(it.started.epochMillis, it.id) } else null, more)
    }

    override suspend fun ride(rideId: String): HistoryRide? = current.ride(rideId) ?: legacy.ride(rideId)
    override suspend fun sources(rideId: String): List<HistorySource> = reader(rideId).sources(rideId)
    override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries {
        require(maxPoints in 3..600)
        return reader(rideId).series(rideId, key, range, maxPoints)
    }
    override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryPage<HistoryGpsFix> =
        reader(rideId).gps(rideId, range, after)
    override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats =
        reader(rideId).stats(rideId, key, range)
    override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage =
        reader(rideId).route(rideId, range, after)

    /** Choose once for the request. Empty/unavailable v2 data never borrows old data. */
    private suspend fun reader(rideId: String): HistoryReadRepository =
        if (current.ride(rideId) != null) current else legacy

    private fun wallTick(text: String) = HistoryTick(Instant.parse(text).toEpochMilliseconds(), 0, 0)
}
