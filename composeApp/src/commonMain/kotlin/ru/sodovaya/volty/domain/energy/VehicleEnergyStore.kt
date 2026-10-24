package ru.sodovaya.volty.domain.energy

import ru.sodovaya.volty.domain.model.Chemistry

/** What the app has learned about one vehicle's pack. Every field is null until earned. */
data class EnergyMemory(
    val learned: LearnedCapacity?,
    val resistanceOhm: Float?,
    val counter: PersistedCharge?
) {
    companion object {
        val EMPTY = EnergyMemory(null, null, null)
    }
}

/** A stored row as it is: the memory plus the (seriesCells, chemistry) key it was saved under. */
data class EnergyRecord(val memory: EnergyMemory, val seriesCells: Int, val chemistry: Chemistry)

/**
 * Per-vehicle learned energy state. The key is (vehicle, series cell count, chemistry) and
 * nothing else: the cell BOUNDS are deliberately not part of it (changing them does not
 * unlearn a pack), and no method takes them.
 */
interface VehicleEnergyStore {
    /** Empty memory, and the stored row deleted, when its seriesCells/chemistry differ. */
    suspend fun load(vehicleId: String, seriesCells: Int, chemistry: Chemistry): EnergyMemory

    /**
     * The stored row exactly as saved, or null. **Never deletes**: unlike [load] it takes no key to
     * compare against, so a reader that does not know the runtime-derived cell count (the vehicle
     * editor) can look without wiping what the tracker learned.
     */
    suspend fun peek(vehicleId: String): EnergyRecord?
    suspend fun save(vehicleId: String, seriesCells: Int, chemistry: Chemistry, memory: EnergyMemory)
    suspend fun reset(vehicleId: String)
}
