package ru.sodovaya.volty.presentation.vehicle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.alert.AlertLevel
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.model.AlertConfig
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
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Regression guard for the onSave() data-loss bug (G-vehicle-composer.md §8).
 *
 * Saving used to REBUILD the vehicle through `singlePackVehicle()` and then
 * hand-copy back the fields the form does not expose, so every field missing
 * from that list was reset to its default on any save — three of them reached
 * review that way. `onSave()` now `copy()`s the stored vehicle instead, which
 * inverts the failure mode: an unnamed field is preserved.
 *
 * These tests are only as strong as [existingVehicle], which is why every one
 * of its fields is deliberately non-default — and why
 * `the identity fixture leaves no Vehicle field at its default` exists to keep
 * it that way when [Vehicle] grows.
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
        override suspend fun connectDemo(profile: DemoProfile): Result<Unit> = Result.success(Unit)
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

    /**
     * Every field non-default, [MotorConfig] included — the motor numbers are
     * the ones this screen edits, so loading them and saving them back
     * unchanged must be an identity too, not a quiet reset to 15/0/1.
     */
    private val originalControllers = listOf(
        Controller(
            index = 0,
            label = "Main",
            controllerType = ControllerType.VESC,
            address = "AA:BB",
            canId = 21,
            motor = MotorConfig(polePairs = 21, wheelDiameterMm = 500, gearRatio = 3.5f),
            providesDerivedBattery = true
        )
    )

    private val createdAtFixture = Instant.fromEpochSeconds(1_700_000_000)
    private val lastConnectedAtFixture = Instant.fromEpochSeconds(1_800_000_000)

    /**
     * **Not one field at its default. That is the fixture's job, not a
     * flourish** — a field left at its default is preserved and dropped by
     * indistinguishable saves, so it could not prove anything.
     * `the identity fixture leaves no Vehicle field at its default` enforces it
     * mechanically, including for fields that do not exist yet.
     */
    private fun existingVehicle() = Vehicle(
        id = "v1",
        name = "Wheel",
        iconKey = "wheel",
        packs = listOf(
            Pack(
                index = 0,
                label = "Wheel",
                bmsType = BmsType.VESC_BMS,
                bmsAddress = "AA:BB",
                // Auto-filled from live telemetry (KableBmsRepository), and the
                // two routing fields this form has never been able to express.
                cellCount = 20,
                canId = 11,
                aliasGroup = "alias-a"
            )
        ),
        controllers = originalControllers,
        topology = PackTopology.SERIES,
        chemistry = Chemistry.LI_ION_NMC,
        averagingWindowMin = 30,
        alertConfig = AlertConfig(
            cellHighV = 4.1f,
            cellLowV = 3.1f,
            // The four AlertConfig fields this form does NOT expose.
            cellDeltaMv = 350,
            temperatureWarnC = 44f,
            temperatureHighC = 55f,
            socLowPercent = 22,
            socCutoffPercent = 8,
            disconnectNotify = false,
            chargeCompleteNotify = false
        ),
        createdAt = createdAtFixture,
        lastConnectedAt = lastConnectedAtFixture,
        isPinned = true,
        dashboardStyle = DashboardStyle.CLASSIC,
        secondaryGauge = SecondaryGauge.POWER,
        yieldBmsToHeadUnit = false,
        // Every kind deliberately silenced (F §10.2: an empty level list is the
        // only way to say "off"). Non-null, so it is an ANSWER — and a
        // non-default one, which is what makes a dropped field visible below.
        motionAlerts = MotionAlertKind.entries.map { AlertRule(it, emptyList()) }
    )

    /**
     * A Begode wheel after the pack auto-fill has persisted the second branch
     * `expandedTo` synthesised (G-vehicle-composer.md §8.1). One BLE address,
     * two packs, one controller — the shape a Controller pick now lands the
     * rider on directly.
     */
    private fun wheelWithSecondBranch() = existingVehicle().copy(
        name = "Falcon",
        packs = listOf(
            Pack(index = 0, label = "Falcon", bmsType = BmsType.BEGODE, bmsAddress = "WH:01", cellCount = 20),
            Pack(index = 1, label = "Pack 2", bmsType = BmsType.BEGODE, bmsAddress = "WH:01")
        ),
        controllers = listOf(
            Controller(
                index = 0,
                label = "Falcon",
                controllerType = ControllerType.BEGODE,
                address = "WH:01",
                providesDerivedBattery = false
            )
        )
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
        createdAt = createdAtFixture
    )

    private fun component(
        vehicleRepo: FakeVehicleRepo,
        vehicleId: String? = "v1",
        prefilledBmsType: BmsType? = null,
        prefilledBmsAddress: String? = null
    ): DefaultVehicleEditComponent {
        val ctx = DefaultComponentContext(LifecycleRegistry())
        return DefaultVehicleEditComponent(
            componentContext = ctx,
            vehicleId = vehicleId,
            vehicleRepository = vehicleRepo,
            bmsRepository = FakeBmsRepo(),
            onSaved = {},
            onCancelled = {},
            onDeleted = {},
            prefilledBmsType = prefilledBmsType,
            prefilledBmsAddress = prefilledBmsAddress
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
     * **The structural guard.**
     *
     * Loading a vehicle and saving it with nothing edited must be an identity on
     * the whole object — not "equal in the fields someone remembered to check",
     * because field-by-field assertions are exactly how this defect reached
     * review three times.
     *
     * It is only as strong as [existingVehicle]: a field left at its default is
     * carried and dropped by identical-looking saves. That hole used to be the
     * author's to remember; it is now closed mechanically by
     * `the identity fixture leaves no Vehicle field at its default`.
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
     * **Closes the identity test's one weakness — read this if it fails.**
     *
     * `saving with nothing edited is an identity` can only see a dropped field
     * if [existingVehicle] gave that field a non-default value: preserving a
     * default and resetting to it are the same bytes. So the fixture's
     * completeness was, until now, something a new field's author had to
     * remember — the same *omission* failure mode the rewrite was about, moved
     * one file over.
     *
     * This closes it without reflection (unavailable in common code): a data
     * class' `toString()` renders every primary-constructor property, so a
     * field added to [Vehicle] shows up here the moment it exists. Comparing the
     * fixture against a vehicle that took every default names any field the
     * fixture failed to override.
     *
     * If this fails, do not weaken it — give the named field a non-default value
     * in [existingVehicle].
     */
    @Test
    fun `the identity fixture leaves no Vehicle field at its default`() {
        // Every optional field at its declared default; every REQUIRED field at
        // a value the fixture demonstrably does not use (so it is never
        // reported, and a required field can never be silently skipped either).
        val allDefaults = Vehicle(
            id = "bare",
            name = "Bare",
            iconKey = "generic",
            packs = listOf(Pack(index = 9, label = "Bare", bmsType = BmsType.JK_BMS, bmsAddress = "ZZ:ZZ")),
            chemistry = Chemistry.LIFEPO4,
            createdAt = Instant.DISTANT_PAST
        )
        val fixtureRendering = existingVehicle().toString()
        val defaultsRendering = allDefaults.toString()
        val fixture = renderedFields(fixtureRendering)
        val defaults = renderedFields(defaultsRendering)

        // The splitter is test code, and a broken one would pass vacuously —
        // two empty maps agree about everything. Two guards, neither of which
        // hardcodes today's field count (a floor of 16 still clears a splitter
        // that drops 4 of 20 fields):
        //
        //  1. re-joining the parsed pairs must reproduce the rendering exactly,
        //     so nothing can be dropped or mangled;
        //  2. both renderings must yield the SAME key set. Splitting is
        //     value-dependent, so this is not implied by (1): an unbalanced
        //     bracket inside a *declared default* (`val marker: String = "("`)
        //     leaves the depth counter high and every later comma stops
        //     separating, collapsing `defaults` while `fixture` — which
        //     overrides that field — parses in full. Both would then still
        //     round-trip, and comparing a 17-key map against a 4-key one silently
        //     stops checking 13 fields.
        assertEquals(
            renderedBody(fixtureRendering),
            fixture.entries.joinToString(", ") { "${it.key}=${it.value}" },
            "the toString splitter dropped or mangled part of the fixture rendering"
        )
        assertEquals(
            renderedBody(defaultsRendering),
            defaults.entries.joinToString(", ") { "${it.key}=${it.value}" },
            "the toString splitter dropped or mangled part of the all-defaults rendering"
        )
        assertEquals(
            defaults.keys,
            fixture.keys,
            "the toString splitter disagrees across the two renderings, so the check below " +
                "would compare a collapsed map against a full one and silently skip fields: " +
                "fixture=${fixture.keys} defaults=${defaults.keys}"
        )

        val untouched = defaults.filterKeys { fixture[it] == defaults[it] }.keys
        assertEquals(
            emptySet(),
            untouched,
            "these Vehicle fields sit at their default in existingVehicle(), so no test in " +
                "this file can prove onSave() carries them: $untouched"
        )
    }

    /**
     * The other direction, and the reason the identity test above cannot be
     * satisfied by an `onSave()` that simply re-persists what it loaded: every
     * control this form offers must actually reach the stored vehicle.
     *
     * One edit per editable field, each to a value [existingVehicle] does not
     * already hold — so dropping any single line from the save's `copy()` fails
     * here, while dropping a line it must NOT have fails the identity test.
     * Together they pin the save's copy list from both sides.
     */
    @Test
    fun `every field this form edits lands on the saved vehicle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        c.onNameChanged("Renamed")
        c.onIconChanged("moto")
        c.onChemistryChanged(Chemistry.LEAD_ACID)
        c.onAveragingWindowChanged(1)
        c.onCellHighVChanged(4.25f)
        c.onCellLowVChanged(2.9f)
        c.onTemperatureWarnChanged(41f)
        c.onTemperatureHighChanged(61f)
        c.onSocLowChanged(9)
        c.onMotorPolePairsChanged(7)
        c.onMotorWheelDiameterChanged(200)
        c.onMotorGearRatioChanged(2.5f)
        c.onDashboardStyleChanged(DashboardStyle.CLEAN)
        c.onSecondaryGaugeChanged(SecondaryGauge.BATTERY)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals("Renamed", saved.name)
        assertEquals("moto", saved.iconKey)
        assertEquals(Chemistry.LEAD_ACID, saved.chemistry)
        assertEquals(1, saved.averagingWindowMin)
        // Whole-object on AlertConfig: the five thresholds this form owns must
        // land, and the four it does not must ride along untouched.
        assertEquals(
            AlertConfig(
                cellHighV = 4.25f,
                cellLowV = 2.9f,
                cellDeltaMv = 350,
                temperatureWarnC = 41f,
                temperatureHighC = 61f,
                socLowPercent = 9,
                socCutoffPercent = 8,
                disconnectNotify = false,
                chargeCompleteNotify = false
            ),
            saved.alertConfig
        )
        assertEquals("Renamed", saved.packs.single().label, "the name field renames pack 0 too")
        assertEquals(MotorConfig(polePairs = 7, wheelDiameterMm = 200, gearRatio = 2.5f), saved.controllers.single().motor)
        assertEquals(DashboardStyle.CLEAN, saved.dashboardStyle)
        assertEquals(SecondaryGauge.BATTERY, saved.secondaryGauge)
    }

    /**
     * The half of the motor edit that is a restriction, not an application: G1
     * gives a vehicle exactly one controller and this screen edits
     * `controllers[0]`'s [MotorConfig] only. A save must not spray the form's
     * three numbers across every controller a vehicle happens to carry.
     *
     * No UI creates a two-controller vehicle yet — [Vehicle] has always allowed
     * one, and Part G2 is about to start building them, so the rule the code
     * states is worth holding to before it has a screen behind it.
     */
    @Test
    fun `the motor edit reaches controller 0 only`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val second = Controller(
            index = 1,
            label = "Rear",
            controllerType = ControllerType.FARDRIVER,
            address = "CC:DD",
            motor = MotorConfig(polePairs = 4, wheelDiameterMm = 300, gearRatio = 2f)
        )
        val repo = FakeVehicleRepo(listOf(existingVehicle().copy(controllers = originalControllers + second)))
        val c = component(repo)
        advanceUntilIdle()

        c.onMotorPolePairsChanged(7)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(7, saved.controllers[0].motor.polePairs, "the edited controller")
        assertEquals(second, saved.controllers[1], "and the other one untouched, motor included")
    }

    /**
     * The create path — the one place this screen still calls a constructor,
     * because a vehicle that does not exist has nothing to preserve.
     *
     * It must keep producing exactly the single-BMS shape it always has: one
     * pack at the picker's prefilled address, labelled after the vehicle, no
     * controllers, and the two dashboard prefs this form owns applied on top.
     */
    @Test
    fun `creating a vehicle builds the single-pack shape from the form`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(emptyList())
        val c = component(
            repo,
            vehicleId = null,
            prefilledBmsType = BmsType.JK_BMS,
            prefilledBmsAddress = "CR:01"
        )
        advanceUntilIdle()

        c.onNameChanged("Fresh")
        c.onIconChanged("ebike")
        c.onCellHighVChanged(4.2f)
        c.onDashboardStyleChanged(DashboardStyle.CLEAN)
        c.onSecondaryGaugeChanged(SecondaryGauge.BATTERY)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertTrue(saved.id.startsWith("v-"), "a generated id, not the null vehicleId: ${saved.id}")
        assertEquals("Fresh", saved.name)
        assertEquals("ebike", saved.iconKey)
        assertEquals(
            Pack(index = 0, label = "Fresh", bmsType = BmsType.JK_BMS, bmsAddress = "CR:01"),
            saved.packs.single()
        )
        assertEquals(emptyList(), saved.controllers)
        assertEquals(4.2f, saved.alertConfig.cellHighV)
        assertEquals(DashboardStyle.CLEAN, saved.dashboardStyle)
        assertEquals(SecondaryGauge.BATTERY, saved.secondaryGauge)
        // Never configured — the repository resolves that to AlarmDefaults, and
        // a brand-new vehicle must not claim the rider has answered already.
        assertEquals(null, saved.motionAlerts)
    }

    /**
     * The stale-snapshot variant of the same defect.
     *
     * The alerts screen (F Task 9) is PUSHED on top of this form, so it persists
     * motionAlerts while this component is still alive holding the snapshot
     * `initialize()` took. Applying the edits to that snapshot instead of to the
     * stored row would roll the rider's alert work back — losing a field by
     * writing a stale copy of it rather than by forgetting to copy it.
     */
    @Test
    fun `a save carries forward what the alerts screen wrote while this form was open`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        // The rider opens Alerts on top of this form, re-arms duty, comes back.
        val rearmed = listOf(
            AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(thresholdValue = 85f))),
            AlertRule(MotionAlertKind.SPEED, emptyList()),
            AlertRule(MotionAlertKind.MOTOR_TEMP, emptyList()),
            AlertRule(MotionAlertKind.ESC_TEMP, emptyList())
        )
        repo.upsert(existingVehicle().copy(motionAlerts = rearmed))

        c.onSecondaryGaugeChanged(SecondaryGauge.BATTERY)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.last()
        assertEquals(SecondaryGauge.BATTERY, saved.secondaryGauge, "the edit itself must land")
        assertEquals(rearmed, saved.motionAlerts, "the alerts screen's write must not be rolled back")
    }

    /**
     * §8.1 — the wheel's auto-filled second branch.
     *
     * The rebuild went through `singlePackVehicle()`, which always emits exactly
     * ONE pack, so a save collapsed a two-branch Begode back to one and the
     * second branch's stored label was gone. (`expandedTo` re-synthesises the
     * slot on the next connect, so the symptom was cosmetic — but Part D now
     * navigates from a Controller pick straight to this form, so a wheel meets
     * it on its very first save.)
     *
     * The guard against inventing a pack was `existing.packs.isEmpty()`, which a
     * wheel with two packs passes straight through. Update-in-place needs no
     * guard at all: pack 0 is renamed with the vehicle — the battery path's
     * long-standing behaviour, and what shows on a single-pack vehicle's card —
     * and every other pack is simply not addressed.
     */
    @Test
    fun `renaming a wheel keeps its auto-filled second branch`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(wheelWithSecondBranch()))
        val c = component(repo)
        advanceUntilIdle()

        c.onNameChanged("Falcon II")
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals("Falcon II", saved.name, "the edit itself must land")
        assertEquals(2, saved.packs.size, "the second branch must survive a rename")
        assertEquals(
            listOf("Falcon II", "Pack 2"),
            saved.packs.map { it.label },
            "pack 0 follows the vehicle name (battery path, unchanged); the branch keeps its own"
        )
        assertEquals(listOf(0, 1), saved.packs.map { it.index })
        assertEquals(20, saved.packs[0].cellCount, "and the auto-filled cell count with it")
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
     * A save that builds through `singlePackVehicle()` always synthesizes
     * exactly one pack. For a controller-only vehicle that pack would come from
     * the edit form's placeholder defaults — the JK_BMS / "" that `initialize()`
     * falls back to when there is no pack to describe — so simply opening Edit
     * and pressing Save would hand the vehicle a battery it does not have, and
     * put "" into its allAddresses.
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
        // The other half of the same branch: a vehicle that HAS packs must keep
        // it, so nothing done for the pack-less shape can cost the BMS path its
        // battery.
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
     * Note the pack fields are asserted to STAY at their placeholders: they feed
     * the pack `singlePackVehicle` builds when CREATING, and a controller's
     * address must never leak into one.
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

        // Sanity: the form loaded the controller's current motor config.
        assertEquals(true, c.state.value.hasController)
        assertEquals(21, c.state.value.motorPolePairs)
        assertEquals(500, c.state.value.motorWheelDiameterMm)
        assertEquals(3.5f, c.state.value.motorGearRatio)

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
        assertEquals(21, savedController.canId)
        assertEquals(true, savedController.providesDerivedBattery)
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

        // Sanity: the form loaded the controller's current motor config.
        assertEquals(true, c.state.value.hasController)
        assertEquals(21, c.state.value.motorPolePairs)
        assertEquals(500, c.state.value.motorWheelDiameterMm)
        assertEquals(3.5f, c.state.value.motorGearRatio)

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

/**
 * Splits a data class' `toString()` into its top-level `property=value` pairs.
 *
 * Stands in for reflection, which common code does not have: `toString()` is
 * generated from the primary constructor, so it enumerates exactly the
 * properties a `copy()` can carry — including ones added tomorrow. Depth
 * tracking is what makes it usable: nested `Pack(...)` renderings and list
 * brackets are full of commas and `=` signs that are not field separators.
 */
/** The inside of a data class' `toString()`, without the class name or parentheses. */
private fun renderedBody(rendered: String): String =
    rendered.substring(rendered.indexOf('(') + 1, rendered.length - 1)

private fun renderedFields(rendered: String): Map<String, String> {
    val body = renderedBody(rendered)
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    body.forEachIndexed { i, ch ->
        when (ch) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ',' -> if (depth == 0) { parts += body.substring(start, i); start = i + 1 }
        }
    }
    parts += body.substring(start)
    // The value is NOT trimmed: it starts immediately after '=', and trimming a
    // trailing space would break the round-trip check above on a String field
    // that legitimately ends in one.
    return parts.associate { part ->
        val eq = part.indexOf('=')
        part.substring(0, eq).trim() to part.substring(eq + 1)
    }
}
