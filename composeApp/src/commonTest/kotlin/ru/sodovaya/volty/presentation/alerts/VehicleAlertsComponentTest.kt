package ru.sodovaya.volty.presentation.alerts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.alert.AlertAvailability
import ru.sodovaya.volty.domain.alert.AlertLevel
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.AlertUnavailableReason
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.motionAlertRules
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.notification.AlarmPreview
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * The alert settings component: what it shows, what it refuses, and what it
 * writes.
 *
 * Every test here is a way the screen could be wrong in the rider's hand. The
 * load-bearing one is the first: a rider opens settings **while disconnected**,
 * and if that reads as "your hardware lacks this sensor" the whole screen goes
 * read-only in the ordinary case.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class VehicleAlertsComponentTest {

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

    private class FakeVehicleRepo(private var stored: Vehicle?) : VehicleRepository {
        val upserts = mutableListOf<Vehicle>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(listOfNotNull(stored))
        override suspend fun get(id: String): Vehicle? = stored?.takeIf { it.id == id }
        override suspend fun upsert(vehicle: Vehicle) {
            upserts += vehicle
            stored = vehicle
        }
        override suspend fun delete(id: String) { stored = null }
        override suspend fun touch(id: String) {}

        /** Simulate another screen (the edit form) saving while this one is open. */
        fun replaceStored(vehicle: Vehicle) { stored = vehicle }
    }

    private class RecordingPreview : AlarmPreview {
        val played = mutableListOf<Int>()
        override fun preview(level: Int) { played += level }
    }

    private class FakePreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    private val lifecycle = LifecycleRegistry()

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vehicle(
        controllerType: ControllerType = ControllerType.VESC,
        motionAlerts: List<AlertRule>? = null,
        name: String = "Wheel"
    ) = Vehicle(
        id = "v1",
        name = name,
        iconKey = "generic",
        packs = emptyList(),
        controllers = listOf(
            Controller(index = 0, label = "ESC", controllerType = controllerType, address = "AA:BB")
        ),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now(),
        motionAlerts = motionAlerts
    )

    private fun component(
        repo: FakeVehicleRepo,
        bms: FakeBmsRepo = FakeBmsRepo(),
        preview: AlarmPreview = RecordingPreview(),
        prefs: AppPrefs = AppPrefs(FakePreferencesDataStore(mutablePreferencesOf())),
        onSaved: () -> Unit = {},
        onBack: () -> Unit = {}
    ) = DefaultVehicleAlertsComponent(
        componentContext = DefaultComponentContext(lifecycle),
        vehicleId = "v1",
        vehicleRepository = repo,
        bmsRepository = bms,
        appPrefs = prefs,
        alarmPreview = preview,
        onSaved = onSaved,
        onBackRequested = onBack
    )

    // -------------------------------------------------------------- what shows

    /**
     * The one that matters most. A disconnected vehicle emits the placeholder
     * `ControllerData()`, so `availabilityFor` answers Unknown for all three
     * sensor-backed kinds — and Unknown must edit.
     */
    @Test
    fun `opening while disconnected leaves every threshold editable`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val bms = FakeBmsRepo()
        assertFalse(bms.activeMotion.value.isConnected, "fixture check: nothing is connected")
        val c = component(FakeVehicleRepo(vehicle()), bms)
        advanceUntilIdle()

        assertTrue(c.state.value.loaded)
        assertEquals("Wheel", c.state.value.vehicleName, "the rider must see which vehicle they are editing")
        val kinds = c.state.value.kinds
        assertEquals(MotionAlertKind.entries.size, kinds.size, "every kind is listed")
        assertTrue(kinds.all { it.isEditable }, "a disconnected vehicle is not a missing sensor")
        assertIs<AlertAvailability.Unknown>(
            kinds.single { it.kind == MotionAlertKind.MOTOR_TEMP }.availability,
            "and the sensor kinds must be Unknown, which has its own wording"
        )
    }

    /**
     * F §10: an alert the hardware provably cannot supply is still shown — greyed,
     * with its reason — because an absent row leaves the rider wondering.
     */
    @Test
    fun `a kind the protocol cannot supply is listed, locked, and carries its reason`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeVehicleRepo(vehicle(controllerType = ControllerType.KELLY)))
        advanceUntilIdle()

        val duty = c.state.value.kinds.single { it.kind == MotionAlertKind.DUTY }
        val unavailable = assertIs<AlertAvailability.Unavailable>(duty.availability)
        assertIs<AlertUnavailableReason.ControllerReportsNoDuty>(unavailable.reason)
        assertFalse(duty.isEditable, "no setting may arm what the hardware cannot report")
        assertEquals(MotionAlertKind.entries.size, c.state.value.kinds.size, "it is greyed, not hidden")
    }

    /** Nothing renders before the vehicle is read, and Save cannot fire into a void. */
    @Test
    fun `nothing is offered before the vehicle has loaded`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeVehicleRepo(vehicle()))

        assertFalse(c.state.value.loaded)
        assertFalse(c.state.value.canSave)
    }

    /**
     * Connecting mid-edit answers the sensor question — and must not throw away
     * the number the rider was halfway through typing.
     */
    @Test
    fun `connecting mid-edit refreshes availability without discarding a half-typed threshold`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(vehicle()), bms)
        advanceUntilIdle()

        c.onThresholdChanged(MotionAlertKind.MOTOR_TEMP, 0, "12")
        bms.activeMotion.value = ControllerData(
            isConnected = true,
            hasMotorTemp = true,
            escTempC = 40f,
            speedSource = SpeedSource.REPORTED
        )
        advanceUntilIdle()

        val motor = c.state.value.kinds.single { it.kind == MotionAlertKind.MOTOR_TEMP }
        assertIs<AlertAvailability.Available>(motor.availability, "the sample answers the sensor question")
        assertEquals("12", motor.levels.single().text, "and the rider keeps typing where they left off")
    }

    /**
     * A disconnected placeholder is not evidence — and the fixture has to have
     * *learned something first* for that to mean anything. Starting from
     * never-connected, Unknown-in / Unknown-out proves nothing: the assertion
     * would hold with the guard deleted. So this connects, learns the sensor is
     * there, and then drops the link.
     */
    @Test
    fun `a link dropping mid-edit does not un-learn what the last sample proved`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(vehicle()), bms)
        advanceUntilIdle()

        bms.activeMotion.value = ControllerData(isConnected = true, hasMotorTemp = true)
        advanceUntilIdle()
        assertIs<AlertAvailability.Available>(
            c.state.value.kinds.single { it.kind == MotionAlertKind.MOTOR_TEMP }.availability,
            "fixture check: the sensor must be known present before the link drops"
        )

        // Exactly the placeholder `activeMotion` emits when nothing is connected.
        // Believing it would revert the row to "no data yet" — or, worse, claim
        // the thermistor had gone missing.
        bms.activeMotion.value = ControllerData(isConnected = false, hasMotorTemp = false)
        advanceUntilIdle()

        assertIs<AlertAvailability.Available>(
            c.state.value.kinds.single { it.kind == MotionAlertKind.MOTOR_TEMP }.availability
        )
    }

    // --------------------------------------------------------------- the save

    @Test
    fun `saving writes the riders rules onto the vehicle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(vehicle())
        var saved = false
        val c = component(repo, onSaved = { saved = true })
        advanceUntilIdle()

        c.onThresholdChanged(MotionAlertKind.DUTY, 0, "72")
        c.onSave()
        advanceUntilIdle()

        val written = repo.upserts.single()
        val duty = assertNotNull(written.motionAlerts).single { it.kind == MotionAlertKind.DUTY }
        assertEquals(72f, duty.levels.first().thresholdValue)
        assertTrue(saved, "and the screen closes itself")
    }

    /** Out-of-order input reaches storage sorted — the normaliser is on the save path. */
    @Test
    fun `the save path normalises out-of-order thresholds`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(vehicle())
        val c = component(repo)
        advanceUntilIdle()

        // Defaults are 80 / 90; type them back to front.
        c.onThresholdChanged(MotionAlertKind.DUTY, 0, "95")
        c.onSave()
        advanceUntilIdle()

        val duty = assertNotNull(repo.upserts.single().motionAlerts).single { it.kind == MotionAlertKind.DUTY }
        assertEquals(listOf(90f, 95f), duty.levels.map { it.thresholdValue })
    }

    /**
     * A blank field must not be persisted as "the rider turned this off". Blocking
     * the save is what keeps the empty list the *only* way to say off.
     */
    @Test
    fun `a half-typed threshold blocks the save instead of silently switching a kind off`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(vehicle())
        val c = component(repo)
        advanceUntilIdle()

        c.onThresholdChanged(MotionAlertKind.DUTY, 0, "")

        assertFalse(c.state.value.canSave)
        c.onSave()
        advanceUntilIdle()
        assertTrue(repo.upserts.isEmpty(), "nothing may be written from an incomplete form")
    }

    /**
     * This screen is pushed on top of the vehicle edit form, which can save a
     * rename while it is open. Writing a snapshot taken at open time would roll
     * that back.
     */
    @Test
    fun `saving re-reads the vehicle so a rename made elsewhere is not rolled back`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(vehicle(name = "Old"))
        val c = component(repo)
        advanceUntilIdle()

        repo.replaceStored(vehicle(name = "New"))
        c.onSave()
        advanceUntilIdle()

        assertEquals("New", repo.upserts.single().name)
    }

    /**
     * The vehicle can be deleted from the settings list while this screen sits on
     * the stack. Writing then would **re-create** it from a snapshot — a vehicle
     * the rider deliberately deleted coming back with alert rules attached.
     */
    @Test
    fun `saving onto a vehicle that has been deleted writes nothing and leaves`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(vehicle())
        var backed = false
        val c = component(repo, onBack = { backed = true })
        advanceUntilIdle()

        repo.delete("v1")
        c.onSave()
        advanceUntilIdle()

        assertTrue(repo.upserts.isEmpty(), "a deleted vehicle must not be resurrected by a save")
        assertTrue(backed, "and the screen must not sit there spinning")
        assertFalse(c.state.value.saving)
    }

    /** Silence must be persistable, and must not come back as the defaults. */
    @Test
    fun `switching every kind off persists silence rather than null`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(vehicle())
        val c = component(repo)
        advanceUntilIdle()

        MotionAlertKind.entries.forEach { c.onKindEnabledChanged(it, false) }
        c.onSave()
        advanceUntilIdle()

        val written = repo.upserts.single()
        assertNotNull(written.motionAlerts, "null would mean 'never configured' and hand back the defaults")
        assertTrue(written.motionAlerts.all { it.isOff })
        assertTrue(written.motionAlertRules.all { it.isOff }, "and resolving it must stay silent")
    }

    /** An unavailable kind's numbers survive the save that could not edit them. */
    @Test
    fun `saving keeps the numbers of a kind this hardware cannot supply`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val tuned = listOf(
            AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(77f))),
            AlertRule(MotionAlertKind.SPEED, emptyList()),
            AlertRule(MotionAlertKind.MOTOR_TEMP, listOf(AlertLevel(120f))),
            AlertRule(MotionAlertKind.ESC_TEMP, listOf(AlertLevel(88f)))
        )
        val repo = FakeVehicleRepo(vehicle(controllerType = ControllerType.KELLY, motionAlerts = tuned))
        val c = component(repo)
        advanceUntilIdle()

        c.onSave()
        advanceUntilIdle()

        val duty = assertNotNull(repo.upserts.single().motionAlerts).single { it.kind == MotionAlertKind.DUTY }
        assertEquals(listOf(77f), duty.levels.map { it.thresholdValue }, "Kelly cannot arm it; the rider still owns it")
    }

    // ------------------------------------------------------------- the sound

    @Test
    fun `the master switch and both modalities write through to prefs`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = AppPrefs(FakePreferencesDataStore(mutablePreferencesOf()))
        val c = component(FakeVehicleRepo(vehicle()), prefs = prefs)
        advanceUntilIdle()

        // Defaults are all on — the alarm must warn a fresh install unconfigured.
        assertTrue(c.state.value.alarmEnabled && c.state.value.toneEnabled && c.state.value.vibrationEnabled)

        c.onAlarmEnabledChanged(false)
        c.onToneEnabledChanged(false)
        c.onVibrationEnabledChanged(false)
        advanceUntilIdle()

        assertFalse(prefs.alarmEnabled.first { !it })
        assertFalse(prefs.alarmToneEnabled.first { !it })
        assertFalse(prefs.alarmVibrationEnabled.first { !it })

        // And the switches move with them: prefs are the source of truth, so the
        // screen mirrors them back rather than keeping a local copy that could
        // drift from what the alarm is actually doing.
        val mirrored = c.state.first { !it.alarmEnabled && !it.toneEnabled && !it.vibrationEnabled }
        assertFalse(mirrored.alarmEnabled)
    }

    /**
     * A rider who turned the alarm off last week must not find it on again — not
     * even for a frame.
     *
     * The assertions before `advanceUntilIdle()` are the point: the prefs
     * collectors have not run yet at that instant, so what they see is the
     * *initial* state alone. Reading the saved value there is what stops the
     * screen painting three "on" switches and then snapping them off.
     */
    @Test
    fun `the screen opens showing the switches as they were saved`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = AppPrefs(
            FakePreferencesDataStore(
                mutablePreferencesOf(
                    booleanPreferencesKey("alarm_enabled") to false,
                    booleanPreferencesKey("alarm_vibration_enabled") to false
                )
            )
        )
        prefs.alarmEnabled.first { !it }
        prefs.alarmVibrationEnabled.first { !it }

        val c = component(FakeVehicleRepo(vehicle()), prefs = prefs)

        // Before any coroutine has run: the initial state alone.
        assertFalse(c.state.value.alarmEnabled, "no frame may show the alarm as on")
        assertFalse(c.state.value.vibrationEnabled)
        assertTrue(c.state.value.toneEnabled, "and the one that was never touched keeps its default")

        advanceUntilIdle()

        assertFalse(c.state.value.alarmEnabled)
        assertFalse(c.state.value.vibrationEnabled)
        assertTrue(c.state.value.toneEnabled)
    }

    /**
     * F §11's answer to the open tone-design question (§9.1): the curve is judged
     * by ear on a real phone, so the button must actually reach the speaker.
     */
    @Test
    fun `check-the-signal plays each configurable step through the holder`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val preview = RecordingPreview()
        val c = component(FakeVehicleRepo(vehicle()), preview = preview)
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 3), c.state.value.previewLevels, "one button per rider-definable step")
        c.state.value.previewLevels.forEach { c.onPreview(it) }

        assertEquals(listOf(1, 2, 3), preview.played)
    }
}
