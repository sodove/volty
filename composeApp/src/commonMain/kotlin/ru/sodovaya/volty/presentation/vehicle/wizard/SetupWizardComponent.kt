package ru.sodovaya.volty.presentation.vehicle.wizard

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.MotorConfigProvenance
import ru.sodovaya.volty.domain.repository.CanDiscovery
import ru.sodovaya.volty.domain.repository.CanScanRefusal
import ru.sodovaya.volty.domain.repository.CanScanRefusedException
import ru.sodovaya.volty.domain.repository.DeviceTypeMemory
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.presentation.picker.ScannedAdd
import ru.sodovaya.volty.presentation.vehicle.CanCandidate
import ru.sodovaya.volty.presentation.vehicle.CanCandidateKind
import ru.sodovaya.volty.presentation.vehicle.CanScanState
import ru.sodovaya.volty.presentation.vehicle.ComposerIssue
import ru.sodovaya.volty.presentation.vehicle.DerivedBatteryChoice
import ru.sodovaya.volty.presentation.vehicle.DraftExitComponent
import ru.sodovaya.volty.presentation.vehicle.DraftExitCoordinator
import ru.sodovaya.volty.presentation.vehicle.PersistableVehicleDraft
import ru.sodovaya.volty.presentation.vehicle.VehicleDraft
import ru.sodovaya.volty.presentation.vehicle.VehicleSourceScanState
import ru.sodovaya.volty.presentation.vehicle.VehicleSourceScanner
import ru.sodovaya.volty.presentation.vehicle.addCanController
import ru.sodovaya.volty.presentation.vehicle.addPack
import ru.sodovaya.volty.presentation.vehicle.canCandidateLabel
import ru.sodovaya.volty.presentation.vehicle.canCandidates as buildCanCandidates
import ru.sodovaya.volty.presentation.vehicle.canScanTarget
import ru.sodovaya.volty.presentation.vehicle.newVehicleFromDraft
import ru.sodovaya.volty.presentation.vehicle.persistableValues
import ru.sodovaya.volty.presentation.vehicle.removeSetupController
import ru.sodovaya.volty.presentation.vehicle.removeSetupPack
import ru.sodovaya.volty.presentation.vehicle.updateController
import ru.sodovaya.volty.presentation.vehicle.updatePack
import ru.sodovaya.volty.presentation.vehicle.validate
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Stage-one hint. It chooses defaults and never filters a later action. */
enum class VehicleArchetype { WHEEL, SCOOTER, BICYCLE, CUSTOM }

interface SetupWizardComponent : DraftExitComponent {
    val stack: Value<ChildStack<*, Child>>
    val state: StateFlow<State>

    fun onCancel()
    fun onDiscardConfirmed()
    fun onDiscardDismissed()

    sealed interface Child {
        data class WhatAreWeBuilding(val component: ArchetypeStage) : Child
        data class Controllers(val component: ControllerStage) : Child
        data class Battery(val component: BatteryStage) : Child
        data class Review(val component: ReviewStage) : Child
        data class Done(val component: DoneStage) : Child
    }

    data class EditableValues(
        val archetype: VehicleArchetype,
        val name: String,
        val iconKey: String,
        val secondaryGauge: SecondaryGauge,
        val topology: PackTopology,
        val draft: PersistableVehicleDraft
    )

    /** One physical scan card and every draft addition the rider can choose on it. */
    data class ScanRow(
        val device: DiscoveredDevice,
        val additions: List<ScannedAdd>
    )

    data class State(
        val archetype: VehicleArchetype,
        val name: String,
        val iconKey: String,
        val secondaryGauge: SecondaryGauge,
        val topology: PackTopology = PackTopology.PARALLEL,
        val draft: VehicleDraft,
        val issues: List<ComposerIssue>,
        val savedValues: EditableValues,
        val scanning: Boolean = false,
        val scannedDevices: List<DiscoveredDevice> = emptyList(),
        val advanceBlocked: Boolean = false,
        val saveBlocked: Boolean = false,
        val saving: Boolean = false,
        val discardPrompt: Boolean = false,
        val canScanTarget: String? = null,
        val canScan: CanScanState = CanScanState.Idle,
        val savedVehicle: Vehicle? = null,
        val connecting: Boolean = false,
        val connectFailed: Boolean = false
    ) {
        val editableValues: EditableValues
            get() = EditableValues(
                archetype,
                name,
                iconKey,
                secondaryGauge,
                topology,
                draft.persistableValues()
            )

        val isDirty: Boolean get() = editableValues != savedValues

        /** Save owns its snapshot until persistence returns. */
        val navigationEnabled: Boolean get() = !saving

        /** Pack wiring changes aggregation only once a second physical pack exists. */
        val showTopologyChoice: Boolean get() = draft.packs.size >= 2

        /** Offers from the bus that actually answered the last successful scan. */
        val canCandidates: List<CanCandidate>
            get() {
                val found = canScan as? CanScanState.Found ?: return emptyList()
                return buildCanCandidates(draft, found.address, found.ids)
            }

        val canSave: Boolean
            get() = name.isNotBlank() &&
                draft.sourceCount > 0 &&
                issues.none { it.blocking || it is ComposerIssue.BlankAddress }
    }

    interface Stage {
        val state: StateFlow<State>
    }

    interface ArchetypeStage : Stage {
        fun onArchetypeSelected(archetype: VehicleArchetype)
        fun onSkip()
        fun onNext()
        fun onCancel()
    }

    /** Source corrections common to stages 2 and 3. Detection is only a hint. */
    interface SourceStage : Stage {
        fun onRemovePack(key: String)
        fun onRemoveController(key: String)
        fun onPackTypeChanged(key: String, bmsType: BmsType)
        fun onControllerTypeChanged(key: String, controllerType: ControllerType)
        fun onControllerCanIdChanged(key: String, canId: Int?)
        fun onControllerPolePairsChanged(key: String, value: Int?)
        fun onControllerWheelDiameterChanged(key: String, value: Int?)
        fun onControllerGearRatioChanged(key: String, value: Float?)
    }

    interface ControllerStage : SourceStage {
        val scanRows: List<ScanRow>
        fun onNameChanged(name: String)
        fun onAddScannedDevice(device: DiscoveredDevice, add: ScannedAdd)
        fun onDiscoverCanDevices()
        fun onAddCanCandidate(candidate: CanCandidate, asBattery: Boolean)
        fun onDismissCanScan()
        fun onNoController()
        fun onNext()
        fun onBack()
        fun onCancel()
    }

    interface BatteryStage : SourceStage {
        val scanRows: List<ScanRow>
        val canUseControllerBattery: Boolean
        fun onAddScannedDevice(device: DiscoveredDevice, add: ScannedAdd)
        fun onUseSeparateBms(device: DiscoveredDevice)
        fun onUseControllerBattery()
        fun onUseDeviceAsBoth(device: DiscoveredDevice)
        fun onTopologyChanged(topology: PackTopology)
        fun onNoBattery()
        fun onNext()
        fun onBack()
        fun onCancel()
    }

    interface ReviewStage : Stage {
        fun onSave()
        fun onBack()
        fun onCancel()
    }

    interface DoneStage : Stage {
        fun onShowVehicleList()
        fun onConnect()
    }
}

@Serializable
private sealed class WizardConfig {
    @Serializable data object WhatAreWeBuilding : WizardConfig()
    @Serializable data object Controllers : WizardConfig()
    @Serializable data object Battery : WizardConfig()
    @Serializable data object Review : WizardConfig()
    @Serializable data object Done : WizardConfig()
}

private data class ArchetypeDefaults(
    val iconKey: String,
    val secondaryGauge: SecondaryGauge
)

private fun defaultsFor(archetype: VehicleArchetype): ArchetypeDefaults = when (archetype) {
    VehicleArchetype.WHEEL -> ArchetypeDefaults("unicycle", SecondaryGauge.DUTY)
    VehicleArchetype.SCOOTER -> ArchetypeDefaults("scooter", SecondaryGauge.BATTERY)
    VehicleArchetype.BICYCLE -> ArchetypeDefaults("ebike", SecondaryGauge.BATTERY)
    VehicleArchetype.CUSTOM -> ArchetypeDefaults("generic", SecondaryGauge.DUTY)
}

@OptIn(DelicateDecomposeApi::class, ExperimentalTime::class)
class DefaultSetupWizardComponent(
    componentContext: ComponentContext,
    initialDraft: VehicleDraft = VehicleDraft(),
    initialName: String = "",
    initialArchetype: VehicleArchetype = VehicleArchetype.CUSTOM,
    private val scanAll: () -> Flow<DiscoveredDevice>,
    private val canDiscovery: CanDiscovery? = null,
    liveAddresses: Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet()),
    private val saveVehicle: suspend (Vehicle) -> Unit,
    private val rememberDeviceType: suspend (DeviceTypeMemory) -> Unit = {},
    private val connectVehicle: suspend (Vehicle) -> Result<Unit> = { Result.success(Unit) },
    private val newVehicleId: () -> String = { "v-${Random.nextLong()}" },
    private val now: () -> Instant = { Clock.System.now() },
    private val onCancelled: () -> Unit,
    private val onShowVehicleList: () -> Unit,
    private val onConnected: () -> Unit
) : SetupWizardComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<WizardConfig>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val initialDefaults = defaultsFor(initialArchetype)
    private val initialValues = SetupWizardComponent.EditableValues(
        archetype = initialArchetype,
        name = initialName,
        iconKey = initialDefaults.iconKey,
        secondaryGauge = initialDefaults.secondaryGauge,
        topology = PackTopology.PARALLEL,
        draft = initialDraft.persistableValues()
    )
    private val _state = MutableStateFlow(
        SetupWizardComponent.State(
            archetype = initialArchetype,
            name = initialName,
            iconKey = initialDefaults.iconKey,
            secondaryGauge = initialDefaults.secondaryGauge,
            draft = initialDraft,
            issues = validate(initialDraft),
            savedValues = initialValues
        )
    )
    override val state: StateFlow<SetupWizardComponent.State> = _state.asStateFlow()
    override val hasUnsavedDraft: Boolean get() = state.value.isDirty

    private val sourceScanner = VehicleSourceScanner(scope, scanAll, ::publishScan)
    private var currentLiveAddresses: Set<String> = emptySet()
    private val exitCoordinator = DraftExitCoordinator(
        isDirty = { _state.value.isDirty },
        publishPrompt = { visible -> _state.update { it.copy(discardPrompt = visible) } }
    )
    private val backCallback = BackCallback(onBack = ::onSystemBack)

    override val stack: Value<ChildStack<*, SetupWizardComponent.Child>> = childStack(
        source = navigation,
        serializer = WizardConfig.serializer(),
        initialConfiguration = WizardConfig.WhatAreWeBuilding,
        handleBackButton = false,
        childFactory = ::createChild
    )

    init {
        backHandler.register(backCallback)
        scope.launch {
            liveAddresses.collect { addresses ->
                currentLiveAddresses = addresses
                _state.update { it.copy(canScanTarget = canScanTarget(it.draft, addresses)) }
            }
        }
        lifecycle.doOnDestroy {
            sourceScanner.stop()
            scope.coroutineContext[Job]?.cancel()
        }
    }

    private fun createChild(
        config: WizardConfig,
        @Suppress("UNUSED_PARAMETER") context: ComponentContext
    ): SetupWizardComponent.Child = when (config) {
        WizardConfig.WhatAreWeBuilding ->
            SetupWizardComponent.Child.WhatAreWeBuilding(ArchetypeStageComponent())
        WizardConfig.Controllers ->
            SetupWizardComponent.Child.Controllers(ControllerStageComponent())
        WizardConfig.Battery ->
            SetupWizardComponent.Child.Battery(BatteryStageComponent())
        WizardConfig.Review ->
            SetupWizardComponent.Child.Review(ReviewStageComponent())
        WizardConfig.Done ->
            SetupWizardComponent.Child.Done(DoneStageComponent())
    }

    override fun onCancel() = requestExit(onCancelled)
    override fun requestExit(onDiscarded: () -> Unit) {
        if (!_state.value.navigationEnabled) return
        exitCoordinator.requestExit(onDiscarded)
    }
    override fun onDiscardConfirmed() = exitCoordinator.confirm()
    override fun onDiscardDismissed() = exitCoordinator.dismiss()

    private fun selectArchetype(archetype: VehicleArchetype) {
        if (!_state.value.navigationEnabled) return
        val defaults = defaultsFor(archetype)
        _state.update {
            it.copy(
                archetype = archetype,
                iconKey = defaults.iconKey,
                secondaryGauge = defaults.secondaryGauge,
                advanceBlocked = false
            )
        }
    }

    private fun openControllers() {
        if (!_state.value.navigationEnabled) return
        sourceScanner.start()
        navigation.push(WizardConfig.Controllers)
    }

    private fun openBattery() {
        if (!_state.value.navigationEnabled) return
        _state.update { it.copy(advanceBlocked = false) }
        navigation.push(WizardConfig.Battery)
    }

    private fun openReview() {
        if (!_state.value.navigationEnabled) return
        if (_state.value.draft.sourceCount == 0) {
            _state.update { it.copy(advanceBlocked = true) }
            return
        }
        sourceScanner.stop()
        _state.update { it.copy(advanceBlocked = false) }
        navigation.push(WizardConfig.Review)
    }

    private fun backFromReview() {
        if (!_state.value.navigationEnabled) return
        sourceScanner.start()
        navigation.pop()
    }

    private fun backFromControllers() {
        if (!_state.value.navigationEnabled) return
        sourceScanner.stop()
        _state.update { it.copy(advanceBlocked = false) }
        navigation.pop()
    }

    private fun backFromStage() {
        if (!_state.value.navigationEnabled) return
        _state.update { it.copy(advanceBlocked = false) }
        navigation.pop()
    }

    private fun publishScan(scan: VehicleSourceScanState) {
        _state.update { it.copy(scanning = scan.scanning, scannedDevices = scan.devices) }
    }

    private fun scanRows(): List<SetupWizardComponent.ScanRow> =
        _state.value.scannedDevices.map { device ->
            SetupWizardComponent.ScanRow(device, ScannedAdd.entries)
        }

    private fun addScannedDevice(device: DiscoveredDevice, add: ScannedAdd) {
        if (!_state.value.navigationEnabled) return
        mutateDraft { draft -> sourceScanner.addTo(draft, device, add) }
    }

    /** One writer for every setup-stage source correction. */
    private fun mutateDraft(block: (VehicleDraft) -> VehicleDraft) {
        if (!_state.value.navigationEnabled) return
        _state.update { current ->
            val draft = block(current.draft)
            current.copy(
                draft = draft,
                topology = if (draft.packs.size >= 2) current.topology else PackTopology.PARALLEL,
                issues = validate(draft),
                advanceBlocked = false,
                saveBlocked = false,
                canScanTarget = canScanTarget(draft, currentLiveAddresses)
            )
        }
    }

    private fun removePack(key: String) = mutateDraft { it.removeSetupPack(key) }
    private fun removeController(key: String) = mutateDraft { it.removeSetupController(key) }
    private fun changePackType(key: String, type: BmsType) {
        val address = _state.value.draft.packs.firstOrNull { it.key == key }?.address.orEmpty()
        mutateDraft { draft -> draft.updatePack(key) { it.copy(bmsType = type) } }
        rememberType(address, DeviceTypeMemory(address = address, bmsType = type))
    }

    private fun changeControllerType(key: String, type: ControllerType) {
        val address = _state.value.draft.controllers.firstOrNull { it.key == key }?.address.orEmpty()
        mutateDraft { draft -> draft.updateController(key) { it.copy(controllerType = type) } }
        rememberType(address, DeviceTypeMemory(address = address, controllerType = type))
    }

    private fun rememberType(address: String, memory: DeviceTypeMemory) {
        if (address.isBlank()) return
        scope.launch { rememberDeviceType(memory) }
    }
    private fun changeControllerCanId(key: String, canId: Int?) =
        mutateDraft { draft -> draft.updateController(key) { it.copy(canId = canId) } }
    private fun changeControllerPolePairs(key: String, value: Int?) =
        editControllerMotor(key) { it.copy(polePairs = value) }
    private fun changeControllerWheelDiameter(key: String, value: Int?) =
        editControllerMotor(key) { it.copy(wheelDiameterMm = value) }
    private fun changeControllerGearRatio(key: String, value: Float?) =
        editControllerMotor(key) { it.copy(gearRatio = value) }

    private fun editControllerMotor(
        key: String,
        edit: (ru.sodovaya.volty.presentation.vehicle.MotorDraft) ->
            ru.sodovaya.volty.presentation.vehicle.MotorDraft
    ) = mutateDraft { draft ->
        draft.updateController(key) {
            it.copy(motor = edit(it.motor), motorProvenance = MotorConfigProvenance.RIDER)
        }
    }

    private fun chooseNoBattery() {
        if (!_state.value.navigationEnabled) return
        mutateDraft { draft ->
            draft.copy(
                packs = emptyList(),
                controllers = draft.controllers.map {
                    it.copy(derivedBattery = DerivedBatteryChoice.OFF)
                }
            )
        }
        openReview()
    }

    private fun discoverCanDevices() {
        val snapshot = _state.value
        if (snapshot.canScan is CanScanState.Running) return
        val target = snapshot.canScanTarget ?: return
        val discovery = canDiscovery ?: return
        _state.update { it.copy(canScan = CanScanState.Running) }
        scope.launch {
            val result = discovery.discoverCanIds(target)
            _state.update { state ->
                state.copy(
                    canScan = result.fold(
                        onSuccess = { ids -> CanScanState.Found(target, ids) },
                        onFailure = { error ->
                            CanScanState.Failed(
                                (error as? CanScanRefusedException)?.refusal
                                    ?: CanScanRefusal.NO_REPLY
                            )
                        }
                    )
                )
            }
        }
    }

    private fun addCanCandidate(candidate: CanCandidate, asBattery: Boolean) {
        val target = (_state.value.canScan as? CanScanState.Found)?.address ?: return
        if (candidate.alreadyAdded) return
        val label = canCandidateLabel(candidate, asBattery)
        mutateDraft { draft ->
            if (asBattery || candidate.kind == CanCandidateKind.HOSTED_BATTERY) {
                draft.addPack(BmsType.VESC_BMS, target, label, candidate.canId)
            } else {
                draft.addCanController(
                    ControllerType.VESC,
                    target,
                    label,
                    candidate.canId
                )
            }
        }
    }

    private fun useControllerBattery() {
        if (!_state.value.navigationEnabled || _state.value.draft.controllers.isEmpty()) return
        _state.update { current ->
            val draft = current.draft.copy(
                controllers = current.draft.controllers.map { controller ->
                    controller.copy(derivedBattery = DerivedBatteryChoice.ON)
                }
            )
            current.copy(
                draft = draft,
                issues = validate(draft),
                advanceBlocked = false,
                saveBlocked = false
            )
        }
        openReview()
    }

    private fun changeTopology(topology: PackTopology) {
        if (!_state.value.navigationEnabled) return
        _state.update { current ->
            current.copy(
                topology = if (current.showTopologyChoice) topology else PackTopology.PARALLEL
            )
        }
    }

    private fun save() {
        val snapshot = _state.value
        if (snapshot.saving) return
        if (!snapshot.canSave) {
            _state.update { it.copy(saveBlocked = true) }
            return
        }
        // Close the tap-sized window before the launched coroutine dispatches.
        _state.update { it.copy(saving = true) }
        scope.launch {
            val vehicle = newVehicleFromDraft(
                id = newVehicleId(),
                name = snapshot.name,
                iconKey = snapshot.iconKey,
                draft = snapshot.draft,
                chemistry = Chemistry.LI_ION_NMC,
                createdAt = now(),
                secondaryGauge = snapshot.secondaryGauge,
                topology = if (snapshot.showTopologyChoice) {
                    snapshot.topology
                } else {
                    PackTopology.PARALLEL
                }
            )
            saveVehicle(vehicle)
            _state.update {
                it.copy(
                    saving = false,
                    savedVehicle = vehicle,
                    savedValues = snapshot.editableValues,
                    saveBlocked = false,
                    discardPrompt = false
                )
            }
            exitCoordinator.afterSave()
            navigation.push(WizardConfig.Done)
        }
    }

    private fun connect() {
        val vehicle = _state.value.savedVehicle ?: return
        scope.launch {
            _state.update { it.copy(connecting = true, connectFailed = false) }
            val result = connectVehicle(vehicle)
            _state.update { it.copy(connecting = false, connectFailed = result.isFailure) }
            if (result.isSuccess) onConnected()
        }
    }

    private fun onSystemBack() {
        if (!_state.value.navigationEnabled) return
        when (stack.value.active.instance) {
            is SetupWizardComponent.Child.WhatAreWeBuilding -> onCancel()
            is SetupWizardComponent.Child.Controllers -> backFromControllers()
            is SetupWizardComponent.Child.Battery -> backFromStage()
            is SetupWizardComponent.Child.Review -> backFromReview()
            is SetupWizardComponent.Child.Done -> onShowVehicleList()
        }
    }

    private inner class ArchetypeStageComponent : SetupWizardComponent.ArchetypeStage {
        override val state = this@DefaultSetupWizardComponent.state
        override fun onArchetypeSelected(archetype: VehicleArchetype) = selectArchetype(archetype)
        override fun onSkip() {
            selectArchetype(VehicleArchetype.CUSTOM)
            openControllers()
        }
        override fun onNext() = openControllers()
        override fun onCancel() = this@DefaultSetupWizardComponent.onCancel()
    }

    private abstract inner class SourceStageComponent : SetupWizardComponent.SourceStage {
        override val state = this@DefaultSetupWizardComponent.state
        override fun onRemovePack(key: String) = this@DefaultSetupWizardComponent.removePack(key)
        override fun onRemoveController(key: String) =
            this@DefaultSetupWizardComponent.removeController(key)
        override fun onPackTypeChanged(key: String, bmsType: BmsType) =
            this@DefaultSetupWizardComponent.changePackType(key, bmsType)
        override fun onControllerTypeChanged(key: String, controllerType: ControllerType) =
            this@DefaultSetupWizardComponent.changeControllerType(key, controllerType)
        override fun onControllerCanIdChanged(key: String, canId: Int?) =
            this@DefaultSetupWizardComponent.changeControllerCanId(key, canId)
        override fun onControllerPolePairsChanged(key: String, value: Int?) =
            this@DefaultSetupWizardComponent.changeControllerPolePairs(key, value)
        override fun onControllerWheelDiameterChanged(key: String, value: Int?) =
            this@DefaultSetupWizardComponent.changeControllerWheelDiameter(key, value)
        override fun onControllerGearRatioChanged(key: String, value: Float?) =
            this@DefaultSetupWizardComponent.changeControllerGearRatio(key, value)
    }

    private inner class ControllerStageComponent :
        SourceStageComponent(), SetupWizardComponent.ControllerStage {
        override val scanRows: List<SetupWizardComponent.ScanRow>
            get() = this@DefaultSetupWizardComponent.scanRows()
        override fun onNameChanged(name: String) {
            if (_state.value.saving) return
            _state.update { it.copy(name = name, saveBlocked = false) }
        }
        override fun onAddScannedDevice(device: DiscoveredDevice, add: ScannedAdd) =
            this@DefaultSetupWizardComponent.addScannedDevice(device, add)
        override fun onDiscoverCanDevices() = this@DefaultSetupWizardComponent.discoverCanDevices()
        override fun onAddCanCandidate(candidate: CanCandidate, asBattery: Boolean) =
            this@DefaultSetupWizardComponent.addCanCandidate(candidate, asBattery)
        override fun onDismissCanScan() = _state.update { it.copy(canScan = CanScanState.Idle) }
        override fun onNoController() = openBattery()
        override fun onNext() = openBattery()
        override fun onBack() = backFromControllers()
        override fun onCancel() = this@DefaultSetupWizardComponent.onCancel()
    }

    private inner class BatteryStageComponent :
        SourceStageComponent(), SetupWizardComponent.BatteryStage {
        override val scanRows: List<SetupWizardComponent.ScanRow>
            get() = this@DefaultSetupWizardComponent.scanRows()
        override val canUseControllerBattery: Boolean
            get() = _state.value.draft.controllers.isNotEmpty()
        override fun onAddScannedDevice(device: DiscoveredDevice, add: ScannedAdd) =
            this@DefaultSetupWizardComponent.addScannedDevice(device, add)
        override fun onUseSeparateBms(device: DiscoveredDevice) =
            this@DefaultSetupWizardComponent.addScannedDevice(device, ScannedAdd.BATTERY)
        override fun onUseControllerBattery() =
            this@DefaultSetupWizardComponent.useControllerBattery()
        override fun onUseDeviceAsBoth(device: DiscoveredDevice) =
            this@DefaultSetupWizardComponent.addScannedDevice(device, ScannedAdd.WHEEL)
        override fun onTopologyChanged(topology: PackTopology) =
            this@DefaultSetupWizardComponent.changeTopology(topology)
        override fun onNoBattery() = chooseNoBattery()
        override fun onNext() = openReview()
        override fun onBack() = backFromStage()
        override fun onCancel() = this@DefaultSetupWizardComponent.onCancel()
    }

    private inner class ReviewStageComponent : SetupWizardComponent.ReviewStage {
        override val state = this@DefaultSetupWizardComponent.state
        override fun onSave() = save()
        override fun onBack() = backFromReview()
        override fun onCancel() = this@DefaultSetupWizardComponent.onCancel()
    }

    private inner class DoneStageComponent : SetupWizardComponent.DoneStage {
        override val state = this@DefaultSetupWizardComponent.state
        override fun onShowVehicleList() = this@DefaultSetupWizardComponent.onShowVehicleList()
        override fun onConnect() = connect()
    }
}
