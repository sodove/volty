package ru.sodovaya.volty.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import ru.sodovaya.volty.domain.alert.AlertLevel
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.sortedLevels
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
    private val alertLevelQueries = provider.database.alertLevelRowQueries

    private val vehicleRows: Flow<List<VehicleRow>> = queries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)

    private val packRows: Flow<List<PackRow>> = packQueries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)

    private val controllerRows: Flow<List<ControllerRow>> = controllerQueries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)

    private val alertLevelRows: Flow<List<AlertLevelRow>> = alertLevelQueries.selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)

    override val vehicles: Flow<List<Vehicle>> =
        combine(vehicleRows, packRows, controllerRows, alertLevelRows) { rows, packs, ctrls, levels ->
            val packsByVehicle = packs.groupBy { it.vehicleId }
            val controllersByVehicle = ctrls.groupBy { it.vehicleId }
            val levelsByVehicle = levels.groupBy { it.vehicleId }
            // A vehicle with neither packs nor controllers cannot be
            // constructed (Vehicle.init requires at least one source), and
            // would mean a broken migration — drop it rather than crash the
            // whole list. A controller-only vehicle (no packs) is valid.
            rows.mapNotNull { row ->
                val ownPacks = packsByVehicle[row.id].orEmpty()
                val ownControllers = controllersByVehicle[row.id].orEmpty()
                if (ownPacks.isEmpty() && ownControllers.isEmpty()) null
                else row.toDomain(ownPacks, ownControllers, levelsByVehicle[row.id].orEmpty())
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun get(id: String): Vehicle? {
        val row = queries.selectById(id).executeAsOneOrNull() ?: return null
        val packs = packQueries.selectByVehicle(id).executeAsList()
        val controllers = controllerQueries.selectByVehicle(id).executeAsList()
        if (packs.isEmpty() && controllers.isEmpty()) return null
        return row.toDomain(packs, controllers, alertLevelQueries.selectByVehicle(id).executeAsList())
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
                yieldBmsToHeadUnit = vehicle.yieldBmsToHeadUnit?.let { if (it) 1L else 0L },
                // The "never configured" bit, and the reason it is a column and
                // not `AlertLevelRow is empty`: a rider who switches every kind
                // off writes zero level rows, exactly like a rider who has never
                // opened the screen — and the two must not read back the same.
                // See AlertLevelRow.sq / Vehicle.motionAlerts.
                motionAlertsConfigured = if (vehicle.motionAlerts != null) 1L else 0L,
                // Zero is a value here, not an absence — see Vehicle.gaugePeakCurrentA
                // and 7.sqm — so these two ride along on every upsert like any
                // other NOT NULL column. `updateGaugePeaks` below is the hot path
                // that avoids rewriting the child tables, not the only writer.
                gaugePeakCurrentA = vehicle.gaugePeakCurrentA.toDouble(),
                gaugePeakPowerW = vehicle.gaugePeakPowerW.toDouble()
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
            // And for the alert levels. Inside the SAME transaction as the
            // vehicle row on purpose: motionAlertsConfigured and the rows it
            // vouches for are one fact split across two tables, and a crash
            // between them would leave the flag set over a stale level set —
            // i.e. the rider's alarm firing at somebody else's numbers.
            //
            // THE SECOND HALF OF THE DOWNGRADE PATH. `toRules()` below is
            // non-destructively tolerant — it skips rows it cannot represent
            // (an unknown `kind`, levels past MAX_LEVELS) and leaves them in the
            // table, so a newer version's data survives being *read* by an older
            // build. This write is not: it replaces the level set wholesale from
            // the in-memory list, which by construction contains only what
            // `toRules()` could represent. So on a downgrade those rows live
            // exactly until the rider touches any vehicle setting, and are then
            // deleted for good, silently and unrecoverably.
            //
            // Unreachable today — nothing writes an unknown kind and `upsert`
            // cannot exceed MAX_LEVELS — and not worth a merge strategy now. But
            // read-tolerance is NOT downgrade-safety, and a future release that
            // wants it must fix this write, not just that read.
            alertLevelQueries.deleteByVehicle(vehicle.id)
            vehicle.motionAlerts?.forEach { rule ->
                rule.levels.forEachIndexed { position, level ->
                    alertLevelQueries.upsert(
                        vehicleId = vehicle.id,
                        kind = rule.kind.name,
                        position = position.toLong(),
                        threshold = level.thresholdValue.toDouble(),
                        enabled = if (level.enabled) 1L else 0L
                    )
                }
            }
        }
    }

    override suspend fun delete(id: String) {
        // Explicit rather than relying on ON DELETE CASCADE: foreign keys are
        // off by default in SQLite unless PRAGMA foreign_keys is enabled.
        //
        // In one transaction for the same reason upsert is: four statements
        // means three windows in which process death leaves child rows behind
        // with no parent, and a recycled vehicle id would then adopt a stranger's
        // packs, controllers and alarm thresholds. It also collapses four change
        // notifications into one.
        queries.transaction {
            packQueries.deleteByVehicle(id)
            controllerQueries.deleteByVehicle(id)
            alertLevelQueries.deleteByVehicle(id)
            queries.delete(id)
        }
    }

    override suspend fun touch(id: String) {
        queries.touch(now = Clock.System.now().toString(), id = id)
    }

    /**
     * Two columns, no children, no transaction — the same shape as [touch].
     *
     * Overrides [VehicleRepository.updateGaugePeaks]'s correct-but-heavy default
     * (read, `copy`, full upsert) for the reason stated there: this runs while the
     * rider is riding, and the default would replay the caller's snapshot of the
     * pack/controller/alert tables. A single UPDATE also needs no transaction —
     * there is no second statement for a crash to land between.
     */
    override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {
        queries.updateGaugePeaks(
            gaugePeakCurrentA = currentA.toDouble(),
            gaugePeakPowerW = powerW.toDouble(),
            id = id
        )
    }
}

@OptIn(ExperimentalTime::class)
private fun VehicleRow.toDomain(
    packRows: List<PackRow>,
    controllerRows: List<ControllerRow>,
    alertLevelRows: List<AlertLevelRow>
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
    yieldBmsToHeadUnit = yieldBmsToHeadUnit?.let { it != 0L },
    // Null — "never configured, use AlarmDefaults" — comes from the column, NOT
    // from the row list being empty. `motionAlertsConfigured = 1` with no rows
    // is a rider who switched everything off, and stays off.
    motionAlerts = if (motionAlertsConfigured == 1L) alertLevelRows.toRules() else null,
    // No `runCatching` and no null-coalesce: the columns are NOT NULL DEFAULT 0
    // and zero is a legitimate reading. A hostile file could still hand back a
    // NaN or an infinity, which is why PeakTracker.seededAt — the one consumer —
    // treats a non-finite seed as "nothing learned" rather than trusting it.
    gaugePeakCurrentA = gaugePeakCurrentA.toFloat(),
    gaugePeakPowerW = gaugePeakPowerW.toFloat()
)

/**
 * Rows -> one [AlertRule] per [MotionAlertKind], in enum order. A kind with no
 * rows becomes an empty rule, which is exactly [AlertRule.isOff] — the caller
 * only reaches here when the vehicle *has* been configured, so an absent kind
 * means the rider removed its levels.
 *
 * **Every step below exists to keep a hostile row set from crashing the app at
 * startup rather than merely failing to load a value.** [AlertRule] and
 * [AlertLevel] enforce their invariants with `require`, so a database that
 * violates one throws `IllegalArgumentException` out of `get()` / the vehicles
 * flow — on the launch path, before any screen. Nothing here can be trusted:
 * SQLite is a file on the device, backups get restored across app versions, and
 * a future writer bug would land its damage here.
 *
 *  - **sorted by threshold, not by `position`.** [AlertRule] requires ascending
 *    thresholds; stored positions are only what some past writer believed. A row
 *    set whose positions disagree with its thresholds is the crash. `position`
 *    survives as the tie-break, since [sortedLevels] is stable — two levels
 *    sharing a threshold keep the order the rider typed;
 *  - **non-finite thresholds dropped.** [AlertLevel] rejects them (a NaN level
 *    can never fire and never release, while `isOff` stays false — an alarm that
 *    silently does not exist). SQLite will happily hand back an infinity;
 *  - **at most [AlertRule.MAX_LEVELS], keeping the HIGHEST.** Over-long row sets
 *    are impossible through [upsert] but trivial to hand-edit. Dropping from the
 *    top would discard the most urgent step, so the mildest goes instead;
 *  - **unrecognised `kind` strings ignored** — same treatment `dashboardStyle`
 *    gets above, and the path a downgrade takes.
 */
private fun List<AlertLevelRow>.toRules(): List<AlertRule> {
    val byKind = groupBy { runCatching { MotionAlertKind.valueOf(it.kind) }.getOrNull() }
    return MotionAlertKind.entries.map { kind ->
        val levels = byKind[kind].orEmpty()
            .sortedBy { it.position }
            .filter { it.threshold.toFloat().isFinite() }
            .map { AlertLevel(thresholdValue = it.threshold.toFloat(), enabled = it.enabled == 1L) }
        AlertRule(kind = kind, levels = sortedLevels(levels).takeLast(AlertRule.MAX_LEVELS))
    }
}
