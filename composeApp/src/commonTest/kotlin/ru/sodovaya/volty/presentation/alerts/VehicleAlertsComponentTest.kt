package ru.sodovaya.volty.presentation.alerts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
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
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.alert.AlarmCommand
import ru.sodovaya.volty.domain.alert.alarmPreviewCommand
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
        override suspend fun connectDemo(profile: DemoProfile): Result<Unit> = Result.success(Unit)
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
        // Explicit, because VehicleRepository.updateGaugePeaks is abstract: no fake gets a
        // silent default. Nothing in this file rides a learned dial range (G §9.2).
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}

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

        /**
         * What is on "disk" right now.
         *
         * Asserted against instead of `AppPrefs`' StateFlows because those are
         * `stateIn`-ed on `Dispatchers.Default`, which the test scheduler does not
         * drive: their value after `advanceUntilIdle()` is a race, whereas the
         * write itself runs on the component's (test) dispatcher and is settled.
         */
        val current: Preferences get() = state.value
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
        onBack: () -> Unit = {},
        /**
         * The system back gesture's dispatcher. Supplied only by the two tests
         * that press it; everywhere else Decompose's default is fine.
         */
        backDispatcher: BackDispatcher? = null
    ) = DefaultVehicleAlertsComponent(
        componentContext = DefaultComponentContext(lifecycle, backHandler = backDispatcher),
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

    // ------------------------------------------------- what an idle screen is

    /**
     * Deferred item 9, closed. A vehicle whose `motionAlerts` is null opens on
     * the shipped defaults, so an idle tap on Save would **materialise** them
     * onto it — null becomes non-null — and every later change to the shipped
     * numbers would stop reaching that vehicle, silently and for ever.
     *
     * Opening a screen and touching nothing must not pin anything.
     */
    @Test
    fun `opening a never-configured vehicle and touching nothing leaves Save disarmed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(vehicle(motionAlerts = null))
        val c = component(repo)
        advanceUntilIdle()

        assertTrue(c.state.value.loaded, "fixture check: the screen has actually opened")
        assertTrue(
            c.state.value.kinds.any { it.levels.isNotEmpty() },
            "fixture check: it opened on the shipped defaults, which is what an idle save would pin"
        )
        assertFalse(c.state.value.isDirty, "showing the defaults is not editing them")
        assertFalse(c.state.value.canSave)

        c.onSave()
        advanceUntilIdle()
        assertTrue(repo.upserts.isEmpty(), "an idle tap must not write the defaults onto the vehicle")
    }

    /** And one keystroke arms it — the dirty check must not be a Save button that never works. */
    @Test
    fun `one edit arms Save and saving disarms it again`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(vehicle())
        val c = component(repo)
        advanceUntilIdle()

        c.onThresholdChanged(MotionAlertKind.DUTY, 0, "85")
        assertTrue(c.state.value.isDirty)
        assertTrue(c.state.value.canSave)

        c.onSave()
        advanceUntilIdle()

        assertEquals(1, repo.upserts.size)
        assertFalse(c.state.value.isDirty, "what was just written is not still outstanding")
        assertFalse(c.state.value.canSave)
    }

    /**
     * Availability is re-derived whenever a link comes up mid-edit. If the dirty
     * check compared the whole draft, connecting would mark an untouched screen
     * edited — and then backing out of it would ask about edits nobody made,
     * which is how a confirmation dialog gets trained away.
     */
    @Test
    fun `connecting mid-view does not make an untouched screen dirty`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(vehicle()), bms)
        advanceUntilIdle()
        assertFalse(c.state.value.isDirty)

        bms.activeMotion.value = ControllerData(
            speedKmh = 10f,
            speedSource = SpeedSource.REPORTED,
            motorTempC = 40f,
            hasMotorTemp = true,
            isConnected = true
        )
        advanceUntilIdle()

        assertIs<AlertAvailability.Available>(
            c.state.value.kinds.single { it.kind == MotionAlertKind.MOTOR_TEMP }.availability,
            "fixture check: the sample really did change availability, or this proves nothing"
        )
        assertFalse(c.state.value.isDirty, "a sensor appearing is not the rider editing")
    }

    /** The stash is an undo buffer, so a switch flipped off and back on is not an edit. */
    @Test
    fun `toggling a kind off and back on again is not an edit`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeVehicleRepo(vehicle()))
        advanceUntilIdle()

        c.onKindEnabledChanged(MotionAlertKind.DUTY, false)
        assertTrue(c.state.value.isDirty, "turning it off is certainly an edit")
        c.onKindEnabledChanged(MotionAlertKind.DUTY, true)

        assertFalse(c.state.value.isDirty, "and putting it back leaves nothing to save")
    }

    // ------------------------------------------------------- leaving the screen

    /**
     * The nav slot is a `Cancel` button and the thresholds live only in memory
     * until Save, so backing out used to drop them without a word — while the
     * three switches above them had been saving instantly all along, which makes
     * the screen look like it saves as you go.
     */
    @Test
    fun `backing out of unsaved threshold edits asks instead of dropping them`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var backed = false
        val c = component(FakeVehicleRepo(vehicle()), onBack = { backed = true })
        advanceUntilIdle()

        c.onThresholdChanged(MotionAlertKind.DUTY, 0, "85")
        c.onBack()

        assertTrue(c.state.value.discardPrompt, "the rider must be told there is something to lose")
        assertFalse(backed, "and must not have left yet")

        c.onDiscardDismissed()
        assertFalse(c.state.value.discardPrompt)
        assertFalse(backed, "dismissing keeps them on the screen with their edits")
        assertEquals("85", c.state.value.kinds.single { it.kind == MotionAlertKind.DUTY }.levels.first().text)

        c.onBack()
        c.onDiscardConfirmed()
        assertTrue(backed, "and confirming really does leave")
        assertFalse(c.state.value.discardPrompt)
    }

    /** With nothing edited it must not nag: a prompt on every exit is a prompt nobody reads. */
    @Test
    fun `backing out of an untouched screen leaves at once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var backed = false
        val c = component(FakeVehicleRepo(vehicle()), onBack = { backed = true })
        advanceUntilIdle()

        c.onBack()

        assertFalse(c.state.value.discardPrompt)
        assertTrue(backed)
    }

    /**
     * **The way most Android riders actually leave a screen**, and the half the
     * discard prompt used to miss entirely.
     *
     * `RootComponent` builds its stack with `handleBackButton = true`, so an edge
     * swipe pops without consulting this component at all: the rider typed 85
     * into a duty threshold, swiped, and the edit was gone with no dialog. The
     * app bar's `Cancel` was guarded; the gesture nobody uses a button for was
     * not.
     *
     * `BackDispatcher.back()` returning false is the shape of that regression —
     * it means nothing here registered a callback and the stack would have popped
     * — so it is asserted rather than ignored.
     */
    @Test
    fun `the system back gesture raises the discard prompt just as the Cancel button does`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val back = BackDispatcher()
        var backed = false
        val c = component(FakeVehicleRepo(vehicle()), onBack = { backed = true }, backDispatcher = back)
        advanceUntilIdle()

        c.onThresholdChanged(MotionAlertKind.DUTY, 0, "85")
        assertTrue(c.state.value.isDirty, "fixture check: there is an edit to lose, or this proves nothing")

        assertTrue(back.back(), "the gesture must be intercepted — unhandled, Decompose pops the stack")

        assertTrue(c.state.value.discardPrompt, "the rider must be told there is something to lose")
        assertFalse(backed, "and must not have left yet")

        c.onDiscardDismissed()
        assertEquals(
            "85",
            c.state.value.kinds.single { it.kind == MotionAlertKind.DUTY }.levels.first().text,
            "staying keeps the edit the swipe would have dropped"
        )
    }

    /**
     * And the gesture is the *same* path, not a second one: with nothing to lose
     * it leaves at once, exactly as the button does. A callback that always
     * prompted would train the dialog away.
     */
    @Test
    fun `the system back gesture on an untouched screen leaves at once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val back = BackDispatcher()
        var backed = false
        val c = component(FakeVehicleRepo(vehicle()), onBack = { backed = true }, backDispatcher = back)
        advanceUntilIdle()

        assertFalse(c.state.value.isDirty, "fixture check: nothing has been edited")
        assertTrue(back.back(), "the callback is registered whether or not there is anything to lose")

        assertFalse(c.state.value.discardPrompt)
        assertTrue(backed, "an untouched screen must not stop the rider leaving")
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
        // An edit is what arms Save now (State.canSave is dirty-aware), so the
        // rider has to have changed something for there to be a save to re-read
        // the vehicle for.
        c.onThresholdChanged(MotionAlertKind.DUTY, 0, "85")
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
        c.onThresholdChanged(MotionAlertKind.DUTY, 0, "85") // Save is armed by an edit — see canSave.
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

        // The save has to be armed by an edit (canSave is dirty-aware), and it
        // has to be an edit to a kind this hardware *can* supply — which is the
        // shape of the case anyway: the rider came here to change something else
        // and the untouchable kind's numbers have to ride along unharmed.
        c.onThresholdChanged(MotionAlertKind.ESC_TEMP, 0, "95")
        c.onSave()
        advanceUntilIdle()

        val duty = assertNotNull(repo.upserts.single().motionAlerts).single { it.kind == MotionAlertKind.DUTY }
        assertEquals(listOf(77f), duty.levels.map { it.thresholdValue }, "Kelly cannot arm it; the rider still owns it")
    }

    // ------------------------------------------------------------- the sound

    @Test
    fun `the master switch and both modalities write through to prefs`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = FakePreferencesDataStore(mutablePreferencesOf())
        val prefs = AppPrefs(store)
        val c = component(FakeVehicleRepo(vehicle()), prefs = prefs)
        advanceUntilIdle()

        // Defaults are all on — the alarm must warn a fresh install unconfigured.
        assertTrue(c.state.value.alarmEnabled && c.state.value.toneEnabled && c.state.value.vibrationEnabled)

        c.onAlarmEnabledChanged(false)
        c.onToneEnabledChanged(false)
        c.onVibrationEnabledChanged(false)
        advanceUntilIdle()

        // `assertFalse(prefs.alarmEnabled.first { !it })` used to stand here, three
        // times over: `first { !it }` returns `false` by construction, so all
        // three passed for every implementation — a no-op `onAlarmEnabledChanged`
        // failed only by `runTest` timing out, which is a wedge and not a
        // verdict. These read what was actually written, under the keys it has to
        // be written under for the setting to survive a restart.
        assertEquals(false, store.current[booleanPreferencesKey("alarm_enabled")], "the master switch never reached the store")
        assertEquals(false, store.current[booleanPreferencesKey("alarm_tone_enabled")], "the tone switch never reached the store")
        assertEquals(false, store.current[booleanPreferencesKey("alarm_vibration_enabled")], "the vibration switch never reached the store")

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
        assertTrue(c.state.value.canPreview, "with everything on, the buttons must be live")
        c.state.value.previewLevels.forEach { c.onPreview(it) }

        assertEquals(listOf(1, 2, 3), preview.played)
    }

    /**
     * `AlarmSignal.kt`'s instruction, unimplemented until now: with the master
     * switch **on** but tone and vibration both **off**, `alarmCommandFor`
     * returns `Silent` and the platform plans nothing. A live button that
     * provably cannot make a sound teaches the rider that the alarm is broken —
     * the one conclusion this screen must never invite.
     */
    @Test
    fun `the preview buttons go dead when tone and vibration are both off`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = AppPrefs(
            FakePreferencesDataStore(
                mutablePreferencesOf(
                    booleanPreferencesKey("alarm_tone_enabled") to false,
                    booleanPreferencesKey("alarm_vibration_enabled") to false
                )
            )
        )
        prefs.alarmToneEnabled.first { !it }
        prefs.alarmVibrationEnabled.first { !it }
        val c = component(FakeVehicleRepo(vehicle()), prefs = prefs)
        advanceUntilIdle()

        assertTrue(c.state.value.alarmEnabled, "fixture check: the master switch is on, so this is not that")
        assertIs<AlarmCommand.Silent>(
            alarmPreviewCommand(1, c.state.value.modalities),
            "fixture check: the gate really does return Silent here, or the button has something to play"
        )
        assertFalse(c.state.value.canPreview, "a button that cannot make a sound must not look live")
    }

    /** One modality is enough — a rider who kept only the buzz still gets to feel it. */
    @Test
    fun `vibration alone still previews`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = AppPrefs(
            FakePreferencesDataStore(mutablePreferencesOf(booleanPreferencesKey("alarm_tone_enabled") to false))
        )
        prefs.alarmToneEnabled.first { !it }
        val c = component(FakeVehicleRepo(vehicle()), prefs = prefs)
        advanceUntilIdle()

        assertFalse(c.state.value.toneEnabled, "fixture check: tones really are off")
        assertTrue(c.state.value.canPreview)
    }

    /** And the master switch still wins over both, exactly as it does for a live alarm. */
    @Test
    fun `the master switch alone is enough to kill the preview`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = AppPrefs(
            FakePreferencesDataStore(mutablePreferencesOf(booleanPreferencesKey("alarm_enabled") to false))
        )
        prefs.alarmEnabled.first { !it }
        val c = component(FakeVehicleRepo(vehicle()), prefs = prefs)
        advanceUntilIdle()

        assertTrue(c.state.value.toneEnabled && c.state.value.vibrationEnabled, "fixture check: only the master is off")
        assertFalse(c.state.value.canPreview)
    }
}
