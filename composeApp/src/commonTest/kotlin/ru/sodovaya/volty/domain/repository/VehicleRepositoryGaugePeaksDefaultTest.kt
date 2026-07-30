package ru.sodovaya.volty.domain.repository

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * [VehicleRepository.updateGaugePeaks]'s **default body**, which
 * [ru.sodovaya.volty.data.db.SqlDelightVehicleRepository] overrides and two dozen test fakes inherit.
 *
 * It exists so that inheriting it is not the same as swallowing the write: a no-op default would let
 * every one of those fakes report success while storing nothing, and no test above them could tell a
 * working writer from a missing one. That claim is only true if the default actually works, which is
 * what this file is for — nothing else exercises it.
 */
@OptIn(ExperimentalTime::class)
class VehicleRepositoryGaugePeaksDefaultTest {

    /** The minimum a fake needs, and — deliberately — no `updateGaugePeaks` override. */
    private class InheritingRepo(initial: List<Vehicle>) : VehicleRepository {
        val rows = initial.associateBy { it.id }.toMutableMap()
        val upserts = mutableListOf<Vehicle>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(initial)
        override suspend fun get(id: String): Vehicle? = rows[id]
        override suspend fun upsert(vehicle: Vehicle) {
            upserts += vehicle
            rows[vehicle.id] = vehicle
        }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun touch(id: String) {}
    }

    private fun vehicle(id: String = "v1") = Vehicle(
        id = id, name = "Wheel", iconKey = "generic",
        packs = listOf(Pack(0, "P", BmsType.BEGODE, "WH:01")),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(1_700_000_000),
        isPinned = true
    )

    @Test
    fun the_inherited_default_really_stores_both_peaks() = runTest {
        val repo = InheritingRepo(listOf(vehicle()))
        repo.updateGaugePeaks("v1", currentA = 24f, powerW = 1800f)

        assertEquals(24f, repo.get("v1")!!.gaugePeakCurrentA)
        assertEquals(1800f, repo.get("v1")!!.gaugePeakPowerW)
        // Through `upsert`, so a fake that only records upserts sees it — that is the whole reason
        // the default is a `get` + `copy` + `upsert` rather than nothing.
        assertEquals(1, repo.upserts.size)
    }

    /** Exactly two fields, so the default cannot quietly reset the rest of the vehicle either. */
    @Test
    fun the_inherited_default_changes_nothing_else() = runTest {
        val original = vehicle()
        val repo = InheritingRepo(listOf(original))
        repo.updateGaugePeaks("v1", currentA = 24f, powerW = 1800f)
        assertEquals(
            original.copy(gaugePeakCurrentA = 24f, gaugePeakPowerW = 1800f),
            repo.upserts.single()
        )
    }

    @Test
    fun the_inherited_default_ignores_an_unknown_id() = runTest {
        val repo = InheritingRepo(listOf(vehicle()))
        repo.updateGaugePeaks("ghost", currentA = 24f, powerW = 1800f)
        assertEquals(emptyList(), repo.upserts)
    }
}
