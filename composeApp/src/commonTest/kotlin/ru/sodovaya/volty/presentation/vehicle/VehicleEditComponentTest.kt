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
        val motorKey = c.state.value.draft.controllers.single().key
        c.onControllerPolePairsChanged(motorKey, 7)
        c.onControllerWheelDiameterChanged(motorKey, 200)
        c.onControllerGearRatioChanged(motorKey, 2.5f)
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
     * The half of the motor edit that is a restriction, not an application: a
     * vehicle with two motors has two different wheels, and editing one row's
     * geometry must not spray it across the other.
     *
     * This used to be the *only* thing keeping the flat Motor card honest,
     * because that card was positional — it wrote `controllers[0]` whatever the
     * rider believed they were editing. G2 Task 3 replaced it with a card per
     * controller, so the rule is now structural (a keyed setter cannot reach a
     * row it was not given) rather than a guard; the test is kept because
     * "structural" is a claim, and this is what falsifies it.
     */
    @Test
    fun `the motor edit reaches only the controller it names`() = runTest {
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

        c.onControllerPolePairsChanged(c.state.value.draft.controllers[0].key, 7)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(7, saved.controllers[0].motor.polePairs, "the edited controller")
        assertEquals(second, saved.controllers[1], "and the other one untouched, motor included")
    }

    /**
     * The whole per-controller motor editor, one field at a time and on the
     * **second** controller — the row the deleted flat card could never reach,
     * since it wrote position 0 unconditionally.
     */
    @Test
    fun `each motor field is editable per controller`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val second = Controller(
            index = 1,
            label = "Rear",
            controllerType = ControllerType.VESC,
            address = "CC:DD",
            motor = MotorConfig(polePairs = 4, wheelDiameterMm = 300, gearRatio = 2f)
        )
        val repo = FakeVehicleRepo(listOf(existingVehicle().copy(controllers = originalControllers + second)))
        val c = component(repo)
        advanceUntilIdle()

        val rear = c.state.value.draft.controllers[1].key
        c.onControllerPolePairsChanged(rear, 8)
        c.onControllerWheelDiameterChanged(rear, 150)
        c.onControllerGearRatioChanged(rear, 2.25f)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(MotorConfig(polePairs = 8, wheelDiameterMm = 150, gearRatio = 2.25f), saved.controllers[1].motor)
        assertEquals(
            MotorConfig(polePairs = 21, wheelDiameterMm = 500, gearRatio = 3.5f),
            saved.controllers[0].motor,
            "and controller 0 — the one the flat card used to write — is untouched"
        )
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

        // Sanity: the card loaded the controller's current motor config.
        val key = c.state.value.draft.controllers.single().key
        assertEquals(MotorDraft(polePairs = 21, wheelDiameterMm = 500, gearRatio = 3.5f), c.state.value.draft.controllers.single().motor)

        c.onControllerPolePairsChanged(key, 7)
        c.onControllerWheelDiameterChanged(key, 200)
        c.onControllerGearRatioChanged(key, 2.5f)
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
     * the Picker's controller-creation path in Task 5).
     *
     * (This KDoc used to claim [existingVehicle] "can never actually connect"
     * because its VESC_BMS pack and VESC controller share one address. That was
     * true when it was written and is **not** true now: Part C §6 sanctions
     * exactly that pairing — a gateway hosting its own battery — and
     * `resolveLinkKind` resolves it to VESC. The fixture is a legal head-unit
     * shape. The reason this test exists is still good: every other motor test
     * runs on a vehicle that HAS a pack, so the bare controller vehicle would
     * otherwise have no motor-edit coverage.)
     */
    @Test
    fun `save persists edited motor config onto a zero-pack controller vehicle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        // Sanity: the card loaded the controller's current motor config.
        val key = c.state.value.draft.controllers.single().key
        assertEquals(MotorDraft(polePairs = 21, wheelDiameterMm = 500, gearRatio = 3.5f), c.state.value.draft.controllers.single().motor)

        c.onControllerPolePairsChanged(key, 7)
        c.onControllerWheelDiameterChanged(key, 200)
        c.onControllerGearRatioChanged(key, 2.5f)
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
     * **The rule the deleted flat Motor card carried, rehomed.**
     *
     * Blanking a motor field (as IntField/FloatField do when the text cannot
     * parse — see that pattern for cellHighV) must fall back to
     * `MotorConfig()`'s own default, not silently persist a zero or the
     * pre-edit value. Starting from a non-default 9 and clearing it makes a
     * fallback-to-zero and a fallback-to-stale-9 equally visible: only the real
     * default (15) satisfies the assertion.
     *
     * Both halves matter and they pull opposite ways. The **draft** must keep
     * the blank as `null` — `IntField` re-syncs its text from the value it is
     * handed, so a draft that resolved the blank to 15 on the spot would type
     * 15 straight back into the box the rider had just cleared — while the
     * **save** must resolve it. That is the whole of why [MotorDraft] has
     * nullable fields and one `resolve()`.
     */
    @Test
    fun `a blanked motor field stays blank and saves MotorConfig's default`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        val key = c.state.value.draft.controllers.single().key
        c.onControllerPolePairsChanged(key, 9)
        c.onControllerPolePairsChanged(key, null) // user cleared the field
        assertEquals(
            null,
            c.state.value.draft.controllers.single().motor.polePairs,
            "a cleared field must not refill itself while the rider retypes it"
        )
        assertEquals(
            500,
            c.state.value.draft.controllers.single().motor.wheelDiameterMm,
            "and clearing one field must not disturb its neighbours"
        )
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(MotorConfig().polePairs, saved.controllers.single().motor.polePairs)
        assertEquals(500, saved.controllers.single().motor.wheelDiameterMm)
    }

    /**
     * A pack-only vehicle has no controller card to render, and saving it
     * unchanged must not fabricate one (mapIndexed over an empty list stays
     * empty). No Compose test harness exists, so the draft's own controller
     * list is the reachable assertion for "there are no controller cards".
     */
    @Test
    fun `a pack-only vehicle has no controller to configure, and saving fabricates none`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val packOnly = existingVehicle().copy(controllers = emptyList())
        val repo = FakeVehicleRepo(listOf(packOnly))
        val c = component(repo)
        advanceUntilIdle()

        assertEquals(emptyList(), c.state.value.draft.controllers)

        c.onSave()
        advanceUntilIdle()

        assertEquals(emptyList(), repo.upserts.single().controllers)
    }

    // ----- G2 Task 2: the composer (N sources) -----
    //
    // The rules themselves live in VehicleComposer.kt and are pinned by
    // VehicleComposerTest. What is tested here is the wiring: that the
    // component reaches them, and that the save applies a composed source list
    // without giving back any of the ground Task 1 took.

    @Test
    fun `adding a battery source writes it and recomputes the derived battery`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        // The loaded shape: one controller, no pack, so it derives a battery.
        assertEquals(true, c.state.value.draft.toControllers().single().providesDerivedBattery)

        c.onAddPack(BmsType.ANT_BMS, "AN:01", "Основной")
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(
            Pack(index = 0, label = "Основной", bmsType = BmsType.ANT_BMS, bmsAddress = "AN:01"),
            saved.packs.single()
        )
        assertEquals(
            false,
            saved.controllers.single().providesDerivedBattery,
            "a real battery source turns the controller's derived battery off (G §6)"
        )
        // The controller is otherwise untouched — the composer edits what it is
        // asked to and carries the rest.
        assertEquals("AA:BB", saved.controllers.single().address)
        assertEquals(21, saved.controllers.single().canId)
    }

    @Test
    fun `adding a second controller renumbers and keeps the first`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        c.onAddController(ControllerType.VESC, "AA:BB", "uBox R")
        c.onControllerCanIdChanged(c.state.value.draft.controllers[1].key, 42)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(listOf(0, 1), saved.controllers.map { it.index })
        assertEquals(listOf("Main", "uBox R"), saved.controllers.map { it.label })
        assertEquals(listOf(21, 42), saved.controllers.map { it.canId })
        assertEquals(MotorConfig(polePairs = 21, wheelDiameterMm = 500, gearRatio = 3.5f), saved.controllers[0].motor)
    }

    /**
     * **The exception the UI must not be able to reach.** [Vehicle]'s `init`
     * requires a source; the component refuses the removal and advertises
     * `canRemoveSource` so the screen can disable the control. Nothing throws
     * and nothing is caught.
     */
    @Test
    fun `removing the last source is refused, not thrown`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        assertEquals(false, c.state.value.canRemoveSource)
        c.onRemoveController(c.state.value.draft.controllers.single().key)
        assertEquals(1, c.state.value.draft.controllers.size, "the only source must survive")

        c.onSave()
        advanceUntilIdle()
        assertEquals(originalControllers, repo.upserts.single().controllers)
    }

    /**
     * The other half of the refusal above, and the reason that test cannot
     * stand alone: "nothing happened" is what a control wired to nothing looks
     * like too. When a source remains, the removal must actually happen.
     */
    @Test
    fun `removing a controller works while another source remains`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rear = Controller(
            index = 1,
            label = "Rear",
            controllerType = ControllerType.VESC,
            address = "CC:DD"
        )
        val repo = FakeVehicleRepo(
            listOf(controllerOnlyVehicle().copy(controllers = originalControllers + rear))
        )
        val c = component(repo)
        advanceUntilIdle()
        assertEquals(true, c.state.value.canRemoveSource)

        c.onRemoveController(c.state.value.draft.controllers[0].key)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(listOf("Rear"), saved.controllers.map { it.label })
        assertEquals(listOf(0), saved.controllers.map { it.index }, "and the survivor is renumbered")
    }

    /**
     * The composer must not be able to emit a config `planLinks` throws on —
     * prevented, not caught. A JK BMS at the VESC controller's own address
     * resolves one link to two protocol kinds.
     */
    @Test
    fun `a config planLinks would reject blocks the save`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()))
        val c = component(repo)
        advanceUntilIdle()
        assertEquals(true, c.state.value.canSave)

        c.onAddPack(BmsType.JK_BMS, "AA:BB")
        assertEquals(false, c.state.value.canSave)
        assertTrue(c.state.value.issues.any { it is ComposerIssue.ConflictingKinds && it.blocking })

        c.onSave()
        advanceUntilIdle()
        assertEquals(emptyList(), repo.upserts, "nothing may be persisted while the config conflicts")

        // And the rider can get out of it: move the pack to its own address.
        c.onPackAddressChanged(c.state.value.draft.packs.single().key, "JK:01")
        assertEquals(true, c.state.value.canSave)
        c.onSave()
        advanceUntilIdle()
        assertEquals("JK:01", repo.upserts.single().packs.single().bmsAddress)
    }

    /**
     * A vehicle whose stored config already cannot connect says so on open,
     * before the rider touches anything — an advisory one still saves.
     */
    @Test
    fun `a stored config volty cannot decode is reported on load without blocking`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fardriver = controllerOnlyVehicle().copy(
            controllers = listOf(
                Controller(index = 0, label = "FD", controllerType = ControllerType.FARDRIVER, address = "FD:01")
            )
        )
        val c = component(FakeVehicleRepo(listOf(fardriver)))
        advanceUntilIdle()

        assertEquals(
            listOf(ComposerIssue.NoControllerDecoder("c0", ControllerType.FARDRIVER)),
            c.state.value.issues
        )
        assertEquals(true, c.state.value.canSave, "a missing decoder is Parts E/H's job, not a config error")
    }

    /**
     * **The stale-draft counterpart of Task 1's stale-snapshot test.**
     *
     * `State.draft` is a snapshot taken when the form opened, and the stored row
     * moves underneath it: `KableBmsRepository.maybePersistPacks` appends a
     * Begode wheel's second branch mid-connection, and Part D navigates a
     * Controller pick straight to this form, so that is exactly when this form
     * is open. Writing a never-edited draft back would drop the branch —
     * §8.1's data loss arriving by a stale copy instead of by a forgotten
     * field.
     */
    @Test
    fun `a pack persisted while the form was open survives a save that did not compose`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        // The connection's pack auto-fill appends a second branch while this
        // form sits in front of the rider.
        val appended = Pack(index = 1, label = "Pack 2", bmsType = BmsType.VESC_BMS, bmsAddress = "AA:BB")
        repo.upsert(existingVehicle().copy(packs = existingVehicle().packs + appended))

        c.onNameChanged("Renamed")
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.last()
        assertEquals("Renamed", saved.name, "the edit itself must land")
        assertEquals(2, saved.packs.size, "the branch persisted underneath must not be dropped")
        assertEquals(appended, saved.packs[1])
        assertEquals("Renamed", saved.packs[0].label, "and pack 0 still follows the vehicle name")
    }

    /**
     * The controller half of the same rule, and the reason the save's condition
     * is `controllersEdited || packsEdited` rather than an unconditional write:
     * a save must touch only the half the rider composed.
     *
     * Nothing in the app writes `controllers` mid-edit *today* — but the pack
     * auto-fill did not write packs mid-edit either until Part D made a
     * Controller pick land straight on this form, and Task 5's CAN discovery is
     * the obvious next writer. The rule is cheap to hold now and expensive to
     * rediscover later, which is the same argument Task 1 made for keeping
     * `the motor edit reaches controller 0 only`.
     */
    @Test
    fun `a controller persisted while the form was open survives a save that did not compose`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        val discovered = Controller(
            index = 1,
            label = "uBox R",
            controllerType = ControllerType.VESC,
            address = "AA:BB",
            canId = 42
        )
        repo.upsert(existingVehicle().copy(controllers = originalControllers + discovered))

        c.onNameChanged("Renamed")
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.last()
        assertEquals("Renamed", saved.name, "the edit itself must land")
        assertEquals(2, saved.controllers.size, "the controller added underneath must not be dropped")
        assertEquals(discovered, saved.controllers[1])
    }

    /**
     * The **field**-level counterpart of the two tests above, and the case the
     * `packsEdited` switch alone cannot cover.
     *
     * `State.draft` is the load-time snapshot, and
     * `KableBmsRepository.maybePersistCellCount` upserts `withCellCount(n)` the
     * moment the pack's first cell frames arrive — while this form is on
     * screen. Once the rider touches the pack list, the save replaces `packs`
     * wholesale from the draft, so a stale `cellCount` would overwrite the real
     * one — and `lastPersistedCellCount` then suppresses re-persisting for the
     * rest of the process, so nothing would ever restore it. Cell count is
     * spec'd as auto-filled from telemetry (`G §4`); silently reverting it is a
     * value the rider cannot re-enter.
     */
    @Test
    fun `a cell count auto-filled while the form was open survives a composed save`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val unknownCells = existingVehicle().copy(
            packs = listOf(existingVehicle().packs.single().copy(cellCount = null))
        )
        val repo = FakeVehicleRepo(listOf(unknownCells))
        val c = component(repo)
        advanceUntilIdle()
        assertEquals(null, c.state.value.draft.packs.single().cellCount, "nothing known when the form opened")

        // The connection learns the cell count and persists it. `aliasGroup`
        // moves with it — not because anything writes it today, but because the
        // guarantee is generic: a pack field the composer does not edit comes
        // from the freshly-read row, not from the load-time snapshot. Task 4
        // makes that field editable and will need the same rule stated the
        // other way round.
        repo.upsert(
            unknownCells.copy(
                packs = listOf(unknownCells.packs.single().copy(cellCount = 20, aliasGroup = "moved"))
            )
        )

        // The rider composes — which is what makes the save write `packs` from
        // the draft instead of leaving the stored list alone.
        c.onPackLabelChanged(c.state.value.draft.packs.single().key, "Батарея")
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.last()
        assertEquals("Батарея", saved.packs.single().label, "the edit itself must land")
        assertEquals(20, saved.packs.single().cellCount, "and the auto-filled count must not be reverted")
        assertEquals("moved", saved.packs.single().aliasGroup, "nor any other field written underneath")
    }

    /**
     * The other side of the same switch: once the rider composes the pack list,
     * it is theirs. The vehicle-name → pack-0-label coupling exists because
     * `singlePackVehicle` labels pack 0 after the vehicle and the pack card
     * shows it verbatim; a composed list carries a label per pack, so the
     * coupling stands down rather than overwriting what they typed.
     */
    @Test
    fun `a composed pack list keeps the rider's own labels`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        c.onPackLabelChanged(c.state.value.draft.packs.single().key, "Передний")
        c.onNameChanged("Renamed")
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals("Renamed", saved.name)
        assertEquals("Передний", saved.packs.single().label)
        // Fields the composer does not model still ride along on the origin.
        assertEquals(20, saved.packs.single().cellCount)
        assertEquals("alias-a", saved.packs.single().aliasGroup)
    }

    /**
     * The G §6 collision, through the component: the rider's word outlives
     * every recompute a source change triggers.
     */
    @Test
    fun `the rider's derived-battery choice outlives adding and removing a BMS`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        val key = c.state.value.draft.controllers.single().key
        c.onControllerDerivedBatteryChanged(key, false)
        c.onAddPack(BmsType.ANT_BMS, "AN:01")
        c.onRemovePack(c.state.value.draft.packs.single().key)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(emptyList(), saved.packs)
        assertEquals(
            false,
            saved.controllers.single().providesDerivedBattery,
            "the rule says ON for a lone controller; the rider said OFF"
        )
    }

    @Test
    fun `topology is editable and lands on the saved vehicle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        assertEquals(PackTopology.SERIES, c.state.value.topology, "loaded from the vehicle")
        c.onTopologyChanged(PackTopology.PARALLEL)
        c.onSave()
        advanceUntilIdle()

        assertEquals(PackTopology.PARALLEL, repo.upserts.single().topology)
    }

    /**
     * A reorder renumbers the saved indices, and each controller's geometry
     * must travel with its own row rather than staying at a position.
     *
     * This is the test that used to assert the opposite-looking thing: while
     * the flat Motor card existed it had to *re-point* at whatever controller a
     * reorder brought to the front, which is the wart Task 3 deleted. Now the
     * card is the row, so the second half — editing by key after the reorder —
     * is what proves nothing is still addressing position 0.
     */
    @Test
    fun `a reorder carries each controller's geometry with its own row`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rear = Controller(
            index = 1,
            label = "Rear",
            controllerType = ControllerType.VESC,
            address = "CC:DD",
            motor = MotorConfig(polePairs = 4, wheelDiameterMm = 300, gearRatio = 2f)
        )
        val repo = FakeVehicleRepo(listOf(existingVehicle().copy(controllers = originalControllers + rear)))
        val c = component(repo)
        advanceUntilIdle()
        val mainKey = c.state.value.draft.controllers[0].key

        c.onMoveController(1, 0)
        assertEquals(
            listOf(4, 21),
            c.state.value.draft.controllers.map { it.motor.polePairs },
            "the rear controller is now first, and brought its own geometry"
        )
        // Editing by key after the reorder: "Main" is at position 1 now, and the
        // edit must land on it rather than on whatever sits at position 0.
        c.onControllerPolePairsChanged(mainKey, 6)

        c.onSave()
        advanceUntilIdle()
        val saved = repo.upserts.single()
        assertEquals(listOf("Rear", "Main"), saved.controllers.map { it.label })
        assertEquals(
            listOf(MotorConfig(4, 300, 2f), MotorConfig(6, 500, 3.5f)),
            saved.controllers.map { it.motor },
            "each controller keeps its own geometry across the reorder"
        )
    }

    /**
     * The composer's counterpart of `every field this form edits lands on the
     * saved vehicle`: every per-source control, one edit each, to a value
     * [existingVehicle] does not already hold — so a control wired to nothing
     * fails here rather than shipping as a dead switch.
     */
    @Test
    fun `every composer control reaches the saved vehicle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        val pk = c.state.value.draft.packs.single().key
        val ck = c.state.value.draft.controllers.single().key
        c.onPackLabelChanged(pk, "Батарея")
        c.onPackTypeChanged(pk, BmsType.ANT_BMS)
        c.onPackAddressChanged(pk, "AN:01")
        c.onPackCanIdChanged(pk, 33)
        c.onAddPack(BmsType.ANT_BMS, "AN:02", "Вторая")
        c.onMovePack(0, 1)
        c.onControllerLabelChanged(ck, "Левый")
        c.onControllerTypeChanged(ck, ControllerType.BEGODE)
        c.onControllerAddressChanged(ck, "WH:01")
        c.onControllerCanIdChanged(ck, null)
        c.onControllerMotorChanged(ck, MotorConfig(polePairs = 3, wheelDiameterMm = 100, gearRatio = 1.5f))
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(
            listOf(
                Pack(index = 0, label = "Вторая", bmsType = BmsType.ANT_BMS, bmsAddress = "AN:02"),
                Pack(
                    index = 1,
                    label = "Батарея",
                    bmsType = BmsType.ANT_BMS,
                    bmsAddress = "AN:01",
                    // Not modelled as editable here, and carried on the origin
                    // rather than re-typed by the rider.
                    cellCount = 20,
                    canId = 33,
                    aliasGroup = "alias-a"
                )
            ),
            saved.packs
        )
        assertEquals(
            listOf(
                Controller(
                    index = 0,
                    label = "Левый",
                    controllerType = ControllerType.BEGODE,
                    address = "WH:01",
                    canId = null,
                    motor = MotorConfig(polePairs = 3, wheelDiameterMm = 100, gearRatio = 1.5f),
                    // The fixture stores `true` beside a pack, which the rule
                    // says should be `false` — so it reloads as the rider's
                    // explicit ON and survives every recompute above.
                    providesDerivedBattery = true
                )
            ),
            saved.controllers
        )
    }

    /**
     * The create path builds the single-BMS shape through `singlePackVehicle`
     * and never reads the draft, so a composer control offered while creating
     * would take the rider's work and discard it at save time with no signal.
     * The state says so (`canComposeSources`) **and** the mutations are no-ops
     * — disabled *and* prevented, the same pair as the last-source rule, rather
     * than trusting the screen to remember.
     */
    @Test
    fun `the source list cannot be composed while creating`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(emptyList())
        val c = component(repo, vehicleId = null, prefilledBmsType = BmsType.JK_BMS, prefilledBmsAddress = "CR:01")
        advanceUntilIdle()
        assertEquals(false, c.state.value.canComposeSources)

        c.onNameChanged("Fresh")
        c.onAddController(ControllerType.VESC, "VE:01")
        c.onAddPack(BmsType.ANT_BMS, "AN:01")
        assertEquals(VehicleDraft(), c.state.value.draft, "nothing may be accepted that the save would discard")
        assertEquals(false, c.state.value.packsEdited)
        assertEquals(false, c.state.value.controllersEdited)

        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals(
            Pack(index = 0, label = "Fresh", bmsType = BmsType.JK_BMS, bmsAddress = "CR:01"),
            saved.packs.single()
        )
        assertEquals(emptyList(), saved.controllers)
    }

    /**
     * A refusal the screen can see. `nameError` has had this shape forever; a
     * blocking issue returning silently would give a screen that wires only the
     * Save button a control that appears dead.
     */
    @Test
    fun `a refused save says so, and the refusal clears when the config is fixed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()))
        val c = component(repo)
        advanceUntilIdle()
        assertEquals(false, c.state.value.saveBlocked)

        c.onAddPack(BmsType.JK_BMS, "AA:BB")
        c.onSave()
        advanceUntilIdle()
        assertEquals(true, c.state.value.saveBlocked)
        assertEquals(emptyList(), repo.upserts)

        c.onPackAddressChanged(c.state.value.draft.packs.single().key, "JK:01")
        assertEquals(false, c.state.value.saveBlocked, "fixing the config withdraws the refusal")
    }

    /**
     * A cleared motor box must survive an edit somewhere else on the form.
     *
     * This was a real wart while the flat Motor card existed: its three fields
     * were re-projected off draft controller 0 on **any** structural change, so
     * adding a pack refilled a box the rider had just emptied with the 15 the
     * draft had resolved it to. Deleting the card removes the mechanism; this
     * pins that no replacement re-projection grew back.
     */
    @Test
    fun `a cleared motor field survives an unrelated source edit`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        val key = c.state.value.draft.controllers.single().key
        c.onControllerPolePairsChanged(key, null)
        c.onAddPack(BmsType.ANT_BMS, "AN:09")
        val motor = c.state.value.draft.controllers.single().motor
        assertEquals(null, motor.polePairs, "an unrelated edit must not refill it")
        assertEquals(500, motor.wheelDiameterMm, "and must not disturb the others")
    }

    /**
     * A controller added by hand starts from `MotorConfig()`'s own defaults
     * rather than from three empty boxes: a blank is reserved for "the rider
     * cleared this", and a card that opened blank would ask them to re-type a
     * number the save was going to supply anyway.
     */
    @Test
    fun `an added controller starts from MotorConfig's defaults`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val packOnly = existingVehicle().copy(controllers = emptyList())
        val repo = FakeVehicleRepo(listOf(packOnly))
        val c = component(repo)
        advanceUntilIdle()
        assertEquals(emptyList(), c.state.value.draft.controllers)

        c.onAddController(ControllerType.VESC, "VE:01")
        assertEquals(MotorDraft.of(MotorConfig()), c.state.value.draft.controllers.single().motor)

        c.onSave()
        advanceUntilIdle()
        assertEquals(MotorConfig(), repo.upserts.single().controllers.single().motor)
    }

    /**
     * Every issue must reach the card the rider has to edit, which for the three
     * per-address ones means re-walking the draft — see [affectedKeys]. The
     * component only has to expose it; the mapping itself is pinned in
     * `VehicleComposerTest`.
     */
    @Test
    fun `a blocking issue is reported on both of the sources that contradict`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeVehicleRepo(listOf(controllerOnlyVehicle())))
        advanceUntilIdle()

        c.onAddPack(BmsType.JK_BMS, "AA:BB") // the controller's own link
        val controllerKey = c.state.value.draft.controllers.single().key
        val packKey = c.state.value.draft.packs.single().key
        val bySource = c.state.value.issuesBySource

        assertEquals(
            listOf(controllerKey, packKey).toSet(),
            bySource.keys,
            "a conflict names a link, and both sources on it must say so"
        )
        assertTrue(bySource.getValue(controllerKey).all { it is ComposerIssue.ConflictingKinds })
        assertTrue(bySource.getValue(packKey).all { it is ComposerIssue.ConflictingKinds })
    }

    /**
     * The units link (`B §9`) is navigation and nothing else — no state on this
     * form moves, so there is nothing to go stale while Settings is on top of
     * it. Same shape as [VehicleEditComponent.onOpenAlerts].
     */
    @Test
    fun `the units link navigates and touches no state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var opened = 0
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = DefaultVehicleEditComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            vehicleId = "v1",
            vehicleRepository = repo,
            bmsRepository = FakeBmsRepo(),
            onSaved = {},
            onCancelled = {},
            onDeleted = {},
            onOpenUnitsRequested = { opened++ }
        )
        advanceUntilIdle()
        val before = c.state.value

        c.onOpenUnits()
        assertEquals(1, opened)
        assertEquals(before, c.state.value, "a link must not touch the form")
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
