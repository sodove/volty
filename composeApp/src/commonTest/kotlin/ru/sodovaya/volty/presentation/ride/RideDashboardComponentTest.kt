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
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DEMO_VEHICLE_ID
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.withCellCount
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.GaugeScale
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.domain.stats.PeakTracker
import ru.sodovaya.volty.util.UnitSystem
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNull
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
        override suspend fun connectDemo(profile: DemoProfile): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun disconnectLink(address: String) {}
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}

        fun emitMotion(data: ControllerData) {
            activeMotion.value = data
        }
    }

    /**
     * Records what the component writes.
     *
     * [gaugePeakWrites] is deliberately a LIST rather than a last-value: `G §9.2` item 4 is a
     * statement about how OFTEN the write happens ("only when the rung changes"), and a
     * last-value fake cannot tell one write from forty identical ones.
     *
     * [updateGaugePeaks] is overridden rather than inherited so this fake records the call itself.
     * The interface's default would route through `get`/`upsert` — correct, but it would make every
     * assertion below about the wrong member.
     */
    private class FakeVehicleRepo(initial: List<Vehicle> = emptyList()) : VehicleRepository {
        /**
         * Hot, because `DefaultRideDashboardComponent` now takes the active vehicle's STORED peaks
         * from this flow rather than from `activeVehicle` (which is a connect-time snapshot). A
         * `flowOf` could not model a stored value changing.
         */
        val rows = MutableStateFlow(initial)
        override val vehicles: Flow<List<Vehicle>> = rows
        override suspend fun get(id: String): Vehicle? = rows.value.firstOrNull { it.id == id }
        override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
        override suspend fun touch(id: String) {}

        /**
         * **Mirrors `VehicleRow.sq`: an upsert PRESERVES the two learned dial widths.** Modelled
         * rather than ignored, because the component's reaction to an upsert from a stale snapshot is
         * exactly what one of these tests is about, and a fake that let the upsert revert the peaks
         * would be testing a storage layer that does not exist.
         */
        val upserts = mutableListOf<Vehicle>()
        override suspend fun upsert(vehicle: Vehicle) {
            upserts += vehicle
            val stored = rows.value.firstOrNull { it.id == vehicle.id }
            val kept = vehicle.copy(
                gaugePeakCurrentA = stored?.gaugePeakCurrentA ?: 0f,
                gaugePeakPowerW = stored?.gaugePeakPowerW ?: 0f
            )
            rows.value = rows.value.filterNot { it.id == vehicle.id } + kept
        }

        val gaugePeakWrites = mutableListOf<Triple<String, Float, Float>>()
        /**
         * Every ATTEMPT, successful or not. The booking-order test is about how often the component
         * tries, which a record of successes cannot show.
         */
        var gaugePeakAttempts = 0
        /** While true, every write throws — for the booking-order test. */
        var failGaugePeakWrites = false
        /** Holds a write open, so a test can put samples on the wire while one is outstanding. */
        var gaugePeakGate: CompletableDeferred<Unit>? = null
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {
            gaugePeakAttempts++
            gaugePeakGate?.await()
            if (failGaugePeakWrites) {
                throw IllegalStateException("disk full")
            }
            gaugePeakWrites += Triple(id, currentA, powerW)
            rows.value = rows.value.map {
                if (it.id == id) it.copy(gaugePeakCurrentA = currentA, gaugePeakPowerW = powerW) else it
            }
        }
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
        vehicleRepo: FakeVehicleRepo = FakeVehicleRepo(),
        onOpenGraphRequested: () -> Unit = {},
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
            vehicleRepository = vehicleRepo,
            appPrefs = appPrefs,
            onOpenGraphRequested = onOpenGraphRequested,
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

        // G §9.1: a protocol that keeps NO energy counters must produce an
        // absence, not a 0.0 the card then prints as "avg 0.0 Wh/km" for the
        // whole ride. The trip has moved, so the old `tripKm <= 0` guard — the
        // only one this used to have — does not fire.
        repo.emitMotion(
            ControllerData(consumedWh = 0f, hasEnergyCounters = false, tripKm = 58f, isConnected = true)
        )
        advanceUntilIdle()
        c.state.test {
            assertNull(awaitItem().sessionWhPerKm, "a counterless wheel consumes an unknown amount")
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
    fun opening_the_graph_closes_the_sheet_and_forwards_the_request() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        var graphRequested = false
        val c = component(repo, onOpenGraphRequested = { graphRequested = true })
        advanceUntilIdle()
        c.onPillClicked() // open the sheet
        c.onOpenGraph()
        advanceUntilIdle()
        assertTrue(graphRequested)
        c.state.test { assertEquals(false, awaitItem().sheetOpen) }
    }

    // --- G §9.2: the learned CURRENT/POWER dial ranges live HERE, in the component ---------------

    /**
     * One motion frame with a battery current and an OBSERVED power.
     *
     * [seq] varies `tripKm`, and it is not decoration: `activeMotion` is a `StateFlow`, which
     * conflates equal values, and `ControllerData.timestamp` defaults to a wall-clock `now()` that
     * two constructions inside one test can share. Without a field that provably differs, a `repeat`
     * loop emitting the same reading delivers ONE sample and every median-of-three assertion below
     * silently tests a window of one. `tripKm` feeds nothing either tracker reads.
     */
    private fun sample(currentA: Float, powerW: Float, seq: Int = 0) = ControllerData(
        batteryCurrentA = currentA, powerW = powerW, hasPower = true,
        tripKm = seq.toFloat(), isConnected = true
    )


    /**
     * A stored peak must arrive on the state as a RUNG, not as itself — the dials draw the state.
     * 137 A * 1.25 = 171.25, so the 200 A rung; 6421 W * 1.25 = 8026, so the 10 kW rung.
     */
    @Test
    fun a_stored_peak_opens_the_dials_on_its_own_rung() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val ridden = vehicleWith(null, SecondaryGauge.DUTY)
            .copy(gaugePeakCurrentA = 137f, gaugePeakPowerW = 6421f)
        repo.activeVehicle.value = ridden
        val c = component(repo, vehicleRepo = FakeVehicleRepo(listOf(ridden)))
        advanceUntilIdle()
        c.state.test {
            val s = awaitItem()
            assertEquals(200f, s.currentRangeA)
            assertEquals(10_000f, s.powerRangeW)
        }
    }

    /**
     * The defect: an unridden wheel must open on the NARROWEST rung, not on VESC's ±60 A / ±10 kW.
     */
    @Test
    fun an_unridden_vehicle_opens_the_dials_on_the_narrowest_rung() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()
        c.state.test {
            val s = awaitItem()
            assertEquals(GaugeScale.CURRENT_RUNGS_A.first(), s.currentRangeA)
            assertEquals(GaugeScale.POWER_RUNGS_W.first(), s.powerRangeW)
            assertTrue(s.currentRangeA < 60f, "VESC's 60 A floor must be gone")
        }
    }

    /**
     * **`§9.2` item 3, at the component level: the two numbers must not be one.**
     *
     * A single 200 A frame on a dial that has learned nothing widens the DISPLAY immediately (the
     * rider's excursion is on-scale in the frame it happens) and writes NOTHING — one frame cannot
     * move a median of three. If display and persistence were collapsed, either the range would lag
     * a frame behind (and this fails) or the write would land (and this fails).
     */
    @Test
    fun a_single_excursion_widens_the_dial_now_and_persists_nothing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val vehicleRepo = FakeVehicleRepo()
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()

        repo.emitMotion(sample(currentA = 200f, powerW = 9000f))
        advanceUntilIdle()

        c.state.test {
            val s = awaitItem()
            assertEquals(300f, s.currentRangeA, "200 A * 1.25 = 250, so the 300 A rung, this frame")
            assertEquals(20_000f, s.powerRangeW)
        }
        assertEquals(
            emptyList(),
            vehicleRepo.gaugePeakWrites,
            "one frame is not a measurement — a median of three cannot have moved"
        )
    }

    /**
     * The corroborated case: frames that agree move the learned peak, and THAT is what is written.
     *
     * The climb is `20, 21, 22, 23, 24, 25` A on top of the repository's own initial 0 A frame, so
     * the five-sample windows are `[0,20,21,22,23] -> [20,21,22,23,24] -> [21,22,23,24,25]` and the
     * learned peak walks 21 -> 22 -> 23. Only the first of those crosses a rung (10 -> 30), so there
     * is exactly one write and it carries **21 A: the median of the window, not its maximum (23 A),
     * and not the 60 A rung the dial ends up displaying.** A writer that stored the run's maximum, or
     * the displayed rung, fails right here.
     *
     * The display and the store genuinely disagree at the end of this run, which is item 3 working:
     * the last raw sample is 25 A, and 25 * 1.25 = 31.25 needs the 60 A rung, while the median-filtered
     * 23 A still fits the 30 A one. Two numbers, and this test would fail in both directions if they
     * were collapsed.
     */
    @Test
    fun a_corroborated_peak_writes_the_median_once_the_rung_changes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val vehicleRepo = FakeVehicleRepo(listOf(vehicleWith(null, SecondaryGauge.DUTY)))
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()

        listOf(20f, 21f, 22f, 23f, 24f, 25f).forEachIndexed { i, currentA ->
            repo.emitMotion(sample(currentA = currentA, powerW = 400f, seq = i))
            advanceUntilIdle()
        }

        assertEquals(
            listOf("v1"), vehicleRepo.gaugePeakWrites.map { it.first },
            "one rung crossing (10 -> 30 at a learned 21 A), so one write"
        )
        assertEquals(21f, vehicleRepo.gaugePeakWrites.single().second, "the MEDIAN, not the maximum")
        assertEquals(
            30f, GaugeScale.rungFor(21f, GaugeScale.CURRENT_RUNGS_A),
            "the stored peak is the one that resolves to the rung that was written"
        )
        c.state.test {
            assertEquals(
                60f, awaitItem().currentRangeA,
                "the DISPLAY answers for the raw 25 A sample, which the stored 23 A does not reach"
            )
        }
    }

    /**
     * **`§9.2` item 4's own sentence: "a peak growing WITHIN a rung writes nothing".**
     *
     * 20 A and 22 A both resolve to the 30 A rung (20 * 1.25 = 25, 22 * 1.25 = 27.5). The first
     * corroborated run crosses into it and writes; the second grows the peak inside it and must not.
     * A writer keyed on the peak rather than on the rung fails here — and a writer that never writes
     * fails the first assertion.
     */
    @Test
    fun a_peak_growing_within_a_rung_writes_nothing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val vehicleRepo = FakeVehicleRepo(listOf(vehicleWith(null, SecondaryGauge.DUTY)))
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()

        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 20f, powerW = 400f, seq = it))
            advanceUntilIdle()
        }
        assertEquals(1, vehicleRepo.gaugePeakWrites.size, "crossing into the 30 A rung must be written")
        assertEquals(20f, vehicleRepo.gaugePeakWrites.single().second)

        // Now grow the peak inside the same rung, for a good long while.
        listOf(21f, 21f, 21f, 22f, 22f, 22f, 21.5f, 22f, 22f, 24f, 24f, 24f, 24f, 24f)
            .forEachIndexed { i, currentA ->
                repo.emitMotion(sample(currentA = currentA, powerW = 400f, seq = 10 + i))
                advanceUntilIdle()
            }
        assertEquals(
            1,
            vehicleRepo.gaugePeakWrites.size,
            "the peak grew from 20 A to 24 A without leaving the 30 A rung — nothing to write"
        )
        c.state.test { assertEquals(30f, awaitItem().currentRangeA) }
    }

    /**
     * A run of identical quiet frames must not write at all: the rung never leaves where it started.
     * This is the write-budget claim ("a handful over a vehicle's life, not one per BLE
     * notification") stated as a test.
     */
    @Test
    fun a_quiet_ride_writes_nothing_at_all() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val vehicleRepo = FakeVehicleRepo()
        component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()
        repeat(30) {
            repo.emitMotion(sample(currentA = 6f, powerW = 380f, seq = it))
            advanceUntilIdle()
        }
        assertEquals(emptyList(), vehicleRepo.gaugePeakWrites)
    }

    /**
     * **The unknown-vs-zero contract, at the third consumer** (`§9.2` item 6 / Task 6).
     *
     * The sample is INCOHERENT on purpose — `powerW = 4200f` with `hasPower = false`, a combination
     * no producer emits — which is precisely what separates the contract from the producers' habits:
     * a fixture that paired the false flag with a 0 W number would pass against an implementation
     * that ignored the flag.
     *
     * The current on the same frame IS observed (current has no known-flag, deliberately), so it
     * must still be learned — proving the filter is selective rather than simply off.
     */
    @Test
    fun an_unobserved_power_never_widens_or_persists_the_power_dial() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val vehicleRepo = FakeVehicleRepo(listOf(vehicleWith(null, SecondaryGauge.DUTY)))
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()

        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(
                ControllerData(
                    batteryCurrentA = 20f, powerW = 4200f, hasPower = false,
                    // Varies only to defeat StateFlow conflation — see `sample`.
                    tripKm = it.toFloat(), isConnected = true
                )
            )
            advanceUntilIdle()
        }

        c.state.test {
            val s = awaitItem()
            assertEquals(
                GaugeScale.POWER_RUNGS_W.first(), s.powerRangeW,
                "a placeholder 4200 W must not widen the dial"
            )
            assertEquals(30f, s.currentRangeA, "the current on the same frame IS observed")
        }
        // ...and the write carries a 0 W peak: the power tracker learned nothing, so a Begode is not
        // taught that its peak power is 4200 W either.
        assertEquals(0f, vehicleRepo.gaugePeakWrites.last().third)
    }

    /**
     * `§9.2` item 7's other half, seen from the dashboard: the composer clears the stored peaks while
     * this component sits in the back stack, and the re-emitted vehicle must be adopted rather than
     * ignored in favour of what the trackers still hold in memory.
     *
     * The discriminating step is the last one. Immediately after the clear the dial is STILL wide,
     * because the live 90 A sample is on it — item 3 again, and a test that asserted otherwise would
     * be asserting the display had forgotten the rider's current reading. It is the first quiet frame
     * afterwards that shows whether the learned peak was really dropped: 10 A if it was, 150 A if the
     * component kept its in-memory 90 A.
     */
    @Test
    fun clearing_the_stored_peaks_narrows_the_dials_again() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val stored = vehicleWith(null, SecondaryGauge.DUTY)
        repo.activeVehicle.value = stored
        val vehicleRepo = FakeVehicleRepo(listOf(stored))
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()
        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 90f, powerW = 6000f, seq = it))
            advanceUntilIdle()
        }
        c.state.test { assertEquals(150f, awaitItem().currentRangeA) }

        // Then the rider coasts. The dial HOLDS at 150 — that is item 3's monotone rule, and it is
        // what makes the narrowing below attributable to the clear rather than to the quiet.
        repeat(4) {
            repo.emitMotion(sample(currentA = 6f, powerW = 380f, seq = 50 + it))
            advanceUntilIdle()
        }
        c.state.test { assertEquals(150f, awaitItem().currentRangeA, "the dial narrowed on its own") }

        // Now the composer swaps the controller and clears the peaks (GaugeScale.peaksStillApply),
        // which reaches the dashboard on the REPOSITORY flow — the database's own truth — and not on
        // `activeVehicle`, whose copy of these fields went stale the moment the dashboard wrote one.
        vehicleRepo.updateGaugePeaks(stored.id, currentA = 0f, powerW = 0f)
        advanceUntilIdle()

        // Adopting a cleared peak is what ENDS the monotone session, so the next frame is allowed to
        // narrow. With the learned peak retained this would still read 150.
        repo.emitMotion(sample(currentA = 6f, powerW = 380f, seq = 99))
        advanceUntilIdle()
        c.state.test {
            val s = awaitItem()
            assertEquals(GaugeScale.CURRENT_RUNGS_A.first(), s.currentRangeA)
            assertEquals(GaugeScale.POWER_RUNGS_W.first(), s.powerRangeW)
        }
    }

    /** A demo vehicle has no row to update and must never touch the saved-vehicle store. */
    @Test
    fun a_demo_vehicle_learns_a_range_but_persists_nothing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val vehicleRepo = FakeVehicleRepo()
        repo.activeVehicle.value = vehicleWith(null, SecondaryGauge.DUTY).copy(id = DEMO_VEHICLE_ID)
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()
        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 90f, powerW = 6000f, seq = it))
            advanceUntilIdle()
        }
        c.state.test {
            assertEquals(150f, awaitItem().currentRangeA, "the demo's dial still follows the demo")
        }
        assertEquals(emptyList(), vehicleRepo.gaugePeakWrites)
    }

    /**
     * The Clean renderer's inner ring divides by the same learned range the Classic dial draws — the
     * "a range that only one style honours is half a fix" half of the task.
     */
    @Test
    fun the_secondary_current_ring_divides_by_the_learned_range_on_every_collector() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        repo.activeVehicle.value = vehicleWith(null, SecondaryGauge.CURRENT)
            .copy(gaugePeakCurrentA = 137f)
        repo.activeMotion.value = sample(currentA = 50f, powerW = 400f, seq = 0)
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("unit_system") to UnitSystem.METRIC.name,
            stringPreferencesKey("dashboard_style") to DashboardStyle.CLEAN.name
        )
        val appPrefs = AppPrefs(FakePreferencesDataStore(prefs))
        appPrefs.unitSystem.first { it == UnitSystem.METRIC }
        appPrefs.defaultDashboardStyle.first { it == DashboardStyle.CLEAN }
        val c = DefaultRideDashboardComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = repo,
            vehicleRepository = FakeVehicleRepo(),
            appPrefs = appPrefs,
            onOpenGraphRequested = {},
            onOpenSettingsRequested = {},
            onAddVehicleRequested = {},
            onDisconnectRequested = {}
        )

        suspend fun assertRing(where: String) {
            c.state.test {
                val s = awaitItem()
                assertEquals(200f, s.currentRangeA, "$where: range")
                assertTrue(
                    kotlin.math.abs(s.secondaryReadout.fraction - 0.25f) < 0.01f,
                    "$where: the ring reads ${s.secondaryReadout.fraction} of full scale"
                )
            }
        }

        // 1. the synchronous initial state, before any collector has run.
        assertRing("initial state")
        advanceUntilIdle()

        // 2. the motion collector.
        repo.emitMotion(sample(currentA = 50f, powerW = 400f, seq = 1))
        advanceUntilIdle()
        assertRing("motion collector")

        // 3. the vehicle-data collector.
        repo.activeVehicleData.value = VehicleData(aggregate = BmsData(voltage = 78f, isConnected = true))
        advanceUntilIdle()
        assertRing("vehicle-data collector")

        // 4. the vehicle collector.
        repo.activeVehicle.value = repo.activeVehicle.value!!.copy(name = "Renamed")
        advanceUntilIdle()
        assertRing("vehicle collector")

        // 5. the units collector. `AppPrefs.unitSystem` is shared over a REAL dispatcher, so virtual
        // time cannot be trusted to have carried the new value into it — suspend on the actual
        // condition first, exactly as `appPrefsWith` does, then let the component's collector run.
        // (Found by mutation sweep: without the wait this step observed nothing and the units
        // collector's copy of the range was unkillable.)
        appPrefs.setUnitSystem(UnitSystem.IMPERIAL)
        appPrefs.unitSystem.first { it == UnitSystem.IMPERIAL }
        advanceUntilIdle()
        c.state.test {
            assertEquals(UnitSystem.IMPERIAL, awaitItem().units, "the units collector must have run")
        }
        assertRing("units collector")
    }

    /**
     * **Fix round 1, Important 1: a mid-ride `upsert` from a stale snapshot must not throw away the
     * learned ranges — neither by reverting the stored value nor by tripping a reseed.**
     *
     * `KableBmsRepository.maybePersistCellCount` upserts from `_activeVehicle.value`, a snapshot
     * taken at connect that no peak write ever updates, so it carries the pre-write numbers. Part I
     * makes the cell count rider-editable, which multiplies such callers.
     *
     * Two halves, and both are needed. The storage layer refuses to write the columns from an upsert
     * (pinned at the SQL in `SqlDelightVehicleRepositoryGaugePeaksTest`, mirrored by this fake), and
     * the component takes stored peaks from the repository flow rather than from `activeVehicle` — so
     * the re-emission the auto-fill causes no longer looks like a composer clear. Reverting either
     * half fails this test.
     */
    @Test
    fun a_mid_ride_upsert_from_a_stale_snapshot_keeps_the_learned_ranges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val stored = vehicleWith(null, SecondaryGauge.DUTY).copy(
            packs = listOf(Pack(index = 0, label = "P", bmsType = BmsType.BEGODE, bmsAddress = "WH:01"))
        )
        repo.activeVehicle.value = stored
        val vehicleRepo = FakeVehicleRepo(listOf(stored))
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()

        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 90f, powerW = 6000f, seq = it))
            advanceUntilIdle()
        }
        c.state.test { assertEquals(150f, awaitItem().currentRangeA) }
        assertEquals(90f, vehicleRepo.gaugePeakWrites.single().second)

        // Then the rider coasts, which is when an auto-fill usually fires. This matters for the
        // assertion at the end: while a 90 A sample is still live the DISPLAY holds the dial at 150 A
        // on its own, so a discarded tracker would be invisible. Coasting first makes the final
        // reading attributable to the learned peak and nothing else.
        repeat(4) {
            repo.emitMotion(sample(currentA = 6f, powerW = 380f, seq = 50 + it))
            advanceUntilIdle()
        }

        // The cell-count auto-fill, exactly as it happens: an upsert built from the snapshot
        // `_activeVehicle` was holding, which still says the peaks are zero.
        vehicleRepo.upsert(stored.withCellCount(20))
        // ...and `KableBmsRepository` re-publishing `_activeVehicle` (its own `vehicles` collector
        // does exactly this). The published object is the STALE one — peaks at zero — because that is
        // the race the finding describes: the auto-fill reads and writes inside the window before the
        // peak write's own re-publication lands. This flow is a snapshot, so the dashboard must take
        // identity from it and nothing else.
        repo.activeVehicle.value = stored.withCellCount(20)
        advanceUntilIdle()

        assertEquals(
            90f, vehicleRepo.get(stored.id)!!.gaugePeakCurrentA,
            "the stored peak was reverted by an upsert that had no business writing it"
        )
        assertEquals(20, vehicleRepo.get(stored.id)!!.packs.first().cellCount, "the auto-fill landed")

        // The trackers must still hold what they learned: a quiet frame keeps the 150 A rung, and a
        // reseed to zero would have narrowed it to 10.
        repo.emitMotion(sample(currentA = 6f, powerW = 380f, seq = 99))
        advanceUntilIdle()
        c.state.test {
            assertEquals(150f, awaitItem().currentRangeA, "the live trackers were discarded")
        }
    }

    /**
     * **Fix round 1, Important 3: the displayed rung never narrows within a session.**
     *
     * A symmetric rule would let a noisy vehicle step between rungs — and, on POWER, between tick
     * units — frame to frame, the animation the quantisation exists to prevent. One excursion opens
     * the dial and it stays open.
     */
    @Test
    fun the_displayed_rung_holds_after_an_excursion_passes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.emitMotion(sample(currentA = 200f, powerW = 9000f, seq = 0))
        advanceUntilIdle()
        c.state.test {
            val s = awaitItem()
            assertEquals(300f, s.currentRangeA)
            assertEquals(20_000f, s.powerRangeW)
        }

        // Twenty quiet frames. Nothing was learned (one frame is not a window), so without the
        // monotone floor every one of these would snap the dials back to their narrowest rung.
        val seenCurrent = mutableListOf<Float>()
        val seenPower = mutableListOf<Float>()
        repeat(20) {
            repo.emitMotion(sample(currentA = 4f, powerW = 300f, seq = 1 + it))
            advanceUntilIdle()
            seenCurrent += c.state.value.currentRangeA
            seenPower += c.state.value.powerRangeW
        }
        assertEquals(listOf(300f), seenCurrent.distinct())
        assertEquals(listOf(20_000f), seenPower.distinct())
    }

    /**
     * **Fix round 1, Minor 1: a rung is booked as persisted only once the write has returned.**
     *
     * The booking is also the write CONDITION, so booking before launching meant a write that never
     * happened — the scope is cancelled by `doOnDestroy` — was nonetheless marked saved and never
     * retried. Here the first write fails; the next sample must try again rather than treat the rung
     * as stored.
     */
    @Test
    fun a_failed_peak_write_is_retried_rather_than_treated_as_saved() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val vehicleRepo = FakeVehicleRepo(listOf(vehicleWith(null, SecondaryGauge.DUTY)))
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()

        // Every write fails. The rung crossing therefore happens, is attempted, and must NOT be
        // booked — so each later sample tries again.
        vehicleRepo.failGaugePeakWrites = true
        repeat(PeakTracker.WINDOW + 3) {
            repo.emitMotion(sample(currentA = 20f, powerW = 400f, seq = it))
            advanceUntilIdle()
        }
        assertEquals(emptyList(), vehicleRepo.gaugePeakWrites, "nothing was stored")
        assertTrue(
            vehicleRepo.gaugePeakAttempts >= 2,
            "a booked-before-writing rung is attempted once and then never again; " +
                "attempts=${vehicleRepo.gaugePeakAttempts}"
        )

        // The disk comes back. The very next sample must store it, because the rung was never booked.
        vehicleRepo.failGaugePeakWrites = false
        repo.emitMotion(sample(currentA = 20f, powerW = 400f, seq = 99))
        advanceUntilIdle()
        assertEquals(listOf(Triple("v1", 20f, 400f)), vehicleRepo.gaugePeakWrites)

        // ...and now it IS booked: no further sample writes again.
        val after = vehicleRepo.gaugePeakAttempts
        repeat(5) {
            repo.emitMotion(sample(currentA = 20f, powerW = 400f, seq = 200 + it))
            advanceUntilIdle()
        }
        assertEquals(after, vehicleRepo.gaugePeakAttempts, "a stored rung must not be rewritten")
        c.state.test { assertEquals(30f, awaitItem().currentRangeA) }
    }

    /**
     * The other half of booking-after-the-write: **one outstanding write at a time.**
     *
     * Booking after the write returns means the rung stays unbooked while the write is in flight, so
     * without a guard every sample arriving in that window would see the old booking and launch its
     * own duplicate write. Ten samples land here while the first write is held open; exactly one
     * attempt may have been made.
     *
     * The gate suspends rather than delaying, so nothing here can run virtual time away and wedge
     * `runTest` instead of failing it.
     */
    @Test
    fun only_one_peak_write_is_outstanding_at_a_time() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val vehicleRepo = FakeVehicleRepo(listOf(vehicleWith(null, SecondaryGauge.DUTY)))
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        vehicleRepo.gaugePeakGate = gate

        repeat(PeakTracker.WINDOW + 10) {
            repo.emitMotion(sample(currentA = 20f, powerW = 400f, seq = it))
            advanceUntilIdle()
        }
        assertEquals(
            1, vehicleRepo.gaugePeakAttempts,
            "every sample arriving while a write is outstanding launched its own duplicate"
        )
        assertEquals(emptyList(), vehicleRepo.gaugePeakWrites, "and none of them has completed yet")

        vehicleRepo.gaugePeakGate = null
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(Triple("v1", 20f, 400f)), vehicleRepo.gaugePeakWrites)
        c.state.test { assertEquals(30f, awaitItem().currentRangeA) }
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
