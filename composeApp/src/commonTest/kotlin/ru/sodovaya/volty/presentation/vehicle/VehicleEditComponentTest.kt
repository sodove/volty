package ru.sodovaya.volty.presentation.vehicle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.motionAlertRules
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
        secondaryGauge = SecondaryGauge.POWER,
        yieldBmsToHeadUnit = false,
        // Every kind deliberately silenced (F §10.2: an empty level list is the
        // only way to say "off"). Non-null, so it is an ANSWER — and a
        // non-default one, which is what makes a dropped field visible below.
        motionAlerts = MotionAlertKind.entries.map { AlertRule(it, emptyList()) }
    )

    /**
     * Zero packs, one controller — legal since Part A, and the shape Tasks 3-5
     * will start creating. Same id as [existingVehicle] so the shared
     * [component] helper loads it.
     */
    private fun controllerOnlyVehicle() = Vehicle(
        id = "v1",
        name = "Scooter",
        iconKey = "scooter",
        packs = emptyList(),
        controllers = originalControllers,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now()
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

    /**
     * **The structural guard. Read this before adding a field to [Vehicle].**
     *
     * `onSave()` does not update the stored vehicle — it *rebuilds* one through
     * `singlePackVehicle()`, which knows only the fields it was written for, and
     * then hand-copies back the ones the form does not expose. Every field added
     * to [Vehicle] since has had to be added to that copy list by hand, and each
     * time it was forgotten the symptom was the same: a rider changes their
     * vehicle's name and loses a setting somewhere else entirely.
     *
     * So this asserts the whole object. Loading a vehicle and saving it with
     * nothing edited must be an identity — not "equal in the fields someone
     * remembered to check". A new field dropped by `onSave()` fails here
     * **provided [existingVehicle] gives it a non-default value**, which is the
     * one thing a new field's author must remember to do.
     */
    @Test
    fun `saving with nothing edited is an identity on the whole vehicle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val original = existingVehicle()
        val repo = FakeVehicleRepo(listOf(original))
        val c = component(repo)
        advanceUntilIdle()

        c.onSave()
        advanceUntilIdle()

        assertEquals(
            original,
            repo.upserts.single(),
            "a save with no edits must change nothing — see this test's doc"
        )
    }

    /**
     * The same bug at the sharpest point it can bite, spelled out because
     * `motionAlerts` is the one field where *losing* it is worse than losing a
     * value: null does not mean "no alerts", it means "never configured", and
     * the repository answers that with [AlarmDefaults]. Dropping it therefore
     * does not silently forget the rider's choice — it silently reverses it, and
     * an alarm they switched off starts sounding again after they renamed the
     * vehicle.
     */
    @Test
    fun `an unrelated edit does not resurrect an alarm the rider silenced`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        // Something completely unrelated, from a screen that cannot even see
        // the motion alerts.
        c.onSecondaryGaugeChanged(SecondaryGauge.BATTERY)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(SecondaryGauge.BATTERY, saved.secondaryGauge, "the edit itself must land")
        assertNotNull(
            saved.motionAlerts,
            "null would mean 'never configured' and hand the rider AlarmDefaults back"
        )
        assertTrue(saved.motionAlerts.all { it.isOff }, "the silence must survive verbatim")
        assertTrue(
            saved.motionAlertRules.all { it.isOff },
            "and resolving it must not reintroduce the defaults"
        )
    }

    /**
     * The round trip that must not invent a battery.
     *
     * onSave() builds through singlePackVehicle(), which ALWAYS synthesizes
     * exactly one pack. For a controller-only vehicle that pack would be built
     * from the edit form's placeholder defaults — the JK_BMS / "" that
     * initialize() falls back to when there is no pack to describe — so simply
     * opening Edit and pressing Save would hand the vehicle a battery it does
     * not have, and put "" into its allAddresses.
     *
     * Loading and saving an unchanged controller-only vehicle must therefore
     * be an identity on its sources.
     */
    @Test
    fun `saving an unchanged controller-only vehicle does not invent a pack`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        // The form loaded: name is the vehicle's, and the BMS fields hold the
        // placeholder defaults precisely because there is no pack behind them.
        assertEquals("Scooter", c.state.value.name)
        assertEquals(BmsType.JK_BMS, c.state.value.bmsType)
        assertEquals("", c.state.value.bmsAddress)

        // Save with nothing edited — the plainest possible round trip.
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(emptyList(), saved.packs, "no phantom pack may be synthesized")
        assertEquals(originalControllers, saved.controllers, "the controller is what keeps it valid")
        assertEquals("Scooter", saved.name)
    }

    @Test
    fun `saving a pack-only vehicle still writes its pack unchanged`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        // The other half of the same branch: a vehicle that HAS packs must be
        // rebuilt exactly as before, so the phantom-pack guard cannot cost the
        // BMS path its battery.
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(1, saved.packs.size)
        assertEquals(BmsType.VESC_BMS, saved.packs.single().bmsType)
        assertEquals("AA:BB", saved.packs.single().bmsAddress)
    }

    // ----- G1 Task 5: the read-only source header must not fabricate a BMS -----

    /**
     * `initialize()` fed the header row `bmsTypeOrNull ?: JK_BMS` and
     * `bmsAddressOrNull ?: ""`, so opening a controller-only vehicle's form
     * showed "BMS type: JK BMS" and an em-dash address — a source the vehicle
     * does not have, stated as fact. The header now reads [State.sourceVehicle]
     * (through the shared `vehicleSourceLabel`) and [State.sourceAddress].
     *
     * Note the pack fields are asserted to STAY at their placeholders: they
     * feed the pack `singlePackVehicle` builds in `onSave()`, and a
     * controller's address must never leak into one.
     */
    @Test
    fun `a controller-only vehicle's header describes its controller, not a phantom BMS`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeVehicleRepo(listOf(controllerOnlyVehicle())))
        advanceUntilIdle()

        val s = c.state.value
        assertEquals("AA:BB", s.sourceAddress, "the controller's own address, not an em-dash")
        // What the row renders is vehicleSourceLabel(sourceVehicle, BMS) — a
        // @Composable, so the reachable assertion is that the vehicle it reads
        // is present and controller-only. (No Compose test harness exists; the
        // rendering itself is uncovered.)
        assertEquals(ControllerType.VESC, s.sourceVehicle?.controllers?.single()?.controllerType)
        assertEquals(true, s.sourceVehicle?.packs?.isEmpty())
        // Untouched: these are the pack builder's inputs, not the header's.
        assertEquals(BmsType.JK_BMS, s.bmsType)
        assertEquals("", s.bmsAddress)
    }

    /**
     * The half that must not move. A vehicle with a pack — including one that
     * ALSO has a controller at a different address — keeps naming its BMS and
     * showing the PACK's address, because `primaryAddress` prefers the
     * controller and is therefore only safe as the fallback.
     */
    @Test
    fun `a vehicle with a pack still shows the pack's address, even beside a controller`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dualSource = existingVehicle().copy(
            packs = listOf(Pack(index = 0, label = "P0", bmsType = BmsType.JK_BMS, bmsAddress = "PACK:01")),
            controllers = listOf(
                Controller(index = 0, label = "ESC", controllerType = ControllerType.VESC, address = "CTRL:01")
            )
        )
        val c = component(FakeVehicleRepo(listOf(dualSource)))
        advanceUntilIdle()

        val s = c.state.value
        assertEquals("PACK:01", s.sourceAddress, "the controller's address must not win here")
        assertEquals(BmsType.JK_BMS, s.bmsType)
        assertEquals("PACK:01", s.bmsAddress)
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

    // ----- G1 Task 6: motor configuration -----

    /**
     * The core round trip: onSave() must route the edited [MotorConfig] fields
     * onto `controllers[0]`, not just re-persist the loaded default. Every
     * value used here (7 / 200 / 2.5f) differs from `MotorConfig()`'s own
     * default (15 / 0 / 1f), so a regression that keeps discarding the edit
     * and re-saving the loaded default trips this assertion.
     */
    @Test
    fun `save persists edited motor config onto controllers 0`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        // Sanity: the form loaded the controller's current (default) motor config.
        assertEquals(true, c.state.value.hasController)
        assertEquals(15, c.state.value.motorPolePairs)
        assertEquals(0, c.state.value.motorWheelDiameterMm)
        assertEquals(1f, c.state.value.motorGearRatio)

        c.onMotorPolePairsChanged(7)
        c.onMotorWheelDiameterChanged(200)
        c.onMotorGearRatioChanged(2.5f)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        val savedController = saved.controllers.single()
        assertEquals(MotorConfig(polePairs = 7, wheelDiameterMm = 200, gearRatio = 2.5f), savedController.motor)
        // Everything else about the controller must survive untouched.
        assertEquals("Main", savedController.label)
        assertEquals(ControllerType.VESC, savedController.controllerType)
        assertEquals("AA:BB", savedController.address)
    }

    /**
     * The same round trip as above, but on the shape the app can actually
     * produce: zero packs, one controller (see [controllerOnlyVehicle] and
     * the Picker's controller-creation path in Task 5). [existingVehicle]
     * pairs a VESC_BMS pack with a VESC controller at the SAME address
     * ("AA:BB") — `planLinks` (data/ble/LinkPlan.kt) resolves one address to
     * more than one `ProtocolKind` (VESC_BMS vs VESC) and throws
     * "Address AA:BB resolves to conflicting protocol kinds [...]", so that
     * fixture can never actually connect. Every other Task 6 test above
     * exercises only that unreachable shape, leaving the real one — a bare
     * controller vehicle — with no motor-edit coverage at all.
     */
    @Test
    fun `save persists edited motor config onto a zero-pack controller vehicle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        // Sanity: the form loaded the controller's current (default) motor config.
        assertEquals(true, c.state.value.hasController)
        assertEquals(15, c.state.value.motorPolePairs)
        assertEquals(0, c.state.value.motorWheelDiameterMm)
        assertEquals(1f, c.state.value.motorGearRatio)

        c.onMotorPolePairsChanged(7)
        c.onMotorWheelDiameterChanged(200)
        c.onMotorGearRatioChanged(2.5f)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(emptyList(), saved.packs, "the shape the app produces has zero packs — the save must not invent one")
        val savedController = saved.controllers.single()
        assertEquals(MotorConfig(polePairs = 7, wheelDiameterMm = 200, gearRatio = 2.5f), savedController.motor)
        // Everything else about the controller must survive untouched.
        assertEquals("Main", savedController.label)
        assertEquals(ControllerType.VESC, savedController.controllerType)
        assertEquals("AA:BB", savedController.address)
    }

    /**
     * Blanking a motor field (as IntField/FloatField do when the text can't
     * parse — see that pattern for cellHighV etc.) must fall back to
     * `MotorConfig()`'s own default, not silently persist a zero or the
     * pre-edit value. Starting from a non-default 9 and clearing it makes a
     * fallback-to-zero or fallback-to-stale-9 bug equally visible: only the
     * real default (15) satisfies the assertion.
     */
    @Test
    fun `blanking a motor field falls back to MotorConfig's default`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        c.onMotorPolePairsChanged(9)
        c.onMotorPolePairsChanged(null) // user cleared the field
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(MotorConfig().polePairs, saved.controllers.single().motor.polePairs)
    }

    /**
     * `hasController` — the flag the screen uses to decide whether the Motor
     * section renders at all — must be false for a pack-only vehicle, and
     * saving it unchanged must not fabricate a controller (mapIndexed over an
     * empty list stays empty). No Compose test harness exists, so this is the
     * reachable assertion for "the fields are absent, not merely disabled."
     */
    @Test
    fun `a pack-only vehicle has no controller to configure, and saving fabricates none`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val packOnly = existingVehicle().copy(controllers = emptyList())
        val repo = FakeVehicleRepo(listOf(packOnly))
        val c = component(repo)
        advanceUntilIdle()

        assertEquals(false, c.state.value.hasController)
        assertEquals(null, c.state.value.motorPolePairs)
        assertEquals(null, c.state.value.motorWheelDiameterMm)
        assertEquals(null, c.state.value.motorGearRatio)

        c.onSave()
        advanceUntilIdle()

        assertEquals(emptyList(), repo.upserts.single().controllers)
    }
}
