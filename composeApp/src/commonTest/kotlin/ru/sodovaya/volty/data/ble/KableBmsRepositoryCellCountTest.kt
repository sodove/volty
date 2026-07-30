package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.GUEST_VEHICLE_ID_PREFIX
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.cellCountOrNull
import ru.sodovaya.volty.domain.model.wheelVehicle
import ru.sodovaya.volty.domain.model.withCellCount
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The profile's cell count is an auto-filled cache of live telemetry (see
 * [KableBmsRepository.maybePersistCellCount]): once the reported count is
 * stable for a few consecutive samples, the repo writes it back into the saved
 * vehicle. Guests/demo are transient and must never be persisted.
 *
 * **Stability is not completeness (Task 3).** A dead BMS branch reports the
 * SAME truncated cell list forever, which clears the stability check just as
 * cleanly as a genuine reading — so a second gate,
 * [ru.sodovaya.volty.data.bms.BegodeProtocol.isCellCountConfirmed], must also
 * confirm the candidate sample's cell-sum against its own reported pack
 * voltage. `aStablyPartialCellSetDoesNotOverwriteTheStoredCount` pins that.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryCellCountTest {

    private class RecordingVehicleRepository : VehicleRepository {
        val upserts = mutableListOf<Vehicle>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) { upserts += vehicle }
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
        // Explicit, because VehicleRepository.updateGaugePeaks is abstract: no fake gets a
        // silent default. Nothing in this file rides a learned dial range (G §9.2).
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    private val vehicleRepo = RecordingVehicleRepository()
    /** Every test here owns its repository through [bleRepositoryTest] — see there for why that is not optional. */
    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = vehicleRepo,
        serviceStart = {},
        serviceStop = {},
        body = body
    )

    private fun vehicle(id: String = "v1", cellCount: Int? = null) = singlePackVehicle(
        id = id,
        name = "Test",
        iconKey = "generic",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:BB:CC:DD:EE:FF",
        chemistry = Chemistry.LI_ION_NMC,
        cellCount = cellCount,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    private fun sample(cellVoltages: List<Float>, voltage: Float) = BmsData(
        voltage = voltage,
        cellVoltages = cellVoltages,
        isConnected = true
    )

    /**
     * Emit [count] distinct samples carrying [cellVoltages] against [voltage] —
     * the sample's own reported pack voltage, independent of the cell list so a
     * caller can model a genuinely PARTIAL reading (a full pack's real voltage
     * behind a truncated cell list — exactly what a dropped BMS branch looks
     * like). Defaults to the matching, COMPLETE case, which is what every test
     * here wanted before Task 3 added a completeness gate alongside the
     * stability one.
     *
     * Draining the dispatcher after each: the tiny per-sample offset exists
     * only so consecutive `_activeData` values are structurally distinct —
     * `MutableStateFlow` conflates equal values and the stability streak would
     * never advance past one sample.
     */
    private fun TestScope.feedStableSamples(
        repo: KableBmsRepository,
        count: Int,
        cellVoltages: List<Float>,
        voltage: Float = cellVoltages.sum()
    ) {
        repeat(count) { i ->
            repo.emitActiveDataForTest(sample(cellVoltages, voltage + i * 0.001f))
            runCurrent()
        }
    }

    @Test
    fun `stable cell count is persisted into the saved vehicle`() = repoTest { repo ->
        val v = vehicle(cellCount = null)
        repo.primeConnectedForTest(v, v.primaryAddress, v.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        feedStableSamples(repo, count = 3, cellVoltages = List(4) { 3.3f })

        assertEquals(1, vehicleRepo.upserts.size, "exactly one auto-fill upsert expected")
        assertEquals(4, vehicleRepo.upserts.single().cellCountOrNull)
        assertEquals(v.id, vehicleRepo.upserts.single().id)
    }

    @Test
    fun `unstable cell count is not persisted`() = repoTest { repo ->
        val v = vehicle(cellCount = null)
        repo.primeConnectedForTest(v, v.primaryAddress, v.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        // Daly-style mid-cycle partial lists: 3, 6, 9 — never the same twice.
        repo.emitActiveDataForTest(sample(List(3) { 3.3f }, 13f)); runCurrent()
        repo.emitActiveDataForTest(sample(List(6) { 3.3f }, 14f)); runCurrent()
        repo.emitActiveDataForTest(sample(List(9) { 3.3f }, 15f)); runCurrent()

        assertTrue(vehicleRepo.upserts.isEmpty(), "partial counts must not be persisted")
    }

    @Test
    fun `matching profile count is not re-persisted`() = repoTest { repo ->
        val v = vehicle(cellCount = 4)
        repo.primeConnectedForTest(v, v.primaryAddress, v.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        feedStableSamples(repo, count = 5, cellVoltages = List(4) { 3.3f })

        assertTrue(vehicleRepo.upserts.isEmpty(), "no upsert when the profile already matches")
    }

    @Test
    fun `guest vehicles are never persisted`() = repoTest { repo ->
        val guest = vehicle(id = "${GUEST_VEHICLE_ID_PREFIX}AA:BB", cellCount = null)
        repo.primeConnectedForTest(guest, guest.primaryAddress, guest.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        feedStableSamples(repo, count = 5, cellVoltages = List(4) { 3.3f })

        assertTrue(vehicleRepo.upserts.isEmpty(), "guests are transient — no auto-fill writes")
    }

    /**
     * The defect the pre-flight correction on Task 3 named explicitly: a
     * branch has dropped out (8 of 40 cells), reporting the same truncated
     * list for three consecutive samples — the stability check alone cannot
     * tell that apart from a genuine 8S reading. The sample's own voltage
     * stays at the FULL pack's real reported value (a 40S pack, ~4.1 V/cell),
     * which is what a dropped branch actually looks like on the wire: the
     * cell list shrinks, the frame's own voltage field does not. Reusing
     * [ru.sodovaya.volty.data.bms.BegodeProtocol.isCellSumComplete] here
     * (the loose, one-sided judgement `branchVoltage` uses for a different
     * decision) would pass this case — 8 * 4.1 = 32.8 is nowhere near either
     * bound that function tests against a MATCHING frame field, but the
     * point of this fixture is that the reported voltage belongs to the
     * FULL pack, not the partial one, and only
     * [ru.sodovaya.volty.data.bms.BegodeProtocol.isCellCountConfirmed]'s
     * tight band catches that mismatch.
     */
    @Test
    fun aStablyPartialCellSetDoesNotOverwriteTheStoredCount() = repoTest { repo ->
        val v = wheelVehicle(
            id = "v2",
            name = "ET Max",
            iconKey = "unicycle",
            address = "AA:BB:CC:DD:EE:00",
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Instant.fromEpochSeconds(0L)
        ).withCellCount(40)
        repo.primeConnectedForTest(v, v.primaryAddress, v.packs.first().bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        // 40 cells at ~4.1 V is the pack's real, full voltage (~164 V) — the
        // branch's OWN reported field, independent of how many cells actually
        // arrived this cycle.
        feedStableSamples(repo, count = 3, cellVoltages = List(8) { 4.1f }, voltage = 164f)

        assertTrue(
            vehicleRepo.upserts.isEmpty(),
            "a stably partial cell set must not overwrite the stored count"
        )
    }
}
