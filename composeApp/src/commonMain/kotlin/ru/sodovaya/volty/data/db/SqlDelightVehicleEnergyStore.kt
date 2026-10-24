package ru.sodovaya.volty.data.db

import ru.sodovaya.volty.domain.energy.EnergyMemory
import ru.sodovaya.volty.domain.energy.LearnedCapacity
import ru.sodovaya.volty.domain.energy.PersistedCharge
import ru.sodovaya.volty.domain.energy.VehicleEnergyStore
import ru.sodovaya.volty.domain.model.Chemistry

class SqlDelightVehicleEnergyStore(provider: VoltyDatabaseProvider) : VehicleEnergyStore {
    private val queries = provider.database.vehicleEnergyRowQueries

    override suspend fun load(vehicleId: String, seriesCells: Int, chemistry: Chemistry): EnergyMemory {
        val row = queries.selectByVehicle(vehicleId).executeAsOneOrNull() ?: return EnergyMemory.EMPTY
        if (row.seriesCells != seriesCells.toLong() || row.chemistry != chemistry.name) {
            queries.deleteByVehicle(vehicleId)
            return EnergyMemory.EMPTY
        }
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
            counter = if (counterPercent != null && counterAt != null) PersistedCharge(counterPercent, counterAt) else null
        )
    }

    override suspend fun save(vehicleId: String, seriesCells: Int, chemistry: Chemistry, memory: EnergyMemory) {
        // Learned values feed a dashboard: a non-finite one is dropped, never stored.
        val learned = memory.learned?.takeIf { it.usableAh.isFinite() && it.weight.isFinite() }
        val counter = memory.counter?.takeIf { it.percent.isFinite() }
        queries.upsert(
            vehicleId = vehicleId,
            seriesCells = seriesCells.toLong(),
            chemistry = chemistry.name,
            learnedUsableAh = learned?.usableAh?.toDouble(),
            learnWeight = learned?.weight?.toDouble() ?: 0.0,
            learnRides = learned?.rides?.toLong() ?: 0L,
            resistanceOhm = memory.resistanceOhm?.takeIf { it.isFinite() }?.toDouble(),
            counterPercent = counter?.percent?.toDouble(),
            counterAtMillis = counter?.atMillis
        )
    }

    override suspend fun reset(vehicleId: String) {
        queries.deleteByVehicle(vehicleId)
    }
}
