package ru.sodovaya.volty.presentation.vehicle

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.bmsAddressOrNull
import ru.sodovaya.volty.domain.model.bmsTypeOrNull
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.model.yieldsBms
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.CanDiscovery
import ru.sodovaya.volty.domain.repository.CanScanRefusal
import ru.sodovaya.volty.domain.repository.CanScanRefusedException
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.GaugeScale
import ru.sodovaya.volty.presentation.picker.ScannedAdd
import ru.sodovaya.volty.presentation.picker.addBmsType
import ru.sodovaya.volty.presentation.picker.addControllerType
import ru.sodovaya.volty.presentation.picker.withScanHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface VehicleEditComponent {
    val state: StateFlow<State>

    fun onNameChanged(name: String)
    fun onIconChanged(iconKey: String)
    fun onChemistryChanged(c: Chemistry)
    fun onAveragingWindowChanged(min: Int)
    fun onCellHighVChanged(v: Float?)
    fun onCellLowVChanged(v: Float?)
    fun onTemperatureWarnChanged(v: Float?)
    fun onTemperatureHighChanged(v: Float?)
    fun onSocLowChanged(v: Int?)
    fun onDashboardStyleChanged(style: DashboardStyle?)
    fun onSecondaryGaugeChanged(gauge: SecondaryGauge)
    /**
     * Open the per-vehicle alert settings screen (F Task 9).
     *
     * Navigation only — **no state on this form**, deliberately. The alert rules
     * are edited and persisted by their own component, which is PUSHED on top of
     * this one (RootComponent) and therefore writes while this component is still
     * alive holding a stale snapshot. [onSave] re-reads the vehicle from the
     * repository at save time and edits THAT, so the rules written while this
     * form was in the background are the ones a later save carries forward.
     */
    fun onOpenAlerts()

    /**
     * Open the app-wide unit setting (km / mi, `B §9`) — Settings, pushed on top
     * of this form exactly like [onOpenAlerts].
     *
     * A **link, not a copy** (`G §4`): the unit system is one app-level
     * preference, and a per-vehicle control that wrote the same key would read
     * as a per-vehicle setting and quietly change every other vehicle. Nothing
     * on this form's state moves, so nothing here can go stale.
     */
    fun onOpenUnits()
    fun onSave()
    fun onCancel()
    fun onDelete()

    // ----- The composer (G2 Task 2): a vehicle is N sources -----
    //
    // Every one of these is a thin delegation to a pure operation in
    // VehicleComposer.kt, which is where the decisions live and where they are
    // tested. Sources are addressed by [PackDraft.key] / [ControllerDraft.key]
    // rather than by position, because removal and reorder renumber positions
    // under the screen's feet.

    fun onAddPack(bmsType: BmsType, address: String, label: String = "")
    fun onAddController(controllerType: ControllerType, address: String, label: String = "")

    /**
     * Remove a source. **Refused, not thrown, when it would leave the vehicle
     * with none** — [Vehicle]'s own `init` requires a source and this screen
     * must never be able to reach that exception. [State.canRemoveSource] is
     * the same fact for the screen to disable the control with.
     */
    fun onRemovePack(key: String)
    fun onRemoveController(key: String)

    /** Out-of-range moves are no-ops, so a drag gesture can never fail. */
    fun onMovePack(from: Int, to: Int)
    fun onMoveController(from: Int, to: Int)

    fun onPackLabelChanged(key: String, label: String)
    fun onPackTypeChanged(key: String, bmsType: BmsType)
    fun onPackAddressChanged(key: String, address: String)
    fun onPackCanIdChanged(key: String, canId: Int?)

    /**
     * The rider's own series-cell count for this pack (Task 3) — the only path
     * to a rail voltage on a wheel with no smart BMS, since a dumb wheel sends
     * no cell frames and `KableBmsRepository.maybePersistCellCount`'s auto-fill
     * can never derive one. Blank clears to `null`, not `0` — see [PackDraft].
     */
    fun onPackCellCountChanged(key: String, cellCount: Int?)

    fun onControllerLabelChanged(key: String, label: String)
    fun onControllerTypeChanged(key: String, controllerType: ControllerType)
    fun onControllerAddressChanged(key: String, address: String)
    fun onControllerCanIdChanged(key: String, canId: Int?)
    fun onControllerMotorChanged(key: String, motor: MotorConfig)

    /**
     * The three [MotorConfig] numbers, per controller and one field at a time —
     * the per-source replacement for the flat Motor card G1 left behind, which
     * was the last positional editor on this screen (it wrote `controllers[0]`).
     *
     * Nullable because that is what a cleared text box is, and the value stays
     * null on the draft until the save resolves it: see [MotorDraft]. That is
     * the rule the flat card carried, rehomed onto the source it edits — where
     * it no longer needs a second copy of the numbers in [State] to keep a
     * cleared box from refilling itself on the next unrelated edit.
     */
    fun onControllerPolePairsChanged(key: String, v: Int?)
    fun onControllerWheelDiameterChanged(key: String, v: Int?)
    fun onControllerGearRatioChanged(key: String, v: Float?)

    /**
     * The rider's word on a controller's derived battery. It is recorded as an
     * explicit choice and a later source-set change will **not** overwrite it —
     * see [DerivedBatteryChoice] for the rule and why.
     */
    fun onControllerDerivedBatteryChanged(key: String, enabled: Boolean)

    fun onTopologyChanged(topology: PackTopology)

    // ----- Alias groups (G2 Task 4): two sources, one physical pack -----

    /**
     * Mark two battery sources as **the same physical pack** reached two ways
     * (`G §5`, `01-linking §4`) — the product owner's ANT, reachable directly
     * over BLE while parked and through the head unit while riding.
     *
     * The rider's word, and it has to be: nothing on the wire says two BLE
     * endpoints lead to one battery. Without it the app counts the pack twice
     * (doubled capacity, doubled energy) and the `C §5` handoff has no way to
     * know which two links contend for it. See [VehicleDraft.groupPacks] for
     * what happens when the pair spans two existing groups.
     */
    fun onGroupPacks(keyA: String, keyB: String)

    /** Take a pack out of its alias group. See [VehicleDraft.ungroupPack]. */
    fun onUngroupPack(key: String)

    /**
     * "Yield BMS to head unit while riding" (`C §5`).
     *
     * Belongs to an alias group rather than to the vehicle at large — it is
     * about which of *two paths to one battery* the app gives up — so the screen
     * shows it only where such a group exists
     * ([VehicleDraft.handoffAliasGroups]). It is stored on [Vehicle] all the
     * same, because a vehicle can have several such groups and the policy is one
     * answer for all of them; splitting it per group would be a storage change
     * for a distinction no rider has asked for.
     *
     * **Unset means ON** ([Vehicle.yieldBmsToHeadUnit]), so the toggle comes up
     * checked on a group that has never been configured, which is the default
     * `C §5` specifies. Tapping it writes an explicit answer; nothing here can
     * write it back to "unset".
     */
    fun onYieldBmsToHeadUnitChanged(enabled: Boolean)

    // ----- Discovery (G2 Task 5): the composer learns an address -----
    //
    // Until this task, adding a second source meant typing a BLE MAC by hand,
    // which made `G §3`'s second flow painful and its third and fourth
    // impossible. Two ways in, and neither of them auto-adds anything:
    //
    //  - a BLE SCAN, which is the picker's own `BmsRepository.scanAll()` — one
    //    scanner, shared, see `presentation/picker/SourceScan.kt`;
    //  - CAN DISCOVERY behind a connected gateway (`ComposerCanDiscovery.kt`).

    /**
     * Open the scan sheet and start collecting. Idempotent: a second call while
     * the sheet is open does not start a second collector.
     */
    fun onStartDeviceScan()

    /** Close the sheet and cancel the scan. A scan must not outlive the sheet. */
    fun onStopDeviceScan()

    /**
     * Add [device] to the draft as [add] — the whole point of the scan, and the
     * only thing that ever writes a scanned address into the draft.
     * [ScannedAdd.WHEEL] is `G §3` flow 3: one add, two sources, one link.
     */
    fun onAddScannedDevice(device: DiscoveredDevice, add: ScannedAdd)

    /**
     * Run one `PING_CAN` against [State.canScanTarget].
     *
     * **Refused while one is running**, because the firmware silently discards a
     * second `PING_CAN` inside its ~2.5 s window and answers neither
     * (`C §10.2`) — a rider who tapped twice would wait out a window that can
     * never reply. Refused too when there is no target: the screen says so
     * rather than offering a scan it cannot perform.
     */
    fun onDiscoverCanDevices()

    /**
     * Add one discovered CAN device. [asBattery] chooses which half — a raw CAN
     * id says nothing about what the node is, so the rider does.
     *
     * Never called by discovery itself (`G §3`: never auto-add).
     */
    fun onAddCanCandidate(candidate: CanCandidate, asBattery: Boolean)

    /** Dismiss the scan result — the offers, not the sources already added. */
    fun onDismissCanScan()

    data class State(
        val isEditing: Boolean = false,
        val name: String = "",
        val iconKey: String = "generic",
        val chemistry: Chemistry = Chemistry.LI_ION_NMC,
        val bmsType: BmsType = BmsType.JK_BMS,
        val bmsAddress: String = "",
        /**
         * The loaded vehicle, carried ONLY so the read-only source header can
         * name it through the one shared
         * [ru.sodovaya.volty.presentation.common.vehicleSourceLabel] chain
         * instead of growing a second fallback of its own. A controller-only
         * vehicle has no [bmsType] to describe, and rendering the placeholder
         * default made the form claim a JK BMS that does not exist.
         *
         * Null while CREATING — there is no vehicle yet, and the (possibly
         * prefilled) [bmsType] above is genuinely the answer there.
         */
        val sourceVehicle: Vehicle? = null,
        /**
         * The address that header shows. The primary PACK's whenever there is
         * one — byte-for-byte what the row displayed before — else the
         * vehicle's identity address, which for a controller-only vehicle is
         * its controller's.
         *
         * Separate from [bmsAddress] on purpose: that one feeds the pack
         * [singlePackVehicle] builds when CREATING, and a controller's address
         * must never reach it. (Editing never builds a pack — see [onSave].)
         * "" (rendered as an em-dash) when neither exists.
         */
        val sourceAddress: String = "",
        val averagingWindowMin: Int = 5,
        val cellHighV: Float? = null,
        val cellLowV: Float? = null,
        val temperatureWarnC: Float? = 50f,
        val temperatureHighC: Float? = 60f,
        val socLowPercent: Int? = 15,
        /** Null = follow the app-level default. */
        val dashboardStyle: DashboardStyle? = null,
        val secondaryGauge: SecondaryGauge = SecondaryGauge.DUTY,
        /** How this vehicle's packs are wired. Editable since G2. */
        val topology: PackTopology = PackTopology.PARALLEL,
        /**
         * `C §5`'s ride-time handoff, three-valued exactly as
         * [Vehicle.yieldBmsToHeadUnit] is: **null means "follow the default",
         * which is ON**. Seeded from the loaded vehicle and written straight
         * back, so a vehicle whose rider never touched the toggle keeps its
         * unset column rather than being claimed to have opted in.
         */
        val yieldBmsToHeadUnit: Boolean? = null,
        /**
         * The editable source set (G2 Task 2). Seeded from the loaded vehicle
         * by [draftOf] and round-trips it exactly; empty while CREATING, which
         * still goes through [singlePackVehicle].
         */
        val draft: VehicleDraft = VehicleDraft(),
        /** Everything wrong with [draft] right now — see [ComposerIssue]. */
        val issues: List<ComposerIssue> = emptyList(),
        /**
         * What this sitting has observed of the vehicle being edited (G2 Task
         * 4), and the second input to [issues].
         *
         * Two of the advisories are about the rider's **hardware** rather than
         * their configuration — whether two battery sources are one pack, and
         * whether a head unit is really an ESC — and neither can be decided from
         * a draft. Bounded by construction (see [ComposerTelemetry]) and never
         * persisted: it describes this sitting, which is also the only evidence
         * the rider could be shown.
         */
        val telemetry: ComposerTelemetry = ComposerTelemetry(),
        /**
         * Whether the rider has touched the pack / controller list at all.
         *
         * The save applies [draft] **only** to the half it is set for, and
         * otherwise leaves the stored vehicle's own list alone. That is Task 1's
         * polarity carried into the composer: [draft] is a snapshot taken when
         * the form opened, and the pack auto-fill
         * (`KableBmsRepository.maybePersistPacks`) can append a wheel's second
         * branch to the stored row *while this form is open*. Writing a
         * never-edited draft back would silently drop it — losing a field by
         * saving a stale copy rather than by forgetting to copy it, which is
         * the same defect in a different costume.
         */
        val packsEdited: Boolean = false,
        val controllersEdited: Boolean = false,
        val nameError: Boolean = false,
        /**
         * [onSave] refused because of a blocking [ComposerIssue] — the
         * counterpart of [nameError], so a screen that wires only the button
         * still says something instead of appearing dead. Cleared by any change
         * to the source set, which is the only thing that can fix it.
         */
        val saveBlocked: Boolean = false,
        val saving: Boolean = false,

        // ----- Discovery (G2 Task 5) -----

        /** Whether the scan sheet is open. The scan runs only while it is. */
        val scanning: Boolean = false,
        /** Devices this scan has seen, first-appearance order — see `withScanHit`. */
        val scannedDevices: List<DiscoveredDevice> = emptyList(),
        /**
         * The link a CAN scan would run against, or null when there is none
         * (nothing connected, or nothing VESC in the draft). Recomputed from the
         * live connection and the draft — see [canScanTarget]. **Null is a
         * sentence, not a disabled button**: the screen says "connect to the
         * head unit first" rather than offering a scan that cannot work.
         */
        val canScanTarget: String? = null,
        /** Where a `PING_CAN` stands — see [CanScanState]. */
        val canScan: CanScanState = CanScanState.Idle
    ) {
        /** See [VehicleDraft.canRemoveSource]. False while creating (no draft). */
        val canRemoveSource: Boolean get() = draft.canRemoveSource

        /**
         * [issues] indexed by the source card that must show each one — see
         * [ru.sodovaya.volty.presentation.vehicle.issuesBySource]. A card asks
         * for its own key; nothing on the screen has to know how an issue
         * relates to a row.
         */
        val issuesBySource: Map<String, List<ComposerIssue>>
            get() = issuesBySource(draft, issues)

        /**
         * Whether the source list may be edited at all.
         *
         * **False while CREATING**, because the create path builds the
         * single-BMS shape through `singlePackVehicle` and never reads [draft]
         * — so a composer control offered here would take the rider's work and
         * discard it at save time with no signal. The mutations are no-ops in
         * that state as well (belt and braces, exactly like
         * [canRemoveSource]); a controller-bearing vehicle is created by the
         * Picker, which then navigates straight to this screen to EDIT it, and
         * that is where composing happens.
         */
        val canComposeSources: Boolean get() = isEditing

        /**
         * Whether [onSave] will do anything. A blocking issue is a
         * self-contradictory config the connection layer cannot honour, and the
         * component refuses it outright — [saveBlocked] is how that refusal
         * reaches the rider.
         *
         * **The screen deliberately does NOT disable the Save button on this.**
         * A dead button explains nothing, and the explanation is the whole
         * point of `validate` — so Save stays tappable, the refusal prints a
         * banner, and every blocking issue is already marked on the source card
         * it concerns ([issuesBySource]). This property is the same fact for
         * anything that wants to ask *before* the tap; nothing does today.
         */
        val canSave: Boolean get() = name.isNotBlank() && issues.none { it.blocking }

        /**
         * Whether the "yield BMS to head unit while riding" toggle applies at
         * all — i.e. whether this vehicle has an alias group that genuinely
         * spans a direct BMS and a gateway-hosted one
         * ([VehicleDraft.handoffAliasGroups]).
         *
         * Shown where the contention is, and nowhere else. A switch offered on
         * a vehicle with one battery path would be a setting that can never do
         * anything — `planAliasHandoffs` emits nothing for it — and a rider who
         * turned it off would reasonably believe they had changed something.
         */
        val showYieldToggle: Boolean get() = canComposeSources && draft.handoffAliasGroups.isNotEmpty()

        /**
         * [yieldBmsToHeadUnit] resolved — unset is ON.
         *
         * Through the model's own [yieldsBms] rather than a second `!= false`
         * here: the runtime reads the same nullable through
         * [ru.sodovaya.volty.domain.model.yieldsBmsToHeadUnit], and two copies
         * of the rule are two places to drift. Drifted, the switch would render
         * ON while `planAliasHandoffs` planned nothing.
         */
        val yieldsBmsToHeadUnit: Boolean get() = yieldBmsToHeadUnit.yieldsBms

        /**
         * The offers from the last successful CAN scan, derived on read.
         *
         * Derived rather than stored because `alreadyAdded` changes on every add
         * — a stored list would keep offering a uBox the rider has just taken,
         * and a second tap on it is `DuplicateCanId`. [CanScanState.Found]
         * therefore carries only the raw ids the wire gave us.
         *
         * Empty whenever there is no result to show, including while a scan is
         * running: an empty LIST from a successful scan is still one row (the
         * hosted battery, which no probe can report), so the screen tells
         * "nothing on the bus" from "not scanned" by looking at [canScan], never
         * at this being empty.
         */
        val canCandidates: List<CanCandidate>
            get() {
                val found = canScan as? CanScanState.Found ?: return emptyList()
                // `found.address` — the link the scan ran against — for the same
                // reason `onAddCanCandidate` uses it: the offers must describe
                // the bus that answered, not whatever is live now.
                return canCandidates(draft, found.address, found.ids)
            }
    }
}

@OptIn(ExperimentalTime::class)
class DefaultVehicleEditComponent(
    componentContext: ComponentContext,
    private val vehicleId: String?,
    private val vehicleRepository: VehicleRepository,
    private val bmsRepository: BmsRepository,
    private val onSaved: () -> Unit,
    private val onCancelled: () -> Unit,
    private val onDeleted: () -> Unit,
    /** Defaulted so every existing caller (and test) compiles unchanged. */
    private val onOpenAlertsRequested: () -> Unit = {},
    /** Defaulted for the same reason as [onOpenAlertsRequested]: navigation only. */
    private val onOpenUnitsRequested: () -> Unit = {},
    /**
     * CAN discovery (G2 Task 5), or null when this build has none.
     *
     * Its own interface rather than another `BmsRepository` member — see
     * [CanDiscovery] — and nullable rather than defaulted to a stub that always
     * fails, because [VehicleEditComponent.State.canScanTarget] must be able to
     * say "this screen cannot scan" without pretending to try. Null is also what
     * the eleven existing `BmsRepository` fakes get for free.
     */
    private val canDiscovery: CanDiscovery? = null,
    // Optional prefilled BMS info when creating from Picker
    private val prefilledBmsType: BmsType? = null,
    private val prefilledBmsAddress: String? = null,
    private val prefilledName: String? = null
) : VehicleEditComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(VehicleEditComponent.State())
    override val state: StateFlow<VehicleEditComponent.State> = _state.asStateFlow()

    /**
     * The addresses currently up, refreshed by the collector in `init`.
     *
     * Kept off [VehicleEditComponent.State] because it is an input to
     * [canScanTarget], not something the screen renders — and because it changes
     * on every connection event, which would otherwise recompose the whole form
     * for a fact only one button reads.
     */
    private var liveAddresses: Set<String> = emptySet()

    /** The BLE scan behind the sheet, alive only while the sheet is open. */
    private var scanJob: Job? = null

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }
        scope.launch { initialize() }
        // A CAN scan needs a live link, and whether there is one changes under
        // the form: the rider can arrive here connected, drop, or reconnect. The
        // button follows, rather than being decided once at load.
        scope.launch {
            combine(bmsRepository.connectionState, bmsRepository.activeVehicle) { st, v ->
                liveLinkAddresses(v, st)
            }.collect { addresses ->
                liveAddresses = addresses
                _state.update { it.copy(canScanTarget = canScanTarget(it.draft, addresses)) }
            }
        }
        // What the vehicle is actually reporting, folded into the bounded window
        // the two hardware advisories read (G2 Task 4). A StateFlow collect and
        // a pure fold — nothing here schedules a delay, which is what keeps a
        // multi-sample rule from being the unbounded loop that wedges `runTest`
        // instead of failing it.
        scope.launch {
            combine(bmsRepository.activeVehicle, bmsRepository.activeVehicleData) { v, d -> v to d }
                .collect { (active, data) -> observe(active, data) }
        }
    }

    /**
     * Fold one live emission into [VehicleEditComponent.State.telemetry].
     *
     * **Only when the connected vehicle IS the one being edited.** Sources are
     * matched to draft rows by stored index, and two different vehicles' indices
     * collide by construction — folding another vehicle's pack 0 into this
     * form's pack 0 would answer "is this the same battery?" with somebody
     * else's battery. Creating (no [vehicleId]) collects nothing at all: there
     * is no stored row for any draft to have been observed as.
     */
    private fun observe(active: Vehicle?, data: VehicleData) {
        if (vehicleId == null || active?.id != vehicleId) return
        _state.update { s ->
            if (!s.isEditing) return@update s
            val t = s.telemetry.observing(s.draft, data)
            // Re-validating on every emission would rebuild the issue list at
            // the poll rate; nothing observable changed unless the window did.
            if (t == s.telemetry) s else s.copy(telemetry = t, issues = validate(s.draft, t))
        }
    }

    private suspend fun initialize() {
        if (vehicleId != null) {
            val v = vehicleRepository.get(vehicleId)
            if (v != null) {
                _state.value = VehicleEditComponent.State(
                    isEditing = true,
                    name = v.name,
                    iconKey = v.iconKey,
                    chemistry = v.chemistry,
                    // A controller-only vehicle has no pack to describe, so the
                    // form's BMS fields fall back to their own defaults (the
                    // same ones the "create" branch below uses) instead of
                    // throwing on init. Harmless: an edit never reads them back
                    // — onSave() only ever builds a pack when CREATING. These
                    // two stay the PACK's; what the read-only header shows is
                    // sourceVehicle / sourceAddress below, which do describe a
                    // controller.
                    bmsType = v.bmsTypeOrNull ?: VehicleEditComponent.State().bmsType,
                    bmsAddress = v.bmsAddressOrNull ?: VehicleEditComponent.State().bmsAddress,
                    sourceVehicle = v,
                    // primaryAddress prefers the CONTROLLER, so it is only
                    // correct as the fallback: a vehicle with both sources must
                    // keep showing its pack's address, exactly as before.
                    sourceAddress = v.bmsAddressOrNull ?: v.primaryAddress,
                    averagingWindowMin = v.averagingWindowMin,
                    cellHighV = v.alertConfig.cellHighV,
                    cellLowV = v.alertConfig.cellLowV,
                    temperatureWarnC = v.alertConfig.temperatureWarnC,
                    temperatureHighC = v.alertConfig.temperatureHighC,
                    socLowPercent = v.alertConfig.socLowPercent,
                    dashboardStyle = v.dashboardStyle,
                    secondaryGauge = v.secondaryGauge,
                    topology = v.topology,
                    // Three-valued, and seeded rather than resolved: an unset
                    // column is not the same statement as an explicit `true`,
                    // and `onSave` writes this field back — so resolving it here
                    // would make merely opening and saving the form claim every
                    // rider had opted in. See [Vehicle.yieldBmsToHeadUnit].
                    yieldBmsToHeadUnit = v.yieldBmsToHeadUnit,
                    // The composer's own view of the same vehicle. Seeded even
                    // when the rider never opens the source list, because
                    // [State.issues] must describe the vehicle as STORED —
                    // a config that cannot connect should say so on open, not
                    // only after an edit.
                    draft = draftOf(v),
                    issues = validate(draftOf(v)),
                    // Seeded here as well as by the connection collector: a
                    // rider who arrives already connected (which is the whole of
                    // `G §3` flow 4 — the picker connects, then navigates
                    // straight here) would otherwise see no scan button until
                    // the next connection event, and there may never be one.
                    canScanTarget = canScanTarget(draftOf(v), liveAddresses)
                )
                return
            }
        }
        _state.value = VehicleEditComponent.State(
            isEditing = false,
            name = prefilledName ?: "",
            bmsType = prefilledBmsType ?: BmsType.JK_BMS,
            bmsAddress = prefilledBmsAddress ?: "",
            // No vehicle to describe yet — the header falls back to the
            // prefilled BMS fields, which is what it always showed here.
            sourceAddress = prefilledBmsAddress ?: ""
        )
    }

    override fun onNameChanged(name: String) {
        _state.update { it.copy(name = name, nameError = name.isBlank()) }
    }
    override fun onIconChanged(iconKey: String) { _state.update { it.copy(iconKey = iconKey) } }
    override fun onChemistryChanged(c: Chemistry) { _state.update { it.copy(chemistry = c) } }
    override fun onAveragingWindowChanged(min: Int) { _state.update { it.copy(averagingWindowMin = min) } }
    override fun onCellHighVChanged(v: Float?) { _state.update { it.copy(cellHighV = v) } }
    override fun onCellLowVChanged(v: Float?) { _state.update { it.copy(cellLowV = v) } }
    override fun onTemperatureWarnChanged(v: Float?) { _state.update { it.copy(temperatureWarnC = v) } }
    override fun onTemperatureHighChanged(v: Float?) { _state.update { it.copy(temperatureHighC = v) } }
    override fun onSocLowChanged(v: Int?) { _state.update { it.copy(socLowPercent = v) } }
    override fun onDashboardStyleChanged(style: DashboardStyle?) { _state.update { it.copy(dashboardStyle = style) } }
    override fun onSecondaryGaugeChanged(gauge: SecondaryGauge) { _state.update { it.copy(secondaryGauge = gauge) } }
    override fun onOpenAlerts() { onOpenAlertsRequested() }
    override fun onOpenUnits() { onOpenUnitsRequested() }

    // ----- The composer -----

    /**
     * Apply a pure draft operation and re-derive what follows from the source
     * set — which, since G2 Task 3 deleted the flat Motor card, is only the
     * issue list.
     *
     * The three `motorXxx` state fields and their re-pointing rule are gone
     * with it: they existed because one positional editor showed
     * `controllers[0]`'s geometry, so a reorder had to re-aim them and a
     * cleared box had to be defended from being refilled by that re-aiming.
     * A per-controller card reads its own row, so there is nothing to re-aim
     * and nothing to defend — see [MotorDraft].
     *
     * [packs] / [controllers] say which half the rider took control of; see
     * [VehicleEditComponent.State.packsEdited].
     */
    private fun mutateDraft(
        packs: Boolean = false,
        controllers: Boolean = false,
        block: (VehicleDraft) -> VehicleDraft
    ) {
        _state.update { s ->
            // Nothing to compose onto while creating — see [State.canComposeSources].
            if (!s.canComposeSources) return@update s
            val d = block(s.draft)
            s.copy(
                draft = d,
                issues = validate(d, s.telemetry),
                packsEdited = s.packsEdited || packs,
                controllersEdited = s.controllersEdited || controllers,
                saveBlocked = false,
                // Also derived from the draft: editing a controller's address
                // (or removing it) changes whether anything VESC of this
                // vehicle's is on a live link.
                canScanTarget = canScanTarget(d, liveAddresses)
            )
        }
    }

    override fun onAddPack(bmsType: BmsType, address: String, label: String) =
        mutateDraft(packs = true) { it.addPack(bmsType, address, label) }

    override fun onAddController(controllerType: ControllerType, address: String, label: String) =
        mutateDraft(controllers = true) { it.addController(controllerType, address, label) }

    override fun onRemovePack(key: String) = mutateDraft(packs = true) { it.removePack(key) }
    override fun onRemoveController(key: String) = mutateDraft(controllers = true) { it.removeController(key) }

    override fun onMovePack(from: Int, to: Int) = mutateDraft(packs = true) { it.movePack(from, to) }
    override fun onMoveController(from: Int, to: Int) = mutateDraft(controllers = true) { it.moveController(from, to) }

    override fun onPackLabelChanged(key: String, label: String) =
        mutateDraft(packs = true) { d -> d.updatePack(key) { it.copy(label = label) } }
    override fun onPackTypeChanged(key: String, bmsType: BmsType) =
        mutateDraft(packs = true) { d -> d.updatePack(key) { it.copy(bmsType = bmsType) } }
    override fun onPackAddressChanged(key: String, address: String) =
        mutateDraft(packs = true) { d -> d.updatePack(key) { it.copy(address = address) } }
    override fun onPackCanIdChanged(key: String, canId: Int?) =
        mutateDraft(packs = true) { d -> d.updatePack(key) { it.copy(canId = canId) } }
    override fun onPackCellCountChanged(key: String, cellCount: Int?) =
        mutateDraft(packs = true) { d ->
            d.updatePack(key) { it.copy(cellCount = cellCount, cellCountEdited = true) }
        }

    override fun onControllerLabelChanged(key: String, label: String) =
        mutateDraft(controllers = true) { d -> d.updateController(key) { it.copy(label = label) } }
    override fun onControllerTypeChanged(key: String, controllerType: ControllerType) =
        mutateDraft(controllers = true) { d -> d.updateController(key) { it.copy(controllerType = controllerType) } }
    override fun onControllerAddressChanged(key: String, address: String) =
        mutateDraft(controllers = true) { d -> d.updateController(key) { it.copy(address = address) } }
    override fun onControllerCanIdChanged(key: String, canId: Int?) =
        mutateDraft(controllers = true) { d -> d.updateController(key) { it.copy(canId = canId) } }
    override fun onControllerMotorChanged(key: String, motor: MotorConfig) =
        mutateDraft(controllers = true) { d -> d.updateController(key) { it.copy(motor = MotorDraft.of(motor)) } }

    override fun onControllerPolePairsChanged(key: String, v: Int?) = editMotor(key) { it.copy(polePairs = v) }
    override fun onControllerWheelDiameterChanged(key: String, v: Int?) =
        editMotor(key) { it.copy(wheelDiameterMm = v) }
    override fun onControllerGearRatioChanged(key: String, v: Float?) = editMotor(key) { it.copy(gearRatio = v) }

    private fun editMotor(key: String, edit: (MotorDraft) -> MotorDraft) =
        mutateDraft(controllers = true) { d -> d.updateController(key) { it.copy(motor = edit(it.motor)) } }

    override fun onControllerDerivedBatteryChanged(key: String, enabled: Boolean) =
        mutateDraft(controllers = true) { d ->
            d.updateController(key) {
                // Always an explicit ON/OFF, never back to AUTO: the rider has
                // answered the question, and a later source change must not
                // quietly un-answer it (DerivedBatteryChoice).
                it.copy(
                    derivedBattery =
                        if (enabled) DerivedBatteryChoice.ON else DerivedBatteryChoice.OFF
                )
            }
        }

    override fun onTopologyChanged(topology: PackTopology) {
        _state.update { it.copy(topology = topology) }
    }

    // ----- Alias groups (G2 Task 4) -----

    override fun onGroupPacks(keyA: String, keyB: String) =
        mutateDraft(packs = true) { it.groupPacks(keyA, keyB) }

    override fun onUngroupPack(key: String) = mutateDraft(packs = true) { it.ungroupPack(key) }

    override fun onYieldBmsToHeadUnitChanged(enabled: Boolean) {
        // An explicit true, not a return to null: "unset" is the statement
        // "nobody has answered this", and the rider just did.
        _state.update { it.copy(yieldBmsToHeadUnit = enabled) }
    }

    // ----- Discovery (G2 Task 5) -----

    override fun onStartDeviceScan() {
        // Guarded on the JOB, not on `scanning`: two taps landing before the
        // first recomposition would otherwise start two collectors on one flow
        // and every hit would be folded twice.
        if (scanJob?.isActive == true) return
        _state.update { it.copy(scanning = true, scannedDevices = emptyList()) }
        scanJob = scope.launch {
            // The picker's scanner, unchanged — one BLE scan in this app. What
            // is shared beyond it is the labelling and the fold, in
            // `presentation/picker/SourceScan.kt`.
            bmsRepository.scanAll().collect { dev ->
                _state.update { it.copy(scannedDevices = it.scannedDevices.withScanHit(dev)) }
            }
        }
    }

    override fun onStopDeviceScan() {
        scanJob?.cancel()
        scanJob = null
        // The list is dropped with the sheet: a scan is a live view of what is
        // in range, and rows kept from a previous sheet are stale the moment it
        // closes.
        _state.update { it.copy(scanning = false, scannedDevices = emptyList()) }
    }

    /**
     * The one writer of a scanned address into the draft.
     *
     * The types come off the device's own detection ([addControllerType] /
     * [addBmsType]), which is what makes a Begode arrive as a Begode on both
     * halves of a wheel add rather than as a VESC beside a JK.
     *
     * Exhaustive over [ScannedAdd] with no `else`.
     */
    override fun onAddScannedDevice(device: DiscoveredDevice, add: ScannedAdd) {
        val label = device.name.orEmpty()
        when (add) {
            ScannedAdd.CONTROLLER ->
                mutateDraft(controllers = true) {
                    it.addController(device.addControllerType(), device.address, label)
                }
            ScannedAdd.BATTERY ->
                mutateDraft(packs = true) { it.addPack(device.addBmsType(), device.address, label) }
            // Both halves, so BOTH edited flags: a wheel add writes a pack and a
            // controller, and `withEdits` must carry both lists.
            ScannedAdd.WHEEL ->
                mutateDraft(packs = true, controllers = true) {
                    it.addWheel(device.addControllerType(), device.addBmsType(), device.address, label)
                }
        }
    }

    override fun onDiscoverCanDevices() {
        val s = _state.value
        // The two refusals, both stated on [VehicleEditComponent.onDiscoverCanDevices]:
        // no second scan inside the firmware's ~2.5 s window (it would be
        // silently discarded), and no scan without a live gateway to ask.
        if (s.canScan is CanScanState.Running) return
        val target = s.canScanTarget ?: return
        val discovery = canDiscovery ?: return
        // Set SYNCHRONOUSLY, before `launch`, and that is the whole of the
        // double-fire guard: `scope` is `Dispatchers.Main`, not `.immediate`, so
        // a `Running` written inside the coroutine lands on a later Looper
        // message and two taps within one message would both read `Idle` and
        // both dispatch. It is also what makes the spinner cover the whole
        // window — the firmware blocks ~2.55 s and shows nothing of its own.
        _state.update { it.copy(canScan = CanScanState.Running) }
        scope.launch {
            val result = discovery.discoverCanIds(target)
            _state.update {
                it.copy(
                    canScan = result.fold(
                        onSuccess = { ids -> CanScanState.Found(target, ids) },
                        // Typed, not `e.message`: the four refusals are four
                        // different things a rider can fix and each has to be
                        // said in their language. A failure from some other
                        // implementation has no reason to give, and NO_REPLY is
                        // the honest reading of "it did not come back".
                        onFailure = { e ->
                            CanScanState.Failed(
                                (e as? CanScanRefusedException)?.refusal ?: CanScanRefusal.NO_REPLY
                            )
                        }
                    )
                )
            }
        }
    }

    /**
     * Add one discovered device — **explicitly**, which is the whole difference
     * between a composer and a guess (`G §3`, twice).
     *
     * Every added source carries the candidate's own `canId`, and the only
     * candidate whose id is null is the gateway's hosted battery, which is the
     * one source that legitimately means "the head unit answers this itself"
     * (`C §6`). Anything else with a null id on a gateway link would be
     * [ComposerIssue.AmbiguousGatewaySource] — a config that saves clean,
     * connects, and reports double the current.
     *
     * `VESC_BMS` for a battery either way: a pack behind a VESC gateway — hosted
     * or on its CAN bus — answers `BMS_GET_VALUES`, and `VESC_BMS` beside a VESC
     * controller is the one sanctioned mixed-kind pairing `planLinks` allows.
     */
    override fun onAddCanCandidate(candidate: CanCandidate, asBattery: Boolean) {
        // The address the SCAN ran against, not the one a target recomputed from
        // the live connection currently points at: a link dropping between the
        // scan and the tap would otherwise make the add a silent no-op, and a
        // draft with a second VESC link would put the device on the wrong one.
        val target = (_state.value.canScan as? CanScanState.Found)?.address ?: return
        if (candidate.alreadyAdded) return
        val label = canCandidateLabel(candidate, asBattery)
        if (asBattery || candidate.kind == CanCandidateKind.HOSTED_BATTERY) {
            mutateDraft(packs = true) {
                it.addPack(BmsType.VESC_BMS, target, label, canId = candidate.canId)
            }
        } else {
            mutateDraft(controllers = true) {
                it.addController(ControllerType.VESC, target, label, canId = candidate.canId)
            }
        }
    }

    override fun onDismissCanScan() {
        _state.update { it.copy(canScan = CanScanState.Idle) }
    }

    override fun onSave() {
        val s = _state.value
        if (s.name.isBlank()) { _state.update { it.copy(nameError = true) }; return }
        // A blocking issue is a self-contradictory config: conflicting protocol
        // kinds at one address or two sources claiming one CAN id (both of
        // which `planLinks` throws on), or two sources on one gateway link both
        // claiming to be the gateway itself (which it cannot see). Refusing the
        // save is the composer's half of "the UI must not be able to reach that
        // failure"; [State.canSave] is the same fact for the screen to disable
        // the button with. Nothing here catches anything.
        //
        // Reported like [State.nameError] rather than returning silently: a
        // screen that wires only the button would otherwise look dead.
        if (s.issues.any { it.blocking }) { _state.update { it.copy(saveBlocked = true) }; return }
        scope.launch {
            _state.update { it.copy(saving = true) }
            // Re-read rather than reuse State.sourceVehicle: the alerts screen
            // (F Task 9) is PUSHED on top of this one, so it persists
            // motionAlerts while this component is still alive holding the
            // snapshot initialize() took. Editing the stored row is the point —
            // editing a stale copy of it would be the same defect wearing a
            // different hat.
            val existing = if (s.isEditing) vehicleRepository.get(vehicleId!!) else null
            val v = existing?.withEdits(s) ?: newVehicle(s, id = vehicleId ?: "v-${Random.nextLong()}")
            vehicleRepository.upsert(v)
            // G §9.2 item 7, and **the whole of it** — `withEdits` deliberately does not touch these
            // two fields, because `upsert` cannot write them (see `Vehicle.gaugePeakCurrentA` and
            // `VehicleRow.sq`): every other caller of `upsert` holds a snapshot that would revert
            // them, so the columns have exactly one writer. Clearing them is the one event that
            // legitimately lowers them, and this is it. One decision, asked once.
            //
            // A learned dial range describes HARDWARE, so the question is asked of the resulting
            // controller list and not of `controllersEdited`: a rider who retypes a label has edited
            // the controllers without changing an ohm, and losing a hard-learned range to a typo fix
            // would be its own defect. `GaugeScale.peaksStillApply` owns which fields count.
            if (existing != null && !GaugeScale.peaksStillApply(existing.controllers, v.controllers)) {
                vehicleRepository.updateGaugePeaks(v.id, currentA = 0f, powerW = 0f)
            }
            // If the user saved while a guest connection was live, swap the
            // active connection to the freshly-persisted Vehicle so the
            // dashboard immediately reflects the saved identity (pill name,
            // saved-vehicle list, etc.) without a manual reconnect step.
            val active = bmsRepository.activeVehicle.value
            // Only a live GUEST connection should auto-swap to the saved vehicle.
            // Demo is explicitly excluded (it isn't guest, and we never prefill
            // from it) so its synthetic "demo" identity can never trigger a real
            // connect off a saved profile.
            // primaryAddress on both sides: it is the identity connect() uses,
            // it is defined for a vehicle with zero packs, and — unlike
            // comparing two nullable pack addresses — it can never make two
            // source-less vehicles look equal by both being null.
            if (!s.isEditing && active?.isGuest == true && active.isDemo.not() &&
                active.primaryAddress == v.primaryAddress
            ) {
                bmsRepository.connect(v)
            }
            onSaved()
        }
    }

    override fun onCancel() { onCancelled() }

    override fun onDelete() {
        if (vehicleId == null) { onCancelled(); return }
        scope.launch {
            vehicleRepository.delete(vehicleId)
            onDeleted()
        }
    }
}

/**
 * Applies this form's edits **to the vehicle it loaded** — and touches nothing
 * else.
 *
 * The direction is the whole point, and it is the opposite of what this file
 * used to do. Saving once rebuilt the vehicle from scratch through
 * `singlePackVehicle(...)` and then hand-copied back the fields the form does
 * not expose; every field missing from that list was reset to its default on
 * *any* save, including one that never touched it. It ate `controllers` /
 * `topology`, then `yieldBmsToHeadUnit`, then `motionAlerts`, and a comment
 * warning about it did not stop the third (G-vehicle-composer.md §8).
 *
 * A `copy()` on the stored vehicle inverts the failure mode instead of
 * documenting it: **a field nobody names here is preserved**, so forgetting one
 * is a no-op rather than data loss, and a field can only be lost by someone
 * deliberately writing its name below. That also means this list is
 * self-limiting — the only correct entries are the ones
 * [VehicleEditComponent.State] actually edits, and adding a field to [Vehicle]
 * requires no change here at all.
 *
 * Everything not listed follows from that: `createdAt`, `lastConnectedAt`,
 * `isPinned`, `motionAlerts`. (`cellCount` is NOT one of these plain
 * preserved-by-default fields since Task 3 — it is a `Pack` field with its
 * own per-pack, once-touched rule, same shape as `aliasGroup` below.)
 *
 * `gaugePeakCurrentA` / `gaugePeakPowerW` are **not named here at all**, and that
 * is deliberate rather than an omission. They are cleared when the controller set
 * changes (`G §9.2` item 7) — a learned dial range describes hardware and cannot
 * outlive it — but `upsert` cannot write those two columns, so naming them here
 * would set a value nothing ever reads. [onSave]'s explicit `updateGaugePeaks` is
 * the whole clear, and the only one. They therefore ride this function's
 * preserved-by-default rule like any other field it does not edit.
 *
 * `yieldBmsToHeadUnit` left that list in Task 4 and is now written from
 * [VehicleEditComponent.State.yieldBmsToHeadUnit] — which is **seeded** from
 * the loaded vehicle rather than resolved, so the three-valued column survives
 * a save that never touched the toggle. `aliasGroup` left it too, but only
 * per pack and only once the rider has touched that pack's grouping — see
 * [PackDraft.aliasEdited]. `cellCount` (Task 3) left it on exactly the same
 * terms, per pack and only once touched — see [PackDraft.cellCountEdited].
 *
 * ### The two source lists (G2 Task 2)
 *
 * `packs` and `controllers` are written **only from the half of the composer
 * the rider actually touched** ([VehicleEditComponent.State.packsEdited]).
 * `s.draft` is a snapshot taken when the form opened, and the stored row can
 * move underneath it — `KableBmsRepository.maybePersistPacks` appends a Begode
 * wheel's second branch mid-connection, and this form is open exactly then
 * (Part D navigates a Controller pick straight here). Writing a never-edited
 * draft back would drop it: the same data loss as the rebuild, arriving by a
 * stale copy instead of by a forgotten field.
 *
 * So an untouched half passes through byte-for-byte — the pack list keeps the
 * long-standing "the vehicle name renames pack 0" coupling (`singlePackVehicle`
 * labels pack 0 after the vehicle and `packLabelFor` shows it verbatim on a
 * single-pack card). Once the rider composes that half, it is theirs: the draft
 * carries a label per pack, so the rename coupling stands down rather than
 * overwriting what they typed.
 *
 * The `packsEdited` switch protects an untouched half *wholesale*; a field that
 * moved inside a source the rider also edited needs the second half of the same
 * rule, which is [reanchoredTo] — every draft's `origin` is re-pointed at THIS
 * freshly-read vehicle before it is projected, so a cell count the auto-fill
 * wrote while the form was open is not reverted by a save that renamed a pack.
 */
private fun Vehicle.withEdits(s: VehicleEditComponent.State): Vehicle {
    // The draft is the ONLY writer of `controllers`, motor geometry included —
    // one writer, so there is no second copy of the "blank field falls back to
    // MotorConfig()'s default" rule to drift (it is stated once, in
    // `MotorDraft.resolve`).
    //
    // `|| packsEdited` is the derived-battery rule showing through, not a
    // sloppy condition: `providesDerivedBattery` is a function of the PACK set
    // (`G §6`), so editing the packs alone still changes every controller. The
    // asymmetry is deliberate — a controller-only edit leaves `packs` to the
    // stored row, which is what keeps a branch persisted underneath this form
    // from being dropped, and nothing writes `controllers` underneath us.
    val nextControllers = if (s.controllersEdited || s.packsEdited) {
        s.draft.reanchoredTo(this).toControllers()
    } else {
        controllers
    }
    return copy(
        name = s.name,
        iconKey = s.iconKey,
        chemistry = s.chemistry,
        topology = s.topology,
        averagingWindowMin = s.averagingWindowMin,
        alertConfig = alertConfig.withEdits(s),
        packs = if (s.packsEdited) {
            s.draft.reanchoredTo(this).toPacks()
        } else {
            // Index 0 ONLY: a wheel's second branch keeps the positional label
            // `expandedTo` gave it, and a pack-less vehicle maps an empty list to
            // an empty list — no phantom battery, and no `packs.isEmpty()` special
            // case to forget.
            packs.mapIndexed { i, p -> if (i == 0) p.copy(label = s.name) else p }
        },
        controllers = nextControllers,
        dashboardStyle = s.dashboardStyle,
        secondaryGauge = s.secondaryGauge,
        yieldBmsToHeadUnit = s.yieldBmsToHeadUnit
    )
}

/** The five thresholds this form exposes; the rest of [AlertConfig] is not its business. */
private fun AlertConfig.withEdits(s: VehicleEditComponent.State): AlertConfig = copy(
    cellHighV = s.cellHighV,
    cellLowV = s.cellLowV,
    temperatureWarnC = s.temperatureWarnC,
    temperatureHighC = s.temperatureHighC,
    socLowPercent = s.socLowPercent
)

/**
 * The CREATE path, and the only place this screen still calls a constructor.
 *
 * A vehicle that does not exist yet has nothing to preserve, so a builder is
 * exactly right here — and [singlePackVehicle] is the right one because this
 * form only ever originates the single-BMS shape (a controller vehicle is
 * created by the Picker, `pickedControllerVehicle`, which then navigates
 * straight to this screen to EDIT it).
 */
@OptIn(ExperimentalTime::class)
private fun newVehicle(s: VehicleEditComponent.State, id: String): Vehicle = singlePackVehicle(
    id = id,
    name = s.name,
    iconKey = s.iconKey,
    bmsType = s.bmsType,
    bmsAddress = s.bmsAddress,
    chemistry = s.chemistry,
    averagingWindowMin = s.averagingWindowMin,
    alertConfig = AlertConfig().withEdits(s),
    createdAt = Clock.System.now()
).copy(
    dashboardStyle = s.dashboardStyle,
    secondaryGauge = s.secondaryGauge
)
