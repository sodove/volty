package ru.sodovaya.volty.presentation.dashboard

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.domain.stats.PackAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * State-level contract of the dashboard's branch summary block: rendered only
 * for a genuinely multi-branch battery, and offline branches stay in the state
 * with their last values so the UI can grey them rather than drop them.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class DashboardComponentBranchesTest {

    private class FakeBmsRepo : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> = Result.success(Unit)
        override suspend fun connectDemo(): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}
    }

    private class FakeVehicleRepo : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    private fun component(repo: FakeBmsRepo): DefaultDashboardComponent =
        DefaultDashboardComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = repo,
            vehicleRepository = FakeVehicleRepo(),
            onOpenGraph = {},
            onOpenSettings = {},
            onOpenAddBattery = {},
            onOpenPackDetail = {},
            onDisconnectRequested = {}
        )

    private fun packState(index: Int, label: String, voltage: Float, online: Boolean) = PackState(
        pack = Pack(index, label, BmsType.BEGODE, "AA:BB"),
        data = BmsData(
            voltage = voltage,
            cellVoltages = List(4) { 3.7f },
            isConnected = online
        ),
        isOnline = online
    )

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `single pack means the branch block does not render`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.activeVehicleData.value = PackAggregator.build(
            listOf(packState(0, "Battery", 58.4f, online = true)),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        assertFalse(c.state.value.showBranches)
    }

    @Test
    fun `no packs at all also means no branch block`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeBmsRepo())
        advanceUntilIdle()

        assertFalse(c.state.value.showBranches)
    }

    @Test
    fun `two packs render the block with both labels`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.activeVehicleData.value = PackAggregator.build(
            listOf(
                packState(0, "Wheel", 148.2f, online = true),
                packState(1, "Pack 2", 148.4f, online = true)
            ),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        val s = c.state.value
        assertTrue(s.showBranches)
        assertEquals(listOf("Wheel", "Pack 2"), s.packs.map { it.pack.label })
    }

    @Test
    fun `offline pack stays in the state marked offline with its last values`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.activeVehicleData.value = PackAggregator.build(
            listOf(
                packState(0, "Wheel", 148.2f, online = true),
                packState(1, "Pack 2", 147.9f, online = false)
            ),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        val s = c.state.value
        assertTrue(s.showBranches)
        assertTrue(s.isPartial)
        val offline = s.packs[1]
        assertFalse(offline.isOnline)
        // The last reading survives so the rider can see what the branch read
        // before it went quiet.
        assertEquals(147.9f, offline.data.voltage)
        assertEquals(4, offline.data.cellVoltages.size)
    }
}
