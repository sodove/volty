package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.GUEST_VEHICLE_ID_PREFIX
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
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
    }

    private val vehicleRepo = RecordingVehicleRepository()
    private var underTest: KableBmsRepository? = null

    @AfterTest
    fun tearDown() {
        underTest?.close()
        underTest = null
    }

    private fun newRepo(testScope: TestScope): KableBmsRepository = KableBmsRepository.forTesting(
        vehicleRepository = vehicleRepo,
        serviceStart = {},
        serviceStop = {},
        coroutineContext = StandardTestDispatcher(testScope.testScheduler),
    )

    private fun vehicle(id: String = "v1", cellCount: Int? = null) = Vehicle(
        id = id,
        name = "Test",
        iconKey = "generic",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:BB:CC:DD:EE:FF",
        chemistry = Chemistry.LI_ION_NMC,
        cellCount = cellCount,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    private fun sample(cells: Int, voltage: Float) = BmsData(
        voltage = voltage,
        cellVoltages = List(cells) { 3.3f },
        isConnected = true
    )

    /** Emit [count] distinct samples, draining the dispatcher after each. */
    private fun TestScope.emitSamples(repo: KableBmsRepository, cells: Int, count: Int) {
        repeat(count) { i ->
            repo.emitActiveDataForTest(sample(cells, voltage = 13f + i))
            runCurrent()
        }
    }

    @Test
    fun `stable cell count is persisted into the saved vehicle`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle(cellCount = null)
        repo.primeConnectedForTest(v, v.bmsAddress, v.bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        emitSamples(repo, cells = 4, count = 3)

        assertEquals(1, vehicleRepo.upserts.size, "exactly one auto-fill upsert expected")
        assertEquals(4, vehicleRepo.upserts.single().cellCount)
        assertEquals(v.id, vehicleRepo.upserts.single().id)
    }

    @Test
    fun `unstable cell count is not persisted`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle(cellCount = null)
        repo.primeConnectedForTest(v, v.bmsAddress, v.bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        // Daly-style mid-cycle partial lists: 3, 6, 9 — never the same twice.
        repo.emitActiveDataForTest(sample(3, 13f)); runCurrent()
        repo.emitActiveDataForTest(sample(6, 14f)); runCurrent()
        repo.emitActiveDataForTest(sample(9, 15f)); runCurrent()

        assertTrue(vehicleRepo.upserts.isEmpty(), "partial counts must not be persisted")
    }

    @Test
    fun `matching profile count is not re-persisted`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle(cellCount = 4)
        repo.primeConnectedForTest(v, v.bmsAddress, v.bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        emitSamples(repo, cells = 4, count = 5)

        assertTrue(vehicleRepo.upserts.isEmpty(), "no upsert when the profile already matches")
    }

    @Test
    fun `guest vehicles are never persisted`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val guest = vehicle(id = "${GUEST_VEHICLE_ID_PREFIX}AA:BB", cellCount = null)
        repo.primeConnectedForTest(guest, guest.bmsAddress, guest.bmsType, Clock.System.now().toEpochMilliseconds())
        runCurrent()

        emitSamples(repo, cells = 4, count = 5)

        assertTrue(vehicleRepo.upserts.isEmpty(), "guests are transient — no auto-fill writes")
    }
}
