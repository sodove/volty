package ru.sodovaya.volty.presentation.vehicle

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    fun onSave()
    fun onCancel()
    fun onDelete()

    data class State(
        val isEditing: Boolean = false,
        val name: String = "",
        val iconKey: String = "generic",
        val chemistry: Chemistry = Chemistry.LI_ION_NMC,
        val bmsType: BmsType = BmsType.JK_BMS,
        val bmsAddress: String = "",
        val averagingWindowMin: Int = 5,
        val cellHighV: Float? = null,
        val cellLowV: Float? = null,
        val temperatureWarnC: Float? = 50f,
        val temperatureHighC: Float? = 60f,
        val socLowPercent: Int? = 15,
        val nameError: Boolean = false,
        val saving: Boolean = false
    )
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
    // Optional prefilled BMS info when creating from Picker
    private val prefilledBmsType: BmsType? = null,
    private val prefilledBmsAddress: String? = null,
    private val prefilledName: String? = null
) : VehicleEditComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(VehicleEditComponent.State())
    override val state: StateFlow<VehicleEditComponent.State> = _state.asStateFlow()

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }
        scope.launch { initialize() }
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
                    bmsType = v.bmsType,
                    bmsAddress = v.bmsAddress,
                    averagingWindowMin = v.averagingWindowMin,
                    cellHighV = v.alertConfig.cellHighV,
                    cellLowV = v.alertConfig.cellLowV,
                    temperatureWarnC = v.alertConfig.temperatureWarnC,
                    temperatureHighC = v.alertConfig.temperatureHighC,
                    socLowPercent = v.alertConfig.socLowPercent
                )
                return
            }
        }
        _state.value = VehicleEditComponent.State(
            isEditing = false,
            name = prefilledName ?: "",
            bmsType = prefilledBmsType ?: BmsType.JK_BMS,
            bmsAddress = prefilledBmsAddress ?: ""
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

    override fun onSave() {
        val s = _state.value
        if (s.name.isBlank()) { _state.update { it.copy(nameError = true) }; return }
        scope.launch {
            _state.update { it.copy(saving = true) }
            // Preserve everything the edit form doesn't expose (cutoff / delta /
            // notify toggles, pin, last-connected) — rebuilding from defaults
            // would silently wipe them on every save.
            val existing = if (s.isEditing) vehicleRepository.get(vehicleId!!) else null
            val v = Vehicle(
                id = vehicleId ?: "v-${Random.nextLong()}",
                name = s.name,
                iconKey = s.iconKey,
                bmsType = s.bmsType,
                bmsAddress = s.bmsAddress,
                chemistry = s.chemistry,
                // Auto-filled from live telemetry by the repo (see
                // KableBmsRepository.maybePersistCellCount) — never edited here.
                cellCount = existing?.cellCount,
                averagingWindowMin = s.averagingWindowMin,
                alertConfig = (existing?.alertConfig ?: AlertConfig()).copy(
                    cellHighV = s.cellHighV,
                    cellLowV = s.cellLowV,
                    temperatureWarnC = s.temperatureWarnC,
                    temperatureHighC = s.temperatureHighC,
                    socLowPercent = s.socLowPercent
                ),
                createdAt = existing?.createdAt ?: Clock.System.now(),
                lastConnectedAt = existing?.lastConnectedAt,
                isPinned = existing?.isPinned ?: false
            )
            vehicleRepository.upsert(v)
            // If the user saved while a guest connection was live, swap the
            // active connection to the freshly-persisted Vehicle so the
            // dashboard immediately reflects the saved identity (pill name,
            // saved-vehicle list, etc.) without a manual reconnect step.
            val active = bmsRepository.activeVehicle.value
            // Only a live GUEST connection should auto-swap to the saved vehicle.
            // Demo is explicitly excluded (it isn't guest, and we never prefill
            // from it) so its synthetic "demo" identity can never trigger a real
            // connect off a saved profile.
            if (!s.isEditing && active?.isGuest == true && active.isDemo.not() &&
                active.bmsAddress == v.bmsAddress
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
