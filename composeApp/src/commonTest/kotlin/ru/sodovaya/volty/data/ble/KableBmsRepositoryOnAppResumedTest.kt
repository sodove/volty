package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Defense-in-depth: when the OS suspends our dispatchers (Doze / App-Standby /
 * killed foreground service), the in-session watchdog can't tick. On resume,
 * [KableBmsRepository.onAppResumed] must notice a stale sample and re-fire the
 * same drop pathway the watchdog uses.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryOnAppResumedTest {

    private class StubVehicleRepository : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    private fun newRepo(testScope: TestScope): KableBmsRepository = KableBmsRepository.forTesting(
        vehicleRepository = StubVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        coroutineContext = StandardTestDispatcher(testScope.testScheduler),
    )

    private var underTest: KableBmsRepository? = null

    @AfterTest
    fun tearDown() {
        underTest?.close()
        underTest = null
    }

    private fun vehicle() = Vehicle(
        id = "v1",
        name = "Test",
        iconKey = "generic",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:BB:CC:DD:EE:FF",
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    @Test
    fun `onAppResumed with stale sample triggers reconnect loop`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()

        // Connected pre-background, but the last sample is well past staleSampleMs.
        val now = Clock.System.now().toEpochMilliseconds()
        val staleMs = now - (BleConfig.staleSampleMs + 5_000L)
        repo.primeConnectedForTest(v, v.bmsAddress, v.bmsType, lastSampleAtMs = staleMs)

        // Sanity: state begins Connected with no reconnect job.
        assertTrue(repo.connectionState.value is ConnectionState.Connected)
        assertTrue(repo.reconnectJobForTest() == null)

        repo.onAppResumed()
        runCurrent()

        // After the resume hook fires, the reconnect job must be running:
        // exactly the drop pathway the watchdog would have used.
        val job = repo.reconnectJobForTest()
        assertTrue(job != null, "expected reconnect job to be started by onAppResumed")
        assertTrue(job.isActive, "expected reconnect job to be active")

        // Clean shutdown so the runTest doesn't hang on the live loop.
        repo.disconnect()
        runCurrent()
        assertTrue(!job.isActive, "disconnect must terminate the reconnect job")
    }

    @Test
    fun `onAppResumed with fresh sample is a no-op`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()

        // Sample arrived a moment ago — well within staleSampleMs.
        val now = Clock.System.now().toEpochMilliseconds()
        val freshMs = now - 100L
        repo.primeConnectedForTest(v, v.bmsAddress, v.bmsType, lastSampleAtMs = freshMs)

        repo.onAppResumed()
        runCurrent()

        // Fresh sample → no reconnect needed.
        assertTrue(
            repo.reconnectJobForTest() == null,
            "fresh sample must not trigger reconnect"
        )
        assertTrue(
            repo.connectionState.value is ConnectionState.Connected,
            "fresh sample must leave state Connected"
        )

        repo.disconnect()
        runCurrent()
    }

    @Test
    fun `onAppResumed while Disconnected is a no-op`() = runTest {
        val repo = newRepo(this).also { underTest = it }

        // Default state is Idle; explicit disconnect drops to Disconnected.
        repo.disconnect()
        runCurrent()

        repo.onAppResumed()
        runCurrent()

        assertTrue(
            repo.reconnectJobForTest() == null,
            "onAppResumed must not resurrect a Disconnected repo"
        )
    }
}
