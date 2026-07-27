package ru.sodovaya.volty.data.ble

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.data.bms.BegodeDumpFixture
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.controllerVehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.withCellCount
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.presentation.picker.DefaultPickerComponent
import ru.sodovaya.volty.presentation.picker.SourceChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Part D final fix, item 1 — **the wheel a Controller pick actually builds.**
 *
 * Task 4 landed the picker branch and the cell count together *because the two
 * are one correctness requirement*: without the count `BegodeProtocol` cannot
 * turn the live frame's 67.2 V-referenced reading into volts, `powerW` is built
 * from that voltage, and the Ride dashboard renders the pair as a confident
 * **"0.0 kW"** and "0.0 Wh/km" rather than as a blank. It landed for the shape
 * `D §4` describes — one link owning `controllers = [0]` and `packs = [0, 1]` —
 * and MISSED the shape the picker was creating: `controllerVehicle` produces a
 * PACK-LESS vehicle, the link's only pack slots are the derived ones
 * `planLinkPacks` synthesises (never persisted, no cell count), and
 * `createProtocol`'s lookup runs against `vehicle.packs`, which is empty. Every
 * repair route was a dead end too — `Vehicle.withCellCount` is
 * `packs.mapIndexed`, a silent no-op on an empty list; the pack auto-fill only
 * appends behind an address the profile already names a pack on; and the edit
 * screen deliberately keeps a controller-only vehicle pack-less.
 *
 * **The fix taken, of the two on the table:** the picker's BEGODE branch now
 * builds the `D §4` wheel (`pickedControllerVehicle` → `wheelVehicle`), rather
 * than teaching `createProtocol` to fall back to the derived slots and asking
 * the edit screen for a number it has never edited. Three reasons, in order of
 * weight:
 *
 *  1. **It is the shape the rest of the pipeline was built for.** `planLinks`,
 *     `expandedTo`, the pack auto-fill and `withScaledBegodeLiveVoltage` all
 *     already handle a Begode pack at the wheel's address; the pack-less
 *     variant is the one none of them can serve.
 *  2. **The count then arrives by a route that already exists and already
 *     works** — the same `maybePersistCellCount` auto-fill a Begode configured
 *     as a BATTERY has always used. No new machinery, and no new screen.
 *  3. **It removes the unexplained Begode/VESC divergence at its root.** A VESC
 *     controller *derives* a battery from its own telemetry, which is why
 *     `controllerVehicle` sets `providesDerivedBattery = true`. A wheel does
 *     not derive anything: it decodes two real branches off the same frames. So
 *     the BEGODE arm of `controllerMotionProtocol` ignoring `deriveBattery` is
 *     now correct rather than an omission, and it says so.
 *
 * **The one seam.** `bmsRepository.connect` is faked, because no test in this
 * repo can dial a BLE peripheral (`ConnectionSession` needs a real Kable
 * peripheral — see `installLinksForTest`'s KDoc). Everything either side of it
 * is production: the real `DefaultPickerComponent` writing into a real store,
 * the real `KableBmsRepository` link plan, protocol factory, funnel, router,
 * gates and cell-count auto-fill, and the real ET Max capture at its real
 * MTU-23 notification boundaries.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryBegodeWheelPickTest {

    /** A real store, so "what the picker persisted" is a fact and not a call log. */
    private class Store : VehicleRepository {
        private val byId = mutableMapOf<String, Vehicle>()
        val upserts = mutableListOf<Vehicle>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = byId[id]
        override suspend fun upsert(vehicle: Vehicle) { upserts += vehicle; byId[vehicle.id] = vehicle }
        override suspend fun delete(id: String) { byId.remove(id) }
        override suspend fun touch(id: String) {}
        val stored: List<Vehicle> get() = byId.values.toList()
    }

    /** The picker's repository. Only [connect] matters here, and only that it succeeded. */
    private class FakeBmsRepo(private val scan: List<DiscoveredDevice>) : BmsRepository {
        val vehicleConnects = mutableListOf<Vehicle>()
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = scan.asFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> {
            vehicleConnects += vehicle
            return Result.success(Unit)
        }
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> = Result.success(Unit)
        override suspend fun connectDemo(profile: DemoProfile): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun disconnectLink(address: String) {}
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}
    }

    private val store = Store()

    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = store,
        serviceStart = {},
        serviceStop = {},
        body = body
    )

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The session's observe loop in miniature: the production funnels, routers
     * and gates driven against the protocol the production factory built for
     * this link. Same shape as `KableBmsRepositoryBegodeControllerTest.Wire`.
     */
    private class Wire(repo: KableBmsRepository, vehicle: Vehicle) {
        val protocol: BegodeProtocol
        private val packFunnel: (Int, BmsData, List<SectionState>) -> Unit
        private val motionFunnel: (Int, ControllerData) -> Unit
        private val packGate: PackSampleGate
        private val motionGate: MotionSampleGate

        init {
            packFunnel = repo.installLinksForTest(vehicle, vehicle.primaryAddress, BmsType.BEGODE).single()
            motionFunnel = repo.linkMotionFunnelsForTest().single()
            protocol = assertIs(repo.createProtocolForTest(repo.linkSpecsForTest().single(), vehicle))
            packGate = PackSampleGate(protocol.packCount)
            motionGate = MotionSampleGate(protocol.controllerCount)
        }

        fun notify(bytes: ByteArray) {
            protocol.onNotification(bytes)
            routePackSamples(protocol, packGate) { i, bms, sections -> packFunnel(i, bms, sections) }
            routeControllerSamples(protocol, motionGate) { i, m -> motionFunnel(i, m) }
        }

        /**
         * [afterEach] is pumped between notifications, and the auto-fill walk
         * below needs it: `_activeData` is a StateFlow, so its collector — the
         * cell-count auto-fill — sees only the LATEST value if the whole capture
         * is pushed without letting the scheduler run. That is a property of the
         * test harness, not of the production path, where samples arrive on
         * separate BLE notifications seconds apart.
         */
        fun replayTheCapture(afterEach: () -> Unit = {}) =
            BegodeDumpFixture.chunks().forEach { notify(it); afterEach() }
    }

    private fun TestScope.pickTheWheel(): Vehicle {
        val device = DiscoveredDevice(
            address = WHEEL,
            name = "GotwayET",
            rssi = -50,
            bmsType = null,
            controllerType = ControllerType.BEGODE
        )
        val picker = DefaultPickerComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            mode = "cold",
            bmsRepository = FakeBmsRepo(listOf(device)),
            vehicleRepository = store,
            onConnectedKnown = {},
            onConnectedForEdit = {},
            onConnectedGuestNoSave = {},
            onAddNewBatteryRequested = {},
            onDemoConnected = {},
            onCancelled = {}
        )
        advanceUntilIdle()
        picker.onConnectWithType(device, SourceChoice.Controller(ControllerType.BEGODE))
        advanceUntilIdle()
        return store.stored.single()
    }

    // ----- 1. The shape the pick creates -----

    @Test
    fun `a Begode Controller pick builds the one-link wheel of D 4, not a pack-less controller`() = repoTest { repo ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val created = pickTheWheel()

        // A wheel is a controller AND its own battery over ONE address.
        val pack = created.packs.single()
        assertEquals(BmsType.BEGODE, pack.bmsType)
        assertEquals(WHEEL, pack.bmsAddress)
        assertNull(pack.cellCount, "nothing knows it yet — this is what the auto-fill is for")
        val controller = created.controllers.single()
        assertEquals(ControllerType.BEGODE, controller.controllerType)
        assertEquals(WHEEL, controller.address)
        assertFalse(
            controller.providesDerivedBattery,
            "a wheel DECODES its packs; a derived battery would be a phantom beside the real branches"
        )

        // And it plans as `D §4` archetype 3, through the production planner.
        repo.installLinksForTest(created, created.primaryAddress, BmsType.BEGODE)
        val spec = repo.linkSpecsForTest().single()
        assertEquals(WHEEL, spec.address)
        assertEquals(ProtocolKind.BEGODE, spec.protocolKind)
        assertEquals(listOf(OwnedSource(0)), spec.ownedControllers)
        assertEquals(
            listOf(OwnedSource(0), OwnedSource(1)), spec.ownedPacks,
            "one stored branch plus the slot expandedTo synthesises for the second"
        )
    }

    // ----- 2. The walk: picker -> vehicle -> auto-fill -> protocol -> a real voltage -----

    @Test
    fun `the picked wheel's cell count reaches its decoder, and POWER stops being a confident zero`() = repoTest { repo ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val created = pickTheWheel()

        // --- first connect: nothing knows the cell count, so nothing is claimed ---
        repo.primeConnectedForTest(
            created, created.primaryAddress, BmsType.BEGODE, Clock.System.now().toEpochMilliseconds()
        )
        runCurrent()
        Wire(repo, created).replayTheCapture(afterEach = { runCurrent() })
        advanceUntilIdle()

        assertEquals(
            0f, repo.activeMotion.value.inputVoltageV, 0f,
            "precondition: with no cell count the wheel publishes NO voltage, not the raw 58.9 V"
        )
        assertEquals(0f, repo.activeMotion.value.powerW, 0f)
        assertTrue(repo.activeMotion.value.isConnected, "…everything else the wheel reports is there")

        // --- the profile learns the count, through the production auto-fill ---
        // This is the step the pack-less shape could not take: `withCellCount`
        // is `packs.mapIndexed`, so on a zero-pack vehicle the upsert wrote the
        // vehicle back UNCHANGED and the count was lost forever.
        val autofilled = assertNotNull(
            store.upserts.lastOrNull { it.id == created.id && it.packs.firstOrNull()?.cellCount != null },
            "the cell-count auto-fill must have written the wheel's 40s back into the profile"
        )
        assertEquals(40, autofilled.packs.single().cellCount)

        // --- next connect: the decoder is told, and the dashboard's numbers are real ---
        Wire(repo, autofilled).replayTheCapture()
        advanceUntilIdle()

        val motion = repo.activeMotion.value
        // 58.88 V on Begode's 67.2 V reference x (40 x 4.2 / 67.2) = 147.20 V.
        assertEquals(147.20f, motion.inputVoltageV, 0.01f, "the rail voltage a rider's wheel actually runs at")
        assertEquals(
            147.20f * 0.67f, motion.powerW, 0.05f,
            "power is voltage x battery current — the '0.0 kW' this item is about"
        )
        assertTrue(motion.powerW > 0f, "and it is a number, not a confident zero")
        // The battery half of the same link gets the count too, by the same route.
        assertTrue(repo.activeData.value.voltage in 148.1f..148.7f)
    }

    @Test
    fun `the pack-less controller vehicle is why this had no recovery path at all`() = repoTest { repo ->
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // The CONTROL for the walk above, and the defect in one assertion: built
        // the old way, a Begode vehicle has nowhere to put a cell count. This is
        // not asserting a behaviour we want — it is pinning why the picker's
        // shape had to change rather than the lookup.
        val packLess = controllerVehicle(
            id = "v-old-shape",
            name = "ET Max",
            iconKey = "unicycle",
            controllerType = ControllerType.BEGODE,
            address = WHEEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Instant.fromEpochSeconds(0L)
        )
        assertTrue(packLess.packs.isEmpty())
        assertTrue(
            packLess.withCellCount(40).packs.isEmpty(),
            "withCellCount is packs.mapIndexed — a silent no-op with no list to map"
        )

        // …and the decoder therefore never gets one, however many frames arrive.
        Wire(repo, packLess).replayTheCapture()
        advanceUntilIdle()
        assertEquals(0f, repo.activeMotion.value.inputVoltageV, 0f)
        assertEquals(0f, repo.activeMotion.value.powerW, 0f)
    }

    private companion object {
        const val WHEEL = "AA:BB:CC:DD:EE:FF"
    }
}
