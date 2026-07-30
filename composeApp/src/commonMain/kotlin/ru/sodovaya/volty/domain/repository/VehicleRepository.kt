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
     * **The only writer of [Vehicle.gaugePeakCurrentA] / [Vehicle.gaugePeakPowerW]**
     * (`G §9.2`) — the learned widths of the ride dashboard's CURRENT and POWER
     * dials. Writes those two columns and nothing else.
     *
     * ## Why this is abstract, and must stay abstract
     *
     * [upsert] **cannot** write these two columns: it preserves whatever is
     * stored, because every caller of it holds a [Vehicle] *snapshot* and a
     * snapshot can be older than the last peak write (see
     * [Vehicle.gaugePeakCurrentA] and `VehicleRow.sq`). So this member is not a
     * convenience over `upsert` — it is the only path that exists.
     *
     * It had a default body that re-read and `upsert`ed, on the argument that a
     * *working* default beats a no-op one. That was wrong, and wrong in the
     * direction this whole feature keeps failing in: against the real storage
     * layer that body is a **guaranteed no-op**, because the `upsert` inside it
     * throws the peaks away. An implementation that forgot to override compiled,
     * ran, and silently discarded every learned range for good — the same "saves
     * clean and lies" shape as `G §8`.
     *
     * Abstract, so the compiler asks. A fake that genuinely does not care about
     * these columns writes an empty body and says so — that is a decision at the
     * fake, visible in its own file, not a default imposed on everybody.
     *
     * A conforming implementation must make the two values readable back through
     * [get] and [vehicles], and must not disturb any other column or child row —
     * it runs while the rider is riding, so replaying a snapshot's packs,
     * controllers or alert levels over rows the live connection has moved is the
     * failure mode it exists to avoid. `touch` is the same shape for the same
     * reason.
     */
    suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float)
}
