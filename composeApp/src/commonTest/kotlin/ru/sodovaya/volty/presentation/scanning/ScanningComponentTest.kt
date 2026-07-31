package ru.sodovaya.volty.presentation.scanning

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.model.DemoProfile
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
import ru.sodovaya.volty.domain.repository.GaugePeaks
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The cold-start scan, pinned against the G1 landmine.
 *
 * `runScan()` used to index saved vehicles with
 * `saved.associateBy { it.bmsAddress }`. That shim is `packs.first()`, so the
 * moment a controller-only vehicle existed in the store the index threw inside
 * `scope.launch` — the Scanning screen never appeared, at EVERY cold start. It
 * now indexes by [ru.sodovaya.volty.domain.model.allAddresses], which is both
 * non-throwing and the only way a controller vehicle can be recognised from
 * the address it actually advertises.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class ScanningComponentTest {

    private companion object {
        const val PACK_ADDR = "AA:PACK"
        const val CTRL_ADDR = "BB:CTRL"
    }

    private class FakeBmsRepo(private val scan: List<DiscoveredDevice>) : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = scan.asFlow()
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

    private class FakeVehicleRepo(private val saved: List<Vehicle>) : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(saved)
        override suspend fun get(id: String): Vehicle? = saved.firstOrNull { it.id == id }
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

    /** Same in-memory store RideDashboardComponentTest uses to build a real AppPrefs. */
    private class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    /** Zero packs, one VESC — the shape Tasks 3-5 will start creating. */
    private fun controllerOnlyVehicle() = Vehicle(
        id = "v-vesc",
        name = "Scooter",
        iconKey = "scooter",
        packs = emptyList(),
        controllers = listOf(
            Controller(index = 0, label = "ESC", controllerType = ControllerType.VESC, address = CTRL_ADDR)
        ),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0)
    )

    /** A pack AND a controller — recognisable by either address. */
    private fun dualSourceVehicle() = Vehicle(
        id = "v-both",
        name = "Rig",
        iconKey = "generic",
        packs = listOf(Pack(index = 0, label = "P0", bmsType = BmsType.JK_BMS, bmsAddress = PACK_ADDR)),
        controllers = listOf(
            Controller(index = 0, label = "ESC", controllerType = ControllerType.VESC, address = CTRL_ADDR)
        ),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0)
    )

    private fun packOnlyVehicle() = singlePackVehicle(
        id = "v-bms", name = "Battery", iconKey = "battery",
        bmsType = BmsType.JK_BMS, bmsAddress = PACK_ADDR,
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0)
    )

    private fun device(address: String) =
        DiscoveredDevice(address = address, name = "dev", rssi = -50, bmsType = null)

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a saved controller-only vehicle is recognised from its controller advertisement`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = controllerOnlyVehicle()
        val navigated = mutableListOf<String>()
        val c = DefaultScanningComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = FakeBmsRepo(listOf(device(CTRL_ADDR))),
            vehicleRepository = FakeVehicleRepo(listOf(saved)),
            appPrefs = AppPrefs(FakePreferencesDataStore(mutablePreferencesOf())),
            onSingleKnown = { navigated += it },
            onMultipleOrNone = {}
        )

        c.state.test {
            advanceUntilIdle()
            val s = expectMostRecentItem()
            assertEquals(
                listOf("v-vesc"),
                s.knownInRange.map { it.id },
                "the controller's own address must match the vehicle that owns it"
            )
            assertEquals(1, s.savedCount)
            cancelAndIgnoreRemainingEvents()
        }
        // The user-visible consequence: cold start routes straight to the one
        // known vehicle in range instead of dumping the user in the picker.
        assertEquals(listOf("v-vesc"), navigated)
    }

    @Test
    fun `a saved vehicle with both sources is recognised from either address`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // The controller advertises; the pack does not. Keying on the primary
        // pack alone would have left this vehicle unrecognised.
        val c = DefaultScanningComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = FakeBmsRepo(listOf(device(CTRL_ADDR))),
            vehicleRepository = FakeVehicleRepo(listOf(dualSourceVehicle())),
            appPrefs = AppPrefs(FakePreferencesDataStore(mutablePreferencesOf())),
            onSingleKnown = {},
            onMultipleOrNone = {}
        )

        c.state.test {
            advanceUntilIdle()
            assertEquals(listOf("v-both"), expectMostRecentItem().knownInRange.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a pack-only vehicle still matches on its BMS address, unchanged`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val navigated = mutableListOf<String>()
        val c = DefaultScanningComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = FakeBmsRepo(listOf(device(PACK_ADDR), device("CC:STRANGER"))),
            vehicleRepository = FakeVehicleRepo(listOf(packOnlyVehicle())),
            appPrefs = AppPrefs(FakePreferencesDataStore(mutablePreferencesOf())),
            onSingleKnown = { navigated += it },
            onMultipleOrNone = {}
        )

        c.state.test {
            advanceUntilIdle()
            val s = expectMostRecentItem()
            assertEquals(listOf("v-bms"), s.knownInRange.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("v-bms"), navigated, "the BMS path behaves exactly as before")
    }

    @Test
    fun `a store holding a controller-only vehicle no longer kills the scan`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // The original crash needed no matching advertisement at all: merely
        // BUILDING the index over a zero-pack vehicle threw, so the screen
        // never rendered. Nothing here is in range; the scan must still run to
        // completion and report "none".
        val fellThrough = mutableListOf<Unit>()
        val c = DefaultScanningComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = FakeBmsRepo(emptyList()),
            vehicleRepository = FakeVehicleRepo(listOf(controllerOnlyVehicle(), packOnlyVehicle())),
            appPrefs = AppPrefs(FakePreferencesDataStore(mutablePreferencesOf())),
            onSingleKnown = {},
            onMultipleOrNone = { fellThrough += Unit }
        )

        c.state.test {
            advanceUntilIdle()
            val s = expectMostRecentItem()
            assertEquals(2, s.savedCount, "the scan got far enough to publish the saved count")
            assertEquals(emptyList(), s.knownInRange.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(Unit), fellThrough)
    }
}
