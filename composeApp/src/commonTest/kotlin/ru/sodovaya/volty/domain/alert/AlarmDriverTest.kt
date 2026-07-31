package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.model.DemoProfile
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
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.stats.MovingAvg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Task 8's wiring: the streams that make the alarm sound.
 *
 * Everything here drives the **real** flows through [AlarmDriver.start] rather
 * than calling a per-tick helper, because half of what this task can get wrong is
 * in the wiring itself — a throttle on the wrong stream, rules hoisted out of the
 * collector, a trigger missing. A test that bypassed `start` could not see any of
 * it.
 *
 * Every test is bounded: `runCurrent()` after each mutation, collectors in
 * `backgroundScope`, and no `delay` anywhere — a delayed loop here would advance
 * virtual time forever and wedge the build instead of failing it.
 */
@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class AlarmDriverTest {

    // ------------------------------------------------------------- fixtures

    private class RecordingAlarm : AlarmOutput {
        val states = mutableListOf<AlarmState>()
        val switches = mutableListOf<AlarmModalities>()
        override fun update(state: AlarmState) { states += state }
        override fun setModalities(modalities: AlarmModalities) { switches += modalities }

        /**
         * The level the speaker was last told to play.
         *
         * Deliberately **throws** when the speaker was never told anything, rather
         * than answering 0: "silent" and "nobody ever spoke to the speaker" are
         * different facts, and folding them together would make every
         * `assertEquals(0, alarm.level)` guard pass against an implementation that
         * emits nothing at all.
         */
        val level: Int get() = states.last().level
    }

    private class StubBmsRepository : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle) = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType) = Result.success(Unit)
        override suspend fun connectDemo(profile: DemoProfile) = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun disconnectLink(address: String) {}
        override fun samples(window: Duration): Flow<List<BmsData>> = emptyFlow()
        override fun motionSamples(window: Duration): Flow<List<ControllerData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> =
            flowOf(MovingAvg(0f, 0f, window))
        override suspend fun onAppResumed() {}
    }

    private fun vehicle(
        vararg rules: AlertRule,
        id: String = "v1",
        controllerType: ControllerType = ControllerType.VESC
    ) = Vehicle(
        id = id,
        name = "Test",
        iconKey = "generic",
        packs = emptyList(),
        controllers = listOf(
            Controller(index = 0, label = "ESC", controllerType = controllerType, address = "AA")
        ),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L),
        motionAlerts = rules.toList()
    )

    /** A battery-only vehicle: no controller, so no motion source and nothing armable. */
    private fun vehicleWithoutController(vararg rules: AlertRule) = singlePackVehicle(
        id = "v0",
        name = "Battery only",
        iconKey = "generic",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "BB",
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    ).copy(motionAlerts = rules.toList())

    /**
     * A live motion sample. Temperatures default cold, so a test that raises one
     * is the only thing that can sound; `escTempC` stays above the "no sensor"
     * sentinel so `hasEscTemp` is true unless a test says otherwise.
     */
    private fun motion(
        dutyPercent: Float = 0f,
        speedKmh: Float = 0f,
        speedSource: SpeedSource = SpeedSource.REPORTED,
        motorTempC: Float = 20f,
        escTempC: Float = 20f,
        hasMotorTemp: Boolean = true,
        isConnected: Boolean = true
    ) = ControllerData(
        speedKmh = speedKmh,
        speedSource = speedSource,
        dutyPercent = dutyPercent,
        escTempC = escTempC,
        motorTempC = motorTempC,
        hasMotorTemp = hasMotorTemp,
        isConnected = isConnected
    )

    private fun rule(kind: MotionAlertKind, vararg thresholds: Float) =
        AlertRule(kind, thresholds.map { AlertLevel(it) })

    /** Start a driver on [repo] and settle the initial emissions. */
    private fun TestScope.drive(
        repo: StubBmsRepository,
        modalities: Flow<AlarmModalities> = MutableStateFlow(AlarmModalities.DEFAULT),
        alarm: RecordingAlarm = RecordingAlarm()
    ): RecordingAlarm {
        // backgroundScope: the collector never completes, and runTest cancels it
        // rather than waiting for it.
        AlarmDriver(repo, modalities, alarm).start(backgroundScope)
        runCurrent()
        return alarm
    }

    /** A repository already riding: a live link and the given vehicle selected. */
    private fun ridingOn(vehicle: Vehicle) = StubBmsRepository().apply {
        activeVehicle.value = vehicle
        connectionState.value = ConnectionState.Connected(vehicle)
    }

    // -------------------------------------------------- every sample, at once

    /**
     * F §5 and the brief's second requirement. The live notification in the same
     * service is `sample(2.seconds)`-throttled; putting the alarm behind the same
     * throttle would spend most of the warning a duty spike gives.
     *
     * `runCurrent()` runs what is already runnable and advances virtual time by
     * nothing at all, so a throttled path emits nothing here and the assertion
     * fails.
     */
    @Test
    fun a_duty_spike_sounds_on_the_sample_it_arrives_with_no_throttle_window() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f, 90f)))
        val alarm = drive(repo)
        assertEquals(0, alarm.level, "fixture guard: nothing is sounding before the spike")

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()

        assertEquals(2, alarm.level, "a duty spike must reach the speaker on its own sample, not two seconds later")
    }

    // ------------------------------------------ the recompute, in both senses

    /**
     * **The single most likely way this task fails silently** — `AlarmController`'s
     * KDoc says so, and Task 6 hit the same trap.
     *
     * Motor temperature is `AlertAvailability.Unknown` until a sample proves the
     * thermistor exists, and `armedRules` drops Unknown. So a driver that computes
     * its rules once — at start-up, when the only sample is the disconnected
     * placeholder — arms no temperature alert for the whole ride and nothing ever
     * reports it.
     */
    @Test
    fun a_temperature_alert_arms_on_the_sample_that_proves_the_sensor_exists() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.MOTOR_TEMP, 100f)))
        val alarm = drive(repo)
        assertEquals(
            AlertAvailability.Unknown,
            availabilityFor(repo.activeVehicle.value!!, null)[MotionAlertKind.MOTOR_TEMP],
            "fixture guard: motor temperature really is Unknown before any sample, or this test proves nothing"
        )

        repo.activeMotion.value = motion(motorTempC = 120f, hasMotorTemp = true)
        runCurrent()

        assertEquals(
            1, alarm.level,
            "the rules must be re-derived from this sample: computed once at start-up they arm nothing"
        )
    }

    /**
     * The other half of the same obligation, and the one a vehicle-keyed cache
     * would still get wrong: nothing about the *vehicle* changes here, only what
     * the hardware turns out to report.
     */
    @Test
    fun a_speed_source_appearing_mid_ride_arms_the_speed_alert() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.SPEED, 40f)))
        val alarm = drive(repo)

        repo.activeMotion.value = motion(speedKmh = 60f, speedSource = SpeedSource.NONE)
        runCurrent()
        assertEquals(
            0, alarm.level,
            "fixture guard: with no speed source the alert is Unavailable however fast the reading says it is going"
        )

        repo.activeMotion.value = motion(speedKmh = 60f, speedSource = SpeedSource.REPORTED)
        runCurrent()
        assertEquals(1, alarm.level, "the same reading, now backed by a source, must arm")
    }

    /** The vehicle can change under a running service; its thresholds must follow. */
    @Test
    fun swapping_the_active_vehicle_applies_the_new_vehicle_s_thresholds() = runTest {
        val tolerant = vehicle(rule(MotionAlertKind.DUTY, 95f), id = "tolerant")
        val strict = vehicle(rule(MotionAlertKind.DUTY, 50f), id = "strict")
        val repo = ridingOn(tolerant)
        val alarm = drive(repo)

        repo.activeMotion.value = motion(dutyPercent = 70f)
        runCurrent()
        assertEquals(0, alarm.level, "fixture guard: 70 % is under the tolerant vehicle's only step")

        repo.activeVehicle.value = strict
        runCurrent()
        assertEquals(1, alarm.level, "the new vehicle's thresholds apply to the reading already in hand")
    }

    /**
     * Availability is a fact and no configuration may override it (F §10): a Kelly
     * controller reports no duty, so a duty rule the rider tuned on other hardware
     * must stay silent rather than threshold against a field that is always zero.
     */
    @Test
    fun a_controller_that_reports_no_duty_never_sounds_a_duty_alarm() = runTest {
        val repo = ridingOn(
            vehicle(rule(MotionAlertKind.DUTY, 80f), controllerType = ControllerType.KELLY)
        )
        val alarm = drive(repo)

        repo.activeMotion.value = motion(dutyPercent = 99f)
        runCurrent()

        assertEquals(0, alarm.level, "the rules must go through armedRules, not straight from the rider's config")
    }

    @Test
    fun a_vehicle_with_no_controller_arms_nothing() = runTest {
        val repo = StubBmsRepository().apply {
            activeVehicle.value = vehicleWithoutController(rule(MotionAlertKind.DUTY, 80f))
            connectionState.value = ConnectionState.Connected(activeVehicle.value)
        }
        val alarm = drive(repo)

        repo.activeMotion.value = motion(dutyPercent = 99f)
        runCurrent()

        assertEquals(0, alarm.level, "no motion source means no motion alert, whatever the sample says")
    }

    // ---------------------------------------------------- one engine, not many

    /**
     * The driver must hold **one** [AlarmController] across samples, not build one
     * per tick: hysteresis is its only state, and a fresh engine every sample would
     * release every step the instant the reading dipped, chattering exactly where
     * the release band exists to stop it.
     */
    @Test
    fun a_step_keeps_holding_across_samples_through_its_release_band() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f)))
        val alarm = drive(repo)

        repo.activeMotion.value = motion(dutyPercent = 81f)
        runCurrent()
        assertEquals(1, alarm.level, "fixture guard: the step engages on the way up")

        // 79 is below the threshold but inside the 3 pp release band, so a driver
        // that remembers anything at all still holds step 1.
        repo.activeMotion.value = motion(dutyPercent = 79f)
        runCurrent()
        assertEquals(1, alarm.level, "one engine across samples: a fresh one per sample would have let go here")
    }

    // ------------------------------------------------------------- going quiet

    @Test
    fun a_disconnected_sample_silences_a_sounding_alarm() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f)))
        val alarm = drive(repo)

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        assertEquals(1, alarm.level, "fixture guard: sounding before the link goes")

        repo.activeMotion.value = ControllerData()
        runCurrent()
        assertEquals(0, alarm.level, "a placeholder is not a measurement — the alarm stops")
    }

    /**
     * The drop is not the end of the ride. Attack is immediate and only release is
     * damped, so a reconnect after a pothole warns again on its very first reading
     * — no service restart, no re-arming delay.
     */
    @Test
    fun the_first_reading_after_a_dropout_re_arms_immediately() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f)))
        val alarm = drive(repo)

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        repo.activeMotion.value = ControllerData()
        runCurrent()
        assertEquals(0, alarm.level, "fixture guard: silent across the gap")

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        assertEquals(1, alarm.level, "the condition is still true, so the very next reading sounds again")
    }

    @Test
    fun losing_the_active_vehicle_silences_the_alarm() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f)))
        val alarm = drive(repo)

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        assertEquals(1, alarm.level, "fixture guard: sounding while the vehicle is selected")

        repo.activeVehicle.value = null
        runCurrent()
        assertEquals(
            0, alarm.level,
            "with no vehicle there is no configuration and no availability, so nothing may stay armed"
        )
    }

    /**
     * The independent belt, and the case it exists for.
     *
     * `activeMotion` silencing is a *push*, and **a dropped link does not push**:
     * `onLinkDrop` marks the link reconnecting and starts an unbounded retry loop
     * without writing `activeMotion`, which only ever changes when a sample is
     * submitted. So the last hot reading sits in the StateFlow indefinitely and
     * mechanism 1 can never fire. Nothing in this test touches `activeMotion`
     * after the alarm starts sounding, because in the real failure nothing does.
     *
     * Every state but `Connected` is in this list, and that is exactly right
     * rather than merely cautious: `refoldConnectionStateLocked` answers
     * `Connected` whenever **any** link is online, so each of these is reachable
     * only when nothing at all is up. `Reconnecting` is the one that matters — a
     * rider at 95 % duty whose wheel drops out sits in that state for as long as
     * the retry loop runs, which is forever.
     */
    @Test
    fun every_state_but_connected_silences_the_alarm() = runTest {
        val dead = listOf(
            ConnectionState.Idle,
            ConnectionState.Scanning,
            ConnectionState.Connecting(null),
            ConnectionState.Disconnected,
            ConnectionState.Reconnecting(attempt = 1, reason = "link lost"),
            ConnectionState.Failed("out of range")
        )
        for (state in dead) {
            val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f)))
            val alarm = drive(repo)
            repo.activeMotion.value = motion(dutyPercent = 95f)
            runCurrent()
            assertEquals(1, alarm.level, "fixture guard for $state: the alarm must be sounding first")

            repo.connectionState.value = state
            runCurrent()

            assertEquals(0, alarm.level, "$state means no link can deliver a reading, so the alarm must stop")
        }
    }

    /**
     * The other direction: the belt must not fire while a link really is up, or a
     * `Connected → Connected(otherVehicle)` re-emission would chop a live alarm
     * into fragments. Silence is owed to a dead link, not to every state change.
     */
    @Test
    fun a_connected_link_never_silences_on_the_state_alone() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f)))
        val alarm = drive(repo)
        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        assertEquals(1, alarm.level, "fixture guard: the alarm must be sounding first")

        repo.connectionState.value = ConnectionState.Connected(null)
        runCurrent()

        assertEquals(1, alarm.level, "a link that is up keeps delivering, so the state alone silences nothing")
    }

    /**
     * The reconnect case end to end, stated as the rider experiences it. Separate
     * from the loop above because this is the scenario the belt was written for
     * and it deserves to fail by name.
     */
    @Test
    fun a_link_dropping_mid_alarm_silences_it_even_though_no_sample_follows() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f)))
        val alarm = drive(repo)
        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        assertEquals(1, alarm.level, "fixture guard: 95 % duty, alarm sounding, phone in a pocket")

        // Exactly what onLinkDrop does: the state moves and `activeMotion` is left
        // holding the 95 % reading, because no sample was submitted. Nothing below
        // touches it — that stale hot value staying put IS the hazard, and
        // asserting it would only restate a line this test never executes.
        repo.connectionState.value = ConnectionState.Reconnecting(attempt = 1, reason = "link lost")
        runCurrent()

        assertEquals(0, alarm.level, "the tone must not run in the rider's pocket until the link happens to return")
    }

    // ----------------------------------------------------------- the switches

    /**
     * F §4's master switch has to silence an alarm that is *already* sounding, not
     * merely prevent the next one — which means the driver cannot wait for the next
     * telemetry frame to pass the switches on. On a parked vehicle that frame never
     * comes.
     */
    @Test
    fun a_switch_flipped_mid_alarm_reaches_the_speaker_without_waiting_for_a_sample() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f)))
        val switches = MutableStateFlow(AlarmModalities.DEFAULT)
        val alarm = drive(repo, modalities = switches)

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        assertEquals(1, alarm.level, "fixture guard: sounding when the rider reaches for the switch")
        val before = alarm.states.size

        switches.value = AlarmModalities(alarmEnabled = false)
        runCurrent()

        assertEquals(
            AlarmModalities(alarmEnabled = false),
            alarm.switches.last(),
            "the master switch must reach the speaker the moment it moves"
        )
        assertEquals(
            before, alarm.states.size,
            "and it must not be smuggled in as a fake sample — the engine saw no new reading"
        )
    }

    @Test
    fun every_switch_change_is_forwarded_in_order() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f)))
        val switches = MutableStateFlow(AlarmModalities.DEFAULT)
        val alarm = drive(repo, modalities = switches)

        switches.value = AlarmModalities(toneEnabled = false)
        runCurrent()
        switches.value = AlarmModalities(vibrationEnabled = false)
        runCurrent()

        assertEquals(
            listOf(
                AlarmModalities.DEFAULT,
                AlarmModalities(toneEnabled = false),
                AlarmModalities(vibrationEnabled = false)
            ),
            alarm.switches,
            "the rider's switches arrive at the speaker, in the order they were flipped"
        )
    }

    // -------------------------------------------------------- one collector

    /**
     * [AlarmController] is mutable and not thread-safe, and this runs on
     * `Dispatchers.Default`. Task 6's review measured the harm on exactly this
     * shape: two `scope.launch` collectors over shared unsynchronised state
     * produced corruption in 10/10 runs. The four triggers must therefore share
     * one collector, and this counts them.
     *
     * The silence requests are the newest of the four and the one with the most
     * obvious shortcut: it arrives from a `BroadcastReceiver` on the main thread,
     * and flipping a flag on [AlarmSilencer] from there would race the collector
     * exactly as a second `launch` would.
     */
    @Test
    fun the_four_triggers_share_exactly_one_collector() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            AlarmDriver(
                StubBmsRepository(),
                MutableStateFlow(AlarmModalities.DEFAULT),
                RecordingAlarm()
            ).start(scope)

            assertEquals(
                1, scope.coroutineContext.job.children.count(),
                "samples, switches, link state and silences must be merged into one collector, not launched separately"
            )
        } finally {
            scope.cancel()
        }
    }

    // ------------------------------------------------------------- "Заглушить"

    /**
     * **The scenario the button exists for** (F §14). A rider at 92 % duty whose
     * *controller* link dies on a vehicle whose BMS keeps delivering: the
     * vehicle-level state stays `Connected`, `activeMotion` keeps carrying the
     * frozen hot reading with `isConnected = true`, and both of the driver's stop
     * mechanisms are defeated. Step 2 then plays on `USAGE_ALARM` — through silent
     * mode and DND — for the rest of the ride, and the only escape before this
     * was the notification's `Disconnect`, which ends the session.
     *
     * The frozen link is modelled exactly: the readings that keep arriving are
     * the same danger over again. The silence has to survive them, and the
     * session has to survive the silence.
     */
    @Test
    fun the_notification_action_silences_the_running_alarm_without_ending_the_ride() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f, 90f)))
        val alarm = RecordingAlarm()
        val driver = AlarmDriver(repo, MutableStateFlow(AlarmModalities.DEFAULT), alarm)
        driver.start(backgroundScope)
        runCurrent()

        repo.activeMotion.value = motion(dutyPercent = 92f)
        runCurrent()
        assertEquals(2, alarm.level, "fixture guard: the alarm is sounding")

        driver.silence()
        runCurrent()
        assertEquals(0, alarm.level, "the press must reach the speaker at once — a frozen link sends no next sample")

        // The frozen reading arrives again, a hair different so the StateFlow
        // actually emits. It is the same danger and must stay silenced.
        repo.activeMotion.value = motion(dutyPercent = 92.5f)
        runCurrent()
        assertEquals(0, alarm.level, "the next sample re-raised it — the button is useless in the case it exists for")

        assertEquals(
            ConnectionState.Connected(repo.activeVehicle.value!!), repo.connectionState.value,
            "and the session is untouched: silencing is not disconnecting"
        )
    }

    /**
     * A silence is not a disarm. Once the engine recovers on its own the
     * suppression lifts, so the **next** danger sounds normally — otherwise the
     * rider would finish the ride with no duty alarm at all.
     */
    @Test
    fun a_silenced_alarm_re_arms_once_the_reading_recovers() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f, 90f)))
        val alarm = RecordingAlarm()
        val driver = AlarmDriver(repo, MutableStateFlow(AlarmModalities.DEFAULT), alarm)
        driver.start(backgroundScope)
        runCurrent()

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        driver.silence()
        runCurrent()
        assertEquals(0, alarm.level)

        repo.activeMotion.value = motion(dutyPercent = 10f)
        runCurrent()
        assertEquals(0, alarm.level, "fixture guard: a calm reading is silent either way")

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        assertEquals(2, alarm.level, "a fresh danger after a recovery must be audible")
    }

    /**
     * The other re-arm, and the one that matters on a dropout the app *can* see:
     * a link state that is not `Connected` resets the engine, and the level-0
     * state that produces lifts the silence on its way to the speaker.
     */
    @Test
    fun a_link_the_app_can_see_drop_re_arms_a_silenced_alarm() = runTest {
        val v = vehicle(rule(MotionAlertKind.DUTY, 80f, 90f))
        val repo = ridingOn(v)
        val alarm = RecordingAlarm()
        val driver = AlarmDriver(repo, MutableStateFlow(AlarmModalities.DEFAULT), alarm)
        driver.start(backgroundScope)
        runCurrent()

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        driver.silence()
        runCurrent()

        repo.connectionState.value = ConnectionState.Reconnecting(attempt = 1, reason = "link dropped")
        runCurrent()
        repo.connectionState.value = ConnectionState.Connected(v)
        runCurrent()

        repo.activeMotion.value = motion(dutyPercent = 96f)
        runCurrent()
        assertEquals(2, alarm.level, "a visible dropout must leave the alarm armed for what comes next")
    }

    /**
     * **A silence in force has to be visible**, because the one thing it looks
     * like from the saddle is nothing being wrong.
     *
     * `AlarmSilencer` concedes that on the frozen-link case the button exists for
     * the level never returns to 0, so the suppression can last the rest of the
     * ride: a rider who silences a stuck duty alarm at km 5 arrives at km 40
     * having had no duty alarm at all. The only cue is an alarm that never
     * sounds. This flow is what the live notification reads to say so.
     *
     * All three phases in one test on purpose — a flag that is set but never
     * cleared reads exactly like a correct one until the re-arm is asserted.
     */
    @Test
    fun the_silenced_state_is_published_while_it_is_in_force_and_withdrawn_when_it_lifts() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f, 90f)))
        val alarm = RecordingAlarm()
        val driver = AlarmDriver(repo, MutableStateFlow(AlarmModalities.DEFAULT), alarm)
        driver.start(backgroundScope)
        runCurrent()
        assertEquals(false, driver.isSilenced.value, "nothing is silenced before anything is pressed")

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        assertEquals(2, alarm.level, "fixture guard: the alarm is sounding")
        assertEquals(false, driver.isSilenced.value, "a sounding alarm is not a silenced one")

        driver.silence()
        runCurrent()
        assertEquals(true, driver.isSilenced.value, "the press must be visible at once, not on some later sample")

        // The frozen reading arrives again: the danger has not gone away, so
        // neither has the suppression, and the rider must still be told.
        repo.activeMotion.value = motion(dutyPercent = 95.5f)
        runCurrent()
        assertEquals(
            true, driver.isSilenced.value,
            "the suppression outlives the samples that re-raise it — that IS the thing worth showing"
        )

        repo.activeMotion.value = motion(dutyPercent = 10f)
        runCurrent()
        assertEquals(
            false, driver.isSilenced.value,
            "and it must be withdrawn the moment the engine recovers, or the notification lies for the rest of the ride"
        )
    }

    /**
     * The other re-arm, published as well as applied: a link the app can see drop
     * lifts the silence, and a notification still saying "Silenced" afterwards
     * would tell the rider they have no duty alarm when they do.
     */
    @Test
    fun a_visible_dropout_withdraws_the_published_silence_too() = runTest {
        val v = vehicle(rule(MotionAlertKind.DUTY, 80f, 90f))
        val repo = ridingOn(v)
        val alarm = RecordingAlarm()
        val driver = AlarmDriver(repo, MutableStateFlow(AlarmModalities.DEFAULT), alarm)
        driver.start(backgroundScope)
        runCurrent()

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        driver.silence()
        runCurrent()
        assertEquals(true, driver.isSilenced.value, "fixture guard: a silence really is in force")

        repo.connectionState.value = ConnectionState.Reconnecting(attempt = 1, reason = "link dropped")
        runCurrent()

        assertEquals(false, driver.isSilenced.value, "the belt re-armed the alarm; the notification must follow")
    }

    /**
     * Pressing it with nothing sounding must not bank a silence for later — that
     * would be a disarm, and the master switch is the disarm.
     */
    @Test
    fun silencing_a_quiet_alarm_does_not_swallow_the_next_one() = runTest {
        val repo = ridingOn(vehicle(rule(MotionAlertKind.DUTY, 80f, 90f)))
        val alarm = RecordingAlarm()
        val driver = AlarmDriver(repo, MutableStateFlow(AlarmModalities.DEFAULT), alarm)
        driver.start(backgroundScope)
        runCurrent()
        assertEquals(0, alarm.level, "fixture guard: nothing is sounding")

        driver.silence()
        runCurrent()

        repo.activeMotion.value = motion(dutyPercent = 95f)
        runCurrent()
        assertEquals(2, alarm.level, "a silence pressed against silence must not mute the next real alarm")
    }
}
