package ru.sodovaya.volty.presentation.vehicle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
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
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * Regression guard for the onSave() data-loss bug: DefaultVehicleEditComponent
 * rebuilds the saved vehicle via singlePackVehicle(), which knows nothing about
 * controllers/topology (not editable from this screen) or dashboardStyle/
 * secondaryGauge (edited here). Before the fix, every save through this screen
 * silently reset those four fields to their singlePackVehicle defaults
 * (emptyList(), PARALLEL, null, DUTY) — harmless only as long as nothing ever
 * wrote a non-default value. This vehicle carries a non-default value in all
 * four so a regression trips these assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class VehicleEditComponentTest {

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
        override suspend fun disconnectLink(address: String) {}
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}
    }

    private class FakeVehicleRepo(private val saved: List<Vehicle>) : VehicleRepository {
        val upserts = mutableListOf<Vehicle>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(saved)
        override suspend fun get(id: String): Vehicle? =
            upserts.lastOrNull { it.id == id } ?: saved.firstOrNull { it.id == id }
        override suspend fun upsert(vehicle: Vehicle) { upserts += vehicle }
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    private val originalControllers = listOf(
        Controller(index = 0, label = "Main", controllerType = ControllerType.VESC, address = "AA:BB")
    )

    private fun existingVehicle() = Vehicle(
        id = "v1",
        name = "Wheel",
        iconKey = "wheel",
        packs = listOf(Pack(index = 0, label = "Wheel", bmsType = BmsType.VESC_BMS, bmsAddress = "AA:BB")),
        controllers = originalControllers,
        topology = PackTopology.SERIES,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now(),
        dashboardStyle = DashboardStyle.CLASSIC,
        secondaryGauge = SecondaryGauge.POWER
    )

    private fun component(vehicleRepo: FakeVehicleRepo): DefaultVehicleEditComponent {
        val ctx = DefaultComponentContext(LifecycleRegistry())
        return DefaultVehicleEditComponent(
            componentContext = ctx,
            vehicleId = "v1",
            vehicleRepository = vehicleRepo,
            bmsRepository = FakeBmsRepo(),
            onSaved = {},
            onCancelled = {},
            onDeleted = {}
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `save preserves controllers and topology while applying the chosen dashboard style`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        // Sanity: the edit form loaded the vehicle's existing per-vehicle prefs.
        assertEquals(DashboardStyle.CLASSIC, c.state.value.dashboardStyle)
        assertEquals(SecondaryGauge.POWER, c.state.value.secondaryGauge)

        // The user picks new dashboard prefs — the only two of the four fields
        // this screen actually exposes — then saves.
        c.onDashboardStyleChanged(DashboardStyle.CLEAN)
        c.onSecondaryGaugeChanged(SecondaryGauge.BATTERY)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(DashboardStyle.CLEAN, saved.dashboardStyle)
        assertEquals(SecondaryGauge.BATTERY, saved.secondaryGauge)
        // controllers/topology are not exposed by this screen at all — they
        // must survive the save unchanged.
        assertEquals(originalControllers, saved.controllers)
        assertEquals(PackTopology.SERIES, saved.topology)
    }

    @Test
    fun `dashboard style Default option saves null`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        c.onDashboardStyleChanged(null)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(null, saved.dashboardStyle)
        // Still preserved even though this save's only edited field was the style.
        assertEquals(originalControllers, saved.controllers)
        assertEquals(PackTopology.SERIES, saved.topology)
    }
}
