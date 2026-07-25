package ru.sodovaya.volty.presentation.picker

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class PickerComponentTest {

    private class FakeBmsRepo(private val scan: List<DiscoveredDevice>) : BmsRepository {
        val guestConnects = mutableListOf<Pair<String, BmsType>>()
        val vehicleConnects = mutableListOf<Vehicle>()
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = scan.asFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> { vehicleConnects += vehicle; return Result.success(Unit) }
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> { guestConnects += address to type; return Result.success(Unit) }
        override suspend fun connectDemo(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun disconnectLink(address: String) {}
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}
    }

    private class FakeVehicleRepo(private val saved: List<Vehicle>) : VehicleRepository {
        val upserts = mutableListOf<Vehicle>()
        val deletes = mutableListOf<String>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(saved)
        override suspend fun get(id: String): Vehicle? = saved.firstOrNull { it.id == id }
        override suspend fun upsert(vehicle: Vehicle) { upserts += vehicle }
        override suspend fun delete(id: String) { deletes += id }
        override suspend fun touch(id: String) {}
    }

    private fun vehicle(id: String, address: String) = singlePackVehicle(
        id = id, name = "Saved", iconKey = "generic",
        bmsType = BmsType.JK_BMS, bmsAddress = address,
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0)
    )

    /** Zero packs, one VESC — the shape G1 exists to make reachable. */
    private fun controllerVehicle(id: String, address: String) = Vehicle(
        id = id, name = "Scooter", iconKey = "scooter",
        packs = emptyList(),
        controllers = listOf(
            Controller(index = 0, label = "ESC", controllerType = ControllerType.VESC, address = address)
        ),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0)
    )

    /** A pack AND a controller: recognisable by either address. */
    private fun dualSourceVehicle(id: String, packAddress: String, ctrlAddress: String) = Vehicle(
        id = id, name = "Rig", iconKey = "generic",
        packs = listOf(Pack(index = 0, label = "P0", bmsType = BmsType.JK_BMS, bmsAddress = packAddress)),
        controllers = listOf(
            Controller(index = 0, label = "ESC", controllerType = ControllerType.VESC, address = ctrlAddress)
        ),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0)
    )

    private fun device(address: String, type: BmsType?, rssi: Int = -50, controllerType: ControllerType? = null) =
        DiscoveredDevice(address = address, name = "dev-$address", rssi = rssi, bmsType = type, controllerType = controllerType)

    private fun component(
        mode: String,
        scan: List<DiscoveredDevice>,
        saved: List<Vehicle> = emptyList(),
        bmsRepo: FakeBmsRepo = FakeBmsRepo(scan),
        vehicleRepo: FakeVehicleRepo = FakeVehicleRepo(saved),
    ): Pair<DefaultPickerComponent, FakeBmsRepo> {
        val ctx = DefaultComponentContext(LifecycleRegistry())
        val c = DefaultPickerComponent(
            componentContext = ctx,
            mode = mode,
            bmsRepository = bmsRepo,
            vehicleRepository = vehicleRepo,
            onConnectedKnown = {},
            onConnectedForEdit = {},
            onConnectedGuestNoSave = {},
            onAddNewBatteryRequested = {},
            onDemoConnected = {},
            onCancelled = {},
        )
        return c to bmsRepo
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scan results are classified into saved, detected and undetected`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = vehicle(id = "v1", address = "AA:SAVED")
        val scan = listOf(
            device("AA:SAVED", BmsType.JK_BMS),
            device("BB:DETECT", BmsType.JBD_BMS),
            device("CC:UNKNOWN", null),
        )
        val (c, _) = component(mode = "cold", scan = scan, saved = listOf(saved))
        advanceUntilIdle()

        val s = c.state.value
        assertEquals(listOf("v1"), s.myInRange.map { it.id })
        assertEquals(listOf("BB:DETECT"), s.otherNearby.map { it.address })
        assertEquals(listOf("CC:UNKNOWN"), s.otherDevices.map { it.address })
    }

    @Test
    fun `undetected devices are sorted by rssi descending`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scan = listOf(
            device("FAR", null, rssi = -90),
            device("NEAR", null, rssi = -40),
            device("MID", null, rssi = -65),
        )
        val (c, _) = component(mode = "guest", scan = scan)
        advanceUntilIdle()

        assertEquals(listOf("NEAR", "MID", "FAR"), c.state.value.otherDevices.map { it.address })
    }

    @Test
    fun `onConnectWithType uses the chosen type, overriding the guess (guest mode)`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val unknown = device("CC:UNKNOWN", null)
        val (c, repo) = component(mode = "guest", scan = listOf(unknown))
        advanceUntilIdle()

        c.onConnectWithType(unknown, SourceChoice.Battery(BmsType.JBD_BMS))
        advanceUntilIdle()

        assertEquals(listOf("CC:UNKNOWN" to BmsType.JBD_BMS), repo.guestConnects)
    }

    // ----- G1 Task 3: the type sheet learns controllers -----

    @Test
    fun `tapping a device detected as a controller preselects the matching Controller choice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val d = device("CTRL:VESC", type = null, controllerType = ControllerType.VESC)
        val (c, _) = component(mode = "guest", scan = listOf(d))
        advanceUntilIdle()

        c.onDeviceTapped(d)

        val opened = c.state.value.typePickerFor
        assertEquals(d.address, opened?.address)
        assertEquals(SourceChoice.Controller(ControllerType.VESC), opened?.let(::preselectedChoice))
    }

    @Test
    fun `tapping a device detected as a BMS preselects the matching Battery choice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val d = device("BATT:JK", type = BmsType.JK_BMS)
        val (c, _) = component(mode = "guest", scan = listOf(d))
        advanceUntilIdle()

        c.onDeviceTapped(d)

        val opened = c.state.value.typePickerFor
        assertEquals(SourceChoice.Battery(BmsType.JK_BMS), opened?.let(::preselectedChoice))
    }

    @Test
    fun `tapping an unrecognised device preselects nothing, leaving both sections unselected`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val d = device("UNKNOWN:01", type = null)
        val (c, _) = component(mode = "guest", scan = listOf(d))
        advanceUntilIdle()

        c.onDeviceTapped(d)

        val opened = c.state.value.typePickerFor
        assertEquals(d.address, opened?.address, "the sheet still opens for an unrecognised device")
        assertEquals(null, opened?.let(::preselectedChoice))
    }

    @Test
    fun `onConnectWithType with a Controller choice is an explicit inert no-op (Task 5 wires the connect path)`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val d = device("CTRL:VESC", type = null, controllerType = ControllerType.VESC)
        val vehicleRepo = FakeVehicleRepo(emptyList())
        val (c, bmsRepo) = component(mode = "add", scan = listOf(d), vehicleRepo = vehicleRepo)
        advanceUntilIdle()
        c.onDeviceTapped(d)

        c.onConnectWithType(d, SourceChoice.Controller(ControllerType.VESC))
        advanceUntilIdle()

        assertEquals(null, c.state.value.typePickerFor, "the sheet still closes")
        assertEquals(null, c.state.value.connecting, "but no connection attempt is made")
        assertTrue(vehicleRepo.upserts.isEmpty(), "no vehicle is created — that is Task 4/5's job")
        assertTrue(bmsRepo.vehicleConnects.isEmpty())
    }

    // ----- G1: the picker must see a controller vehicle, and must not die on one -----

    /**
     * `startScan()` used to index saved vehicles with
     * `saved.associateBy { it.bmsAddress }` — a `packs.first()` shim that threw
     * on a zero-pack vehicle, killing Picker init in all three modes. It now
     * indexes by every address, which is also the only way a controller
     * vehicle can be matched to the address it actually advertises.
     */
    @Test
    fun `a saved controller-only vehicle is matched by its controller advertisement`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = controllerVehicle(id = "v-vesc", address = "CTRL:01")
        val (c, _) = component(
            mode = "cold",
            scan = listOf(device("CTRL:01", null)),
            saved = listOf(saved)
        )

        c.state.test {
            advanceUntilIdle()
            val s = expectMostRecentItem()
            assertEquals(listOf("v-vesc"), s.myInRange.map { it.id }, "matched as MINE, not a stranger")
            assertTrue(s.otherDevices.isEmpty(), "and therefore not listed as an unknown device")
            assertTrue(s.otherNearby.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a saved vehicle with both sources is matched by its controller advertisement`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = dualSourceVehicle(id = "v-both", packAddress = "PACK:01", ctrlAddress = "CTRL:01")
        val (c, _) = component(
            mode = "cold",
            scan = listOf(device("CTRL:01", null)),
            saved = listOf(saved)
        )

        c.state.test {
            advanceUntilIdle()
            assertEquals(listOf("v-both"), expectMostRecentItem().myInRange.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a controller-only vehicle in the store does not break classification of others`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // Merely BUILDING the index over a zero-pack vehicle used to throw, so
        // no device was classified at all. The ordinary BMS vehicle alongside
        // it must still land in myInRange exactly as it always did.
        val (c, _) = component(
            mode = "cold",
            scan = listOf(device("AA:SAVED", BmsType.JK_BMS), device("CC:UNKNOWN", null)),
            saved = listOf(controllerVehicle("v-vesc", "CTRL:01"), vehicle("v1", "AA:SAVED"))
        )

        c.state.test {
            advanceUntilIdle()
            val s = expectMostRecentItem()
            assertEquals(listOf("v1"), s.myInRange.map { it.id })
            assertEquals(listOf("CC:UNKNOWN"), s.otherDevices.map { it.address })
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * `connecting` is compared against `v.primaryAddress` by PickerScreen's
     * row, so the two must agree — and for a controller vehicle the only
     * address that exists is the controller's.
     */
    @Test
    fun `onConnectKnown marks a controller-only vehicle as connecting by its controller address`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = controllerVehicle(id = "v-vesc", address = "CTRL:01")
        val (c, repo) = component(mode = "cold", scan = emptyList(), saved = listOf(saved))
        advanceUntilIdle()

        c.onConnectKnown(saved)
        // onConnectKnown's body runs in scope.launch on the (test) Main
        // dispatcher, so pump it before reading the state it publishes.
        runCurrent()
        assertEquals("CTRL:01", c.state.value.connecting)
        advanceUntilIdle()
        assertEquals(listOf("v-vesc"), repo.vehicleConnects.map { it.id })
    }

    @Test
    fun `onConnectKnown still marks a pack-only vehicle by its BMS address`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = vehicle(id = "v1", address = "AA:SAVED")
        val (c, _) = component(mode = "cold", scan = emptyList(), saved = listOf(saved))
        advanceUntilIdle()

        c.onConnectKnown(saved)
        runCurrent()
        assertEquals("AA:SAVED", c.state.value.connecting, "the BMS path is unchanged")
    }

    @Test
    fun `onDeviceTapped opens and onTypeSheetDismissed closes the type sheet`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val d = device("CC:UNKNOWN", null)
        val (c, _) = component(mode = "guest", scan = listOf(d))
        advanceUntilIdle()

        c.onDeviceTapped(d)
        assertEquals("CC:UNKNOWN", c.state.value.typePickerFor?.address)
        c.onTypeSheetDismissed()
        assertTrue(c.state.value.typePickerFor == null)
    }
}
