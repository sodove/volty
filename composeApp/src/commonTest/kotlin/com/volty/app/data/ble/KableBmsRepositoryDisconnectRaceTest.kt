package com.volty.app.data.ble

import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.VehicleRepository
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
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Regression test for the disconnect-vs-reconnect race documented in
 * [KableBmsRepository]. Prior to the [ConnectionSession] / `userInitiatedDisconnect`
 * refactor, a watchdog firing concurrently with a user-initiated [KableBmsRepository.disconnect]
 * could "resurrect" the connection by re-entering the reconnect loop.
 *
 * We exercise the race directly through the test-only [KableBmsRepository.simulateConnectionDropForTest]
 * seam — no real BLE peripheral is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryDisconnectRaceTest {

    private class StubVehicleRepository : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    private val starts = mutableListOf<Unit>()
    private val stops = mutableListOf<Unit>()

    private fun newRepo(testScope: TestScope): KableBmsRepository = KableBmsRepository.forTesting(
        vehicleRepository = StubVehicleRepository(),
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

    private fun vehicle() = Vehicle(
        id = "v1",
        name = "Test",
        iconKey = "generic",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:BB:CC:DD:EE:FF",
        chemistry = com.volty.app.domain.model.Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    @Test
    fun `disconnect during reconnect loop does not resurrect the connection`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()

        // Simulate the situation right after a link drop: the repo enters the
        // reconnect loop. doConnect inside it will fail fast (no real Kable
        // peripheral available in unit test), but the loop keeps retrying
        // forever with backoff delays — so we MUST NOT call advanceUntilIdle()
        // before the disconnect (it would loop indefinitely under virtual time).
        repo.simulateConnectionDropForTest(
            vehicle = v,
            address = v.bmsAddress,
            type = v.bmsType,
            reason = "Link dropped"
        )

        // Pump just enough to enter the loop body, without crossing the
        // 3_000ms backoff that would cycle into a fresh attempt.
        runCurrent()
        val reconnectJob = repo.reconnectJobForTest()
        assertTrue(reconnectJob != null, "reconnect job should have started")

        // User-initiated disconnect during the reconnect loop. This is THE race:
        // before the refactor, the loop could re-enter doConnect after the user
        // had already cleared state.
        repo.disconnect()
        // Drain just enough to let the cancellation propagate. We intentionally
        // do NOT call advanceUntilIdle() since the loop is supposed to stop —
        // if it didn't, we'd hang here.
        runCurrent()
        // Push past the backoff to prove the loop doesn't restart.
        advanceTimeBy(BleConfig.reconnectDelayAfter10Ms + 1_000L)
        runCurrent()

        // Behavioural contract:
        //  1. The flag is set so any in-flight reconnect attempt will bail.
        assertTrue(
            repo.isUserInitiatedDisconnectForTest(),
            "userInitiatedDisconnect must be set after disconnect()"
        )

        //  2. The reconnect job is cancelled / completed — no longer active.
        assertTrue(
            reconnectJob.isActive.not(),
            "reconnect job must not be active after disconnect()"
        )

        //  3. The repo settles on Disconnected, not Reconnecting / Connected.
        assertEquals(ConnectionState.Disconnected, repo.connectionState.value)

        //  4. Active vehicle is cleared (so any survivor loop iteration would
        //     also see "no vehicle, stop").
        assertEquals(null, repo.activeVehicle.value)

        //  5. The service controller's stop() was invoked exactly once.
        assertEquals(1, stops.size)
    }

    @Test
    fun `simulateConnectionDrop starts a reconnect job`() = runTest {
        // Sanity check that the simulation hook actually drives the loop —
        // otherwise the race test above could pass trivially.
        val repo = newRepo(this).also { underTest = it }
        val v = vehicle()

        repo.simulateConnectionDropForTest(
            vehicle = v,
            address = v.bmsAddress,
            type = v.bmsType,
            reason = "Link dropped"
        )
        runCurrent()

        val reconnectJob = repo.reconnectJobForTest()
        assertTrue(reconnectJob != null, "reconnect job must start after a drop")
        assertTrue(reconnectJob.isActive, "reconnect job must be active after a drop")

        // Clean up so the test runner doesn't hang on the still-active loop.
        repo.disconnect()
        runCurrent()
        assertTrue(reconnectJob.isActive.not(), "disconnect must terminate the reconnect job")
    }
}
