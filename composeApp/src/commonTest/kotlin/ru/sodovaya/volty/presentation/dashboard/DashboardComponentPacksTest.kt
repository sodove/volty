package ru.sodovaya.volty.presentation.dashboard

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.model.DemoProfile
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
import ru.sodovaya.volty.domain.repository.GaugePeaks
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

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class DashboardComponentPacksTest {

    private class FakeBmsRepo : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> = Result.success(Unit)
        override suspend fun connectDemo(profile: DemoProfile): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun disconnectLink(address: String) {}
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun motionSamples(window: Duration): Flow<List<ControllerData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}
    }

    private class FakeVehicleRepo : VehicleRepository {
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

    private fun component(repo: FakeBmsRepo): DefaultDashboardComponent =
        DefaultDashboardComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = repo,
            vehicleRepository = FakeVehicleRepo(),
            onOpenGraphRequested = {},
            onOpenSettings = {},
            onOpenAddBattery = {},
            onOpenPackDetail = {},
            onDisconnectRequested = {}
        )

    private fun packState(index: Int, voltage: Float, online: Boolean) = PackState(
        pack = Pack(index, "Branch ${index + 1}", BmsType.ANT_BMS, "AA:0$index"),
        data = BmsData(voltage = voltage, isConnected = online),
        isOnline = online
    )

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `packs and partial flag reach the state from activeVehicleData`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.activeVehicleData.value = PackAggregator.build(
            listOf(packState(0, 100.6f, online = true), packState(1, 100.8f, online = false)),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        val s = c.state.value
        assertEquals(listOf("Branch 1", "Branch 2"), s.packs.map { it.pack.label })
        assertTrue(s.isPartial)
        assertFalse(s.packs[1].isOnline)
    }

    @Test
    fun `a single online pack is not flagged partial`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.activeVehicleData.value = PackAggregator.build(
            listOf(packState(0, 58.4f, online = true)),
            PackTopology.PARALLEL
        )
        advanceUntilIdle()

        val s = c.state.value
        assertEquals(1, s.packs.size)
        assertFalse(s.isPartial)
    }

    @Test
    fun `state starts with no packs before anything connects`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeBmsRepo())
        advanceUntilIdle()

        assertTrue(c.state.value.packs.isEmpty())
        assertFalse(c.state.value.isPartial)
    }
}
