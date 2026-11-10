package ru.sodovaya.volty.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import ru.sodovaya.volty.domain.energy.CapacityCandidate
import ru.sodovaya.volty.domain.energy.CapacityLearnDecision
import ru.sodovaya.volty.domain.energy.CapacityLearner
import ru.sodovaya.volty.domain.energy.EnergyMemory
import ru.sodovaya.volty.domain.energy.EnergyRecord
import ru.sodovaya.volty.domain.energy.LearnedCapacity
import ru.sodovaya.volty.domain.energy.PersistedCharge
import ru.sodovaya.volty.domain.energy.VehicleEnergyStore
import ru.sodovaya.volty.domain.model.Chemistry

class SqlDelightVehicleEnergyStore(provider: VoltyDatabaseProvider) : VehicleEnergyStore {
    private val queries = provider.database.vehicleEnergyRowQueries

    override suspend fun load(vehicleId: String, seriesCells: Int, chemistry: Chemistry): EnergyMemory = queries.transactionWithResult {
        val row = queries.selectByVehicle(vehicleId).executeAsOneOrNull() ?: return@transactionWithResult EnergyMemory.EMPTY
        if (row.seriesCells != seriesCells.toLong() || row.chemistry != chemistry.name) {
            queries.deleteByVehicle(vehicleId)
            EnergyMemory.EMPTY
        } else memoryOf(row)
    }

    override suspend fun peek(vehicleId: String): EnergyRecord? =
        queries.selectByVehicle(vehicleId).executeAsOneOrNull()?.let(::recordOf)

    override fun observe(vehicleId: String) = queries.selectByVehicle(vehicleId).asFlow()
        .mapToOneOrNull(Dispatchers.Default).map { it?.let(::recordOf) }

    private fun recordOf(row: VehicleEnergyRow): EnergyRecord? {
        val chemistry = Chemistry.entries.firstOrNull { it.name == row.chemistry } ?: return null
        return EnergyRecord(memoryOf(row), row.seriesCells.toInt(), chemistry)
    }

    private fun memoryOf(row: ru.sodovaya.volty.data.db.VehicleEnergyRow): EnergyMemory {
        val usable = row.learnedUsableAh?.toFloat()?.takeIf { it.isFinite() }
        val weight = row.learnWeight.toFloat()
        val learned = if (usable != null && weight.isFinite()) {
            LearnedCapacity(usable, weight, row.learnRides.toInt())
        } else null
        val counterPercent = row.counterPercent?.toFloat()?.takeIf { it.isFinite() }
        val counterAt = row.counterAtMillis
        return EnergyMemory(
            learned = learned,
            resistanceOhm = row.resistanceOhm?.toFloat()?.takeIf { it.isFinite() },
            counter = if (counterPercent != null && counterAt != null) PersistedCharge(counterPercent, counterAt) else null,
            capacityCandidate = if (learned != null) candidateOf(row) else null,
            lastCapacityDecision = CapacityLearnDecision.entries.firstOrNull { it.name == row.lastCapacityDecision },
        )
    }

    private fun candidateOf(row: VehicleEnergyRow): CapacityCandidate? {
        val ah = row.candidateUsableAh?.toFloat() ?: return null
        val weight = row.candidateWeight?.toFloat() ?: return null
        val rides = row.candidateRides?.takeIf { it in 1L until CapacityLearner.RECOVERY_MEASUREMENTS.toLong() } ?: return null
        val min = row.candidateMinAh?.toFloat() ?: return null
        val max = row.candidateMaxAh?.toFloat() ?: return null
        return CapacityCandidate(LearnedCapacity(ah, weight, rides.toInt()), min, max).takeIf { it.isUsable() }
    }

    private fun CapacityCandidate.isUsable(): Boolean =
        minAh.isFinite() && minAh > 0f && maxAh.isFinite() && estimate.usableAh in minAh..maxAh &&
            estimate.weight.isFinite() && estimate.weight > 0f && estimate.rides in 1 until CapacityLearner.RECOVERY_MEASUREMENTS

    override suspend fun save(vehicleId: String, seriesCells: Int, chemistry: Chemistry, memory: EnergyMemory) {
        // Learned values feed a dashboard: a non-finite one is dropped, never stored.
        val learned = memory.learned?.takeIf { it.usableAh.isFinite() && it.weight.isFinite() }
        val counter = memory.counter?.takeIf { it.percent.isFinite() }
        val candidate = memory.capacityCandidate?.takeIf { learned != null && it.isUsable() }
        queries.upsert(
            vehicleId = vehicleId,
            seriesCells = seriesCells.toLong(),
            chemistry = chemistry.name,
            learnedUsableAh = learned?.usableAh?.toDouble(),
            learnWeight = learned?.weight?.toDouble() ?: 0.0,
            learnRides = learned?.rides?.toLong() ?: 0L,
            resistanceOhm = memory.resistanceOhm?.takeIf { it.isFinite() }?.toDouble(),
            counterPercent = counter?.percent?.toDouble(),
            counterAtMillis = counter?.atMillis,
            candidateUsableAh = candidate?.estimate?.usableAh?.toDouble(),
            candidateWeight = candidate?.estimate?.weight?.toDouble(),
            candidateRides = candidate?.estimate?.rides?.toLong(),
            candidateMinAh = candidate?.minAh?.toDouble(),
            candidateMaxAh = candidate?.maxAh?.toDouble(),
            lastCapacityDecision = memory.lastCapacityDecision?.name,
        )
    }

    override suspend fun reset(vehicleId: String) {
        queries.deleteByVehicle(vehicleId)
    }
}
