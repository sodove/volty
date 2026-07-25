package ru.sodovaya.volty.presentation.ride

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.util.UnitSystem
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class RideDashboardComponentTest {

    // Copied from DashboardComponentPacksTest — it already implements every
    // BmsRepository member, including activeMotion. Extended here with
    // emitMotion, which pushes straight into the MutableStateFlow.
    class FakeBmsRepo : BmsRepository {
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

        fun emitMotion(data: ControllerData) {
            activeMotion.value = data
        }
    }

    private class FakeVehicleRepo : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    /**
     * A minimal in-memory [DataStore] so we can construct a real [AppPrefs]
     * without touching a filesystem. Its [data] is a hot [MutableStateFlow],
     * so [AppPrefs]'s own `stateIn(Eagerly, ...)` collectors have a value to
     * pick up as soon as they're dispatched.
     */
    private class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    /**
     * [AppPrefs] keeps its own `unitSystem` / `defaultDashboardStyle` as
     * `stateIn(scope, SharingStarted.Eagerly, ...)` over a `CoroutineScope(Dispatchers.Default + ...)`
     * internal to the class — a real dispatcher, not the test's virtual Main
     * scheduler. Constructing the store with the desired values already
     * present isn't enough on its own: that eager collector still needs to
     * be dispatched at least once before `.value` reflects them. Rather than
     * guess at a wall-clock wait, suspend on the actual condition — `first {}`
     * returns the moment the eagerly-shared flow catches up, however long
     * that takes, so the component's synchronous seed (mirroring
     * DefaultDashboardComponent's `.value`-seeded MutableStateFlow) reads the
     * right value deterministically.
     */
    private suspend fun appPrefsWith(units: UnitSystem, style: DashboardStyle): AppPrefs {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("unit_system") to units.name,
            stringPreferencesKey("dashboard_style") to style.name
        )
        val appPrefs = AppPrefs(FakePreferencesDataStore(prefs))
        appPrefs.unitSystem.first { it == units }
        appPrefs.defaultDashboardStyle.first { it == style }
        return appPrefs
    }

    private fun vehicleWith(style: DashboardStyle?, secondary: SecondaryGauge): Vehicle = Vehicle(
        id = "v1",
        name = "Test vehicle",
        iconKey = "wheel",
        packs = emptyList(),
        controllers = listOf(Controller(index = 0, label = "Main", controllerType = ControllerType.VESC, address = "AA:BB")),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now(),
        dashboardStyle = style,
        secondaryGauge = secondary
    )

    private suspend fun component(
        repo: FakeBmsRepo,
        secondary: SecondaryGauge = SecondaryGauge.DUTY,
        vehicleStyle: DashboardStyle? = null,
        appDefault: DashboardStyle = DashboardStyle.CLEAN,
        units: UnitSystem = UnitSystem.METRIC,
        onOpenSettingsRequested: () -> Unit = {},
        onAddVehicleRequested: () -> Unit = {},
        onDisconnectRequested: () -> Unit = {}
    ): DefaultRideDashboardComponent {
        if (repo.activeVehicle.value == null) {
            repo.activeVehicle.value = vehicleWith(vehicleStyle, secondary)
        }
        val appPrefs = appPrefsWith(units, appDefault)
        return DefaultRideDashboardComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = repo,
            vehicleRepository = FakeVehicleRepo(),
            appPrefs = appPrefs,
            onOpenSettingsRequested = onOpenSettingsRequested,
            onAddVehicleRequested = onAddVehicleRequested,
            onDisconnectRequested = onDisconnectRequested
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun motion_reaches_the_state() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()
        repo.emitMotion(ControllerData(speedKmh = 47f, speedSource = SpeedSource.REPORTED, dutyPercent = 76f, isConnected = true))
        advanceUntilIdle()
        c.state.test {
            val s = awaitItem()
            assertEquals(47f, s.motion.speedKmh)
            assertTrue(s.motion.speedKnown)
        }
    }

    @Test
    fun the_secondary_readout_follows_the_vehicles_choice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo, secondary = SecondaryGauge.BATTERY)
        advanceUntilIdle()
        repo.emitMotion(ControllerData(dutyPercent = 76f, isConnected = true))
        advanceUntilIdle()
        c.state.test {
            assertEquals("BATTERY", awaitItem().secondaryReadout.label)
        }
    }

    @Test
    fun a_vehicle_style_overrides_the_app_default() = runTest {
        // App default CLEAN, vehicle CLASSIC -> state.style is CLASSIC.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo, vehicleStyle = DashboardStyle.CLASSIC, appDefault = DashboardStyle.CLEAN)
        advanceUntilIdle()
        c.state.test { assertEquals(DashboardStyle.CLASSIC, awaitItem().style) }
    }

    @Test
    fun a_vehicle_without_a_style_follows_the_app_default() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo, vehicleStyle = null, appDefault = DashboardStyle.CLASSIC)
        advanceUntilIdle()
        c.state.test { assertEquals(DashboardStyle.CLASSIC, awaitItem().style) }
    }

    @Test
    fun session_consumption_is_derived_from_energy_and_trip() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()
        repo.emitMotion(ControllerData(consumedWh = 980f, tripKm = 58f, isConnected = true))
        advanceUntilIdle()
        c.state.test {
            val s = awaitItem()
            assertTrue(kotlin.math.abs(s.sessionWhPerKm!! - 16.9f) < 0.05f)
        }
    }

    @Test
    fun add_vehicle_closes_the_sheet_and_forwards_the_request() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        var addRequested = false
        val c = component(repo, onAddVehicleRequested = { addRequested = true })
        advanceUntilIdle()
        c.onPillClicked() // open the sheet
        c.onAddVehicle()
        advanceUntilIdle()
        assertTrue(addRequested)
        c.state.test { assertEquals(false, awaitItem().sheetOpen) }
    }

    @Test
    fun opening_settings_closes_the_sheet() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        var settingsRequested = false
        val c = component(repo, onOpenSettingsRequested = { settingsRequested = true })
        advanceUntilIdle()
        c.onPillClicked() // open the sheet
        c.onOpenSettings()
        advanceUntilIdle()
        assertTrue(settingsRequested)
        c.state.test { assertEquals(false, awaitItem().sheetOpen) }
    }
}
