package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.AntBmsProtocol
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.MotionSource
import ru.sodovaya.volty.data.bms.VescProtocol
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.bmsAddress
import ru.sodovaya.volty.domain.model.bmsType
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Part B1 Task 4 — the crux: a vehicle's CONTROLLERS are planned into BLE
 * links, a controller link builds a [VescProtocol], and Part A's dormant
 * motion hop (`ConnectionSession.onMotionSample` → funnel → `submitMotion` →
 * `activeMotion`) finally carries a real decode.
 *
 * Everything runs on fakes, like the sibling multi-link / motion tests: the
 * production wiring is installed through [KableBmsRepository.installLinksForTest]
 * and the session's own body — `routeControllerSamples` + the per-link motion
 * funnel — is driven by hand, because [ConnectionSession] needs a real Kable
 * peripheral that commonTest cannot build.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryVescTest {

    private class StubVehicleRepository : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    private var underTest: KableBmsRepository? = null

    @AfterTest
    fun tearDown() {
        underTest?.close()
        underTest = null
    }

    private fun newRepo(testScope: TestScope): KableBmsRepository = KableBmsRepository.forTesting(
        vehicleRepository = StubVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        coroutineContext = StandardTestDispatcher(testScope.testScheduler),
    )

    /**
     * A lone VESC: ZERO stored packs (legal since Part A) and one controller
     * that backs a derived battery. `Vehicle.bmsType` / `Vehicle.bmsAddress`
     * are `packs.first()` shims and THROW on this vehicle — that is the trap
     * this task had to route around.
     */
    private fun vescOnlyVehicle(providesDerivedBattery: Boolean = true): Vehicle = Vehicle(
        id = "v-vesc",
        name = "Scooter",
        iconKey = "scooter",
        packs = emptyList(),
        controllers = listOf(
            Controller(
                index = 0,
                label = "ESC",
                controllerType = ControllerType.VESC,
                address = CTRL_ADDR,
                motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254),
                providesDerivedBattery = providesDerivedBattery
            )
        ),
        topology = PackTopology.PARALLEL,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    /** The same COMM_GET_VALUES_SETUP fixture VescProtocolTest decodes: ~47 km/h. */
    private fun setupFrame(): ByteArray {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) {
            o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
            o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte()
        }
        o += 47; i16(520); i16(680); i32(-8250); i32(5240); i16(760); i32(12000); i32(13056)
        i16(782); i16(840); i32(154000); i32(21000); i32(9800000); i32(1200000)
        i32(12400000); i32(1284600000); i32(0); o += 0; o += 11
        return VescPacket.frame(o.toByteArray())
    }

    // ----- 1. A controller-only vehicle must connect -----

    @Test
    fun `controller-only vehicle plans a controller link and does not touch packs first`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vescOnlyVehicle()

        // The trap, pinned: the primary-pack shims are unusable on this vehicle.
        assertFails("bmsAddress must throw on a zero-pack vehicle") { v.bmsAddress }
        assertFails("bmsType must throw on a zero-pack vehicle") { v.bmsType }
        assertEquals(CTRL_ADDR, v.primaryAddress, "primaryAddress is the safe route")

        // connect() must plan the vehicle THROUGH primaryAddress. No BLE stack
        // exists here, so the link cannot come up — but planning happens before
        // any radio work, and a route through packs.first() would have blown up
        // long before the link list was installed.
        val result = repo.connect(v)
        advanceUntilIdle()
        assertTrue(result.isFailure, "no BLE in a unit test — the link cannot come up")

        assertEquals(1, repo.linkCountForTest(), "the lone controller raises exactly one link")
        val spec = repo.linkSpecsForTest().single()
        assertEquals(CTRL_ADDR, spec.address)
        assertEquals(ProtocolKind.VESC, spec.protocolKind)
        assertEquals(listOf(OwnedSource(0)), spec.ownedControllers)
    }

    // ----- 2. A VESC link builds a VescProtocol that is a MotionSource -----

    @Test
    fun `a vesc link builds a VescProtocol that is a MotionSource`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vescOnlyVehicle(providesDerivedBattery = true)
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val spec = repo.linkSpecsForTest().single()
        val protocol = repo.createProtocolForTest(spec, v)

        assertIs<VescProtocol>(protocol)
        val motion = assertIs<MotionSource>(protocol)
        assertEquals(1, motion.controllerCount)
        assertEquals(1, protocol.packCount, "providesDerivedBattery ⇒ the controller backs one pack")
        // The controller's own motor config must reach the protocol: with no
        // wheel diameter the GET_VALUES fallback could not derive a speed.
        assertEquals(VescProtocol.NUS_SERVICE, protocol.uuids.serviceUuid)
    }

    @Test
    fun `a controller that backs no battery builds a VescProtocol with no pack`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vescOnlyVehicle(providesDerivedBattery = false)
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val protocol = repo.createProtocolForTest(repo.linkSpecsForTest().single(), v)
        assertIs<VescProtocol>(protocol)
        assertEquals(0, protocol.packCount)
        assertTrue(
            repo.linkSpecsForTest().single().ownedPacks.isEmpty(),
            "no derived pack ⇒ the link owns no pack slot"
        )
    }

    // ----- 3. Motion from a VESC link reaches activeMotion -----

    @Test
    fun `motion from a vesc link reaches activeMotion`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = vescOnlyVehicle()
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        assertEquals(SpeedSource.NONE, repo.activeMotion.value.speedSource, "nothing decoded yet")

        val spec = repo.linkSpecsForTest().single()
        val protocol = repo.createProtocolForTest(spec, v)
        val motionFunnel = repo.linkMotionFunnelsForTest().single()

        // Exactly what ConnectionSession's observe loop does: feed the raw
        // notification, then route the protocol's controllers through the gate
        // into this link's motion funnel.
        protocol.onNotification(setupFrame())
        val alive = routeControllerSamples(
            protocol = protocol as MotionSource,
            gate = MotionSampleGate(protocol.controllerCount)
        ) { controllerIndex, motion -> motionFunnel(controllerIndex, motion) }
        assertTrue(alive, "the decoded frame must count as motion liveness")
        advanceUntilIdle()

        assertEquals(SpeedSource.REPORTED, repo.activeMotion.value.speedSource)
        assertTrue(
            abs(repo.activeMotion.value.speedKmh - 47.0f) < 0.05f,
            "expected ~47 km/h, was ${repo.activeMotion.value.speedKmh}"
        )

        // The same submit surfaced the controller on the vehicle-level snapshot.
        val vd = repo.activeVehicleData.value
        assertEquals(1, vd.controllers.size)
        assertTrue(vd.controllers[0].isOnline)
        assertEquals(SpeedSource.REPORTED, vd.motion.speedSource)
    }

    // ----- 4. A battery-only vehicle plans exactly as before -----

    @Test
    fun `a battery-only vehicle plans exactly as before`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = singlePackVehicle(
            id = "v-ant", name = "Rig", iconKey = "battery",
            bmsType = BmsType.ANT_BMS, bmsAddress = ADDR,
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0L)
        )
        repo.installLinksForTest(v, v.bmsAddress, v.bmsType)

        val spec = repo.linkSpecsForTest().single()
        assertEquals(
            LinkSpec(
                address = ADDR,
                protocolKind = ProtocolKind.ANT,
                ownedPacks = listOf(OwnedSource(0)),
                ownedControllers = emptyList()
            ),
            spec
        )
        assertIs<AntBmsProtocol>(repo.createProtocolForTest(spec, v))
    }

    @Test
    fun `a Begode still owns both branches through one address`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = singlePackVehicle(
            id = "v-begode", name = "Wheel", iconKey = "unicycle",
            bmsType = BmsType.BEGODE, bmsAddress = ADDR,
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0L)
        )
        repo.installLinksForTest(v, v.bmsAddress, v.bmsType)

        val spec = repo.linkSpecsForTest().single()
        assertEquals(ProtocolKind.BEGODE, spec.protocolKind)
        assertEquals(listOf(OwnedSource(0), OwnedSource(1)), spec.ownedPacks)
        assertIs<BegodeProtocol>(repo.createProtocolForTest(spec, v))
    }

    @Test
    fun `a BMS plus a VESC at two addresses raise one link each`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = Vehicle(
            id = "v-mixed",
            name = "Mixed",
            iconKey = "scooter",
            packs = listOf(Pack(index = 0, label = "Main", bmsType = BmsType.JK_BMS, bmsAddress = ADDR)),
            controllers = listOf(
                Controller(
                    index = 0, label = "ESC", controllerType = ControllerType.VESC,
                    address = CTRL_ADDR, providesDerivedBattery = false
                )
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Instant.fromEpochSeconds(0L)
        )
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val specs = repo.linkSpecsForTest()
        assertEquals(2, specs.size)
        val battery = specs.single { it.address == ADDR }
        assertEquals(ProtocolKind.JK, battery.protocolKind)
        assertEquals(listOf(OwnedSource(0)), battery.ownedPacks)
        assertTrue(battery.ownedControllers.isEmpty())

        val controller = specs.single { it.address == CTRL_ADDR }
        assertEquals(ProtocolKind.VESC, controller.protocolKind)
        assertEquals(listOf(OwnedSource(0)), controller.ownedControllers)
        assertTrue(controller.ownedPacks.isEmpty(), "no derived battery — no pack slot")
    }

    private companion object {
        const val ADDR = "AA:BB:CC:DD:EE:01"
        const val CTRL_ADDR = "AA:BB:CC:DD:EE:0C"
    }
}
