package ru.sodovaya.volty.data.ble

import app.cash.turbine.test
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Task 9: the single-consumer sample funnel now carries battery OR motion.
 * A [MotionSample] injected into the funnel must reach
 * [VehicleConnection.submitMotion], surface on the vehicle-level snapshot's
 * controller slot, and — through the orchestrator's `onVehicleData` hook —
 * be published on [KableBmsRepository.activeMotion].
 *
 * Runs entirely on fakes. Part A ships no controller protocol (a real
 * [ru.sodovaya.volty.data.bms.MotionSource]-backed session lands in Part B),
 * so the reachable seam here is the funnel channel itself: the vehicle carries
 * a stored controller so the orchestrator holds a matching slot, and the test
 * drives one [MotionSample] straight through the production channel + consumer
 * — the exact shape a controller session will produce once the fan-out lands.
 * The end-to-end "protocol emits motion" path is Task 12's demo-motion test.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryMotionTest {

    private class StubVehicleRepository : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
        // Explicit, because both of VehicleRepository's gauge-peak members are abstract:
        // no fake gets a silent default. Nothing in this file rides a learned dial range
        // (G §9.2), and an EMPTY map is the honest answer rather than a missing one --
        // absence in that map means "has learned nothing", which is exactly the case here.
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    /** Every test here owns its repository through [bleRepositoryTest] — see there for why that is not optional. */
    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = StubVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        body = body
    )

    /** A vehicle with one battery pack AND one motor controller (global index 0). */
    private fun motionVehicle(): Vehicle = Vehicle(
        id = "v-motion",
        name = "Wheel",
        iconKey = "unicycle",
        packs = listOf(Pack(index = 0, label = "Battery", bmsType = BmsType.JK_BMS, bmsAddress = ADDR)),
        controllers = listOf(Controller(index = 0, label = "ESC", controllerType = ControllerType.VESC, address = CTRL_ADDR)),
        topology = PackTopology.PARALLEL,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    private fun motionSample() = ControllerData(
        speedKmh = 25f,
        speedSource = SpeedSource.REPORTED,
        isConnected = true
    )

    @Test
    fun `motion sample through the funnel surfaces on activeMotion`() = repoTest { repo ->
        val v = motionVehicle()
        // Installs the exact production wiring: one orchestrator (now sized
        // with the vehicle's controllers), one channel, one consumer.
        repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)
        val channel = assertNotNull(repo.sampleFunnelChannelForTest())

        assertEquals(SpeedSource.NONE, repo.activeMotion.value.speedSource, "initial motion has no speed source")

        // The exact shape a controller session will produce: the local index
        // already translated to the vehicle-global controller index.
        channel.trySend(MotionSample(globalControllerIndex = 0, data = motionSample()))
        advanceUntilIdle()

        // Routed through submitMotion, then republished via onVehicleData.
        assertEquals(SpeedSource.REPORTED, repo.activeMotion.value.speedSource)
        assertEquals(25f, repo.activeMotion.value.speedKmh, 0.001f)

        // The same submit surfaced the controller on the vehicle-level snapshot.
        val vd = repo.activeVehicleData.value
        assertEquals(1, vd.controllers.size)
        assertTrue(vd.controllers[0].isOnline)
        assertEquals(SpeedSource.REPORTED, vd.motion.speedSource)

        // Battery path untouched: no battery sample was fed.
        assertEquals(0f, repo.activeData.value.current, 0.001f)
    }

    @Test
    fun `activeMotion emits the reported speed via Turbine`() = repoTest { repo ->
        val v = motionVehicle()
        repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)
        val channel = assertNotNull(repo.sampleFunnelChannelForTest())
        val scheduler = testScheduler

        repo.activeMotion.test {
            assertEquals(SpeedSource.NONE, awaitItem().speedSource)
            channel.trySend(MotionSample(globalControllerIndex = 0, data = motionSample()))
            scheduler.advanceUntilIdle()
            assertEquals(SpeedSource.REPORTED, awaitItem().speedSource)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val ADDR = "AA:BB:CC:DD:EE:01"
        const val CTRL_ADDR = "AA:BB:CC:DD:EE:0C"
    }
}
