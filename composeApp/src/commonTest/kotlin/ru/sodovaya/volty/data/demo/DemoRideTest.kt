package ru.sodovaya.volty.data.demo

import ru.sodovaya.volty.data.ble.KableBmsRepository
import ru.sodovaya.volty.data.ble.bleRepositoryTest
import ru.sodovaya.volty.data.ble.planLinks
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.hasControllers
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * End-to-end proof (Task 14) that "Try demo" reaches the Ride dashboard with
 * live synthetic motion and NO hardware:
 *  - the demo vehicle carries a controller ([Vehicle.hasControllers]), which is
 *    what makes `homeConfigFor` (Task 12) route it to Ride instead of the
 *    battery dashboard;
 *  - the demo's pack and controller resolve to two DISTINCT addresses, so
 *    [planLinks] accepts them without throwing — this is the known-issue fix:
 *    the demo controller used to share [ru.sodovaya.volty.domain.model.DEMO_VEHICLE_ID]
 *    with the demo pack, which [planLinks] rejects because one address must
 *    resolve to exactly one `ProtocolKind` (JK for the pack vs. VESC for the
 *    controller);
 *  - `connectDemo()` actually publishes non-zero, `SpeedSource.REPORTED` motion
 *    on `activeMotion` — the full Ride data path, driven by the same synthetic
 *    ride curve [DemoBmsSimulator] feeds the battery stream from.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class DemoRideTest {

    private class StubVehicleRepository : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    @Test
    fun the_demo_vehicle_has_a_controller_so_ride_is_its_home() = runTest {
        val demo = KableBmsRepository.DEMO_VEHICLE
        assertTrue(
            demo.hasControllers,
            "demo vehicle must carry a controller — it is what makes Ride reachable without hardware"
        )

        // The known-issue fix: pack and controller must resolve to DISTINCT
        // addresses so planLinks (one address -> one ProtocolKind) does not
        // throw if this vehicle ever reached link planning.
        val links = planLinks(demo.packs, demo.controllers)
        assertEquals(2, links.size, "pack and controller must plan to two distinct links, got $links")
        assertTrue(links.any { it.ownedPacks.isNotEmpty() && it.ownedControllers.isEmpty() }, "one link must own only the demo pack")
        assertTrue(links.any { it.ownedControllers.isNotEmpty() && it.ownedPacks.isEmpty() }, "one link must own only the demo controller")
    }

    @Test
    fun connecting_the_demo_produces_motion_on_activeMotion() = bleRepositoryTest(vehicleRepository = StubVehicleRepository()) { repo ->
        
        val result = repo.connectDemo()
        runCurrent()
        assertTrue(result.isSuccess)

        // Advance well into the simulator's "riding" phase (past the initial
        // accelerate-from-zero ramp) so speed is guaranteed non-zero — see
        // DemoBmsSimulatorTest, which uses the same ~50s landmark.
        advanceTimeBy(50_000L)
        runCurrent()

        val motion = repo.activeMotion.value
        assertEquals(
            SpeedSource.REPORTED,
            motion.speedSource,
            "demo motion must report SpeedSource.REPORTED — the full Ride data path with no BLE"
        )
        assertTrue(motion.speedKmh > 0f, "demo motion must have non-zero speed at this tick, was ${motion.speedKmh}")

        repo.disconnect()
        runCurrent()
    }

    @Test
    fun connecting_the_demo_also_produces_battery_data_on_activeVehicleData() = bleRepositoryTest(vehicleRepository = StubVehicleRepository()) { repo ->
        // Regression test: connectDemo() used to publish motion into
        // activeVehicleData (controllers/motion/motionPartial) but leave
        // packs/aggregate at VehicleData()'s all-zero default — so the Ride
        // dashboard's fixed BATTERY tile (which reads activeVehicleData.aggregate,
        // not activeData) was stuck at "0% / 0.0V" for the whole demo session
        // even though the simulator's battery sample was flowing fine into
        // activeData (the Battery tab's own flow). Caught by actually running
        // the app and watching the Ride dashboard's 2x2 cluster.

        repo.connectDemo()
        runCurrent()
        advanceTimeBy(DemoBmsSimulator.TICK_INTERVAL_MS + 50L)
        runCurrent()

        val vd = repo.activeVehicleData.value
        assertEquals(1, vd.packs.size, "demo vehicle data must carry the one demo pack")
        assertTrue(vd.aggregate.isConnected, "demo battery aggregate must report connected")
        assertTrue(vd.aggregate.socKnown, "demo battery aggregate must have a known SoC")
        assertTrue(vd.aggregate.soc > 0f, "demo battery aggregate SoC must be non-zero, was ${vd.aggregate.soc}")
        assertTrue(vd.aggregate.voltage > 0f, "demo battery aggregate voltage must be non-zero, was ${vd.aggregate.voltage}")

        repo.disconnect()
        runCurrent()
    }
}
