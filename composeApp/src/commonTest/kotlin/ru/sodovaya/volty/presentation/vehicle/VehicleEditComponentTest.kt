package ru.sodovaya.volty.presentation.vehicle

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackDispatcher
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
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.renderedBody
import ru.sodovaya.volty.renderedFields
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.motionAlertRules
import ru.sodovaya.volty.domain.model.yieldsBmsToHeadUnit
import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.data.ble.isGatewayLink
import ru.sodovaya.volty.data.ble.planAliasHandoffs
import ru.sodovaya.volty.data.ble.planLinks
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.CanDiscovery
import ru.sodovaya.volty.domain.repository.CanScanRefusal
import ru.sodovaya.volty.domain.repository.CanScanRefusedException
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.presentation.picker.ScannedAdd
import ru.sodovaya.volty.presentation.root.Config
import ru.sodovaya.volty.presentation.root.RootComponent
import ru.sodovaya.volty.presentation.root.guardComposerDestruction
import ru.sodovaya.volty.presentation.root.leaveVehicleEdit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private class FakeBmsRepo(
        /** What one collection of [scanAll] emits. Empty = the pre-Task-5 behaviour. */
        private val scan: List<DiscoveredDevice> = emptyList()
    ) : BmsRepository {
        /** How many times [scanAll] has been COLLECTED — one per sheet opening. */
        var scanCollections = 0
        /**
         * How many of those collections have ENDED. The difference from
         * [scanCollections] is the number of BLE scans still running, which is
         * the only way a test can see a sheet that closed without cancelling
         * its scan — the leak is a live radio, not a wrong list.
         */
        var scanCompletions = 0
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        // Emits its script and then STAYS OPEN, which is what a BLE scan does —
        // and the only way a test can tell "the sheet cancelled its scan" from
        // "the flow happened to finish". `awaitCancellation` schedules nothing,
        // so it cannot run virtual time away and wedge `runTest`.
        override fun scanAll(): Flow<DiscoveredDevice> =
            if (scan.isEmpty()) emptyFlow()
            else flow { scanCollections++; scan.forEach { emit(it) }; awaitCancellation() }
                .onCompletion { scanCompletions++ }
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

    /**
     * CAN discovery under the test's control.
     *
     * [gate] is what makes the "one scan at a time" and "Running for the whole
     * window" assertions possible: the call suspends on it until the test says
     * otherwise, which is the shape of a firmware that blocks for ~2.5 s —
     * without ever starting a delayed loop, because an unbounded one wedges
     * `runTest` instead of failing it.
     */
    private class FakeCanDiscovery(
        private var result: Result<List<Int>> = Result.success(listOf(10, 11))
    ) : CanDiscovery {
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun discoverCanIds(address: String): Result<List<Int>> {
            calls += address
            gate?.await()
            return result
        }

        fun answerWith(r: Result<List<Int>>) { result = r }
    }

    private class FakeVehicleRepo(
        private val saved: List<Vehicle>,
        savedPeaks: Map<String, GaugePeaks> = emptyMap()
    ) : VehicleRepository {
        val upserts = mutableListOf<Vehicle>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(saved)
        /**
         * Yields before answering, because the real one does: it is a
         * SQLDelight read, so `initialize()` is genuinely suspended while
         * anything else the component launched runs. A fake that answered
         * without suspending made the load look atomic and hid the fact that
         * `initialize()` replaces the whole state — including a field the
         * connection collector had already filled in.
         */
        /**
         * Held open for as long as [loadGate] is, so a test can put the load
         * exactly where production puts it — finishing AFTER everything else
         * the component launched has already run.
         */
        var loadGate: CompletableDeferred<Unit>? = null
        /** Holds a write after its exact payload has been recorded. */
        var upsertGate: CompletableDeferred<Unit>? = null

        override suspend fun get(id: String): Vehicle? {
            loadGate?.await() ?: yield()
            return upserts.lastOrNull { it.id == id } ?: saved.firstOrNull { it.id == id }
        }
        override suspend fun upsert(vehicle: Vehicle) {
            upserts += vehicle
            upsertGate?.await()
        }
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}

        /**
         * The learned dial widths, in their own store — since `8.sqm` they are not a [Vehicle] field,
         * so [upserts] cannot carry one and this fake cannot accidentally model a composer that
         * "cleared" them by setting a field nothing reads.
         *
         * A vehicle with no entry has learned nothing; the map is not padded (see `GaugePeakRow.sq`),
         * which is what lets `creating a vehicle writes no gauge-peak clear` be a real state rather
         * than a zeroed one.
         */
        val peaks = MutableStateFlow(savedPeaks)
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = peaks

        /**
         * Recorded as a LIST as well as applied to [peaks], because the `G §9.2` tests are about
         * whether the clear HAPPENED — and a store that merely ends up at zero cannot tell "cleared"
         * from "never learned".
         */
        val gaugePeakWrites = mutableListOf<Triple<String, Float, Float>>()
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {
            gaugePeakWrites += Triple(id, currentA, powerW)
            peaks.value = peaks.value + (id to GaugePeaks(currentA, powerW))
        }
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
        // G §9.2's learned dial widths are NOT here, and cannot be: since `8.sqm`
        // they are not fields of a Vehicle at all. Tests that need a vehicle
        // which has been ridden seed [learnedRanges] into the fake instead.
    )

    /**
     * What a vehicle that has been ridden has learned (`G §9.2`) — seeded into the fake repository
     * rather than onto the [Vehicle], because that is where it lives.
     *
     * Deliberately two DIFFERENT numbers, so a save that swapped the amps for the watts is visible,
     * and both non-zero, so a clear is distinguishable from "never learned".
     */
    private val learnedRanges = mapOf("v1" to GaugePeaks(currentA = 137f, powerW = 6421f))

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
        // A vehicle that has been ridden carries no range of its own: the G §9.2 tests below pair
        // this with [learnedRanges] on the fake repository, which is where a range lives.
    )

    private fun component(
        vehicleRepo: FakeVehicleRepo,
        vehicleId: String? = "v1",
        prefilledBmsType: BmsType? = null,
        prefilledBmsAddress: String? = null,
        bmsRepo: FakeBmsRepo = FakeBmsRepo(),
        canDiscovery: CanDiscovery? = null,
        onSaved: () -> Unit = {},
        onCancelled: () -> Unit = {},
        backDispatcher: BackDispatcher? = null
    ): DefaultVehicleEditComponent {
        val ctx = DefaultComponentContext(LifecycleRegistry(), backHandler = backDispatcher)
        return DefaultVehicleEditComponent(
            componentContext = ctx,
            vehicleId = vehicleId,
            vehicleRepository = vehicleRepo,
            bmsRepository = bmsRepo,
            onSaved = onSaved,
            onCancelled = onCancelled,
            onDeleted = {},
            canDiscovery = canDiscovery,
            prefilledBmsType = prefilledBmsType,
            prefilledBmsAddress = prefilledBmsAddress
        )
    }

    /** Real Decompose navigation, with the child instance reduced to Config. */
    @OptIn(DelicateDecomposeApi::class)
    private fun rootStack(initial: Config): Pair<StackNavigation<Config>, Value<ChildStack<Config, Config>>> {
        val context = DefaultComponentContext(LifecycleRegistry())
        val navigation = StackNavigation<Config>()
        val stack = context.childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = initial,
            handleBackButton = true,
            childFactory = { config, _ -> config }
        )
        return navigation to stack
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------- leaving the form

    /**
     * Mutation target: removing any editable field from the saved baseline must
     * fail at that field, while comparing transient state (scan/telemetry/errors)
     * cannot make an untouched form dirty. Each edit is reverted before the next
     * one so a previous assertion cannot carry a later one.
     */
    @Test
    fun `every persisted edit arms dirty and reverting it disarms dirty`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeVehicleRepo(listOf(existingVehicle())))
        advanceUntilIdle()
        val original = c.state.value
        assertFalse(original.isDirty, "fixture check: loading the stored vehicle is not an edit")

        fun changedAndReverted(label: String, change: () -> Unit, revert: () -> Unit) {
            change()
            assertTrue(c.state.value.isDirty, "$label must count as unsaved")
            revert()
            assertFalse(c.state.value.isDirty, "putting $label back must restore the clean baseline")
        }

        changedAndReverted("name", { c.onNameChanged(original.name + " II") }, { c.onNameChanged(original.name) })
        changedAndReverted("icon", { c.onIconChanged("generic") }, { c.onIconChanged(original.iconKey) })
        changedAndReverted("chemistry", { c.onChemistryChanged(Chemistry.LEAD_ACID) }, { c.onChemistryChanged(original.chemistry) })
        changedAndReverted("averaging", { c.onAveragingWindowChanged(1) }, { c.onAveragingWindowChanged(original.averagingWindowMin) })
        changedAndReverted("cell high", { c.onCellHighVChanged(4.29f) }, { c.onCellHighVChanged(original.cellHighV) })
        changedAndReverted("cell low", { c.onCellLowVChanged(2.71f) }, { c.onCellLowVChanged(original.cellLowV) })
        changedAndReverted("temperature warn", { c.onTemperatureWarnChanged(46f) }, { c.onTemperatureWarnChanged(original.temperatureWarnC) })
        changedAndReverted("temperature high", { c.onTemperatureHighChanged(74f) }, { c.onTemperatureHighChanged(original.temperatureHighC) })
        changedAndReverted("SOC low", { c.onSocLowChanged(7) }, { c.onSocLowChanged(original.socLowPercent) })
        changedAndReverted("dashboard", { c.onDashboardStyleChanged(null) }, { c.onDashboardStyleChanged(original.dashboardStyle) })
        changedAndReverted("secondary gauge", { c.onSecondaryGaugeChanged(SecondaryGauge.BATTERY) }, { c.onSecondaryGaugeChanged(original.secondaryGauge) })
        changedAndReverted(
            "head-unit handoff",
            { c.onYieldBmsToHeadUnitChanged(!original.yieldsBmsToHeadUnit) },
            { c.onYieldBmsToHeadUnitChanged(original.yieldsBmsToHeadUnit) }
        )
        val pack = original.draft.packs.single()
        changedAndReverted(
            "source draft",
            { c.onPackLabelChanged(pack.key, pack.label + " X") },
            { c.onPackLabelChanged(pack.key, pack.label) }
        )
    }

    @Test
    fun `adding then removing the same source returns to the clean persisted draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(
            FakeVehicleRepo(listOf(existingVehicle().copy(topology = PackTopology.PARALLEL)))
        )
        advanceUntilIdle()

        c.onAddPack(BmsType.ANT_BMS, "TEMP:01", "Temporary")
        assertTrue(c.state.value.isDirty, "fixture check: the added source must be a persisted change")
        val addedKey = c.state.value.draft.packs.last().key
        c.onRemovePack(addedKey)

        assertFalse(
            c.state.value.isDirty,
            "nextKey is editor bookkeeping; the same persisted source lists are clean"
        )
    }

    @Test
    fun `restoring a pack cell count returns to clean despite its touched flag`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeVehicleRepo(listOf(existingVehicle())))
        advanceUntilIdle()
        val pack = c.state.value.draft.packs.single()
        assertEquals(20, pack.cellCount)

        c.onPackCellCountChanged(pack.key, 24)
        assertTrue(c.state.value.isDirty)
        c.onPackCellCountChanged(pack.key, 20)

        assertFalse(
            c.state.value.isDirty,
            "cellCountEdited changes how Save preserves data, but the projected Pack is unchanged"
        )
    }

    @Test
    fun `grouping then ungrouping the same packs returns to clean persisted aliases`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeVehicleRepo(listOf(wheelWithSecondBranch())))
        advanceUntilIdle()
        val (a, b) = c.state.value.draft.packs.map { it.key }

        c.onGroupPacks(a, b)
        assertTrue(c.state.value.isDirty)
        c.onUngroupPack(a)

        assertFalse(
            c.state.value.isDirty,
            "aliasEdited and the consumed alias key are metadata when both saved Packs are restored"
        )
    }

    @Test
    fun `cancel asks before losing edits and dismiss keeps the exact draft alive`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var cancelled = 0
        val c = component(FakeVehicleRepo(listOf(existingVehicle())), onCancelled = { cancelled++ })
        advanceUntilIdle()

        val key = c.state.value.draft.packs.single().key
        c.onPackAddressChanged(key, "UNSAVED:42")
        assertTrue(c.state.value.isDirty, "fixture check: the destructive branch must actually be reached")
        val exactDraft = c.state.value.draft

        c.onCancel()
        assertTrue(c.state.value.discardPrompt)
        assertEquals(0, cancelled, "Cancel must not destroy the composer before confirmation")

        c.onDiscardDismissed()
        assertFalse(c.state.value.discardPrompt)
        assertEquals(exactDraft, c.state.value.draft, "dismiss keeps the same in-memory composer, not a reconstruction")
        assertEquals(0, cancelled)

        c.onCancel()
        c.onDiscardConfirmed()
        assertFalse(c.state.value.discardPrompt)
        assertEquals(1, cancelled, "confirm completes the exact pending exit")
    }

    @Test
    fun `clean Cancel leaves a picker-created one-entry editor for real home navigation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val edit = Config.VehicleEdit(vehicleId = "v1")
        val (navigation, stack) = rootStack(edit)
        val c = component(
            FakeVehicleRepo(listOf(existingVehicle())),
            onCancelled = {
                leaveVehicleEdit(navigation, stack.value.items.size, Config.Dashboard)
            }
        )
        advanceUntilIdle()
        assertEquals(listOf(edit), stack.value.items.map { it.configuration })

        c.onCancel()

        assertEquals(Config.Dashboard, stack.value.active.configuration)
        assertEquals(1, stack.value.items.size)
    }

    @Test
    fun `dirty confirmation leaves a picker-created one-entry editor for real home navigation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val edit = Config.VehicleEdit(vehicleId = "v1")
        val (navigation, stack) = rootStack(edit)
        val c = component(
            FakeVehicleRepo(listOf(existingVehicle())),
            onCancelled = {
                leaveVehicleEdit(navigation, stack.value.items.size, Config.Dashboard)
            }
        )
        advanceUntilIdle()
        c.onNameChanged("Unsaved")

        c.onCancel()
        assertTrue(c.state.value.discardPrompt)
        assertEquals(edit, stack.value.active.configuration, "the dirty form stays until confirmation")
        c.onDiscardConfirmed()

        assertEquals(Config.Dashboard, stack.value.active.configuration)
        assertEquals(1, stack.value.items.size)
    }

    @Test
    @OptIn(DelicateDecomposeApi::class)
    fun `leaving a multi-entry editor returns to its actual previous screen`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (navigation, stack) = rootStack(Config.Settings)
        navigation.push(Config.VehicleEdit(vehicleId = "v1"))
        assertEquals(2, stack.value.items.size)

        leaveVehicleEdit(navigation, stack.value.items.size, Config.Dashboard)

        assertEquals(Config.Settings, stack.value.active.configuration)
        assertEquals(1, stack.value.items.size)
    }

    @Test
    fun `a prefilled new vehicle starts clean and reverting its first edit is clean again`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var cancelled = 0
        val c = component(
            vehicleRepo = FakeVehicleRepo(emptyList()),
            vehicleId = null,
            prefilledBmsType = BmsType.ANT_BMS,
            prefilledBmsAddress = "NEW:01",
            onCancelled = { cancelled++ }
        )
        advanceUntilIdle()
        assertFalse(c.state.value.isDirty, "prefill is the starting point, not a rider edit")

        c.onNameChanged("New vehicle")
        assertTrue(c.state.value.isDirty, "the create path needs the same protection as edit")
        c.onNameChanged("")
        assertFalse(c.state.value.isDirty, "reverting to the create baseline removes the prompt")

        c.onCancel()
        assertEquals(1, cancelled)
        assertFalse(c.state.value.discardPrompt)
    }

    @Test
    fun `a destructive root action reveals a dirty buried composer and waits for its confirmation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeVehicleRepo(listOf(existingVehicle())))
        advanceUntilIdle()
        c.onNameChanged("Buried draft")
        assertTrue(c.state.value.isDirty, "fixture check: otherwise the guard is vacuous")
        val revealed = mutableListOf<Int>()
        val destinations = mutableListOf<Config>()

        guardComposerDestruction(
            children = listOf(RootComponent.Child.VehicleEdit(c), RootComponent.Child.Loading),
            revealComposerAt = { revealed += it },
            destroyStack = { destinations += Config.Scanning }
        )

        assertEquals(listOf(0), revealed, "the buried form must be brought up so its prompt is visible")
        assertTrue(c.state.value.discardPrompt, "the pending discard is observable by the renderer")
        assertEquals(emptyList(), destinations, "disconnect/navigation is withheld")

        c.onDiscardDismissed()
        assertEquals("Buried draft", c.state.value.name)
        assertEquals(emptyList(), destinations, "dismiss cancels the pending destructive action")

        guardComposerDestruction(
            children = listOf(RootComponent.Child.VehicleEdit(c), RootComponent.Child.Loading),
            revealComposerAt = { revealed += it },
            destroyStack = { destinations += Config.Scanning }
        )
        c.onDiscardConfirmed()
        assertEquals(listOf<Config>(Config.Scanning), destinations, "confirm publishes the requested destination")
    }

    @Test
    fun `a destructive root action proceeds immediately when the buried composer is clean`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeVehicleRepo(listOf(existingVehicle())))
        advanceUntilIdle()
        val revealed = mutableListOf<Int>()
        val destinations = mutableListOf<Config>()

        guardComposerDestruction(
            children = listOf(RootComponent.Child.VehicleEdit(c), RootComponent.Child.Loading),
            revealComposerAt = { revealed += it },
            destroyStack = { destinations += Config.Scanning }
        )

        assertFalse(c.state.value.isDirty, "fixture check: this is the no-edits branch")
        assertFalse(c.state.value.discardPrompt)
        assertEquals(emptyList(), revealed, "an untouched form must not be surfaced or nag")
        assertEquals(listOf<Config>(Config.Scanning), destinations)
    }

    @Test
    fun `each dirty composer in the stack gets its own discard decision before destruction`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val older = component(FakeVehicleRepo(listOf(existingVehicle())))
        val newerVehicle = existingVehicle().copy(id = "v2", name = "Second vehicle")
        val newer = component(FakeVehicleRepo(listOf(newerVehicle)), vehicleId = "v2")
        advanceUntilIdle()
        older.onNameChanged("Older draft")
        newer.onNameChanged("Newer draft")
        assertTrue(older.state.value.isDirty && newer.state.value.isDirty, "fixture check: both drafts can be lost")

        val children = listOf(
            RootComponent.Child.VehicleEdit(older),
            RootComponent.Child.Loading,
            RootComponent.Child.VehicleEdit(newer)
        )
        val revealed = mutableListOf<Int>()
        val destinations = mutableListOf<Config>()
        guardComposerDestruction(
            children = children,
            revealComposerAt = { revealed += it },
            destroyStack = { destinations += Config.Scanning }
        )
        assertEquals(listOf(2), revealed, "the topmost dirty editor asks first")
        newer.onDiscardConfirmed()
        assertEquals(listOf(2, 0), revealed, "confirming one must reveal the other, not destroy it")
        assertEquals(emptyList(), destinations)

        older.onDiscardConfirmed()
        assertEquals(listOf<Config>(Config.Scanning), destinations)
    }

    @Test
    fun `partial multi-composer approval is forgotten when the next rider dismisses`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val older = component(FakeVehicleRepo(listOf(existingVehicle())))
        val newerVehicle = existingVehicle().copy(id = "v2", name = "Second vehicle")
        val newer = component(FakeVehicleRepo(listOf(newerVehicle)), vehicleId = "v2")
        advanceUntilIdle()
        older.onNameChanged("Older draft")
        newer.onNameChanged("Newer draft")
        val children = listOf(
            RootComponent.Child.VehicleEdit(older),
            RootComponent.Child.Loading,
            RootComponent.Child.VehicleEdit(newer)
        )
        val revealed = mutableListOf<Int>()
        val destinations = mutableListOf<Config>()

        guardComposerDestruction(children, { revealed += it }, { destinations += Config.Scanning })
        newer.onDiscardConfirmed()
        assertEquals(listOf(2, 0), revealed)
        older.onDiscardDismissed()

        assertTrue(newer.state.value.isDirty, "approval is not a persisted baseline and must not clean the draft")
        assertTrue(older.state.value.isDirty)
        assertEquals(emptyList(), destinations)

        guardComposerDestruction(children, { revealed += it }, { destinations += Config.Dashboard })
        assertEquals(2, revealed.last(), "a later transaction starts fresh at the newest dirty composer")
        assertTrue(newer.state.value.discardPrompt)
    }

    @Test
    fun `a visible discard prompt keeps the first destructive continuation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(FakeVehicleRepo(listOf(existingVehicle())))
        advanceUntilIdle()
        c.onNameChanged("Pending draft")
        val destinations = mutableListOf<Config>()

        c.requestExit { destinations += Config.Dashboard }
        assertTrue(c.state.value.discardPrompt)
        c.requestExit { destinations += Config.Scanning }
        c.onDiscardConfirmed()

        assertEquals(
            listOf<Config>(Config.Dashboard),
            destinations,
            "the prompt the rider saw must confirm the request that raised it"
        )
    }

    @Test
    fun `system back uses the same dirty discard contract as Cancel`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val back = BackDispatcher()
        var cancelled = 0
        val c = component(
            FakeVehicleRepo(listOf(existingVehicle())),
            onCancelled = { cancelled++ },
            backDispatcher = back
        )
        advanceUntilIdle()
        c.onNameChanged("Gesture draft")

        assertTrue(back.back(), "the child must intercept Decompose's unconditional stack pop")
        assertTrue(c.state.value.discardPrompt)
        assertEquals(0, cancelled)
    }

    @Test
    fun `system back on an untouched composer leaves immediately without prompting`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val back = BackDispatcher()
        var cancelled = 0
        val c = component(
            FakeVehicleRepo(listOf(existingVehicle())),
            onCancelled = { cancelled++ },
            backDispatcher = back
        )
        advanceUntilIdle()
        assertFalse(c.state.value.isDirty, "fixture check: this is the established no-edits branch")

        assertTrue(back.back())
        assertEquals(1, cancelled)
        assertFalse(c.state.value.discardPrompt)
    }

    @Test
    fun `saving establishes the new clean baseline before navigation is published`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        lateinit var c: DefaultVehicleEditComponent
        var dirtyWhenSaved: Boolean? = null
        c = component(
            FakeVehicleRepo(listOf(existingVehicle())),
            onSaved = { dirtyWhenSaved = c.state.value.isDirty }
        )
        advanceUntilIdle()
        c.onNameChanged("Persisted name")
        assertTrue(c.state.value.isDirty)

        c.onSave()
        advanceUntilIdle()

        assertEquals(false, dirtyWhenSaved, "root must not see a false dirty prompt after Save")
        assertFalse(c.state.value.isDirty)
    }

    @Test
    fun `an older save keeps a newer pending discard prompt visible and actionable`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        repo.upsertGate = CompletableDeferred()
        val destinations = mutableListOf<Config>()
        lateinit var c: DefaultVehicleEditComponent
        c = component(
            repo,
            onSaved = { c.requestExit { destinations += Config.Scanning } },
            onCancelled = { destinations += Config.Dashboard }
        )
        advanceUntilIdle()

        c.onNameChanged("Saved A")
        c.onSave()
        advanceUntilIdle()
        assertEquals("Saved A", repo.upserts.single().name, "save A is suspended after issuing its exact payload")
        assertTrue(c.state.value.saving)

        c.onNameChanged("Unsaved B")
        c.onCancel()
        assertTrue(c.state.value.discardPrompt)
        repo.upsertGate!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(c.state.value.isDirty, "B was never part of save A")
        assertTrue(c.state.value.discardPrompt, "the pending Cancel must remain visible after A completes")
        assertEquals(emptyList(), destinations, "onSaved cannot replace the request that owns the prompt")

        c.onDiscardConfirmed()
        assertEquals(listOf<Config>(Config.Dashboard), destinations, "confirmation executes the original Cancel")
        assertFalse(c.state.value.discardPrompt)
    }

    @Test
    fun `dismissing a prompt preserved across save completion allows a later exit`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        repo.upsertGate = CompletableDeferred()
        val destinations = mutableListOf<Config>()
        lateinit var c: DefaultVehicleEditComponent
        c = component(
            repo,
            onSaved = { c.requestExit { destinations += Config.Scanning } },
            onCancelled = { destinations += Config.Dashboard }
        )
        advanceUntilIdle()

        c.onNameChanged("Saved A")
        c.onSave()
        advanceUntilIdle()
        c.onNameChanged("Unsaved B")
        c.onCancel()
        repo.upsertGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(c.state.value.discardPrompt)

        c.onDiscardDismissed()
        assertTrue(c.state.value.isDirty)
        assertFalse(c.state.value.discardPrompt)
        assertEquals(emptyList(), destinations)

        c.requestExit { destinations += Config.Settings }
        assertTrue(c.state.value.discardPrompt, "dismissal clears the old action so a later request can ask")
        c.onDiscardConfirmed()
        assertEquals(listOf<Config>(Config.Settings), destinations)
    }

    @Test
    fun `save completion retires an obsolete pending discard when the current draft is clean`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        repo.upsertGate = CompletableDeferred()
        val destinations = mutableListOf<Config>()
        lateinit var c: DefaultVehicleEditComponent
        c = component(
            repo,
            onSaved = { c.requestExit { destinations += Config.Scanning } },
            onCancelled = { destinations += Config.Dashboard }
        )
        advanceUntilIdle()

        c.onNameChanged("Saved A")
        c.onSave()
        advanceUntilIdle()
        c.onCancel()
        assertTrue(c.state.value.discardPrompt, "A differs from the old baseline until its write completes")

        repo.upsertGate!!.complete(Unit)
        advanceUntilIdle()

        assertFalse(c.state.value.isDirty)
        assertFalse(c.state.value.discardPrompt)
        assertEquals(
            listOf<Config>(Config.Scanning),
            destinations,
            "the obsolete Cancel is retired and the clean onSaved request proceeds"
        )
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

    // --- G §9.2 item 7: a learned dial range describes hardware -----------------------------------

    /**
     * **Adding a second controller doubles what the vehicle can pull, so the learned dial widths no
     * longer describe it and must be cleared.**
     *
     * Hung off the same recomputation the derived-battery rule uses, which is why this test also
     * asserts the controller list actually changed — otherwise a component that cleared the peaks
     * unconditionally would pass.
     */
    @Test
    fun `adding a controller clears the learned dial ranges`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()), learnedRanges)
        val c = component(repo)
        advanceUntilIdle()

        c.onAddController(ControllerType.VESC, "CC:DD", "uBox R")
        c.onSave()
        advanceUntilIdle()

        assertEquals(2, repo.upserts.single().controllers.size, "the hardware really did change")
        // The clear is asserted on the explicit write, because there is nowhere else it could
        // happen: since `8.sqm` the ranges are not fields of the upserted Vehicle at all.
        assertEquals(listOf(Triple("v1", 0f, 0f)), repo.gaugePeakWrites)
        assertEquals(
            GaugePeaks(currentA = 0f, powerW = 0f), repo.peaks.value["v1"],
            "...and the store really is at zero afterwards, not merely written to"
        )
    }

    @Test
    fun `removing a controller clears the learned dial ranges too`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rear = Controller(index = 1, label = "Rear", controllerType = ControllerType.VESC, address = "CC:DD")
        val repo = FakeVehicleRepo(
            listOf(controllerOnlyVehicle().copy(controllers = originalControllers + rear)),
            learnedRanges
        )
        val c = component(repo)
        advanceUntilIdle()

        c.onRemoveController(c.state.value.draft.controllers[1].key)
        c.onSave()
        advanceUntilIdle()

        assertEquals(listOf(Triple("v1", 0f, 0f)), repo.gaugePeakWrites)
    }

    /**
     * **The other direction, and the one that keeps the rule from being "any controller edit".**
     *
     * Renaming a card and correcting a motor's geometry both mark `controllersEdited`, but neither
     * changes an ohm of hardware — throwing away a range that took a whole ride to learn because
     * somebody fixed a typo would be its own defect. `GaugeScale.peaksStillApply` owns which fields
     * count, and this test is what stops the condition drifting back to the edited flag.
     */
    @Test
    fun `renaming a controller or fixing its geometry keeps the learned dial ranges`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()), learnedRanges)
        val c = component(repo)
        advanceUntilIdle()

        val key = c.state.value.draft.controllers.single().key
        c.onControllerLabelChanged(key, "Front")
        c.onControllerWheelDiameterChanged(key, 660)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        assertEquals("Front", saved.controllers.single().label, "the edit really did land")
        assertEquals(660, saved.controllers.single().motor.wheelDiameterMm)
        assertEquals(
            emptyList(), repo.gaugePeakWrites,
            "a cosmetic edit must not touch the stored peaks at all"
        )
        // And the STORE still holds what the rider learned. This is what the deleted
        // `saved.gaugePeakCurrentA` assertions were protecting -- that a save which had no business
        // touching the ranges leaves them where they were -- asked of the place they now live.
        assertEquals(GaugePeaks(currentA = 137f, powerW = 6421f), repo.peaks.value["v1"])
    }

    /**
     * A controller's ADDRESS is hardware identity: a rider who corrects it is pointing the vehicle at
     * a different board, whatever the card is called.
     */
    @Test
    fun `re-addressing a controller clears the learned dial ranges`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(controllerOnlyVehicle()), learnedRanges)
        val c = component(repo)
        advanceUntilIdle()

        c.onControllerAddressChanged(c.state.value.draft.controllers.single().key, "ZZ:ZZ")
        c.onSave()
        advanceUntilIdle()
        assertEquals(listOf(Triple("v1", 0f, 0f)), repo.gaugePeakWrites)
    }

    /**
     * The CREATE path has nothing to clear and must not pretend otherwise: a vehicle that does not
     * exist yet has no stored peaks, and `updateGaugePeaks` on it would be a write against a row the
     * upsert has only just inserted at zero.
     */
    @Test
    fun `creating a vehicle writes no gauge-peak clear`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(emptyList())
        val c = component(repo, vehicleId = null, prefilledBmsType = BmsType.JK_BMS, prefilledBmsAddress = "AA:BB")
        advanceUntilIdle()
        c.onNameChanged("Fresh")
        c.onSave()
        advanceUntilIdle()
        assertEquals(1, repo.upserts.size)
        assertEquals(emptyList(), repo.gaugePeakWrites)
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
     * rest of the process, so nothing would ever restore it.
     *
     * This is the UNTOUCHED path — the rider edited some OTHER pack field and
     * never typed a cell count in this session (`PackDraft.cellCountEdited`
     * stays false), so the freshly-read row must still win. Task 3 made the
     * field editable again for the wheel that has no auto-fill to fall back
     * on at all (no smart BMS, no cell frames, ever) — see
     * `an edited cell count is not reverted by a save` for that side of it.
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
     * The other side of the switch above (Task 3): once the RIDER has typed a
     * cell count in this session, [PackDraft.cellCountEdited] is true and that
     * typed value — not whatever the freshly-read row now holds — is what
     * this save writes. This is the whole point on a wheel with no smart BMS:
     * nothing else will ever supply this number.
     *
     * `KableBmsRepository.maybePersistCellCount` keeps running on the SAME
     * live connection and is INTENDED to win with its own measurement over
     * whatever a rider typed on a smart-BMS wheel — but not necessarily on
     * the very next sample: `lastPersistedCellCount`'s de-dupe is never
     * reset, so if the auto-fill already persisted this same count once
     * before, it will not repeat the write until the wheel reports a
     * genuinely different count. This test does not exercise that timing —
     * only that a save this form performs never reverts what the rider just
     * typed.
     */
    @Test
    fun `an edited cell count is not reverted by a save`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = existingVehicle().copy(packs = listOf(existingVehicle().packs.single().copy(cellCount = null)))
        val repo = FakeVehicleRepo(listOf(v))
        val c = component(repo)
        advanceUntilIdle()

        // A row moved underneath the form too, exactly like the test above —
        // the rider's typed value must win over THIS fresh row, not merely
        // over the load-time snapshot.
        repo.upsert(v.copy(packs = listOf(v.packs.single().copy(cellCount = 30))))

        c.onPackCellCountChanged(c.state.value.draft.packs.single().key, 24)
        c.onSave()
        advanceUntilIdle()

        assertEquals(24, repo.upserts.last().packs.single().cellCount, "the rider's typed value must land")
    }

    /**
     * Clearing the field is a distinct act from typing a smaller number —
     * `onPackCellCountChanged(key, null)` must land `null`, not silently keep
     * whatever the pack already had. `IntField`'s own blank-clears-to-null
     * behaviour is what feeds `null` in here from the real UI; this test
     * pins what the component does with it once it arrives.
     */
    @Test
    fun `clearing the cell count field persists null, not the old value`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle())) // pack's cellCount = 20
        val c = component(repo)
        advanceUntilIdle()

        c.onPackCellCountChanged(c.state.value.draft.packs.single().key, null)
        c.onSave()
        advanceUntilIdle()

        assertEquals(null, repo.upserts.single().packs.single().cellCount, "a cleared field must save as null")
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
        val repo = FakeVehicleRepo(listOf(wheelWithSecondBranch()))
        val c = component(repo)
        advanceUntilIdle()

        assertEquals(PackTopology.SERIES, c.state.value.topology, "loaded from the vehicle")
        c.onTopologyChanged(PackTopology.PARALLEL)
        c.onSave()
        advanceUntilIdle()

        assertEquals(PackTopology.PARALLEL, repo.upserts.single().topology)
    }

    @Test
    fun `topology choice is visible only for two packs and removal clears stale series`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(existingVehicle()))
        val c = component(repo)
        advanceUntilIdle()

        assertFalse(c.state.value.showTopologyChoice, "one pack has no wiring choice")
        c.onAddPack(BmsType.ANT_BMS, "AN:02", "Second")
        assertTrue(c.state.value.showTopologyChoice, "the second pack makes wiring meaningful")
        c.onTopologyChanged(PackTopology.SERIES)
        assertEquals(PackTopology.SERIES, c.state.value.topology, "the visible choice must apply")

        val secondKey = c.state.value.draft.packs.last().key
        c.onRemovePack(secondKey)

        assertFalse(c.state.value.showTopologyChoice)
        assertEquals(PackTopology.PARALLEL, c.state.value.topology)
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
        c.onPackCellCountChanged(pk, 24)
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
                    // Editable again since Task 3 — the rider's typed value,
                    // not the fixture's stored 20.
                    cellCount = 24,
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
     * The creation form owns the same draft as an existing vehicle, so a scan
     * can contribute a source without the rider ever transcribing its address.
     */
    @Test
    fun `a create path can compose a scanned source without typing its address`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(emptyList())
        val bms = FakeBmsRepo(listOf(device("VE:01", "VESC", controllerType = ControllerType.VESC)))
        val c = component(repo, vehicleId = null, bmsRepo = bms)
        advanceUntilIdle()
        assertTrue(c.state.value.canComposeSources)

        c.onNameChanged("Fresh")
        c.onStartDeviceScan()
        advanceUntilIdle()
        c.onAddScannedDevice(c.state.value.scannedDevices.single(), ScannedAdd.CONTROLLER)

        assertEquals("VE:01", c.state.value.draft.controllers.single().address)
        assertTrue(c.state.value.controllersEdited)
    }

    /** A vehicle with an undiallable draft source must not be written. */
    @Test
    fun `a create with a blank draft address is refused`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(emptyList())
        val c = component(repo, vehicleId = null)
        advanceUntilIdle()

        c.onNameChanged("Fresh")
        assertTrue(c.state.value.issues.any { it is ComposerIssue.BlankAddress })

        c.onSave()
        advanceUntilIdle()

        assertEquals(emptyList(), repo.upserts)
        assertTrue(c.state.value.saveBlocked)
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

    // =====================================================================
    // Discovery (G2 Task 5) — the composer learns an address
    //
    // Until this task, adding a second source meant typing a BLE MAC by hand.
    // Everything below is the component's half; the pure rules it delegates to
    // are in SourceScanTest and ComposerCanDiscoveryTest.
    // =====================================================================

    /** A head unit as the picker leaves it: one VESC controller, no CAN id. */
    private fun headUnitVehicle(motor: MotorConfig = MotorConfig()) = Vehicle(
        id = "v1",
        name = "Scooter",
        iconKey = "scooter",
        packs = emptyList(),
        controllers = listOf(
            Controller(
                index = 0,
                label = "Head unit",
                controllerType = ControllerType.VESC,
                address = "HU:01",
                motor = motor
            )
        ),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = createdAtFixture
    )

    private fun device(
        address: String,
        name: String? = null,
        bmsType: BmsType? = null,
        controllerType: ControllerType? = null
    ) = DiscoveredDevice(address, name, -55, bmsType, controllerType)

    // ---------------------------------------------------------------------
    // BLE scan into the composer
    // ---------------------------------------------------------------------

    /**
     * The deliverable in one assertion: a source arrives with an address the
     * rider never typed.
     */
    @Test
    fun `a scanned controller enters the draft with its own address`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val bms = FakeBmsRepo(listOf(device("UB:10", "uBox", controllerType = ControllerType.VESC)))
        val c = component(FakeVehicleRepo(listOf(headUnitVehicle())), bmsRepo = bms)
        advanceUntilIdle()

        c.onStartDeviceScan()
        advanceUntilIdle()
        val found = c.state.value.scannedDevices.single()
        c.onAddScannedDevice(found, ScannedAdd.CONTROLLER)

        val added = c.state.value.draft.controllers.last()
        assertEquals("UB:10", added.address)
        assertEquals(ControllerType.VESC, added.controllerType)
        assertEquals("uBox", added.label)
        assertTrue(c.state.value.controllersEdited)
    }

    /**
     * `G §3` flow 3, and the reason it is a single add: **one link**. Asserted
     * through the real `planLinks`, because "one link" is its outcome and not a
     * property of two equal strings.
     *
     * Both edited flags matter as much as the shapes: `withEdits` writes a list
     * only for the half the rider took control of, so a wheel add that flagged
     * one half would drop the other on save.
     */
    @Test
    fun `a scanned wheel is one add, two sources, one link`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val bms = FakeBmsRepo(listOf(device("WH:01", "Falcon", bmsType = BmsType.BEGODE)))
        val repo = FakeVehicleRepo(listOf(headUnitVehicle()))
        val c = component(repo, bmsRepo = bms)
        advanceUntilIdle()

        c.onStartDeviceScan()
        advanceUntilIdle()
        c.onAddScannedDevice(c.state.value.scannedDevices.single(), ScannedAdd.WHEEL)

        val s = c.state.value
        assertTrue(s.packsEdited && s.controllersEdited, "a wheel writes both lists")
        val wheelController = s.draft.controllers.last()
        val wheelPack = s.draft.packs.last()
        assertEquals(ControllerType.BEGODE, wheelController.controllerType)
        assertEquals(BmsType.BEGODE, wheelPack.bmsType)

        // The claim the add records: these two are ONE device.
        val links = planLinks(s.draft.toPacks(), s.draft.toControllers())
        val wheelLink = links.single { it.address == "WH:01" }
        assertEquals(1, wheelLink.ownedControllers.size)
        assertEquals(1, wheelLink.ownedPacks.size)

        // And it survives the save, which is where the claim has to end up.
        c.onSave()
        advanceUntilIdle()
        val saved = repo.upserts.last()
        assertEquals(1, saved.controllers.count { it.address == "WH:01" })
        assertEquals(1, saved.packs.count { it.bmsAddress == "WH:01" })
    }

    @Test
    fun `a scanned battery enters the draft as a pack only`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val bms = FakeBmsRepo(listOf(device("AN:01", "ANT", bmsType = BmsType.ANT_BMS)))
        val c = component(FakeVehicleRepo(listOf(headUnitVehicle())), bmsRepo = bms)
        advanceUntilIdle()

        c.onStartDeviceScan()
        advanceUntilIdle()
        c.onAddScannedDevice(c.state.value.scannedDevices.single(), ScannedAdd.BATTERY)

        val s = c.state.value
        assertEquals(1, s.draft.packs.size)
        assertEquals("AN:01", s.draft.packs.single().address)
        assertEquals(BmsType.ANT_BMS, s.draft.packs.single().bmsType)
        assertEquals(1, s.draft.controllers.size, "a battery add must not invent a controller")
        assertTrue(s.packsEdited)
        assertFalse(s.controllersEdited)
    }

    /**
     * Two taps before the first recomposition must not start two collectors:
     * each hit would then be folded twice, and the sheet would offer the same
     * device on two rows.
     */
    @Test
    fun `a second scan start does not open a second scan`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val bms = FakeBmsRepo(listOf(device("UB:10", controllerType = ControllerType.VESC)))
        val c = component(FakeVehicleRepo(listOf(headUnitVehicle())), bmsRepo = bms)
        advanceUntilIdle()

        c.onStartDeviceScan()
        c.onStartDeviceScan()
        advanceUntilIdle()
        assertEquals(1, bms.scanCollections)
        assertEquals(1, c.state.value.scannedDevices.size)
    }

    /** A scan must not outlive its sheet, and its list must not linger. */
    @Test
    fun `closing the sheet stops the scan and drops what it found`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val bms = FakeBmsRepo(listOf(device("UB:10", controllerType = ControllerType.VESC)))
        val c = component(FakeVehicleRepo(listOf(headUnitVehicle())), bmsRepo = bms)
        advanceUntilIdle()

        c.onStartDeviceScan()
        advanceUntilIdle()
        assertTrue(c.state.value.scanning)

        c.onStopDeviceScan()
        advanceUntilIdle()
        assertFalse(c.state.value.scanning)
        assertEquals(emptyList(), c.state.value.scannedDevices)
        // The radio, not the flag: a stop that dropped the job reference without
        // cancelling it leaves the BLE scan running for the life of the process,
        // which no assertion on the state could ever see.
        assertEquals(bms.scanCollections, bms.scanCompletions, "no scan may outlive its sheet")

        // And re-opening starts a genuinely new one.
        c.onStartDeviceScan()
        advanceUntilIdle()
        assertTrue(c.state.value.scanning)
        assertEquals(2, bms.scanCollections)
        assertEquals(1, c.state.value.scannedDevices.size)
        c.onStopDeviceScan()
    }

    // ---------------------------------------------------------------------
    // CAN discovery
    // ---------------------------------------------------------------------

    /** Connect the fake so the composer sees a live link at the head unit. */
    private fun FakeBmsRepo.goLive(vehicle: Vehicle) {
        activeVehicle.value = vehicle
        connectionState.value = ConnectionState.Connected(vehicle)
    }

    @Test
    fun `with nothing connected there is no scan target and the call does nothing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val can = FakeCanDiscovery()
        val c = component(FakeVehicleRepo(listOf(headUnitVehicle())), canDiscovery = can)
        advanceUntilIdle()

        assertNull(c.state.value.canScanTarget)
        c.onDiscoverCanDevices()
        advanceUntilIdle()
        assertEquals(emptyList(), can.calls, "the screen must not pretend to scan")
        assertEquals(CanScanState.Idle, c.state.value.canScan)
    }

    @Test
    fun `connecting the head unit gives the composer a scan target`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        assertNull(c.state.value.canScanTarget)

        bms.goLive(v)
        advanceUntilIdle()
        assertEquals("HU:01", c.state.value.canScanTarget)

        // …and loses it again when the link drops, so the button follows the
        // connection rather than being decided once at load.
        bms.connectionState.value = ConnectionState.Reconnecting(1, "drop")
        advanceUntilIdle()
        assertNull(c.state.value.canScanTarget)
    }

    /**
     * The ordering `G §3` flow 4 actually has: the picker connects and navigates
     * **straight here**, so the connection is already up before this component
     * exists. `initialize()` replaces the whole state wholesale, so a target
     * seeded only by the connection collector would be clobbered by a load that
     * finishes after it — and there may never be another connection event to
     * put it back.
     */
    @Test
    fun `arriving already connected shows the scan target immediately`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        bms.goLive(v)
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()

        assertEquals("HU:01", c.state.value.canScanTarget)
    }

    /**
     * The same thing with production's own ordering, which is the one that
     * bites: the vehicle load is a SQLDelight read, so it finishes long after
     * the connection collector has already reported a live link — and
     * `initialize()` then replaces the **whole** state. Without a target
     * computed at load, the collector's answer is erased by a load that came
     * second, and there is no further connection event to put it back.
     */
    @Test
    fun `a slow load must not erase a target the connection already reported`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        bms.goLive(v)
        val repo = FakeVehicleRepo(listOf(v))
        repo.loadGate = CompletableDeferred()
        val c = component(repo, bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        // Everything but the load has now run: the collector has seen the live
        // link, and `initialize()` is still parked on the database.
        advanceUntilIdle()

        repo.loadGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals("HU:01", c.state.value.canScanTarget)
    }

    /**
     * The target follows the DRAFT too, not only the connection: a rider who
     * corrects a misdetected controller's type is now behind a VESC gateway and
     * the scan becomes possible without anything reconnecting.
     */
    @Test
    fun `editing the draft can reveal a scan target`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle().copy(
            controllers = listOf(
                Controller(0, "Head unit", ControllerType.BEGODE, "HU:01", providesDerivedBattery = true)
            )
        )
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()
        assertNull(c.state.value.canScanTarget, "a Begode has no CAN bus to probe")

        c.onControllerTypeChanged(c.state.value.draft.controllers.single().key, ControllerType.VESC)
        assertEquals("HU:01", c.state.value.canScanTarget)
    }

    /**
     * The firmware blocks for ~2.5 s and reports nothing of its own, so
     * `Running` has to hold for the whole call — it is the only thing the screen
     * can show. Asserted while the discovery is still suspended.
     */
    @Test
    fun `the scan reports Running for the whole window`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val can = FakeCanDiscovery()
        can.gate = CompletableDeferred()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = can)
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()

        c.onDiscoverCanDevices()
        advanceUntilIdle()
        assertEquals(CanScanState.Running, c.state.value.canScan, "the whole 2.5 s window")

        can.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(CanScanState.Found("HU:01", listOf(10, 11)), c.state.value.canScan)
    }

    /**
     * `C §10.2`: a second `PING_CAN` inside the window is **silently discarded
     * with no error reply**, so a rider who taps twice would wait out a window
     * that can never answer. The UI must not let them.
     */
    @Test
    fun `a second tap inside the window is refused`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val can = FakeCanDiscovery()
        can.gate = CompletableDeferred()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = can)
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()

        c.onDiscoverCanDevices()
        advanceUntilIdle()
        c.onDiscoverCanDevices()
        c.onDiscoverCanDevices()
        advanceUntilIdle()
        assertEquals(listOf("HU:01"), can.calls, "exactly one PING_CAN may be in flight")

        // Once it has answered, another scan IS allowed — the refusal is the
        // window, not a one-shot.
        can.gate!!.complete(Unit)
        advanceUntilIdle()
        c.onDiscoverCanDevices()
        advanceUntilIdle()
        assertEquals(listOf("HU:01", "HU:01"), can.calls)
    }

    /** `G §3`, twice: never auto-add. Discovery produces offers, nothing else. */
    @Test
    fun `a successful scan adds nothing on its own`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()
        val before = c.state.value.draft

        c.onDiscoverCanDevices()
        advanceUntilIdle()

        assertEquals(before, c.state.value.draft, "discovery offers; the rider adds")
        assertEquals(3, c.state.value.canCandidates.size, "two nodes plus the hosted battery")
    }

    /**
     * **The trap.** Every source added from discovery must carry its real
     * `canId`: on a gateway link a null id means "the head unit itself", whose
     * request `VescGatewayProtocol` sends UNWRAPPED — two of them are
     * byte-identical and `MotionAggregator` sums the same decode twice.
     *
     * So the two uBoxes come in with 10 and 11, the hosted battery is the one
     * source that keeps null, and the whole draft validates clean.
     */
    @Test
    fun `discovered devices are added with their real can ids`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()
        c.onDiscoverCanDevices()
        advanceUntilIdle()

        val nodes = c.state.value.canCandidates.filter { it.kind == CanCandidateKind.NODE }
        nodes.forEach { c.onAddCanCandidate(it, asBattery = false) }
        val hosted = c.state.value.canCandidates.single { it.kind == CanCandidateKind.HOSTED_BATTERY }
        c.onAddCanCandidate(hosted, asBattery = true)

        val s = c.state.value
        // The head unit keeps null; every discovered node carries its own id.
        assertEquals(listOf(null, 10, 11), s.draft.controllers.map { it.canId })
        assertEquals(listOf(null), s.draft.packs.map { it.canId })
        assertEquals(BmsType.VESC_BMS, s.draft.packs.single().bmsType)
        assertEquals(
            emptyList(),
            s.issues,
            "one head unit, two identified slaves and a hosted battery is a legal gateway"
        )
        assertEquals("HU:01", s.draft.linkAddresses.single(), "everything behind one link")
    }

    /**
     * **Part I Task 6.** A controller found on the gateway's CAN bus arrives with
     * the gateway's wheel geometry, not with a bare `MotorConfig()` — whose
     * `wheelDiameterMm` is 0, which `VescValues.derivedSpeedKmh` refuses to
     * derive a speed from, permanently, for any slave that answers `GET_VALUES`
     * and not SETUP.
     *
     * The head unit here carries a geometry a rider actually measured, and every
     * one of its three fields differs from `MotorConfig()`'s own: a fix that
     * copied the diameter alone would leave the pole pairs at 15 and produce a
     * speed off by a factor of 1.4, which reads as a measurement.
     *
     * Asserted through `onSave` as well as on the draft, because the draft is
     * where the geometry is CARRIED and the stored [MotorConfig] is what the
     * decoder will actually be handed.
     */
    @Test
    fun `a CAN-discovered controller inherits the gateway's wheel`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val measured = MotorConfig(polePairs = 21, wheelDiameterMm = 500, gearRatio = 3.5f)
        val v = headUnitVehicle(motor = measured)
        val bms = FakeBmsRepo()
        val repo = FakeVehicleRepo(listOf(v))
        val c = component(repo, bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()
        c.onDiscoverCanDevices()
        advanceUntilIdle()

        c.state.value.canCandidates
            .filter { it.kind == CanCandidateKind.NODE }
            .forEach { c.onAddCanCandidate(it, asBattery = false) }

        assertEquals(
            listOf(MotorDraft.of(measured), MotorDraft.of(measured), MotorDraft.of(measured)),
            c.state.value.draft.controllers.map { it.motor },
            "both discovered slaves start from the wheel the rider already measured"
        )

        c.onSave()
        advanceUntilIdle()
        assertEquals(
            listOf(measured, measured, measured),
            repo.upserts.single().controllers.map { it.motor }
        )
    }

    /**
     * The other half of the same rule: a gateway nobody has measured hands down
     * nothing, and the slave says so rather than claiming a wheel. `0` is what
     * makes `derivedSpeedKmh` return null, which is what makes the gauges read
     * `—` instead of a confident `0 km/h` — see `UnknownMotionRenderingTest`.
     *
     * Inventing a plausible diameter here would be strictly worse: it would turn
     * eRPM into a speed that is wrong and looks measured.
     */
    @Test
    fun `an unmeasured gateway hands its slaves an unset wheel rather than a guess`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val repo = FakeVehicleRepo(listOf(v))
        val c = component(repo, bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()
        c.onDiscoverCanDevices()
        advanceUntilIdle()

        c.onAddCanCandidate(c.state.value.canCandidates.first { it.canId == 10 }, asBattery = false)
        c.onSave()
        advanceUntilIdle()

        val slave = repo.upserts.single().controllers.single { it.canId == 10 }
        assertEquals(0, slave.motor.wheelDiameterMm, "unset, so the speed reads as unknown")
        assertNull(VescValues.derivedSpeedKmh(3000f, slave.motor), "which is what the decoder does with it")
    }

    /**
     * The offers refresh against the draft, so a device already taken cannot be
     * added twice — a second add of the same node is `DuplicateCanId`, which is
     * blocking.
     */
    @Test
    fun `an added node comes back already added and refuses a second add`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()
        c.onDiscoverCanDevices()
        advanceUntilIdle()

        val first = c.state.value.canCandidates.first { it.canId == 10 }
        c.onAddCanCandidate(first, asBattery = false)
        assertTrue(c.state.value.canCandidates.first { it.canId == 10 }.alreadyAdded)

        // The refreshed candidate is refused, and so is the stale one the
        // screen was holding when the rider tapped.
        val refreshed = c.state.value.canCandidates.first { it.canId == 10 }
        c.onAddCanCandidate(refreshed, asBattery = false)
        c.onAddCanCandidate(first.copy(alreadyAdded = true), asBattery = false)
        assertEquals(2, c.state.value.draft.controllers.size)
    }

    @Test
    fun `a node may be added as a battery instead`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()
        c.onDiscoverCanDevices()
        advanceUntilIdle()

        c.onAddCanCandidate(c.state.value.canCandidates.first { it.canId == 11 }, asBattery = true)
        val pack = c.state.value.draft.packs.single()
        assertEquals(11, pack.canId)
        assertEquals(BmsType.VESC_BMS, pack.bmsType, "a pack behind a VESC gateway answers BMS_GET_VALUES")
        assertEquals(1, c.state.value.draft.controllers.size, "adding a battery must not add a controller")
    }

    /**
     * The hosted battery is a battery whichever way it is asked for. The screen
     * never offers it as a controller — a `BMS_GET_VALUES` endpoint is not an
     * ESC — and the component holds that even if some caller did, rather than
     * quietly creating a second "the head unit itself" controller.
     */
    @Test
    fun `the hosted battery is never added as a controller`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()
        c.onDiscoverCanDevices()
        advanceUntilIdle()

        val hosted = c.state.value.canCandidates.single { it.kind == CanCandidateKind.HOSTED_BATTERY }
        c.onAddCanCandidate(hosted, asBattery = false)

        val s = c.state.value
        assertEquals(1, s.draft.packs.size)
        assertNull(s.draft.packs.single().canId)
        assertEquals(1, s.draft.controllers.size, "no second gateway-claiming controller")
        assertEquals(emptyList(), s.issues)
    }

    @Test
    fun `a failed scan says why, and can be retried`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val can = FakeCanDiscovery(
            Result.failure(CanScanRefusedException(CanScanRefusal.LINK_NOT_READY, "Link HU:01 is not online"))
        )
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = can)
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()

        c.onDiscoverCanDevices()
        advanceUntilIdle()
        assertEquals(CanScanState.Failed(CanScanRefusal.LINK_NOT_READY), c.state.value.canScan)
        assertEquals(emptyList(), c.state.value.canCandidates, "a failure offers nothing")

        can.answerWith(Result.success(listOf(7)))
        c.onDiscoverCanDevices()
        advanceUntilIdle()
        assertEquals(CanScanState.Found("HU:01", listOf(7)), c.state.value.canScan)
    }

    /**
     * Every refusal reaches the screen **as itself**.
     *
     * The four are four different things a rider can fix, and the screen has a
     * sentence for each. The first version passed `Throwable.message` through,
     * so all four rendered as English exception text inside a Russian sentence
     * — and no test could see it, because the message was the only thing
     * asserted. Exhaustive over the enum, so a fifth reason has to be carried.
     */
    @Test
    fun `each refusal reaches the screen as its own reason`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (refusal in CanScanRefusal.entries) {
            val v = headUnitVehicle()
            val bms = FakeBmsRepo()
            val can = FakeCanDiscovery(
                Result.failure(CanScanRefusedException(refusal, "whatever this says"))
            )
            val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = can)
            advanceUntilIdle()
            bms.goLive(v)
            advanceUntilIdle()

            c.onDiscoverCanDevices()
            advanceUntilIdle()
            assertEquals(CanScanState.Failed(refusal), c.state.value.canScan, "refusal $refusal")
        }
    }

    /**
     * A failure that is not one of ours has no reason to give. NO_REPLY is the
     * honest reading of "it did not come back" — and it must not crash or
     * silently succeed.
     */
    @Test
    fun `an untyped failure reads as no reply`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val can = FakeCanDiscovery(Result.failure(IllegalStateException("something else entirely")))
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = can)
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()

        c.onDiscoverCanDevices()
        advanceUntilIdle()
        assertEquals(CanScanState.Failed(CanScanRefusal.NO_REPLY), c.state.value.canScan)
    }

    /**
     * `Running` is set **synchronously**, before the coroutine is dispatched.
     *
     * `scope` is `Dispatchers.Main`, not `.immediate`, so a `Running` written
     * inside the launched body lands on a later Looper message — and two taps
     * inside one message would both read `Idle` and both dispatch a `PING_CAN`,
     * the second of which the firmware silently discards. Asserted with **no
     * scheduler advance at all**, which is the whole point.
     */
    @Test
    fun `the window is entered before the coroutine is dispatched`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val can = FakeCanDiscovery()
        can.gate = CompletableDeferred()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = can)
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()

        c.onDiscoverCanDevices()
        assertEquals(CanScanState.Running, c.state.value.canScan, "before anything is dispatched")
        // The second tap in the same Looper message, which is exactly what the
        // guard has to survive.
        c.onDiscoverCanDevices()
        advanceUntilIdle()
        assertEquals(listOf("HU:01"), can.calls)
        can.gate!!.complete(Unit)
        advanceUntilIdle()
    }

    /**
     * Adds are anchored to the link the scan **ran against**, not to a target
     * recomputed from the live connection — a link dropping between the scan and
     * the tap would otherwise turn an add into a silent no-op.
     */
    @Test
    fun `an add still lands after the link drops under it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()
        c.onDiscoverCanDevices()
        advanceUntilIdle()

        bms.connectionState.value = ConnectionState.Reconnecting(1, "drop")
        advanceUntilIdle()
        assertNull(c.state.value.canScanTarget, "the target is gone with the link")

        val node = c.state.value.canCandidates.first { it.canId == 10 }
        c.onAddCanCandidate(node, asBattery = false)
        assertEquals(
            listOf(null, 10),
            c.state.value.draft.controllers.map { it.canId },
            "the offers still describe the bus that answered"
        )
        assertEquals("HU:01", c.state.value.draft.controllers.last().address)
    }

    /**
     * An empty answer is a real one — the gateway replied and its bus is empty
     * — and it is not the same as a failure. The hosted battery is still
     * offered, because no probe could ever have reported it (`C §6`).
     */
    @Test
    fun `an empty bus is a result, not a failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val c = component(
            FakeVehicleRepo(listOf(v)),
            bmsRepo = bms,
            canDiscovery = FakeCanDiscovery(Result.success(emptyList()))
        )
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()

        c.onDiscoverCanDevices()
        advanceUntilIdle()
        assertEquals(CanScanState.Found("HU:01", emptyList<Int>()), c.state.value.canScan)
        assertEquals(
            listOf(CanCandidateKind.HOSTED_BATTERY),
            c.state.value.canCandidates.map { it.kind }
        )
    }

    @Test
    fun `dismissing the result clears the offers but keeps what was added`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()
        c.onDiscoverCanDevices()
        advanceUntilIdle()
        c.onAddCanCandidate(c.state.value.canCandidates.first { it.canId == 10 }, asBattery = false)

        c.onDismissCanScan()
        assertEquals(CanScanState.Idle, c.state.value.canScan)
        assertEquals(emptyList(), c.state.value.canCandidates)
        assertEquals(2, c.state.value.draft.controllers.size, "an added source is not an offer")
    }

    /** A build with no CAN discovery wired must not offer one. */
    @Test
    fun `no discovery implementation means no scan`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(listOf(v)), bmsRepo = bms, canDiscovery = null)
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()

        c.onDiscoverCanDevices()
        advanceUntilIdle()
        assertEquals(CanScanState.Idle, c.state.value.canScan)
    }

    /**
     * The whole of `G §3` flow 4 through the component: connect the head unit,
     * discover, include the two uBoxes and the hosted battery, add the direct
     * ANT by scan, drop the head unit's own phantom controller row, save.
     *
     * The assertion that matters is the saved vehicle, because that is what
     * `planLinks` will be handed at the next connect.
     */
    @Test
    fun `the two-uBox scooter can be described end to end`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val v = headUnitVehicle()
        val bms = FakeBmsRepo(listOf(device("AN:01", "ANT", bmsType = BmsType.ANT_BMS)))
        val repo = FakeVehicleRepo(listOf(v))
        val c = component(repo, bmsRepo = bms, canDiscovery = FakeCanDiscovery())
        advanceUntilIdle()
        bms.goLive(v)
        advanceUntilIdle()

        c.onDiscoverCanDevices()
        advanceUntilIdle()
        c.state.value.canCandidates
            .filter { it.kind == CanCandidateKind.NODE }
            .forEach { c.onAddCanCandidate(it, asBattery = false) }
        c.onAddCanCandidate(
            c.state.value.canCandidates.single { it.kind == CanCandidateKind.HOSTED_BATTERY },
            asBattery = true
        )

        c.onStartDeviceScan()
        advanceUntilIdle()
        c.onAddScannedDevice(c.state.value.scannedDevices.single(), ScannedAdd.BATTERY)
        c.onStopDeviceScan()

        // The head unit is a display, not an ESC — its own controller row would
        // poll a motor that does not exist.
        c.onRemoveController(c.state.value.draft.controllers.first { it.canId == null }.key)

        assertEquals(emptyList(), c.state.value.issues)
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.last()
        assertEquals(listOf(10, 11), saved.controllers.map { it.canId })
        assertEquals(listOf("HU:01", "HU:01"), saved.controllers.map { it.address })
        assertEquals(
            listOf(BmsType.VESC_BMS to "HU:01", BmsType.ANT_BMS to "AN:01"),
            saved.packs.map { it.bmsType to it.bmsAddress }
        )
        // Two links: everything behind the head unit, plus the direct ANT.
        val links = planLinks(saved.packs, saved.controllers)
        assertEquals(listOf("HU:01", "AN:01"), links.map { it.address })
        assertTrue(links.first { it.address == "HU:01" }.isGatewayLink)
    }

    // -----------------------------------------------------------------------
    // Task 4 — alias groups, the handoff toggle, and what this form observes
    // -----------------------------------------------------------------------

    /**
     * The product owner's scooter as a stored vehicle: the head unit hosting a
     * VESC-BMS behind two CAN uBoxes, and the same ANT battery on its own direct
     * BLE link. Nothing says the two batteries are one pack — that is what
     * Task 4 lets him say.
     */
    private fun aliasScooter() = existingVehicle().copy(
        name = "Scooter",
        packs = listOf(
            Pack(index = 0, label = "ANT", bmsType = BmsType.ANT_BMS, bmsAddress = "AN:01"),
            Pack(index = 1, label = "Hosted", bmsType = BmsType.VESC_BMS, bmsAddress = "HU:01")
        ),
        controllers = listOf(
            Controller(index = 0, label = "uBox 1", controllerType = ControllerType.VESC, address = "HU:01", canId = 41),
            Controller(index = 1, label = "uBox 2", controllerType = ControllerType.VESC, address = "HU:01", canId = 42)
        ),
        yieldBmsToHeadUnit = null
    )

    /**
     * The whole of Task 4's first deliverable, end to end: the rider marks the
     * two battery sources as one physical pack and it is what gets stored.
     */
    @Test
    fun `grouping two packs is persisted as one shared alias group`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(aliasScooter()))
        val c = component(repo)
        advanceUntilIdle()

        val keys = c.state.value.draft.packs.map { it.key }
        c.onGroupPacks(keys[0], keys[1])
        c.onSave()
        advanceUntilIdle()

        val saved = repo.upserts.single()
        val groups = saved.packs.map { it.aliasGroup }
        assertTrue(groups.all { it != null }, "both paths carry a group")
        assertEquals(1, groups.toSet().size, "and it is the same one")
        // The point of the group, spanning into the runtime that consumes it.
        assertEquals(
            listOf("AN:01"),
            planAliasHandoffs(planLinks(saved.packs, saved.controllers), saved.packs)
                .map { it.directAddress }
        )
    }

    @Test
    fun `ungrouping is persisted as a cleared alias group on both packs`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val grouped = aliasScooter().let { v ->
            v.copy(packs = v.packs.map { it.copy(aliasGroup = "ant-72v") })
        }
        val repo = FakeVehicleRepo(listOf(grouped))
        val c = component(repo)
        advanceUntilIdle()

        c.onUngroupPack(c.state.value.draft.packs.first().key)
        c.onSave()
        advanceUntilIdle()

        assertEquals(listOf(null, null), repo.upserts.single().packs.map { it.aliasGroup })
    }

    /**
     * `C §5`'s toggle appears **where the group is**: only once the vehicle has
     * an alias group spanning a direct BMS and a gateway-hosted one, because
     * that is the only shape `planAliasHandoffs` acts on.
     */
    @Test
    fun `the yield toggle appears only once an alias group spans both paths`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(aliasScooter()))
        val c = component(repo)
        advanceUntilIdle()

        assertFalse(c.state.value.showYieldToggle, "no group yet, so nothing to yield")
        assertTrue(c.state.value.yieldsBmsToHeadUnit, "and unset already resolves to ON (C §5)")

        val keys = c.state.value.draft.packs.map { it.key }
        c.onGroupPacks(keys[0], keys[1])
        assertTrue(c.state.value.showYieldToggle)
    }

    @Test
    fun `switching the handoff off is persisted, and unset stays unset`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeVehicleRepo(listOf(aliasScooter()))
        val c = component(repo)
        advanceUntilIdle()
        assertEquals(null, c.state.value.yieldBmsToHeadUnit, "seeded three-valued, not resolved")

        c.onYieldBmsToHeadUnitChanged(false)
        c.onSave()
        advanceUntilIdle()
        assertEquals(false, repo.upserts.single().yieldBmsToHeadUnit)

        // A second form over the same vehicle, touching nothing: the column must
        // come back exactly as stored rather than being claimed as an opt-in.
        val untouched = FakeVehicleRepo(listOf(aliasScooter()))
        val c2 = component(untouched)
        advanceUntilIdle()
        c2.onSave()
        advanceUntilIdle()
        assertEquals(null, untouched.upserts.single().yieldBmsToHeadUnit)
    }

    /**
     * The form and the runtime read the SAME three-valued column and must
     * resolve it identically. Drifted, the switch renders ON while
     * `planAliasHandoffs` plans nothing — a setting that lies about itself, and
     * a class of defect no test of either side alone can see.
     *
     * All three values, because the interesting one is `null`: it is the value
     * that is neither, and the only one where "unset means ON" is a decision
     * rather than an identity.
     */
    @Test
    fun `the form and the runtime resolve the handoff default identically`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        for (stored in listOf(null, true, false)) {
            val v = aliasScooter().copy(yieldBmsToHeadUnit = stored)
            val c = component(FakeVehicleRepo(listOf(v)))
            advanceUntilIdle()
            assertEquals(
                v.yieldsBmsToHeadUnit,
                c.state.value.yieldsBmsToHeadUnit,
                "stored=$stored must read the same on the form as in the runtime"
            )
        }
        // …and the decision itself, so "they agree" cannot be satisfied by both
        // being wrong together.
        assertTrue(aliasScooter().copy(yieldBmsToHeadUnit = null).yieldsBmsToHeadUnit, "unset is ON")
    }

    /**
     * The duplicate offer, through the component: the form watches the live
     * vehicle and, after the window fills, offers the grouping instead of
     * letting the pack be counted twice.
     */
    @Test
    fun `a duplicate pack is offered as a grouping once the window fills`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vehicle = aliasScooter()
        val bms = FakeBmsRepo()
        val repo = FakeVehicleRepo(listOf(vehicle))
        val c = component(repo, bmsRepo = bms)
        advanceUntilIdle()

        bms.activeVehicle.value = vehicle
        repeat(DUPLICATE_SAMPLES) { i ->
            bms.activeVehicleData.value = VehicleData(
                packs = listOf(
                    packSample(vehicle.packs[0], 72.40f - i * 0.02f),
                    packSample(vehicle.packs[1], 72.41f - i * 0.02f)
                )
            )
            advanceUntilIdle()
            if (i < DUPLICATE_SAMPLES - 1) {
                assertFalse(
                    c.state.value.issues.any { it is ComposerIssue.DuplicatePack },
                    "not offered on ${i + 1} sample(s)"
                )
            }
        }

        val dup = c.state.value.issues.filterIsInstance<ComposerIssue.DuplicatePack>().single()
        assertEquals(
            c.state.value.draft.packs.map { it.key }.toSet(),
            setOf(dup.keyA, dup.keyB)
        )
        assertTrue(c.state.value.canSave, "an advisory never refuses the save")

        // An unrelated edit re-derives the issue list, and the observed half of
        // it must survive that: `validate` is handed the window, not a fresh
        // empty one, or the offer would blink out on the next keystroke.
        c.onPackLabelChanged(c.state.value.draft.packs.first().key, "Передний")
        assertTrue(
            c.state.value.issues.any { it is ComposerIssue.DuplicatePack },
            "an unrelated edit must not discard what the form has observed"
        )

        // Accepting the offer is the ordinary grouping, and it stops being asked.
        c.onGroupPacks(dup.keyA, dup.keyB)
        assertFalse(c.state.value.issues.any { it is ComposerIssue.DuplicatePack })
    }

    /**
     * Sources are matched to draft rows by stored index, and two vehicles' pack
     * indices collide by construction — so another vehicle's telemetry must
     * never reach this form. Folding it in would answer "is this the same
     * battery?" with somebody else's battery.
     */
    @Test
    fun `telemetry from a different active vehicle is ignored`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vehicle = aliasScooter()
        val bms = FakeBmsRepo()
        val c = component(FakeVehicleRepo(listOf(vehicle)), bmsRepo = bms)
        advanceUntilIdle()

        bms.activeVehicle.value = vehicle.copy(id = "someone-else")
        repeat(DUPLICATE_SAMPLES) {
            bms.activeVehicleData.value = VehicleData(
                packs = listOf(packSample(vehicle.packs[0], 72.4f), packSample(vehicle.packs[1], 72.4f))
            )
            advanceUntilIdle()
        }

        assertEquals(ComposerTelemetry(), c.state.value.telemetry)
        assertEquals(emptyList(), c.state.value.issues)
    }

    /** One online pack, its voltage, and 20 cells — the shape the window folds. */
    private fun packSample(pack: Pack, volts: Float) = PackState(
        pack = pack,
        data = BmsData(voltage = volts, cellVoltages = List(20) { volts / 20f }),
        isOnline = true
    )
}

// The toString-based field splitter this file's default-guard is built on now
// lives in `ru.sodovaya.volty.DataClassFields`, shared with
// KableBmsRepositoryBegodeFunnelTest's equivalent guard on ControllerData (G2
// Task 6). See its KDoc for why one copy rather than two.
