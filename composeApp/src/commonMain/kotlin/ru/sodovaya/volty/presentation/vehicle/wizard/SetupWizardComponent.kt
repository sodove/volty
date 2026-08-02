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
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.presentation.picker.ScannedAdd
import ru.sodovaya.volty.presentation.vehicle.ComposerIssue
import ru.sodovaya.volty.presentation.vehicle.DraftExitComponent
import ru.sodovaya.volty.presentation.vehicle.DraftExitCoordinator
import ru.sodovaya.volty.presentation.vehicle.PersistableVehicleDraft
import ru.sodovaya.volty.presentation.vehicle.VehicleDraft
import ru.sodovaya.volty.presentation.vehicle.VehicleSourceScanState
import ru.sodovaya.volty.presentation.vehicle.VehicleSourceScanner
import ru.sodovaya.volty.presentation.vehicle.newVehicleFromDraft
import ru.sodovaya.volty.presentation.vehicle.persistableValues
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
        val draft: PersistableVehicleDraft
    )

    data class State(
        val archetype: VehicleArchetype,
        val name: String,
        val iconKey: String,
        val secondaryGauge: SecondaryGauge,
        val draft: VehicleDraft,
        val issues: List<ComposerIssue>,
        val savedValues: EditableValues,
        val scanning: Boolean = false,
        val scannedDevices: List<DiscoveredDevice> = emptyList(),
        val advanceBlocked: Boolean = false,
        val saveBlocked: Boolean = false,
        val saving: Boolean = false,
        val discardPrompt: Boolean = false,
        val savedVehicle: Vehicle? = null,
        val connecting: Boolean = false,
        val connectFailed: Boolean = false
    ) {
        val editableValues: EditableValues
            get() = EditableValues(archetype, name, iconKey, secondaryGauge, draft.persistableValues())

        val isDirty: Boolean get() = editableValues != savedValues

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

    interface ControllerStage : Stage {
        val availableAdds: List<ScannedAdd>
        fun onNameChanged(name: String)
        fun onAddScannedDevice(device: DiscoveredDevice, add: ScannedAdd)
        fun onNoController()
        fun onNext()
        fun onBack()
        fun onCancel()
    }

    interface BatteryStage : Stage {
        val availableAdds: List<ScannedAdd>
        fun onAddScannedDevice(device: DiscoveredDevice, add: ScannedAdd)
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
    private val saveVehicle: suspend (Vehicle) -> Unit,
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
    override fun requestExit(onDiscarded: () -> Unit) = exitCoordinator.requestExit(onDiscarded)
    override fun onDiscardConfirmed() = exitCoordinator.confirm()
    override fun onDiscardDismissed() = exitCoordinator.dismiss()

    private fun selectArchetype(archetype: VehicleArchetype) {
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
        sourceScanner.start()
        navigation.push(WizardConfig.Controllers)
    }

    private fun openBattery() {
        _state.update { it.copy(advanceBlocked = false) }
        navigation.push(WizardConfig.Battery)
    }

    private fun openReview() {
        if (_state.value.draft.sourceCount == 0) {
            _state.update { it.copy(advanceBlocked = true) }
            return
        }
        sourceScanner.stop()
        _state.update { it.copy(advanceBlocked = false) }
        navigation.push(WizardConfig.Review)
    }

    private fun backFromReview() {
        sourceScanner.start()
        navigation.pop()
    }

    private fun backFromControllers() {
        sourceScanner.stop()
        _state.update { it.copy(advanceBlocked = false) }
        navigation.pop()
    }

    private fun backFromStage() {
        _state.update { it.copy(advanceBlocked = false) }
        navigation.pop()
    }

    private fun publishScan(scan: VehicleSourceScanState) {
        _state.update { it.copy(scanning = scan.scanning, scannedDevices = scan.devices) }
    }

    private fun addScannedDevice(device: DiscoveredDevice, add: ScannedAdd) {
        _state.update { current ->
            val draft = sourceScanner.addTo(current.draft, device, add)
            current.copy(
                draft = draft,
                issues = validate(draft),
                advanceBlocked = false,
                saveBlocked = false
            )
        }
    }

    private fun save() {
        val snapshot = _state.value
        if (!snapshot.canSave) {
            _state.update { it.copy(saveBlocked = true) }
            return
        }
        scope.launch {
            _state.update { it.copy(saving = true) }
            val vehicle = newVehicleFromDraft(
                id = newVehicleId(),
                name = snapshot.name,
                iconKey = snapshot.iconKey,
                draft = snapshot.draft,
                chemistry = Chemistry.LI_ION_NMC,
                createdAt = now(),
                secondaryGauge = snapshot.secondaryGauge
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

    private inner class ControllerStageComponent : SetupWizardComponent.ControllerStage {
        override val state = this@DefaultSetupWizardComponent.state
        override val availableAdds: List<ScannedAdd> get() = ScannedAdd.entries
        override fun onNameChanged(name: String) {
            _state.update { it.copy(name = name, saveBlocked = false) }
        }
        override fun onAddScannedDevice(device: DiscoveredDevice, add: ScannedAdd) =
            this@DefaultSetupWizardComponent.addScannedDevice(device, add)
        override fun onNoController() = openBattery()
        override fun onNext() = openBattery()
        override fun onBack() = backFromControllers()
        override fun onCancel() = this@DefaultSetupWizardComponent.onCancel()
    }

    private inner class BatteryStageComponent : SetupWizardComponent.BatteryStage {
        override val state = this@DefaultSetupWizardComponent.state
        override val availableAdds: List<ScannedAdd> get() = ScannedAdd.entries
        override fun onAddScannedDevice(device: DiscoveredDevice, add: ScannedAdd) =
            this@DefaultSetupWizardComponent.addScannedDevice(device, add)
        override fun onNoBattery() = openReview()
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
