package ru.sodovaya.volty.domain.repository

import ru.sodovaya.volty.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow

/**
 * The learned widths of one vehicle's CURRENT and POWER dials (`G §9.2`), in
 * amps and watts.
 *
 * **Not a [Vehicle] field, and that is the point of Part I Task 9.** They were
 * two columns on the vehicle row until `8.sqm`, which made them settable through
 * `copy(...)` on a `Vehicle` that `upsert` then refused to write — a setter that
 * compiled, ran, and silently did nothing, whose only signal was a KDoc one
 * layer away. Reached through [VehicleRepository.gaugePeaks] /
 * [VehicleRepository.updateGaugePeaks] instead, the unwritable setter stops
 * existing rather than being documented.
 *
 * **[NONE] is what absence means, and there is no third state.** A vehicle
 * nobody has ridden has no stored row, and answers zero — the same answer as a
 * vehicle that genuinely peaks at nothing, and both correctly open the dial on
 * [GaugeScale.CURRENT_RUNGS_A][ru.sodovaya.volty.domain.stats.GaugeScale.CURRENT_RUNGS_A]'s
 * first rung. So this is a plain non-nullable pair of `Float`s rather than a
 * nullable the way [Vehicle.dashboardStyle] is.
 */
data class GaugePeaks(val currentA: Float = 0f, val powerW: Float = 0f) {
    companion object {
        /** Nothing learned — what a vehicle with no stored row reads back as. */
        val NONE: GaugePeaks = GaugePeaks()
    }
}

interface VehicleRepository {
    val vehicles: Flow<List<Vehicle>>
    suspend fun get(id: String): Vehicle?
    suspend fun upsert(vehicle: Vehicle)
    suspend fun delete(id: String)
    suspend fun touch(id: String)

    /**
     * Every vehicle's learned dial widths, keyed by [Vehicle.id] — **the only
     * trustworthy source for them**, and the flow the composer's clear notifies.
     *
     * A vehicle with **no entry has learned nothing** and must be read as
     * [GaugePeaks.NONE]; the map is not padded, because absence and zero are the
     * same statement (see [GaugePeaks]). A guest or a demo is never in it at all.
     *
     * Its own flow rather than a field of [vehicles] because that is exactly the
     * separation this member exists to enforce: a `Vehicle` is a description the
     * rider edits, these are a cache of live telemetry with one producer
     * (`RideDashboardComponent`), and a snapshot of the former must not be able
     * to carry a stale copy of the latter back into storage.
     */
    val gaugePeaks: Flow<Map<String, GaugePeaks>>

    /**
     * **The only writer of the learned dial widths** (`G §9.2`) — the ride
     * dashboard while the rider is riding, and the composer clearing them when a
     * vehicle's controller set changes. Writes those two values and nothing else.
     *
     * ## Why this is abstract, and must stay abstract
     *
     * It had a default body that re-read the vehicle and `upsert`ed it. That was
     * wrong, and wrong in the direction this whole feature keeps failing in:
     * against the real storage layer that body was a **guaranteed no-op**,
     * because `upsert` could not write the columns it set. An implementation that
     * forgot to override compiled, ran, and silently discarded every learned
     * range for good — the same "saves clean and lies" shape as `G §8`. Since
     * `8.sqm` the values are not `Vehicle` fields at all, so such a default can
     * no longer even be spelled — but abstract is still what makes the compiler
     * ask, and a fake that genuinely does not care writes an empty body and says
     * so, visibly, in its own file.
     *
     * A conforming implementation must make the two values readable back through
     * [gaugePeaks], and must not disturb the vehicle row or any child row — it
     * runs while the rider is riding, so replaying a snapshot's packs,
     * controllers or alert levels over rows the live connection has moved is the
     * failure mode it exists to avoid. `touch` is the same shape for the same
     * reason.
     */
    suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float)
}
