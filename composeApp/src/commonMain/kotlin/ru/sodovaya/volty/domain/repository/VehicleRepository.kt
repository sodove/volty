package ru.sodovaya.volty.domain.repository

import ru.sodovaya.volty.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow

interface VehicleRepository {
    val vehicles: Flow<List<Vehicle>>
    suspend fun get(id: String): Vehicle?
    suspend fun upsert(vehicle: Vehicle)
    suspend fun delete(id: String)
    suspend fun touch(id: String)

    /**
     * Persist the learned dial widths of [Vehicle.gaugePeakCurrentA] /
     * [Vehicle.gaugePeakPowerW] (`G §9.2`) and nothing else.
     *
     * Its own member rather than an `upsert` from the caller's snapshot: the ride
     * dashboard writes these *while riding*, from a [Vehicle] it may have loaded
     * minutes ago, and a full upsert would replay that snapshot's packs,
     * controllers and alert levels over rows the live connection can have moved
     * underneath it (`KableBmsRepository.maybePersistPacks` appends a Begode's
     * second pack branch mid-ride). `touch` exists for the same reason.
     *
     * **The default is a real implementation, not a no-op.** A no-op default
     * would make every existing fake silently swallow the write, so a test could
     * not tell a working writer from a missing one — and there are two dozen such
     * fakes. Re-reading and `copy()`ing is correct for all of them; the storage
     * layer overrides it with a two-column `UPDATE` purely so it does not have to
     * rewrite the child tables.
     */
    suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {
        val v = get(id) ?: return
        upsert(v.copy(gaugePeakCurrentA = currentA, gaugePeakPowerW = powerW))
    }
}
