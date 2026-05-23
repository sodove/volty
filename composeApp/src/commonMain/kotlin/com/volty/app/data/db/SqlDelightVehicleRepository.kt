package com.volty.app.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.volty.app.domain.model.AlertConfig
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.Chemistry
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.VehicleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepository(provider: VoltyDatabaseProvider) : VehicleRepository {

    private val queries = provider.database.vehicleRowQueries

    override val vehicles: Flow<List<Vehicle>> = queries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows -> rows.map { it.toDomain() } }
        .flowOn(Dispatchers.Default)

    override suspend fun get(id: String): Vehicle? =
        queries.selectById(id).executeAsOneOrNull()?.toDomain()

    override suspend fun upsert(vehicle: Vehicle) {
        val a = vehicle.alertConfig
        queries.upsert(
            id = vehicle.id,
            name = vehicle.name,
            iconKey = vehicle.iconKey,
            bmsType = vehicle.bmsType.name,
            bmsAddress = vehicle.bmsAddress,
            chemistry = vehicle.chemistry.name,
            cellCount = vehicle.cellCount?.toLong(),
            averagingWindowMin = vehicle.averagingWindowMin.toLong(),
            cellHighV = a.cellHighV?.toDouble(),
            cellLowV = a.cellLowV?.toDouble(),
            cellDeltaMv = a.cellDeltaMv?.toLong(),
            temperatureHighC = a.temperatureHighC?.toDouble(),
            socLowPercent = a.socLowPercent?.toLong(),
            socCutoffPercent = a.socCutoffPercent?.toLong(),
            disconnectNotify = if (a.disconnectNotify) 1L else 0L,
            chargeCompleteNotify = if (a.chargeCompleteNotify) 1L else 0L,
            createdAt = vehicle.createdAt.toString(),
            lastConnectedAt = vehicle.lastConnectedAt?.toString(),
            isPinned = if (vehicle.isPinned) 1L else 0L
        )
    }

    override suspend fun delete(id: String) { queries.delete(id) }

    override suspend fun touch(id: String) {
        queries.touch(now = Clock.System.now().toString(), id = id)
    }
}

@OptIn(ExperimentalTime::class)
private fun VehicleRow.toDomain(): Vehicle = Vehicle(
    id = id,
    name = name,
    iconKey = iconKey,
    bmsType = BmsType.valueOf(bmsType),
    bmsAddress = bmsAddress,
    chemistry = Chemistry.valueOf(chemistry),
    cellCount = cellCount?.toInt(),
    averagingWindowMin = averagingWindowMin.toInt(),
    alertConfig = AlertConfig(
        cellHighV = cellHighV?.toFloat(),
        cellLowV = cellLowV?.toFloat(),
        cellDeltaMv = cellDeltaMv?.toInt(),
        temperatureHighC = temperatureHighC?.toFloat(),
        socLowPercent = socLowPercent?.toInt(),
        socCutoffPercent = socCutoffPercent?.toInt(),
        disconnectNotify = disconnectNotify == 1L,
        chargeCompleteNotify = chargeCompleteNotify == 1L
    ),
    createdAt = Instant.parse(createdAt),
    lastConnectedAt = lastConnectedAt?.let { Instant.parse(it) },
    isPinned = isPinned == 1L
)
