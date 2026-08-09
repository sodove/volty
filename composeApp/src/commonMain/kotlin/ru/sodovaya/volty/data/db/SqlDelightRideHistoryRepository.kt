package ru.sodovaya.volty.data.db

import ru.sodovaya.volty.domain.repository.RideHistoryRepository
import ru.sodovaya.volty.domain.repository.RidePoint
import ru.sodovaya.volty.domain.repository.RideSummary
import ru.sodovaya.volty.domain.repository.StoredRide
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SqlDelightRideHistoryRepository(provider: VoltyDatabaseProvider) : RideHistoryRepository {
    private val database = provider.database
    private val rides = database.rideRowQueries
    private val points = database.ridePointRowQueries

    override suspend fun startRide(summary: RideSummary) {
        database.transaction {
            rides.insert(
                id = summary.id,
                vehicleId = summary.vehicleId,
                startedAt = summary.startedAt.toString(),
                endedAt = summary.endedAt?.toString()
            )
        }
    }

    override suspend fun appendPoint(rideId: String, point: RidePoint) {
        points.insert(
            rideId = rideId,
            metricKey = point.metricKey,
            timestamp = point.timestamp.toString(),
            cellIndex = (point.cellIndex ?: -1).toLong(),
            value = point.value.toDouble(),
            isKnown = if (point.isKnown) 1L else 0L
        )
    }

    override suspend fun finishRide(rideId: String, endedAt: Instant) {
        rides.finish(endedAt = endedAt.toString(), id = rideId)
    }

    override suspend fun listRides(vehicleId: String?): List<RideSummary> =
        (vehicleId?.let(rides::selectByVehicle) ?: rides.selectAll())
            .executeAsList()
            .map(::toSummary)

    override suspend fun loadRide(rideId: String): StoredRide? {
        val row = rides.selectById(rideId).executeAsOneOrNull() ?: return null
        return StoredRide(
            summary = toSummary(row),
            points = points.selectByRide(rideId).executeAsList().map { point ->
                RidePoint(
                    metricKey = point.metricKey,
                    timestamp = Instant.parse(point.timestamp),
                    value = point.value_.toFloat(),
                    cellIndex = point.cellIndex.takeUnless { it == -1L }?.toInt(),
                    isKnown = point.isKnown != 0L
                )
            }
        )
    }

    override suspend fun deleteRide(rideId: String) {
        database.transaction {
            points.deleteByRide(rideId)
            rides.delete(rideId)
        }
    }

    override suspend fun pruneOldest(keep: Int) {
        require(keep >= 0) { "keep must not be negative" }
        val count = rides.selectAll().executeAsList().size - keep
        if (count > 0) {
            val oldest = rides.selectAll().executeAsList()
                .sortedBy { it.startedAt }
                .take(count)
            database.transaction {
                oldest.forEach { row ->
                    points.deleteByRide(row.id)
                    rides.delete(row.id)
                }
            }
        }
    }

    private fun toSummary(row: RideRow) = RideSummary(
        id = row.id,
        vehicleId = row.vehicleId,
        startedAt = Instant.parse(row.startedAt),
        endedAt = row.endedAt?.let(Instant::parse)
    )
}
