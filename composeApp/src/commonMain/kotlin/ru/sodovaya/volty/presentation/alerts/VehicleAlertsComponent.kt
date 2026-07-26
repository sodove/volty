package ru.sodovaya.volty.presentation.alerts

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.availabilityFor
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.notification.AlarmPreview

/**
 * The per-vehicle alert settings screen (F §10, §10.2, §11.2) — the thing that
 * turns a shipped set of thresholds into *the rider's* thresholds.
 *
 * Without it the alarm ships with numbers nobody can change, which is the
 * product owner's explicit rejection (*"все алерты должны быть редактируемыми,
 * что вкл\выкл, что по лимитам"*) and, on a rider who runs his motor to 130 °C
 * by choice, is F §10's train-them-to-ignore-it failure shipped deliberately.
 *
 * Every decision lives in [MotionAlertEditing]'s pure functions or in this
 * component; [VehicleAlertsScreen] renders and nothing more, because Compose UI
 * is not unit-testable in this repo.
 */
interface VehicleAlertsComponent {
    val state: StateFlow<State>

    /** The per-kind switch. Maps onto the level list and nothing else — see [withEnabled]. */
    fun onKindEnabledChanged(kind: MotionAlertKind, enabled: Boolean)
    fun onThresholdChanged(kind: MotionAlertKind, index: Int, text: String)
    fun onLevelEnabledChanged(kind: MotionAlertKind, index: Int, enabled: Boolean)
    fun onAddLevel(kind: MotionAlertKind)
    fun onRemoveLevel(kind: MotionAlertKind, index: Int)

    /** The three global modality switches (F §4). Applied immediately, not on save. */
    fun onAlarmEnabledChanged(enabled: Boolean)
    fun onToneEnabledChanged(enabled: Boolean)
    fun onVibrationEnabledChanged(enabled: Boolean)

    /** "Проверить сигнал" — play one step so the tone design can be judged by ear (F §11). */
    fun onPreview(level: Int)

    fun onSave()
    fun onBack()

    data class State(
        /** False until the vehicle has been read. The screen shows a spinner and no rows. */
        val loaded: Boolean = false,
        val vehicleName: String = "",
        /** One entry per [MotionAlertKind], in enum order, always. */
        val kinds: List<AlertKindDraft> = emptyList(),
        val alarmEnabled: Boolean = true,
        val toneEnabled: Boolean = true,
        val vibrationEnabled: Boolean = true,
        val saving: Boolean = false
    ) {
        /**
         * Blocked while any visible row is half-typed.
         *
         * This is what keeps "an empty level list is the only off" honest: a
         * blank threshold parses to nothing, [toRule] would drop the row, and a
         * kind the rider had just switched **on** would be persisted **off**
         * without a word. Refusing the save asks for the number instead.
         */
        val canSave: Boolean
            get() = loaded && !saving && kinds.none { it.hasInvalidThreshold }

        /** The steps "проверить сигнал" offers — the whole range the rider can configure. */
        val previewLevels: List<Int> get() = (1..AlertRule.MAX_LEVELS).toList()
    }
}

class DefaultVehicleAlertsComponent(
    componentContext: ComponentContext,
    private val vehicleId: String,
    private val vehicleRepository: VehicleRepository,
    private val bmsRepository: BmsRepository,
    private val appPrefs: AppPrefs,
    private val alarmPreview: AlarmPreview,
    private val onSaved: () -> Unit,
    private val onBackRequested: () -> Unit
) : VehicleAlertsComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(
        VehicleAlertsComponent.State(
            alarmEnabled = appPrefs.alarmEnabled.value,
            toneEnabled = appPrefs.alarmToneEnabled.value,
            vibrationEnabled = appPrefs.alarmVibrationEnabled.value
        )
    )
    override val state: StateFlow<VehicleAlertsComponent.State> = _state.asStateFlow()

    /**
     * The last sample that was actually an observation.
     *
     * Kept rather than read live because `activeMotion` emits a default
     * `ControllerData()` whenever nothing is connected, and that placeholder
     * carries `hasMotorTemp = false` / `speedSource = NONE`. `availabilityFor`
     * discards a disconnected sample by itself, so the worst a stale one costs is
     * an out-of-date row; holding the last good one keeps the screen from
     * reverting every sensor row to "no data yet" the moment the link drops
     * mid-edit — exactly what `availabilityFor`'s doc asks callers to do.
     */
    private var lastObservedMotion: ControllerData? = null

    /** The vehicle as loaded, kept only to name it in the app bar. Saving re-reads. */
    private var loadedVehicle: Vehicle? = null

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }

        scope.launch {
            val vehicle = vehicleRepository.get(vehicleId) ?: return@launch
            loadedVehicle = vehicle
            _state.update {
                it.copy(
                    loaded = true,
                    vehicleName = vehicle.name,
                    kinds = alertDraftsFor(vehicle, availabilityFor(vehicle, lastObservedMotion))
                )
            }
        }

        scope.launch {
            bmsRepository.activeMotion.collect { sample ->
                if (!sample.isConnected) return@collect
                lastObservedMotion = sample
                refreshAvailability()
            }
        }

        scope.launch { appPrefs.alarmEnabled.collect { v -> _state.update { it.copy(alarmEnabled = v) } } }
        scope.launch { appPrefs.alarmToneEnabled.collect { v -> _state.update { it.copy(toneEnabled = v) } } }
        scope.launch { appPrefs.alarmVibrationEnabled.collect { v -> _state.update { it.copy(vibrationEnabled = v) } } }
    }

    /**
     * Re-evaluate availability against the newest observation **without touching
     * the rider's rows**. Connecting mid-edit must answer "is the sensor there?",
     * not throw away half-typed thresholds.
     */
    private fun refreshAvailability() {
        val vehicle = loadedVehicle ?: return
        val availability = availabilityFor(vehicle, lastObservedMotion)
        _state.update { s ->
            s.copy(
                kinds = s.kinds.map { draft ->
                    draft.copy(availability = availability[draft.kind] ?: draft.availability)
                }
            )
        }
    }

    private fun editKind(kind: MotionAlertKind, edit: (AlertKindDraft) -> AlertKindDraft) {
        _state.update { s ->
            s.copy(kinds = s.kinds.map { if (it.kind == kind) edit(it) else it })
        }
    }

    override fun onKindEnabledChanged(kind: MotionAlertKind, enabled: Boolean) =
        editKind(kind) { it.withEnabled(enabled) }

    override fun onThresholdChanged(kind: MotionAlertKind, index: Int, text: String) =
        editKind(kind) { it.withThreshold(index, text) }

    override fun onLevelEnabledChanged(kind: MotionAlertKind, index: Int, enabled: Boolean) =
        editKind(kind) { it.withLevelEnabled(index, enabled) }

    override fun onAddLevel(kind: MotionAlertKind) = editKind(kind) { it.withLevelAdded() }

    override fun onRemoveLevel(kind: MotionAlertKind, index: Int) =
        editKind(kind) { it.withLevelRemoved(index) }

    override fun onAlarmEnabledChanged(enabled: Boolean) {
        scope.launch { appPrefs.setAlarmEnabled(enabled) }
    }

    override fun onToneEnabledChanged(enabled: Boolean) {
        scope.launch { appPrefs.setAlarmToneEnabled(enabled) }
    }

    override fun onVibrationEnabledChanged(enabled: Boolean) {
        scope.launch { appPrefs.setAlarmVibrationEnabled(enabled) }
    }

    override fun onPreview(level: Int) = alarmPreview.preview(level)

    /**
     * Persist the rider's rules onto the vehicle **as it is right now**.
     *
     * Re-read rather than `loadedVehicle.copy(...)`: this screen is pushed on top
     * of the vehicle edit form, which can have saved a rename in between, and
     * writing a snapshot taken at open time would silently roll that back. The
     * one field this screen owns is the only one it sets.
     */
    override fun onSave() {
        val s = _state.value
        if (!s.canSave) return
        scope.launch {
            _state.update { it.copy(saving = true) }
            val current = vehicleRepository.get(vehicleId)
            if (current == null) {
                _state.update { it.copy(saving = false) }
                onBackRequested()
                return@launch
            }
            vehicleRepository.upsert(current.copy(motionAlerts = commitRules(s.kinds)))
            onSaved()
        }
    }

    override fun onBack() = onBackRequested()
}
