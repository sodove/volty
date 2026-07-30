package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.demo.DemoBmsSimulator
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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
        // Explicit, because VehicleRepository.updateGaugePeaks is abstract: no fake gets a
        // silent default. Nothing in this file rides a learned dial range (G §9.2).
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    private val starts = mutableListOf<Unit>()
    private val stops = mutableListOf<Unit>()
    private val vehicleRepo = StubVehicleRepository()

    /** Every test here owns its repository through [bleRepositoryTest] — see there for why that is not optional. */
    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = vehicleRepo,
        serviceStart = { starts += Unit },
        serviceStop = { stops += Unit },
        body = body
    )

    @Test
    fun `connectDemo sets demo vehicle connected and starts service`() = repoTest { repo ->

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
    fun `disconnect tears down the demo session`() = repoTest { repo ->

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

    /**
     * Review regression (Task 14, round 1 + round 2): [PickerComponent]'s
     * "+ Add battery" is reachable from a live demo session and connects a
     * real vehicle WITHOUT calling [KableBmsRepository.disconnect] first
     * (unlike `DashboardComponent.onSwitchVehicle`). Before the round-1 fix,
     * `doConnect`'s address-transition reset cleared
     * [KableBmsRepository.activeData] / [KableBmsRepository.activeMotion] but
     * left [KableBmsRepository.activeVehicleData] holding the demo's
     * `packs`/`aggregate` — exactly what `RideDashboardComponent` reads for
     * the BATTERY tile — so a plausible but WRONG SoC/voltage from the demo
     * would show on the real vehicle until its first sample arrived.
     *
     * ROUND 2 (re-review): the first version of this test only asserted after
     * `advanceUntilIdle()`, which is vacuous — a fully-failed `connect()` ALSO
     * zeroes `activeVehicleData` via the pre-existing, unrelated
     * `clearOrchestratorAfterFailure` -> `clearOrchestratorLocked` path
     * (`KableBmsRepository.kt:1095-1105`), so the assertion passed whether or
     * not the round-1 fix line was present.
     *
     * A `launch { connect(...) }` + `runCurrent()` seam (observing state while
     * `resolveAdvertisement`'s 5s timeout is supposedly still pending) was
     * tried next and does NOT work in this harness: in a plain JVM unit test
     * (no Robolectric shadow), touching real Android BLE classes throws
     * synchronously (`RuntimeException: Method build in
     * android.bluetooth.le.ScanSettings$Builder not mocked`) instead of
     * actually suspending on the virtual timer — confirmed by printing
     * `testScheduler.currentTime` immediately after a single `runCurrent()`
     * call: it had NOT advanced past the earlier demo-tick's value, meaning
     * the whole failed attempt — including `clearOrchestratorAfterFailure` —
     * ran inside that one `runCurrent()` call, so there is no observable
     * mid-flight window there either.
     *
     * The scenario actually used instead: give the real vehicle's pack and
     * controller the SAME address but conflicting [ProtocolKind]s. `planLinks`
     * (via `effectiveLinkSpecs`) throws `IllegalArgumentException` for this —
     * synchronously, and CRUCIALLY *before* `doConnect` calls
     * `buildOrchestrator`, so `installedOrchestrator` is never reassigned from
     * its initial `null`. `clearOrchestratorAfterFailure(null)` is then a
     * guaranteed no-op (`if (installed == null) return`), which removes the
     * confound entirely: the address-transition reset (the round-1 fix) is
     * the ONLY code path that can still zero `activeVehicleData` on this
     * particular failure. No timing games needed — `repo.connect(...)` can be
     * awaited directly.
     */
    @Test
    fun `connecting a real vehicle after demo does not leak the demo's battery snapshot`() = repoTest { repo ->

        // Demo populates activeVehicleData.packs/aggregate (Task 14's own fix).
        repo.connectDemo()
        advanceTimeBy(DemoBmsSimulator.TICK_INTERVAL_MS + 50L)
        runCurrent()
        val demoAggregate = repo.activeVehicleData.value.aggregate
        assertTrue(demoAggregate.isConnected, "demo should have populated the aggregate before the real connect")
        assertTrue(demoAggregate.soc > 0f, "demo SoC should be nonzero so a leak would be visible")

        // Mirrors PickerComponent.onConnectKnown/onConnectWithType: connect a
        // REAL vehicle at a DIFFERENT address with no preceding disconnect().
        // Pack and controller deliberately share ONE address with conflicting
        // ProtocolKinds (JK vs. VESC) so planLinks throws BEFORE
        // buildOrchestrator runs — see the doc comment above for why this is
        // what makes the failure path a no-op instead of a confound.
        val conflictingAddress = "AA:BB:CC:DD:EE:FF"
        val conflicting = Vehicle(
            id = "v-conflict",
            name = "Conflict",
            iconKey = "generic",
            packs = listOf(
                Pack(index = 0, label = "Battery", bmsType = BmsType.JK_BMS, bmsAddress = conflictingAddress)
            ),
            controllers = listOf(
                Controller(index = 0, label = "ESC", controllerType = ControllerType.VESC, address = conflictingAddress)
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Instant.fromEpochSeconds(0L)
        )

        val result = repo.connect(conflicting)
        runCurrent()

        assertTrue(result.isFailure, "conflicting protocol kinds at one address must fail planLinks")
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            message.contains("conflicting protocol kinds"),
            "expected planLinks' own error, got: $message"
        )

        val vd = repo.activeVehicleData.value
        assertTrue(vd.packs.isEmpty(), "stale demo packs must not leak into the new (failed) connection's snapshot")
        assertFalse(vd.aggregate.isConnected, "stale demo aggregate must not leak into the new connection's Battery tile")
        assertEquals(0f, vd.aggregate.soc, 0.001f, "stale demo SoC must not leak")
        assertFalse(vd.isPartial)
    }
}
