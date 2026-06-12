package ru.sodovaya.volty.presentation.picker

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Vehicle
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class PickerComponentTest {

    private class FakeBmsRepo(private val scan: List<DiscoveredDevice>) : BmsRepository {
        val guestConnects = mutableListOf<Pair<String, BmsType>>()
        val vehicleConnects = mutableListOf<Vehicle>()
        override val activeData = MutableStateFlow(BmsData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = scan.asFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> { vehicleConnects += vehicle; return Result.success(Unit) }
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> { guestConnects += address to type; return Result.success(Unit) }
        override suspend fun connectDemo(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
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

    private fun vehicle(id: String, address: String) = Vehicle(
        id = id, name = "Saved", iconKey = "generic",
        bmsType = BmsType.JK_BMS, bmsAddress = address,
        chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now()
    )

    private fun device(address: String, type: BmsType?, rssi: Int = -50) =
        DiscoveredDevice(address = address, name = "dev-$address", rssi = rssi, bmsType = type)

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
        Dispatchers.resetMain()
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
        Dispatchers.resetMain()
    }

    @Test
    fun `onConnectWithType uses the chosen type, overriding the guess (guest mode)`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val unknown = device("CC:UNKNOWN", null)
        val (c, repo) = component(mode = "guest", scan = listOf(unknown))
        advanceUntilIdle()

        c.onConnectWithType(unknown, BmsType.JBD_BMS)
        advanceUntilIdle()

        assertEquals(listOf("CC:UNKNOWN" to BmsType.JBD_BMS), repo.guestConnects)
        Dispatchers.resetMain()
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
        Dispatchers.resetMain()
    }
}
