package ru.sodovaya.volty.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SecondaryGauge
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
    private val controllerQueries = provider.database.controllerRowQueries

    private val vehicleRows: Flow<List<VehicleRow>> = queries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)

    private val packRows: Flow<List<PackRow>> = packQueries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)

    private val controllerRows: Flow<List<ControllerRow>> = controllerQueries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)

    override val vehicles: Flow<List<Vehicle>> =
        combine(vehicleRows, packRows, controllerRows) { rows, packs, ctrls ->
            val packsByVehicle = packs.groupBy { it.vehicleId }
            val controllersByVehicle = ctrls.groupBy { it.vehicleId }
            // A vehicle with neither packs nor controllers cannot be
            // constructed (Vehicle.init requires at least one source), and
            // would mean a broken migration — drop it rather than crash the
            // whole list. A controller-only vehicle (no packs) is valid.
            rows.mapNotNull { row ->
                val ownPacks = packsByVehicle[row.id].orEmpty()
                val ownControllers = controllersByVehicle[row.id].orEmpty()
                if (ownPacks.isEmpty() && ownControllers.isEmpty()) null
                else row.toDomain(ownPacks, ownControllers)
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun get(id: String): Vehicle? {
        val row = queries.selectById(id).executeAsOneOrNull() ?: return null
        val packs = packQueries.selectByVehicle(id).executeAsList()
        val controllers = controllerQueries.selectByVehicle(id).executeAsList()
        if (packs.isEmpty() && controllers.isEmpty()) return null
        return row.toDomain(packs, controllers)
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
                isPinned = if (vehicle.isPinned) 1L else 0L,
                dashboardStyle = vehicle.dashboardStyle?.name,
                secondaryGauge = vehicle.secondaryGauge.name,
                // Three-valued on purpose (see Vehicle.yieldBmsToHeadUnit):
                // NULL is "unset / follow the default", NOT the same row state
                // as an explicit 0 or 1.
                yieldBmsToHeadUnit = vehicle.yieldBmsToHeadUnit?.let { if (it) 1L else 0L }
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
                    cellCount = p.cellCount?.toLong(),
                    canId = p.canId?.toLong(),
                    aliasGroup = p.aliasGroup
                )
            }
            // Same wholesale-replace treatment for controllers.
            controllerQueries.deleteByVehicle(vehicle.id)
            vehicle.controllers.forEach { c ->
                controllerQueries.upsert(
                    vehicleId = vehicle.id,
                    controllerIndex = c.index.toLong(),
                    label = c.label,
                    controllerType = c.controllerType.name,
                    address = c.address,
                    canId = c.canId?.toLong(),
                    polePairs = c.motor.polePairs.toLong(),
                    wheelDiameterMm = c.motor.wheelDiameterMm.toLong(),
                    gearRatio = c.motor.gearRatio.toDouble(),
                    providesDerivedBattery = if (c.providesDerivedBattery) 1L else 0L
                )
            }
        }
    }

    override suspend fun delete(id: String) {
        // Explicit rather than relying on ON DELETE CASCADE: foreign keys are
        // off by default in SQLite unless PRAGMA foreign_keys is enabled.
        packQueries.deleteByVehicle(id)
        controllerQueries.deleteByVehicle(id)
        queries.delete(id)
    }

    override suspend fun touch(id: String) {
        queries.touch(now = Clock.System.now().toString(), id = id)
    }
}

@OptIn(ExperimentalTime::class)
private fun VehicleRow.toDomain(
    packRows: List<PackRow>,
    controllerRows: List<ControllerRow>
): Vehicle = Vehicle(
    id = id,
    name = name,
    iconKey = iconKey,
    packs = packRows.sortedBy { it.packIndex }.map { p ->
        Pack(
            index = p.packIndex.toInt(),
            label = p.label,
            bmsType = BmsType.valueOf(p.bmsType),
            bmsAddress = p.bmsAddress,
            cellCount = p.cellCount?.toInt(),
            canId = p.canId?.toInt(),
            aliasGroup = p.aliasGroup
        )
    },
    controllers = controllerRows.sortedBy { it.controllerIndex }.map { c ->
        Controller(
            index = c.controllerIndex.toInt(),
            label = c.label,
            controllerType = ControllerType.valueOf(c.controllerType),
            address = c.address,
            canId = c.canId?.toInt(),
            motor = MotorConfig(
                polePairs = c.polePairs.toInt(),
                wheelDiameterMm = c.wheelDiameterMm.toInt(),
                gearRatio = c.gearRatio.toFloat()
            ),
            providesDerivedBattery = c.providesDerivedBattery == 1L
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
    isPinned = isPinned == 1L,
    dashboardStyle = dashboardStyle?.let { runCatching { DashboardStyle.valueOf(it) }.getOrNull() },
    secondaryGauge = secondaryGauge?.let { runCatching { SecondaryGauge.valueOf(it) }.getOrNull() }
        ?: SecondaryGauge.DUTY,
    // NULL stays null (unset) rather than collapsing to false: `yieldsBmsToHeadUnit`
    // is what resolves the default, and it must be able to tell the two apart.
    yieldBmsToHeadUnit = yieldBmsToHeadUnit?.let { it != 0L }
)
