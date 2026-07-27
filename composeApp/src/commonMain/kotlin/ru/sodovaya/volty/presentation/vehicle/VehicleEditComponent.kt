package ru.sodovaya.volty.presentation.vehicle

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.bmsAddressOrNull
import ru.sodovaya.volty.domain.model.bmsTypeOrNull
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.model.singlePackVehicle
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
    fun onMotorPolePairsChanged(v: Int?)
    fun onMotorWheelDiameterChanged(v: Int?)
    fun onMotorGearRatioChanged(v: Float?)
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
        /**
         * Whether the Motor section should render at all — true only when the
         * loaded vehicle has a controller. A pack-only vehicle must not see an
         * empty motor card, so the screen omits the section entirely rather
         * than disabling it. Always false while CREATING: this screen never
         * originates a controller (Picker does), so a new vehicle has none yet.
         */
        val hasController: Boolean = false,
        /**
         * [ru.sodovaya.volty.domain.model.MotorConfig] fields for
         * `controllers[0]` — G1 supports exactly one controller per vehicle, so
         * there is no list editor here (Part G2/C). Nullable to reuse the same
         * IntField/FloatField empty-input handling as the alert-threshold
         * fields below; a blank field falls back to `MotorConfig()`'s default
         * for that field at save time rather than persisting a hole.
         */
        val motorPolePairs: Int? = null,
        val motorWheelDiameterMm: Int? = null,
        val motorGearRatio: Float? = null,
        /** Null = follow the app-level default. */
        val dashboardStyle: DashboardStyle? = null,
        val secondaryGauge: SecondaryGauge = SecondaryGauge.DUTY,
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
    /** Defaulted so every existing caller (and test) compiles unchanged. */
    private val onOpenAlertsRequested: () -> Unit = {},
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
                    // G1: exactly one controller per vehicle, so controllers[0]
                    // is THE controller — no index to pick.
                    hasController = v.controllers.isNotEmpty(),
                    motorPolePairs = v.controllers.firstOrNull()?.motor?.polePairs,
                    motorWheelDiameterMm = v.controllers.firstOrNull()?.motor?.wheelDiameterMm,
                    motorGearRatio = v.controllers.firstOrNull()?.motor?.gearRatio,
                    dashboardStyle = v.dashboardStyle,
                    secondaryGauge = v.secondaryGauge
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
    override fun onMotorPolePairsChanged(v: Int?) { _state.update { it.copy(motorPolePairs = v) } }
    override fun onMotorWheelDiameterChanged(v: Int?) { _state.update { it.copy(motorWheelDiameterMm = v) } }
    override fun onMotorGearRatioChanged(v: Float?) { _state.update { it.copy(motorGearRatio = v) } }
    override fun onDashboardStyleChanged(style: DashboardStyle?) { _state.update { it.copy(dashboardStyle = style) } }
    override fun onSecondaryGaugeChanged(gauge: SecondaryGauge) { _state.update { it.copy(secondaryGauge = gauge) } }
    override fun onOpenAlerts() { onOpenAlertsRequested() }

    override fun onSave() {
        val s = _state.value
        if (s.name.isBlank()) { _state.update { it.copy(nameError = true) }; return }
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
 * Everything not listed follows from that: `packs` beyond index 0 (a Begode
 * wheel's auto-filled second branch — §8.1), `cellCount`, `canId`,
 * `aliasGroup`, `topology`, `createdAt`, `lastConnectedAt`, `isPinned`,
 * `yieldBmsToHeadUnit`, `motionAlerts`.
 */
private fun Vehicle.withEdits(s: VehicleEditComponent.State): Vehicle = copy(
    name = s.name,
    iconKey = s.iconKey,
    chemistry = s.chemistry,
    averagingWindowMin = s.averagingWindowMin,
    alertConfig = alertConfig.withEdits(s),
    // The name field has always renamed the primary pack too — `singlePackVehicle`
    // labels pack 0 after the vehicle, and for a single-pack vehicle that label is
    // what the pack card shows (`packLabelFor`). Index 0 ONLY: a wheel's second
    // branch keeps the positional label `expandedTo` gave it, and a pack-less
    // vehicle maps an empty list to an empty list — no phantom battery, and no
    // `packs.isEmpty()` special case to forget.
    packs = packs.mapIndexed { i, p -> if (i == 0) p.copy(label = s.name) else p },
    // Motor config for controllers[0] — G1 has exactly one controller per
    // vehicle, and this screen is the only place that edits a MotorConfig. A
    // blank field falls back to MotorConfig()'s own default rather than
    // persisting a hole. Every other controller field is untouched.
    controllers = controllers.mapIndexed { i, c ->
        if (i == 0) c.copy(motor = s.motorConfig()) else c
    },
    dashboardStyle = s.dashboardStyle,
    secondaryGauge = s.secondaryGauge
)

/** The five thresholds this form exposes; the rest of [AlertConfig] is not its business. */
private fun AlertConfig.withEdits(s: VehicleEditComponent.State): AlertConfig = copy(
    cellHighV = s.cellHighV,
    cellLowV = s.cellLowV,
    temperatureWarnC = s.temperatureWarnC,
    temperatureHighC = s.temperatureHighC,
    socLowPercent = s.socLowPercent
)

private fun VehicleEditComponent.State.motorConfig(): MotorConfig = MotorConfig(
    polePairs = motorPolePairs ?: MotorConfig().polePairs,
    wheelDiameterMm = motorWheelDiameterMm ?: MotorConfig().wheelDiameterMm,
    gearRatio = motorGearRatio ?: MotorConfig().gearRatio
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
