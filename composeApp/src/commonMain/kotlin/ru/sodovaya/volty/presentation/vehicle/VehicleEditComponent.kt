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
import ru.sodovaya.volty.domain.model.cellCountOrNull
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
     * Navigation only — **no state on this form**, deliberately. `onSave()`
     * rebuilds the vehicle from scratch through `singlePackVehicle(...)` and
     * hand-copies the rest across, a pattern that has already eaten three fields;
     * the alert rules are edited and persisted by their own component, which
     * re-reads the vehicle at save time, so nothing here can drop them. The
     * `motionAlerts = existing?.motionAlerts` carry-through in [onSave] is what
     * keeps a save from this form from resurrecting the defaults over them.
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
         * [singlePackVehicle] builds in [onSave], and a controller's address
         * must never reach it. "" (rendered as an em-dash) when neither exists.
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
                    // throwing on init. onSave() will not invent a pack for it
                    // — see the packs preservation there. These two stay the
                    // PACK's; what the read-only header shows is sourceVehicle
                    // / sourceAddress below, which do describe a controller.
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
            // Preserve everything the edit form doesn't expose (cutoff / delta /
            // notify toggles, pin, last-connected) — rebuilding from defaults
            // would silently wipe them on every save.
            val existing = if (s.isEditing) vehicleRepository.get(vehicleId!!) else null
            val built = singlePackVehicle(
                id = vehicleId ?: "v-${Random.nextLong()}",
                name = s.name,
                iconKey = s.iconKey,
                bmsType = s.bmsType,
                bmsAddress = s.bmsAddress,
                chemistry = s.chemistry,
                // Auto-filled from live telemetry by the repo (see
                // KableBmsRepository.maybePersistCellCount) — never edited here.
                cellCount = existing?.cellCountOrNull,
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
            // singlePackVehicle() only knows the single-pack shape — it can't
            // forward controllers/topology (not editable from this screen yet)
            // or dashboardStyle/secondaryGauge (edited here, via the state).
            // Without this .copy(), every save through this screen silently
            // wiped a vehicle's VESC controllers and reset its dashboard prefs.
            val v = built.copy(
                // singlePackVehicle() ALWAYS synthesizes one pack. For a
                // controller-only vehicle (zero packs) that pack would be built
                // from this form's placeholder defaults — a phantom JK_BMS at
                // address "" — so a save from this screen would silently invent
                // a battery the vehicle doesn't have. Keep it pack-less; the
                // controllers copied below satisfy Vehicle's "needs a source".
                packs = if (existing != null && existing.packs.isEmpty()) emptyList() else built.packs,
                // Preserve every existing controller field (address, type,
                // canId, providesDerivedBattery...) EXCEPT motor: this screen
                // is the only place that edits MotorConfig, and it only ever
                // edits controllers[0] (G1: exactly one controller per
                // vehicle). Blank fields fall back to MotorConfig()'s own
                // defaults rather than persisting a hole.
                controllers = (existing?.controllers ?: emptyList()).mapIndexed { i, c ->
                    if (i == 0) {
                        c.copy(
                            motor = MotorConfig(
                                polePairs = s.motorPolePairs ?: MotorConfig().polePairs,
                                wheelDiameterMm = s.motorWheelDiameterMm ?: MotorConfig().wheelDiameterMm,
                                gearRatio = s.motorGearRatio ?: MotorConfig().gearRatio
                            )
                        )
                    } else c
                },
                topology = existing?.topology ?: built.topology,
                dashboardStyle = s.dashboardStyle,
                secondaryGauge = s.secondaryGauge,
                // Not editable here (this screen is single-pack / single-controller
                // and cannot express the two-path alias group the toggle is about
                // — see the Part C task-5 report). Carried through explicitly so a
                // save from this form does not silently reset a rider's opt-out,
                // exactly as topology and the controller list are above.
                yieldBmsToHeadUnit = existing?.yieldBmsToHeadUnit,
                // Also not editable here (F Task 5's own screen owns it), and
                // carried through for a sharper reason than the fields above.
                // singlePackVehicle() leaves motionAlerts null, and null does
                // not mean "no alerts" — it means "never configured", which the
                // repository answers with AlarmDefaults. So dropping it here
                // does not merely lose the rider's numbers: it RESURRECTS the
                // defaults over them. A rider who silenced every kind and then
                // renamed the vehicle would have the alarm switch itself back
                // on. See SqlDelightVehicleRepository / Vehicle.motionAlerts.
                motionAlerts = existing?.motionAlerts
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
