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
import ru.sodovaya.volty.domain.alert.AlarmCommand
import ru.sodovaya.volty.domain.alert.AlarmModalities
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.alarmPreviewCommand
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

    /**
     * The nav slot. **Not** an unconditional exit: with unsaved threshold edits it
     * raises [State.discardPrompt] instead of dropping them silently.
     */
    fun onBack()

    /** The rider chose to leave anyway. Their threshold edits are gone. */
    fun onDiscardConfirmed()

    /** The rider chose to stay. Nothing changes. */
    fun onDiscardDismissed()

    data class State(
        /** False until the vehicle has been read. The screen shows a spinner and no rows. */
        val loaded: Boolean = false,
        val vehicleName: String = "",
        /** One entry per [MotionAlertKind], in enum order, always. */
        val kinds: List<AlertKindDraft> = emptyList(),
        /**
         * The rows **as last persisted** — what [isDirty] compares against.
         *
         * Set when the vehicle is read and again after a successful save, so the
         * two commit models on this screen (see [isDirty]) can each say honestly
         * whether they have anything outstanding.
         */
        val savedLevels: Map<MotionAlertKind, List<AlertLevelDraft>> = emptyMap(),
        val alarmEnabled: Boolean = true,
        val toneEnabled: Boolean = true,
        val vibrationEnabled: Boolean = true,
        val saving: Boolean = false,
        /** True while the "leave without saving?" dialog is up. */
        val discardPrompt: Boolean = false
    ) {
        /**
         * Has the rider changed a threshold since the last write?
         *
         * **This screen has two commit models and this covers exactly one of
         * them.** The three sound switches at the top are written to prefs the
         * instant they move (a rider reaching for "tone off" mid-alarm means
         * now), so they are never outstanding and never make this true. The
         * per-vehicle thresholds below are written on Save, and those are what
         * can be lost by backing out — which is what this exists to prevent.
         * The captions on both sections say which is which.
         *
         * Availability is deliberately not part of the comparison: a link coming
         * up mid-edit re-derives it ([refreshAvailability]) and would otherwise
         * mark an untouched screen dirty. Neither is [AlertKindDraft.stashed] —
         * it is an undo buffer that is never persisted.
         */
        val isDirty: Boolean get() = loaded && editedLevels(kinds) != savedLevels

        /**
         * Blocked while any visible row is half-typed, and **blocked when nothing
         * has been edited**.
         *
         * The half-typed half is what keeps "an empty level list is the only off"
         * honest: a blank threshold parses to nothing, [toRule] would drop the
         * row, and a kind the rider had just switched **on** would be persisted
         * **off** without a word. Refusing the save asks for the number instead.
         *
         * The dirty half closes the other one: a vehicle whose `motionAlerts` is
         * null reads the shipped [ru.sodovaya.volty.domain.alert.AlarmDefaults],
         * so an idle tap on Save would *materialise* those defaults onto it —
         * null becomes non-null — and later changes to the shipped numbers would
         * stop reaching that vehicle for ever, silently. Opening a screen and
         * touching nothing must not pin anything.
         */
        val canSave: Boolean
            get() = loaded && !saving && isDirty && kinds.none { it.hasInvalidThreshold }

        /** The steps "проверить сигнал" offers — the whole range the rider can configure. */
        val previewLevels: List<Int> get() = (1..AlertRule.MAX_LEVELS).toList()

        /** The three switches as the one value the alarm's gate takes. */
        val modalities: AlarmModalities
            get() = AlarmModalities(alarmEnabled, toneEnabled, vibrationEnabled)

        /**
         * Can pressing a preview button produce anything at all?
         *
         * With the master switch on but **both** tone and vibration off,
         * [alarmPreviewCommand] returns [AlarmCommand.Silent] and the platform
         * plans nothing — so a rider who pressed "Уровень 1" would feel and hear
         * nothing and conclude the alarm is broken. `AlarmSignal.kt` says the
         * button is to be greyed in that case rather than gated a second time
         * here; this is that greying, asked of the same function the live alarm
         * goes through, so the two can never disagree.
         *
         * Asked at level 1 because the modality gate is level-independent: it
         * runs before any tone is looked up, so every step answers alike.
         */
        val canPreview: Boolean
            get() = alarmPreviewCommand(1, modalities) is AlarmCommand.Play
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
            val drafts = alertDraftsFor(vehicle, availabilityFor(vehicle, lastObservedMotion))
            _state.update {
                it.copy(
                    loaded = true,
                    vehicleName = vehicle.name,
                    kinds = drafts,
                    // The baseline both dirty checks read. Taken from the drafts
                    // rather than from the vehicle so that a never-configured
                    // vehicle — which opens on the shipped defaults — starts out
                    // *clean*: those defaults are what it already behaves as, so
                    // showing them is not an edit and must not arm Save.
                    savedLevels = editedLevels(drafts)
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
            // The rows are now what is stored, so the screen is clean again: if
            // navigation is deferred or refused, backing out afterwards must not
            // ask about edits that have already been written.
            _state.update { it.copy(saving = false, savedLevels = editedLevels(s.kinds)) }
            onSaved()
        }
    }

    /**
     * Backing out with unsaved threshold edits **asks** instead of discarding.
     *
     * The nav slot is a `Cancel` button and the thresholds only exist in memory
     * until Save, so without this a rider who edits a number and taps Cancel — or
     * whose muscle memory takes them back — loses it without a word. The two
     * commit models make that trap worse, not better: the switches above the
     * thresholds *did* save instantly, so the screen appears to save as you go.
     *
     * The prompt is raised in the component rather than remembered in the
     * `@Composable`, because whether there is anything to lose is a decision and
     * decisions are what this class is for.
     */
    override fun onBack() {
        if (_state.value.isDirty) {
            _state.update { it.copy(discardPrompt = true) }
            return
        }
        onBackRequested()
    }

    override fun onDiscardConfirmed() {
        _state.update { it.copy(discardPrompt = false) }
        onBackRequested()
    }

    override fun onDiscardDismissed() {
        _state.update { it.copy(discardPrompt = false) }
    }
}
