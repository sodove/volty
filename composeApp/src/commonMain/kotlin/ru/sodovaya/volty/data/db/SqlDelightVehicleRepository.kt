package ru.sodovaya.volty.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class SqlDelightVehicleRepository(provider: VoltyDatabaseProvider) : VehicleRepository {

    private val queries = provider.database.vehicleRowQueries
    private val packQueries = provider.database.packRowQueries

    private val vehicleRows: Flow<List<VehicleRow>> = queries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)

    private val packRows: Flow<List<PackRow>> = packQueries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)

    override val vehicles: Flow<List<Vehicle>> =
        combine(vehicleRows, packRows) { rows, packs ->
            val byVehicle = packs.groupBy { it.vehicleId }
            // A vehicle with no packs cannot be constructed (Vehicle.init
            // requires at least one), and would mean a broken migration —
            // drop it rather than crash the whole list.
            rows.mapNotNull { row ->
                val own = byVehicle[row.id].orEmpty()
                if (own.isEmpty()) null else row.toDomain(own)
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun get(id: String): Vehicle? {
        val row = queries.selectById(id).executeAsOneOrNull() ?: return null
        val packs = packQueries.selectByVehicle(id).executeAsList()
        if (packs.isEmpty()) return null
        return row.toDomain(packs)
    }

    override suspend fun upsert(vehicle: Vehicle) {
        val a = vehicle.alertConfig
        // One transaction for the vehicle row and its packs: a crash between
        // the writes must never leave a packless VehicleRow behind (get() and
        // the vehicles flow would silently drop it forever), and committing
        // both tables together collapses their change notifications into a
        // single emission instead of two.
        queries.transaction {
            queries.upsert(
                id = vehicle.id,
                name = vehicle.name,
                iconKey = vehicle.iconKey,
                topology = vehicle.topology.name,
                chemistry = vehicle.chemistry.name,
                averagingWindowMin = vehicle.averagingWindowMin.toLong(),
                cellHighV = a.cellHighV?.toDouble(),
                cellLowV = a.cellLowV?.toDouble(),
                cellDeltaMv = a.cellDeltaMv?.toLong(),
                temperatureWarnC = a.temperatureWarnC?.toDouble(),
                temperatureHighC = a.temperatureHighC?.toDouble(),
                socLowPercent = a.socLowPercent?.toLong(),
                socCutoffPercent = a.socCutoffPercent?.toLong(),
                disconnectNotify = if (a.disconnectNotify) 1L else 0L,
                chargeCompleteNotify = if (a.chargeCompleteNotify) 1L else 0L,
                createdAt = vehicle.createdAt.toString(),
                lastConnectedAt = vehicle.lastConnectedAt?.toString(),
                isPinned = if (vehicle.isPinned) 1L else 0L
            )
            // Replace the pack set wholesale. Stored indices are whatever the
            // caller provided — nothing guarantees a contiguous 0..n-1 — so
            // trimming by index cannot be trusted to remove every stale row.
            packQueries.deleteByVehicle(vehicle.id)
            vehicle.packs.forEach { p ->
                packQueries.upsert(
                    vehicleId = vehicle.id,
                    packIndex = p.index.toLong(),
                    label = p.label,
                    bmsType = p.bmsType.name,
                    bmsAddress = p.bmsAddress,
                    cellCount = p.cellCount?.toLong()
                )
            }
        }
    }

    override suspend fun delete(id: String) {
        // Explicit rather than relying on ON DELETE CASCADE: foreign keys are
        // off by default in SQLite unless PRAGMA foreign_keys is enabled.
        packQueries.deleteByVehicle(id)
        queries.delete(id)
    }

    override suspend fun touch(id: String) {
        queries.touch(now = Clock.System.now().toString(), id = id)
    }
}

@OptIn(ExperimentalTime::class)
private fun VehicleRow.toDomain(packRows: List<PackRow>): Vehicle = Vehicle(
    id = id,
    name = name,
    iconKey = iconKey,
    packs = packRows.sortedBy { it.packIndex }.map { p ->
        Pack(
            index = p.packIndex.toInt(),
            label = p.label,
            bmsType = BmsType.valueOf(p.bmsType),
            bmsAddress = p.bmsAddress,
            cellCount = p.cellCount?.toInt()
        )
    },
    topology = PackTopology.valueOf(topology),
    chemistry = Chemistry.valueOf(chemistry),
    averagingWindowMin = averagingWindowMin.toInt(),
    alertConfig = AlertConfig(
        cellHighV = cellHighV?.toFloat(),
        cellLowV = cellLowV?.toFloat(),
        cellDeltaMv = cellDeltaMv?.toInt(),
        temperatureWarnC = temperatureWarnC?.toFloat(),
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
