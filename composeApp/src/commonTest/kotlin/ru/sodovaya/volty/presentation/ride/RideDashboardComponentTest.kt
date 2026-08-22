package ru.sodovaya.volty.presentation.ride

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.data.controller.kelly.ErrorCodes
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DEMO_VEHICLE_ID
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.withCellCount
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.GaugePeaks
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
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
        /**
         * The motion RING BUFFER's contents — a plain field, not a flow, exactly
         * as in `KableBmsRepository`.
         */
        var motionWindow: List<ControllerData> = emptyList()

        /**
         * **Derived from [activeMotion], like the real one** (`_activeMotion.map
         * { motionRingBuffer.within(window) }`), and that is not a detail: it
         * means ONE emission wakes BOTH of the component's collectors, in
         * subscriber order — the motion collector first, with the integral it
         * has in hand, then this one with the fresh window. Modelling the two as
         * independent flows would let the window collector always run first and
         * would hide whether the component publishes the newest integral for the
         * newest sample or one sample late.
         */
        override fun motionSamples(window: Duration): Flow<List<ControllerData>> =
            activeMotion.map { motionWindow }
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}

        fun emitMotion(data: ControllerData) {
            activeMotion.value = data
        }

        /**
         * Retain [samples] and publish the newest of them, the way a real ride
         * does: the buffer is filled by the funnel and the flow that announces
         * it is [activeMotion].
         */
        fun emitMotionWindow(samples: List<ControllerData>) {
            motionWindow = samples
            samples.lastOrNull()?.let { activeMotion.value = it }
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
     */
    private class FakeVehicleRepo(
        initial: List<Vehicle> = emptyList(),
        initialPeaks: Map<String, GaugePeaks> = emptyMap()
    ) : VehicleRepository {
        val rows = MutableStateFlow(initial)
        override val vehicles: Flow<List<Vehicle>> = rows
        override suspend fun get(id: String): Vehicle? = rows.value.firstOrNull { it.id == id }
        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
            peaks.value = peaks.value - id
        }
        override suspend fun touch(id: String) {}

        /**
         * Hot, and **the only place a learned dial width lives in this fake** — since `8.sqm` the
         * peaks are not a `Vehicle` field, so [rows] cannot carry a stale copy of one and [upsert]
         * cannot revert one by accident. A `flowOf` could not model the composer's clear arriving
         * while the dashboard is in the back stack, which two tests below are about.
         *
         * **A vehicle with no entry has learned nothing** ([GaugePeaks.NONE]) — the map is not
         * padded, exactly like `GaugePeakRow`, so `a vehicle nobody has ridden` is a state this fake
         * can actually be in rather than one it papers over with a zeroed entry.
         */
        val peaks = MutableStateFlow(initialPeaks)

        /**
         * Holds the peaks flow SILENT until completed — the window a real database has between the
         * component being constructed and its first row arriving. Nothing else can model it: a
         * [MutableStateFlow] answers on subscription, so with it alone the gap
         * `DefaultRideDashboardComponent.storedPeaksSeen` exists for is unreachable.
         */
        var peaksGate: CompletableDeferred<Unit>? = null
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flow {
            peaksGate?.await()
            emitAll(peaks)
        }

        val upserts = mutableListOf<Vehicle>()
        override suspend fun upsert(vehicle: Vehicle) {
            upserts += vehicle
            rows.value = rows.value.filterNot { it.id == vehicle.id } + vehicle
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
            peaks.value = peaks.value + (id to GaugePeaks(currentA, powerW))
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
    private suspend fun appPrefsWith(
        units: UnitSystem,
        style: DashboardStyle,
        faultLingerSeconds: Int = 60
    ): AppPrefs {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("unit_system") to units.name,
            stringPreferencesKey("dashboard_style") to style.name,
            intPreferencesKey("fault_display_duration_sec") to faultLingerSeconds
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
        appDefault: DashboardStyle = DashboardStyle.LIGHT,
        units: UnitSystem = UnitSystem.METRIC,
        faultLingerSeconds: Int = 60,
        vehicleRepo: FakeVehicleRepo = FakeVehicleRepo(),
        onOpenGraphRequested: () -> Unit = {},
        onOpenSettingsRequested: () -> Unit = {},
        onAddVehicleRequested: () -> Unit = {},
        onDisconnectRequested: () -> Unit = {},
        onEditVehicleRequested: (String) -> Unit = {},
    ): DefaultRideDashboardComponent {
        if (repo.activeVehicle.value == null) {
            repo.activeVehicle.value = vehicleWith(vehicleStyle, secondary)
        }
        val appPrefs = appPrefsWith(units, appDefault, faultLingerSeconds)
        return DefaultRideDashboardComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bmsRepository = repo,
            vehicleRepository = vehicleRepo,
            appPrefs = appPrefs,
            onOpenGraphRequested = onOpenGraphRequested,
            onOpenSettingsRequested = onOpenSettingsRequested,
            onAddVehicleRequested = onAddVehicleRequested,
            onDisconnectRequested = onDisconnectRequested,
            onEditVehicleRequested = onEditVehicleRequested,
        )
    }

    /**
     * A pack can keep the vehicle-level connection fold alive while its VESC
     * link is notifying replies we cannot decode.  The dashboard must preserve
     * that controller-specific fact instead of calling the whole vehicle
     * simply connected.
     */
    @Test
    fun `online pack plus unrecognised silent controller is exposed as mixed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val controller = Controller(0, "Drive", ControllerType.VESC, "CTRL")
        val pack = Pack(0, "Battery", BmsType.JK_BMS, "PACK")
        val vehicle = vehicleWith(null, SecondaryGauge.DUTY).copy(
            packs = listOf(pack),
            controllers = listOf(controller)
        )
        repo.activeVehicle.value = vehicle
        repo.activeVehicleData.value = VehicleData(
            packs = listOf(PackState(pack, BmsData(isConnected = true), isOnline = true)),
            controllers = listOf(ControllerState(controller, ControllerData(), isOnline = false)),
            motionPartial = true
        )
        repo.connectionState.value = ConnectionState.Connected(
            vehicle,
            linkNotUnderstood = listOf(ConnectionState.LinkNotUnderstood("CTRL"))
        )

        val component = component(repo)
        advanceUntilIdle()

        val summary = component.state.value.connectionSummary
        assertEquals(RideConnectionSummary.Kind.MIXED, summary.kind)
        assertEquals(RideConnectionSummary.ControllerIssue.NOT_UNDERSTOOD, summary.controllerIssue)
        assertTrue(summary.motionPartial, "the renderer must receive the aggregate's partial flag")
        assertEquals(RideConnectionSummary.PillSource.BATTERY, summary.pillSource)
    }

    @Test
    fun `healthy reported controller keeps the controller connected pill`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val controller = Controller(0, "Drive", ControllerType.VESC, "CTRL")
        val pack = Pack(0, "Battery", BmsType.JK_BMS, "PACK")
        val vehicle = vehicleWith(null, SecondaryGauge.DUTY).copy(
            packs = listOf(pack),
            controllers = listOf(controller)
        )
        repo.activeVehicle.value = vehicle
        repo.activeVehicleData.value = VehicleData(
            packs = listOf(PackState(pack, BmsData(isConnected = true), isOnline = true)),
            controllers = listOf(
                ControllerState(controller, ControllerData(isConnected = true), isOnline = true)
            )
        )
        repo.connectionState.value = ConnectionState.Connected(vehicle)

        val component = component(repo)
        advanceUntilIdle()

        val summary = component.state.value.connectionSummary
        assertEquals(RideConnectionSummary.Kind.CONNECTED, summary.kind)
        assertEquals(null, summary.controllerIssue)
        assertFalse(summary.motionPartial)
        assertEquals(RideConnectionSummary.PillSource.CONTROLLER, summary.pillSource)
    }

    @Test
    fun `controller poll write failure remains a controller-side mixed reason`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val controller = Controller(0, "Drive", ControllerType.VESC, "CTRL")
        val pack = Pack(0, "Battery", BmsType.JK_BMS, "PACK")
        val vehicle = vehicleWith(null, SecondaryGauge.DUTY).copy(
            packs = listOf(pack),
            controllers = listOf(controller)
        )
        repo.activeVehicle.value = vehicle
        repo.activeVehicleData.value = VehicleData(
            packs = listOf(PackState(pack, BmsData(isConnected = true), isOnline = true)),
            controllers = listOf(ControllerState(controller, ControllerData(), isOnline = false)),
            motionPartial = true
        )
        repo.connectionState.value = ConnectionState.Connected(
            vehicle,
            linkWriteFailures = listOf(ConnectionState.LinkWriteFailure("CTRL", 3, "no write property"))
        )

        val component = component(repo)
        advanceUntilIdle()

        assertEquals(
            RideConnectionSummary.ControllerIssue.WRITE_FAILED,
            component.state.value.connectionSummary.controllerIssue
        )
    }

    @Test
    fun `controller reconnect reason remains renderer-bindable beside an online battery`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val controller = Controller(0, "Drive", ControllerType.VESC, "CTRL")
        val pack = Pack(0, "Battery", BmsType.JK_BMS, "PACK")
        val vehicle = vehicleWith(null, SecondaryGauge.DUTY).copy(
            packs = listOf(pack),
            controllers = listOf(controller)
        )
        repo.activeVehicle.value = vehicle
        repo.activeVehicleData.value = VehicleData(
            packs = listOf(PackState(pack, BmsData(isConnected = true), isOnline = true)),
            controllers = listOf(ControllerState(controller, ControllerData(), isOnline = false)),
            motionPartial = true
        )
        repo.connectionState.value = ConnectionState.Connected(
            vehicle,
            linkReconnecting = listOf(ConnectionState.LinkReconnecting("CTRL", 2, "Link dropped"))
        )

        val component = component(repo)
        advanceUntilIdle()

        val summary = component.state.value.connectionSummary
        assertEquals(RideConnectionSummary.Kind.MIXED, summary.kind)
        assertEquals(RideConnectionSummary.ControllerIssue.RECONNECTING, summary.controllerIssue)
        assertEquals("Link dropped", summary.controllerIssueReason)
        assertEquals(RideConnectionSummary.PillSource.BATTERY, summary.pillSource)
    }

    @Test
    fun controller_faults_are_visible_while_active_then_linger_and_expire() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo, faultLingerSeconds = 60)
        advanceUntilIdle()
        val start = Instant.fromEpochSeconds(10_000)
        fun emit(at: Long, faults: List<String>) = repo.emitMotion(
            ControllerData(faults = faults, isConnected = true, timestamp = start + at.seconds)
        )

        emit(0, listOf("over-voltage"))
        advanceUntilIdle()
        assertEquals(listOf(RideDashboardComponent.FaultEntry("over-voltage", 1, true)), c.state.value.faults)

        emit(1, listOf("over-voltage"))
        advanceUntilIdle()
        assertEquals(listOf(RideDashboardComponent.FaultEntry("over-voltage", 2, true)), c.state.value.faults)

        emit(2, emptyList())
        advanceUntilIdle()
        assertEquals(listOf(RideDashboardComponent.FaultEntry("over-voltage", 2, false)), c.state.value.faults)

        emit(62, emptyList())
        advanceUntilIdle()
        assertTrue(c.state.value.faults.isEmpty(), "a cleared fault expires after the configured minute")
    }

    @Test
    fun distinct_faults_form_a_newest_first_stack() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()
        val start = Instant.fromEpochSeconds(20_000)
        repo.emitMotion(ControllerData(faults = listOf("over-voltage"), isConnected = true, timestamp = start))
        advanceUntilIdle()
        repo.emitMotion(
            ControllerData(
                faults = listOf("over-voltage", "over-temperature"),
                isConnected = true,
                timestamp = start + 1.seconds
            )
        )
        advanceUntilIdle()
        assertEquals(
            listOf(
                RideDashboardComponent.FaultEntry("over-temperature", 1, true),
                RideDashboardComponent.FaultEntry("over-voltage", 2, true)
            ),
            c.state.value.faults
        )
    }

    @Test
    fun zero_fault_linger_keeps_only_active_faults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo, faultLingerSeconds = 0)
        advanceUntilIdle()
        val start = Instant.fromEpochSeconds(30_000)
        repo.emitMotion(ControllerData(faults = listOf("over-voltage"), isConnected = true, timestamp = start))
        advanceUntilIdle()
        assertTrue(c.state.value.faults.single().active)
        repo.emitMotion(ControllerData(isConnected = true, timestamp = start + 1.seconds))
        advanceUntilIdle()
        assertTrue(c.state.value.faults.isEmpty(), "zero is not never-show; it means active-only")
    }

    @Test
    fun battery_faults_are_visible_and_linger_like_controller_faults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo, faultLingerSeconds = 60)
        advanceUntilIdle()

        val start = Instant.fromEpochSeconds(10)
        repo.activeVehicleData.value = VehicleData(
            aggregate = BmsData(
                bmsFaults = listOf("cell-overvoltage"),
                isConnected = true,
                timestamp = start
            )
        )
        advanceUntilIdle()
        assertEquals(
            listOf(RideDashboardComponent.FaultEntry("cell-overvoltage", 1, true)),
            c.state.value.faults
        )

        repo.activeVehicleData.value = VehicleData(
            aggregate = BmsData(isConnected = true, timestamp = start + 1.seconds)
        )
        advanceUntilIdle()
        assertEquals(
            listOf(RideDashboardComponent.FaultEntry("cell-overvoltage", 1, false)),
            c.state.value.faults
        )

        repo.activeVehicleData.value = VehicleData(
            aggregate = BmsData(isConnected = true, timestamp = start + 61.seconds)
        )
        advanceUntilIdle()
        assertTrue(c.state.value.faults.isEmpty(), "a cleared BMS fault expires after the configured minute")
    }

    @Test
    fun changing_vehicle_clears_fault_history_before_the_new_ride() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.emitMotion(
            ControllerData(
                faults = listOf("over-voltage"),
                isConnected = true,
                timestamp = Instant.fromEpochSeconds(10)
            )
        )
        advanceUntilIdle()
        assertTrue(c.state.value.faults.single().active)

        repo.activeVehicle.value = vehicleWith(null, SecondaryGauge.DUTY).copy(id = "v2")
        advanceUntilIdle()

        assertTrue(c.state.value.faults.isEmpty(), "faults from the previous vehicle must not leak")
    }

    @Test
    fun Kelly_faults_reach_the_ride_history_and_clear_on_disconnect_or_vehicle_change() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val kelly = vehicleWith(null, SecondaryGauge.CURRENT).copy(
            controllers = listOf(Controller(0, "KLS", ControllerType.KELLY, "KLS:01"))
        )
        repo.activeVehicle.value = kelly
        repo.connectionState.value = ConnectionState.Connected(kelly)
        val c = component(repo)
        advanceUntilIdle()

        // The decoder's real public fault mapping, not a dashboard-local list.
        val kellyFaults = ErrorCodes.decode(0x05)
        assertEquals(listOf("Identify Err", "Low Volt"), kellyFaults)
        repo.emitMotion(
            ControllerData(faults = kellyFaults, isConnected = true, timestamp = Instant.fromEpochSeconds(40))
        )
        advanceUntilIdle()
        assertEquals(
            listOf(
                RideDashboardComponent.FaultEntry("Low Volt"),
                RideDashboardComponent.FaultEntry("Identify Err")
            ),
            c.state.value.faults
        )

        repo.connectionState.value = ConnectionState.Disconnected
        advanceUntilIdle()
        assertTrue(c.state.value.faults.isEmpty(), "a disconnected Kelly must not retain active faults")

        repo.connectionState.value = ConnectionState.Connected(kelly)
        repo.emitMotion(
            ControllerData(faults = kellyFaults, isConnected = true, timestamp = Instant.fromEpochSeconds(41))
        )
        advanceUntilIdle()
        assertTrue(c.state.value.faults.isNotEmpty())
        repo.activeVehicle.value = kelly.copy(id = "other-kelly")
        advanceUntilIdle()
        assertTrue(c.state.value.faults.isEmpty(), "a Kelly fault must not leak into the next vehicle")
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
    fun actual_motion_arrivals_expose_rate_and_warmup_phase() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        val start = Instant.fromEpochSeconds(10)
        listOf(0L, 100L, 200L).forEach { offsetMs ->
            repo.emitMotion(
                ControllerData(
                    isConnected = true,
                    timestamp = start + offsetMs.milliseconds
                )
            )
            advanceUntilIdle()
        }

        assertEquals(10f, c.state.value.sampleRateHz!!, absoluteTolerance = 0.001f)
        assertEquals(SampleCadencePhase.WARMUP, c.state.value.sampleRatePhase)
    }

    @Test
    fun actual_fast_motion_stream_leaves_warmup_after_six_seconds() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        val start = Instant.fromEpochSeconds(10)
        (0L..6100L step 100L).forEach { offsetMs ->
            repo.emitMotion(
                ControllerData(
                    isConnected = true,
                    timestamp = start + offsetMs.milliseconds
                )
            )
            advanceUntilIdle()
        }

        assertEquals(SampleCadencePhase.STEADY, c.state.value.sampleRatePhase)
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
    fun an_unconfigured_vehicle_uses_light_as_the_new_app_default() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()
        c.state.test { assertEquals(DashboardStyle.LIGHT, awaitItem().style) }
    }

    @Test
    fun app_prefs_fall_back_to_light_when_no_dashboard_style_has_been_saved() = runTest {
        val appPrefs = AppPrefs(FakePreferencesDataStore(mutablePreferencesOf()))
        appPrefs.defaultDashboardStyle.first { it == DashboardStyle.LIGHT }
        assertEquals(DashboardStyle.LIGHT, appPrefs.defaultDashboardStyle.value)
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
            val s = awaitItem()
            assertNull(s.sessionWhPerKm, "a counterless wheel with no samples behind it is still unknown")
            assertFalse(s.sessionWhPerKmSynthesised, "an absence is not an approximation of anything")
        }
    }

    // -----------------------------------------------------------------------------
    // `I` Task 8 — the motion ring buffer finally has a reader
    // -----------------------------------------------------------------------------

    /**
     * A Begode has no watt-hour counters, so the chip was blank for a whole real
     * ride. Fed the retained motion window, the component integrates `power × dt`
     * and answers — **marked** as derived.
     *
     * The synthetic ride is the brief's: 600 W held for a minute over 1 km, i.e.
     * 10 Wh and 10 Wh/km.
     */
    @Test
    fun a_counterless_wheel_gets_a_consumption_integrated_from_its_own_power() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        val t0 = Instant.fromEpochSeconds(1_000_000)
        fun begode(atSeconds: Long, tripKm: Float, powerW: Float = 600f) = ControllerData(
            powerW = powerW,
            hasEnergyCounters = false,
            consumedWh = 0f,
            tripKm = tripKm,
            isConnected = true,
            timestamp = t0 + atSeconds.seconds
        )
        // The first sample also starts the session clock, which is what bounds
        // the integral — see RideEnergy.windowedRide's `since`.
        repo.emitMotion(begode(0, tripKm = 0f))
        advanceUntilIdle()
        repo.emitMotionWindow(listOf(begode(0, tripKm = 0f), begode(60, tripKm = 1f)))
        advanceUntilIdle()

        c.state.test {
            val s = awaitItem()
            assertEquals(10f, assertNotNull(s.sessionWhPerKm), 0.01f, "600 W for a minute over 1 km")
            assertTrue(s.sessionWhPerKmSynthesised, "integrated, and the state says so")
            assertFalse(
                s.motion.hasEnergyCounters,
                "the SAMPLE is untouched: the wheel still keeps no counters, and every " +
                    "consumer that has not heard of synthesis still reads it that way"
            )
        }

        // The ride continues. Both halves of the figure follow the window: a
        // third minute at 1800 W adds 20 Wh and a second kilometre, so 30 Wh
        // over 2 km. (A divisor taken from the newest sample's own `tripKm`
        // would agree here only because this window has not been evicted from —
        // RideEnergyTest is where that distinction is pinned.)
        repo.emitMotionWindow(
            listOf(
                begode(0, tripKm = 0f),
                begode(60, tripKm = 1f),
                begode(120, tripKm = 2f, powerW = 1800f)
            )
        )
        advanceUntilIdle()
        c.state.test {
            val s = awaitItem()
            assertEquals(15f, assertNotNull(s.sessionWhPerKm), 0.01f, "30 Wh over 2 km")
            assertTrue(s.sessionWhPerKmSynthesised, "still derived, and still says so")
        }
    }

    /**
     * The same window on hardware that keeps counters changes nothing: the
     * measurement wins and the readout is not marked.
     *
     * Without this, an implementation that always preferred the integral would
     * pass the test above while silently replacing every VESC's own coulomb
     * counting with a reconstruction from BLE arrival gaps.
     */
    @Test
    fun a_vehicle_that_keeps_counters_is_not_overridden_by_the_integral() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        val t0 = Instant.fromEpochSeconds(2_000_000)
        fun vesc(atSeconds: Long, tripKm: Float) = ControllerData(
            powerW = 600f,
            hasEnergyCounters = true,
            consumedWh = 980f,
            tripKm = tripKm,
            isConnected = true,
            timestamp = t0 + atSeconds.seconds
        )
        repo.emitMotion(vesc(0, tripKm = 58f))
        advanceUntilIdle()
        repo.emitMotionWindow(listOf(vesc(0, tripKm = 58f), vesc(60, tripKm = 58f)))
        advanceUntilIdle()

        c.state.test {
            val s = awaitItem()
            assertEquals(980f / 58f, assertNotNull(s.sessionWhPerKm), 0.01f, "the counter's figure")
            assertFalse(s.sessionWhPerKmSynthesised)
        }
    }

    /**
     * A retained window whose samples carry no measured power synthesises
     * nothing — the readout is exactly as blank as before this task, never a
     * confident `≈0.0`.
     *
     * **Deliberately incoherent fixture**: `powerW = 4200f` behind
     * `hasPower = false` is a pair no producer emits, which is why it separates
     * "the integrator honoured the flag" from "the integrator added the number,
     * and every producer happens to write 0 there".
     */
    @Test
    fun a_window_with_no_measured_power_synthesises_nothing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        val t0 = Instant.fromEpochSeconds(3_000_000)
        fun unscaled(atSeconds: Long, tripKm: Float) = ControllerData(
            powerW = 4200f,
            hasPower = false,
            hasEnergyCounters = false,
            tripKm = tripKm,
            isConnected = true,
            timestamp = t0 + atSeconds.seconds
        )
        repo.emitMotion(unscaled(0, tripKm = 0f))
        advanceUntilIdle()
        repo.emitMotionWindow(listOf(unscaled(0, tripKm = 0f), unscaled(60, tripKm = 1f)))
        advanceUntilIdle()

        c.state.test {
            val s = awaitItem()
            assertNull(s.sessionWhPerKm, "a wheel with no voltage scale has no power to integrate")
            assertFalse(s.sessionWhPerKmSynthesised)
        }
    }

    /**
     * **A reconnect restarts the trip, so it must restart the energy too.**
     *
     * The motion ring buffer deliberately survives a reconnect to the same
     * address (the graph keeps its history), while `tripKm` is a session delta
     * the protocol rebuilds from a fresh baseline. Integrate the whole retained
     * buffer and the previous leg's watt-hours get charged against the new leg's
     * kilometres — here, 3600 Wh over 1 km instead of 10.
     */
    @Test
    fun a_reconnect_restarts_the_integral_because_it_restarts_the_trip() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        val v = assertNotNull(repo.activeVehicle.value)
        repo.connectionState.value = ConnectionState.Connected(v)
        advanceUntilIdle()

        val t0 = Instant.fromEpochSeconds(4_000_000)
        fun leg(atSeconds: Long, powerW: Float, tripKm: Float) = ControllerData(
            powerW = powerW,
            hasEnergyCounters = false,
            tripKm = tripKm,
            isConnected = true,
            timestamp = t0 + atSeconds.seconds
        )
        // The first leg: an hour at 3600 W, i.e. 3600 Wh.
        repo.emitMotionWindow(listOf(leg(0, 3600f, tripKm = 20f), leg(3600, 3600f, tripKm = 40f)))
        advanceUntilIdle()

        // The link drops and comes back. The buffer keeps everything; `tripKm`
        // starts over from the protocol's new baseline.
        repo.connectionState.value = ConnectionState.Connecting(v)
        advanceUntilIdle()
        repo.connectionState.value = ConnectionState.Connected(v)
        // The new session's own first sample is what starts its clock.
        val keptFromBefore = listOf(leg(0, 3600f, tripKm = 20f), leg(3600, 3600f, tripKm = 40f))
        repo.emitMotionWindow(keptFromBefore + leg(3660, 600f, tripKm = 0f))
        advanceUntilIdle()
        repo.emitMotionWindow(
            keptFromBefore + leg(3660, 600f, tripKm = 0f) + leg(3720, 600f, tripKm = 1f)
        )
        advanceUntilIdle()

        c.state.test {
            val s = awaitItem()
            assertEquals(
                10f,
                assertNotNull(s.sessionWhPerKm),
                0.05f,
                "the new leg's 10 Wh over its own 1 km — not the buffer's whole 3645 Wh"
            )
            assertTrue(s.sessionWhPerKmSynthesised)
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
     *
     * **The stored peak is supplied through the peaks flow and not on the vehicle**, because since
     * `8.sqm` there is nowhere on a [Vehicle] to put one. The vehicle row and its learned range are
     * two separate answers from two separate tables, and this fake keeps them separate too.
     */
    @Test
    fun a_stored_peak_opens_the_dials_on_its_own_rung() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val ridden = vehicleWith(null, SecondaryGauge.DUTY)
        repo.activeVehicle.value = ridden
        val c = component(
            repo,
            vehicleRepo = FakeVehicleRepo(
                listOf(ridden),
                mapOf(ridden.id to GaugePeaks(currentA = 137f, powerW = 6421f))
            )
        )
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

    @Test
    fun an_unobserved_battery_current_never_widens_or_persists_the_current_dial() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val vehicleRepo = FakeVehicleRepo(listOf(vehicleWith(null, SecondaryGauge.DUTY)))
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()

        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(
                ControllerData(
                    batteryCurrentA = 900f,
                    hasBatteryCurrent = false,
                    powerW = 0f,
                    hasPower = true,
                    tripKm = it.toFloat(),
                    isConnected = true
                )
            )
            advanceUntilIdle()
        }

        c.state.test {
            assertEquals(
                GaugeScale.CURRENT_RUNGS_A.first(), awaitItem().currentRangeA,
                "a placeholder 900 A must not widen the current dial"
            )
        }
        assertEquals(emptyList(), vehicleRepo.gaugePeakWrites)
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
     *
     * **Step 1 is no longer the synchronous initial state, and that is a real behaviour change Task 9
     * paid for.** The connect-time seed used to read the peaks off the `activeVehicle` snapshot, so
     * the very first `_state` a rider saw already carried the learned rung. Since `8.sqm` a snapshot
     * carries no peak at all and the only authority is a flow, so the dials open on the narrowest
     * rung for the one dispatch it takes the peaks flow to answer. What must still hold — and is what
     * this test now pins — is that once it *has* answered, every collector divides by the learned
     * range rather than by a default.
     */
    @Test
    fun the_secondary_current_ring_divides_by_the_learned_range_on_every_collector() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val ridden = vehicleWith(null, SecondaryGauge.CURRENT)
        repo.activeVehicle.value = ridden
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
            vehicleRepository = FakeVehicleRepo(
                listOf(ridden),
                mapOf(ridden.id to GaugePeaks(currentA = 137f))
            ),
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

        // 1. the state as it stands once the peaks flow has answered and nothing else has run. The
        //    stored range has to reach the ring through the peaks collector's own `_state.update`,
        //    which is the copy the sweep otherwise cannot kill.
        advanceUntilIdle()
        assertRing("peaks collector")

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
            GaugePeaks(currentA = 90f, powerW = 6000f), vehicleRepo.peaks.value[stored.id],
            "the stored peak was reverted by an upsert that had no business writing it"
        )
        assertEquals(20, vehicleRepo.get(stored.id)!!.packs.first().cellCount, "the auto-fill landed")

        // **The attributable assertion.** A quiet frame still shows the 150 A rung, but that alone
        // proves little: the monotone display floor would hold it there even with the trackers wiped.
        // What only an intact tracker can produce is SILENCE -- another corroborated run at 90 A must
        // write nothing, because the learned peak is still 90 and its rung is still booked. Had the
        // re-publication reseeded, the same run would re-learn 90 from zero and write a second time.
        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 90f, powerW = 6000f, seq = 90 + it))
            advanceUntilIdle()
        }
        assertEquals(
            1, vehicleRepo.gaugePeakWrites.size,
            "the live trackers were discarded and had to re-learn what they already knew"
        )
        // Corroborating, not load-bearing: the dial is still 150 A.
        repo.emitMotion(sample(currentA = 6f, powerW = 380f, seq = 99))
        advanceUntilIdle()
        c.state.test { assertEquals(150f, awaitItem().currentRangeA) }
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
     * **A failing write is attempted ONCE per rung, not once per sample** (fix round 2).
     *
     * "Booked only on success" means the rung stays eligible, and eligible-on-every-sample is a
     * database call and a log line five to ten times a second for the rest of a ride whose storage is
     * failing. A learned rung is worth almost nothing; a ride's battery and log are not. So the
     * throttle is one attempt per rung VALUE — and the next rung up is a fresh budget, which is what
     * keeps the throttle from being a permanent silence.
     */
    @Test
    fun a_failing_write_is_attempted_once_per_rung_not_once_per_sample() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val vehicleRepo = FakeVehicleRepo(listOf(vehicleWith(null, SecondaryGauge.DUTY)))
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()
        vehicleRepo.failGaugePeakWrites = true

        // Twenty samples on the 30 A rung, every write failing.
        repeat(PeakTracker.WINDOW + 15) {
            repo.emitMotion(sample(currentA = 20f, powerW = 400f, seq = it))
            advanceUntilIdle()
        }
        assertEquals(emptyList(), vehicleRepo.gaugePeakWrites, "nothing was stored")
        assertEquals(
            1, vehicleRepo.gaugePeakAttempts,
            "an unbooked rung must not be re-attempted on every sample for the rest of the ride"
        )

        // The next rung up is a new budget: the learned peak crosses into 60 A and is tried again.
        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 40f, powerW = 400f, seq = 100 + it))
            advanceUntilIdle()
        }
        assertEquals(2, vehicleRepo.gaugePeakAttempts, "a NEW rung must still be attempted")
        assertEquals(emptyList(), vehicleRepo.gaugePeakWrites)

        // The disk comes back, and the rung after that stores — carrying a peak that subsumes both
        // the values the failures lost, because the learned peak only ever grows.
        vehicleRepo.failGaugePeakWrites = false
        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 90f, powerW = 400f, seq = 200 + it))
            advanceUntilIdle()
        }
        assertEquals(listOf(Triple("v1", 90f, 400f)), vehicleRepo.gaugePeakWrites)

        // ...and a stored rung is not rewritten.
        val after = vehicleRepo.gaugePeakAttempts
        repeat(5) {
            repo.emitMotion(sample(currentA = 90f, powerW = 400f, seq = 300 + it))
            advanceUntilIdle()
        }
        assertEquals(after, vehicleRepo.gaugePeakAttempts, "a stored rung must not be rewritten")
        c.state.test { assertEquals(150f, awaitItem().currentRangeA) }
    }

    /**
     * **The booking-order claim, with an observable that does not depend on the retry.**
     *
     * If a failed write booked its rung anyway, `persistedCurrentRungA` would claim the database holds
     * 30 A while it actually holds 0 — and that field is also the reseed comparator. So the very next
     * emission of the (unchanged) stored row looks like a mismatch, trips an adoption, and discards
     * the live trackers. Here the row is re-emitted unchanged after a failed write: with truthful
     * booking nothing happens; with optimistic booking the learned 20 A is thrown away and the dial
     * narrows.
     */
    @Test
    fun a_failed_write_does_not_claim_the_database_holds_its_rung() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val stored = vehicleWith(null, SecondaryGauge.DUTY)
        repo.activeVehicle.value = stored
        val vehicleRepo = FakeVehicleRepo(listOf(stored))
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()
        vehicleRepo.failGaugePeakWrites = true

        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 20f, powerW = 400f, seq = it))
            advanceUntilIdle()
        }
        assertEquals(emptyList(), vehicleRepo.gaugePeakWrites)

        // The saved-vehicle list re-emits, unchanged. `persistedCurrentRungA` must still say 10 A --
        // what the database really holds -- so this is not a mismatch and nothing is adopted.
        vehicleRepo.rows.value = listOf(stored.copy())
        advanceUntilIdle()

        // The learned 20 A is intact, so the dial still shows the 30 A rung on a quiet frame. A
        // spurious adoption would have reset the tracker and narrowed it to 10 A.
        vehicleRepo.failGaugePeakWrites = false
        repo.emitMotion(sample(currentA = 2f, powerW = 100f, seq = 99))
        advanceUntilIdle()
        c.state.test {
            assertEquals(30f, awaitItem().currentRangeA, "the trackers were discarded")
        }
    }

    /**
     * **The throttle is per rung, and one vehicle's failure must not silence another's first write.**
     *
     * Found by mutation sweep: the throttle budget is reset on adoption, and nothing pinned it. The
     * hole it leaves is specific and nasty — two vehicles can easily learn the *same* rung, so a
     * failed write on the wheel would suppress the scooter's very first write, permanently, with the
     * disk perfectly healthy by then.
     */
    @Test
    fun a_failed_write_on_one_vehicle_does_not_throttle_another() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val wheel = vehicleWith(null, SecondaryGauge.DUTY)
        val scooter = wheel.copy(id = "v2", name = "Scooter")
        repo.activeVehicle.value = wheel
        val vehicleRepo = FakeVehicleRepo(listOf(wheel, scooter))
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()

        // The wheel crosses into the 30 A rung and the write fails.
        vehicleRepo.failGaugePeakWrites = true
        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 20f, powerW = 400f, seq = it))
            advanceUntilIdle()
        }
        assertEquals(1, vehicleRepo.gaugePeakAttempts)
        assertEquals(emptyList(), vehicleRepo.gaugePeakWrites)

        // The rider switches to the scooter, on a healthy disk, and rides it to the SAME rung.
        vehicleRepo.failGaugePeakWrites = false
        repo.activeVehicle.value = scooter
        advanceUntilIdle()
        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 20f, powerW = 400f, seq = 100 + it))
            advanceUntilIdle()
        }
        assertEquals(
            listOf(Triple("v2", 20f, 400f)), vehicleRepo.gaugePeakWrites,
            "the wheel's failed attempt at the same rung silenced the scooter"
        )
        c.state.test { assertEquals(30f, awaitItem().currentRangeA) }
    }

    /**
     * Switching vehicles must adopt the new vehicle's STORED peaks, **without waiting for the saved
     * -vehicle table to change.**
     *
     * `adoptStoredPeaks`' rule is that stored peaks come from `VehicleRepository.gaugePeaks` and never
     * from `activeVehicle`, which is a snapshot. Honouring that by *awaiting* a re-emission would make
     * this depend on `touch()` happening to write a row on every connect — true today, pinned by
     * nothing. So an id change looks the new id up in the map already published. Here **nothing on
     * either flow changes after the switch**: only `activeVehicle` moves.
     *
     * **The fixture is deliberately incoherent** in the way `GaugePeakRow` makes real: the wheel is a
     * saved vehicle with NO entry in the peaks map, and the scooter is one WITH an entry. No producer
     * emits a map padded with zeroes, so a component that read absence as anything other than
     * [GaugePeaks.NONE] would be caught by the first assertion here rather than by nothing.
     */
    @Test
    fun switching_vehicles_adopts_the_new_vehicles_stored_peaks_without_a_table_change() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val wheel = vehicleWith(null, SecondaryGauge.DUTY)
        val scooter = wheel.copy(id = "v2", name = "Scooter")
        repo.activeVehicle.value = wheel
        val vehicleRepo = FakeVehicleRepo(
            listOf(wheel, scooter),
            mapOf(scooter.id to GaugePeaks(currentA = 240f))
        )
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()
        c.state.test { assertEquals(10f, awaitItem().currentRangeA, "the wheel has learned nothing") }

        repo.activeVehicle.value = scooter
        advanceUntilIdle()
        c.state.test {
            assertEquals(
                300f, awaitItem().currentRangeA,
                "240 A * 1.25 = 300, from the STORED row -- adopted on the switch, not on a table change"
            )
        }
    }

    /**
     * **Nothing may be persisted until the database has said what it already holds** — the gap Task 9
     * opened and `storedPeaksSeen` closes.
     *
     * Before `8.sqm` the learned range arrived synchronously, on the `activeVehicle` snapshot the
     * `_state` initializer reads, so the trackers were seeded before the first sample could be
     * folded. It cannot now: the only authority is a flow, and a flow answers on its own schedule. A
     * rider who connects mid-acceleration therefore has a window in which five samples can be folded,
     * a rung crossed, and a median five samples old written over a range learned across weeks — the
     * exact loss this whole feature exists to prevent, arriving through the fix for a different one.
     *
     * So: hold the peaks flow silent, ride hard enough to cross a rung, and require silence. Then let
     * the range through and require that it is the STORED 137 A that wins, not the fresh 90 A — and
     * that no write ever happened, because 90 is below what the vehicle already knew.
     *
     * The gate suspends rather than delaying, so nothing here can run virtual time away and wedge
     * `runTest` instead of failing it.
     */
    @Test
    fun no_peak_is_written_before_the_stored_range_has_arrived() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val ridden = vehicleWith(null, SecondaryGauge.DUTY)
        repo.activeVehicle.value = ridden
        val vehicleRepo = FakeVehicleRepo(
            listOf(ridden),
            mapOf(ridden.id to GaugePeaks(currentA = 137f, powerW = 6421f))
        )
        val gate = CompletableDeferred<Unit>()
        vehicleRepo.peaksGate = gate
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()

        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 90f, powerW = 6000f, seq = it))
            advanceUntilIdle()
        }
        assertEquals(
            0, vehicleRepo.gaugePeakAttempts,
            "a range learned across weeks was overwritten by a median five samples old"
        )

        gate.complete(Unit)
        advanceUntilIdle()
        // The stored range wins the adoption, so a further run at 90 A is not news either.
        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 90f, powerW = 6000f, seq = 50 + it))
            advanceUntilIdle()
        }
        assertEquals(emptyList(), vehicleRepo.gaugePeakWrites)
        assertEquals(
            GaugePeaks(currentA = 137f, powerW = 6421f), vehicleRepo.peaks.value[ridden.id],
            "the stored range must be exactly what it was"
        )
        c.state.test {
            assertEquals(200f, awaitItem().currentRangeA, "137 A * 1.25 = 171.25, so the 200 A rung")
        }
    }

    /**
     * The other side of the gate: once the stored range HAS arrived, a genuinely new rung is written.
     * Without this, `storedPeaksSeen` could be pinned to `false` forever and the test above would
     * still pass while the dashboard silently persisted nothing for the rest of the app's life.
     */
    @Test
    fun a_new_rung_is_written_once_the_stored_range_has_arrived() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val ridden = vehicleWith(null, SecondaryGauge.DUTY)
        repo.activeVehicle.value = ridden
        val vehicleRepo = FakeVehicleRepo(listOf(ridden))
        val gate = CompletableDeferred<Unit>()
        vehicleRepo.peaksGate = gate
        val c = component(repo, vehicleRepo = vehicleRepo)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        repeat(PeakTracker.WINDOW) {
            repo.emitMotion(sample(currentA = 90f, powerW = 6000f, seq = it))
            advanceUntilIdle()
        }
        assertEquals(listOf(Triple("v1", 90f, 6000f)), vehicleRepo.gaugePeakWrites)
        c.state.test { assertEquals(150f, awaitItem().currentRangeA) }
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

    @Test
    fun edit_action_requests_the_active_vehicle_and_closes_the_sheet() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        var editedId: String? = null
        val c = component(repo, onEditVehicleRequested = { editedId = it })
        advanceUntilIdle()

        c.onPillClicked()
        c.onEditVehicle()
        advanceUntilIdle()

        assertEquals(repo.activeVehicle.value?.id, editedId)
        c.state.test { assertEquals(false, awaitItem().sheetOpen) }
    }
}
