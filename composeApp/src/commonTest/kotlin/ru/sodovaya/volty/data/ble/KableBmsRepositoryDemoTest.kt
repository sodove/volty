package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.demo.DemoBmsSimulator
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * Verifies the "Try demo" lifecycle on [KableBmsRepository]: connectDemo brings
 * up a simulated session (active vehicle flagged [Vehicle.isDemo], state
 * Connected, foreground service started, samples flowing) and disconnect tears
 * it down completely with no persistence side-effects.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryDemoTest {

    private class StubVehicleRepository : VehicleRepository {
        val upserts = mutableListOf<Vehicle>()
        val touches = mutableListOf<String>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) { upserts += vehicle }
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) { touches += id }
    }

    private val starts = mutableListOf<Unit>()
    private val stops = mutableListOf<Unit>()
    private val vehicleRepo = StubVehicleRepository()

    private fun newRepo(testScope: TestScope): KableBmsRepository = KableBmsRepository.forTesting(
        vehicleRepository = vehicleRepo,
        serviceStart = { starts += Unit },
        serviceStop = { stops += Unit },
        coroutineContext = StandardTestDispatcher(testScope.testScheduler),
    )

    private var underTest: KableBmsRepository? = null

    @AfterTest
    fun tearDown() {
        underTest?.close()
        underTest = null
    }

    @Test
    fun `connectDemo sets demo vehicle connected and starts service`() = runTest {
        val repo = newRepo(this).also { underTest = it }

        val result = repo.connectDemo()
        runCurrent()

        assertTrue(result.isSuccess)
        val active = repo.activeVehicle.value
        assertTrue(active != null && active.isDemo, "active vehicle must be the demo vehicle")
        assertTrue(repo.connectionState.value is ConnectionState.Connected)
        assertEquals(1, starts.size, "foreground service should start for demo")

        // The simulator should feed at least one synthetic sample once virtual
        // time advances one tick interval.
        advanceTimeBy(DemoBmsSimulator.TICK_INTERVAL_MS + 50L)
        runCurrent()
        assertTrue(repo.activeData.value.isConnected, "demo sample should have populated activeData")
        assertEquals(16, repo.activeData.value.cellVoltages.size)

        // Demo must never be persisted.
        assertTrue(vehicleRepo.upserts.isEmpty(), "demo must not be upserted")
        assertTrue(vehicleRepo.touches.isEmpty(), "demo must not be touched")

        // Clean up the still-running simulator loop.
        repo.disconnect()
        runCurrent()
    }

    @Test
    fun `disconnect tears down the demo session`() = runTest {
        val repo = newRepo(this).also { underTest = it }

        repo.connectDemo()
        advanceTimeBy(DemoBmsSimulator.TICK_INTERVAL_MS + 50L)
        runCurrent()

        repo.disconnect()
        runCurrent()
        // Push well past a tick to prove the simulator loop has truly stopped
        // and isn't still mutating activeData.
        advanceTimeBy(DemoBmsSimulator.TICK_INTERVAL_MS * 5)
        runCurrent()

        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
        assertNull(repo.activeVehicle.value, "active vehicle cleared after disconnect")
        assertFalse(repo.activeData.value.isConnected, "activeData reset after disconnect")
        assertEquals(1, stops.size, "service stop invoked once")
    }
}
