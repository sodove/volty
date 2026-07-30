package ru.sodovaya.volty.presentation.ride

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.GaugeScale
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.domain.stats.PeakTracker
import ru.sodovaya.volty.util.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface RideDashboardComponent {
    val state: StateFlow<State>
    fun onPillClicked()
    fun onSheetDismiss()
    fun onSwitchVehicle(v: Vehicle)
    fun onAddVehicle()
    /** Graph is no longer a top-level tab — every dashboard carries a button to it. */
    fun onOpenGraph()
    fun onOpenSettings()
    fun onDisconnect()

    @OptIn(ExperimentalTime::class)
    data class State(
        val vehicle: Vehicle? = null,
        val motion: ControllerData = ControllerData(),
        val battery: BmsData = BmsData(),
        /** true when some controller is offline and [motion] covers only the rest. */
        val motionPartial: Boolean = false,
        val connection: ConnectionState = ConnectionState.Idle,
        val units: UnitSystem = UnitSystem.METRIC,
        val style: DashboardStyle = DashboardStyle.CLEAN,
        val secondary: SecondaryGauge = SecondaryGauge.DUTY,
        val secondaryReadout: SecondaryReadout = SecondaryGaugeMapper.map(
            SecondaryGauge.DUTY, ControllerData(), BmsData(), UnitSystem.METRIC
        ),
        /**
         * The CURRENT dial's `±max` in amps — `G §9.2`'s learned rung for THIS vehicle, already
         * combined with the live sample ([GaugeScale.currentDisplayRungA]).
         *
         * On the state rather than recomputed by each renderer because the tracker behind it is
         * per-vehicle and persisted, so there is exactly one right answer per frame and both
         * renderers must draw it. It defaults to the narrowest rung, not to VESC's old ±60 A floor:
         * that floor is the defect (`§9.2`).
         */
        val currentRangeA: Float = GaugeScale.CURRENT_RUNGS_A.first(),
        /** The POWER dial's `±max` in watts. See [currentRangeA]. */
        val powerRangeW: Float = GaugeScale.POWER_RUNGS_W.first(),
        val sessionWhPerKm: Float? = null,
        /**
         * Elapsed time since the first motion sample of the current
         * connection — no ticker involved. See [DefaultRideDashboardComponent].
         */
        val uptimeSeconds: Long = 0L,
        val savedVehicles: List<Vehicle> = emptyList(),
        val sheetOpen: Boolean = false
    )
}

@OptIn(ExperimentalTime::class)
class DefaultRideDashboardComponent(
    componentContext: ComponentContext,
    private val bmsRepository: BmsRepository,
    private val vehicleRepository: VehicleRepository,
    private val appPrefs: AppPrefs,
    private val onOpenGraphRequested: () -> Unit,
    private val onOpenSettingsRequested: () -> Unit,
    private val onAddVehicleRequested: () -> Unit,
    private val onDisconnectRequested: () -> Unit
) : RideDashboardComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Latest app-level default, kept alongside [RideDashboardComponent.State.style] so either
     * the vehicle collector or the app-prefs collector can recompute the winning style without
     * waiting on the other. */
    private var appDefaultStyle: DashboardStyle = appPrefs.defaultDashboardStyle.value

    /**
     * Instant of the first motion sample seen since the vehicle was last
     * [ConnectionState.Connected]. Null before any sample has arrived for this
     * connection. Reset whenever [ConnectionState] leaves Connected, so a
     * reconnect starts the uptime clock over.
     */
    private var sessionStartedAt: Instant? =
        if (bmsRepository.connectionState.value is ConnectionState.Connected) {
            bmsRepository.activeMotion.value.timestamp
        } else {
            null
        }

    // -----------------------------------------------------------------------------------------
    // G §9.2 — the learned widths of the CURRENT and POWER dials
    //
    // HERE, in the component, and not in a `LaunchedEffect` on RideDashboardScreen: Task 6's ledger
    // records that the screen's existing session trackers are unverifiable where they sit, and this
    // one carries a spike guard and a database write, both of which have to be provable.
    //
    // Plain `var`s rather than State fields because they are not what the screen draws — the RANGE
    // is (State.currentRangeA), and it is a quantised function of these. A tracker on the state
    // would recompose the whole dashboard whenever a peak moved *within* a rung, which is most
    // samples of most rides.
    // -----------------------------------------------------------------------------------------

    /** Which vehicle [currentPeak] / [powerPeak] describe. See [seedPeaksFrom]. */
    private var peakVehicleId: String? = null
    private var currentPeak = PeakTracker()
    private var powerPeak = PeakTracker()

    /**
     * The rungs the STORED peaks resolve to — what the database already says, not what the trackers
     * currently hold.
     *
     * The write condition is a change in these (`§9.2` item 4: "written back only when the rung
     * changes"), which is what turns a per-notification stream into a handful of writes over a
     * vehicle's life. It doubles as the reseed condition — see [seedPeaksFrom].
     */
    private var persistedCurrentRungA = GaugeScale.CURRENT_RUNGS_A.first()
    private var persistedPowerRungW = GaugeScale.POWER_RUNGS_W.first()

    /**
     * Adopt [v]'s stored peaks, when they are not already the ones in hand.
     *
     * Two events reach this, and the condition covers both without a second change-detector:
     *
     *  - **a different vehicle.** Its peaks describe its own hardware;
     *  - **the same vehicle whose stored peaks now resolve to a different rung.** That is the
     *    composer having cleared them (`§9.2` item 7) while this component sat in the back stack.
     *    It cannot be triggered by our OWN write: we only write when the rung changes, and we set
     *    [persistedCurrentRungA] to the rung we wrote, so the re-emission that follows agrees.
     *
     * Comparing RUNGS rather than raw peaks is what makes the second case safe. Raw peaks disagree
     * constantly — ours grows within a rung while the stored one stays put — so a raw comparison
     * would reseed on almost every sample and discard everything just learned.
     */
    private fun seedPeaksFrom(v: Vehicle?) {
        val storedCurrent = v?.gaugePeakCurrentA ?: 0f
        val storedPower = v?.gaugePeakPowerW ?: 0f
        val storedCurrentRung = GaugeScale.rungFor(storedCurrent, GaugeScale.CURRENT_RUNGS_A)
        val storedPowerRung = GaugeScale.rungFor(storedPower, GaugeScale.POWER_RUNGS_W)
        if (v?.id == peakVehicleId &&
            storedCurrentRung == persistedCurrentRungA &&
            storedPowerRung == persistedPowerRungW
        ) {
            return
        }
        peakVehicleId = v?.id
        currentPeak = PeakTracker.seededAt(storedCurrent)
        powerPeak = PeakTracker.seededAt(storedPower)
        persistedCurrentRungA = storedCurrentRung
        persistedPowerRungW = storedPowerRung
    }

    /**
     * Fold one motion sample into both trackers and answer the two rungs the dials draw.
     *
     * Power goes through [MotionReadings] and current does not, and that asymmetry is Task 6's
     * contract rather than an oversight: a `powerW` whose `hasPower` is false is a placeholder — let
     * it in and every Begode teaches its dial that peak power is 0 W, while the readout above it
     * correctly dashes. Battery current has no such flag, deliberately, because no producer could
     * ever set one to false (see [MotionReadings]' own note).
     *
     * The returned rungs use [GaugeScale.displayRung], so they answer for the LIVE sample even
     * before the median has confirmed it. Nothing is persisted from that number.
     */
    private fun foldPeaks(motion: ControllerData): Pair<Float, Float> {
        currentPeak = currentPeak.accept(motion.batteryCurrentA)
        MotionReadings.powerW(motion)?.let { powerPeak = powerPeak.accept(it) }
        return displayRungs(motion)
    }

    /** The two rungs for [motion] against whatever the trackers hold now — folds nothing. */
    private fun displayRungs(motion: ControllerData): Pair<Float, Float> =
        GaugeScale.currentDisplayRungA(currentPeak.learnedPeak, motion.batteryCurrentA) to
            GaugeScale.powerDisplayRungW(powerPeak.learnedPeak, MotionReadings.powerW(motion) ?: 0f)

    /**
     * Write the learned peaks back if — and only if — the rung one of them resolves to has moved.
     *
     * **The MEDIAN-FILTERED peak is what gets stored, never the live excursion** ([foldPeaks]'s
     * return value): the display may widen for one frame on a single raw sample, and persisting that
     * would make one corrupt frame permanent, which is the entire reason [PeakTracker] exists.
     *
     * Guarded on the vehicle being real and persisted. A guest has no row to update and a demo must
     * never touch the saved-vehicle store — the same refusal every other writer in the app makes.
     */
    private fun persistPeaksIfRungChanged(v: Vehicle?) {
        if (v == null || v.isGuest || v.isDemo) return
        val currentRung = GaugeScale.rungFor(currentPeak.learnedPeak, GaugeScale.CURRENT_RUNGS_A)
        val powerRung = GaugeScale.rungFor(powerPeak.learnedPeak, GaugeScale.POWER_RUNGS_W)
        if (currentRung == persistedCurrentRungA && powerRung == persistedPowerRungW) return
        persistedCurrentRungA = currentRung
        persistedPowerRungW = powerRung
        val currentA = currentPeak.learnedPeak
        val powerW = powerPeak.learnedPeak
        scope.launch { vehicleRepository.updateGaugePeaks(v.id, currentA, powerW) }
    }

    private val _state: MutableStateFlow<RideDashboardComponent.State> = run {
        val initialVehicle = bmsRepository.activeVehicle.value
        seedPeaksFrom(initialVehicle)
        val initialMotion = bmsRepository.activeMotion.value
        val initialVehicleData = bmsRepository.activeVehicleData.value
        val initialUnits = appPrefs.unitSystem.value
        val initialSecondary = initialVehicle?.secondaryGauge ?: SecondaryGauge.DUTY
        val initialStyle = initialVehicle?.dashboardStyle ?: appDefaultStyle
        // The stored peaks resolved against whatever sample was already in hand — NOT folded in.
        // A snapshot the repository happens to be holding from before this screen existed has
        // already been through the tracker on some earlier component's watch, and folding it again
        // would let one retained sample count twice towards a median.
        val (initialCurrentRange, initialPowerRange) = displayRungs(initialMotion)
        MutableStateFlow(
            RideDashboardComponent.State(
                vehicle = initialVehicle,
                motion = initialMotion,
                battery = initialVehicleData.aggregate,
                motionPartial = initialVehicleData.motionPartial,
                connection = bmsRepository.connectionState.value,
                units = initialUnits,
                style = initialStyle,
                secondary = initialSecondary,
                secondaryReadout = SecondaryGaugeMapper.map(
                    initialSecondary, initialMotion, initialVehicleData.aggregate, initialUnits,
                    currentRangeA = initialCurrentRange, powerRangeW = initialPowerRange
                ),
                currentRangeA = initialCurrentRange,
                powerRangeW = initialPowerRange,
                sessionWhPerKm = MotionReadings.sessionWhPerKm(initialMotion),
                uptimeSeconds = sessionStartedAt?.let { (initialMotion.timestamp - it).inWholeSeconds.coerceAtLeast(0) } ?: 0L
            )
        )
    }
    override val state: StateFlow<RideDashboardComponent.State> = _state.asStateFlow()

    init {
        lifecycle.doOnDestroy { scope.coroutineContext[Job]?.cancel() }

        scope.launch {
            bmsRepository.activeMotion.collect { motion ->
                if (sessionStartedAt == null) sessionStartedAt = motion.timestamp
                val uptime = (motion.timestamp - sessionStartedAt!!).inWholeSeconds.coerceAtLeast(0)
                // Folded once per sample, OUTSIDE `_state.update` — that block can be re-run when
                // another collector wins the compare-and-set, and a tracker folded inside it would
                // count the same sample twice towards a median (`§9.2` item 2).
                val (currentRange, powerRange) = foldPeaks(motion)
                persistPeaksIfRungChanged(_state.value.vehicle)
                _state.update { current ->
                    current.copy(
                        motion = motion,
                        uptimeSeconds = uptime,
                        sessionWhPerKm = MotionReadings.sessionWhPerKm(motion),
                        currentRangeA = currentRange,
                        powerRangeW = powerRange,
                        secondaryReadout = SecondaryGaugeMapper.map(
                            current.secondary, motion, current.battery, current.units,
                            currentRangeA = currentRange, powerRangeW = powerRange
                        )
                    )
                }
            }
        }

        scope.launch {
            bmsRepository.activeVehicleData.collect { vd ->
                _state.update { current ->
                    current.copy(
                        battery = vd.aggregate,
                        motionPartial = vd.motionPartial,
                        secondaryReadout = SecondaryGaugeMapper.map(
                            current.secondary, current.motion, vd.aggregate, current.units,
                            currentRangeA = current.currentRangeA, powerRangeW = current.powerRangeW
                        )
                    )
                }
            }
        }

        scope.launch {
            bmsRepository.activeVehicle.collect { vehicle ->
                // The vehicle IS the peaks' owner, so this is where they are adopted — and it is
                // the same emission the composer's own source-set change arrives on, which is why
                // `§9.2` item 7 needs no second change-detector here (see [seedPeaksFrom]).
                seedPeaksFrom(vehicle)
                val (currentRange, powerRange) = displayRungs(_state.value.motion)
                _state.update { current ->
                    val secondary = vehicle?.secondaryGauge ?: SecondaryGauge.DUTY
                    current.copy(
                        vehicle = vehicle,
                        style = vehicle?.dashboardStyle ?: appDefaultStyle,
                        secondary = secondary,
                        currentRangeA = currentRange,
                        powerRangeW = powerRange,
                        secondaryReadout = SecondaryGaugeMapper.map(
                            secondary, current.motion, current.battery, current.units,
                            currentRangeA = currentRange, powerRangeW = powerRange
                        )
                    )
                }
            }
        }

        scope.launch {
            bmsRepository.connectionState.collect { c ->
                if (c !is ConnectionState.Connected) sessionStartedAt = null
                _state.update { it.copy(connection = c) }
            }
        }

        scope.launch {
            vehicleRepository.vehicles.collect { list ->
                _state.update { it.copy(savedVehicles = list) }
            }
        }

        scope.launch {
            appPrefs.unitSystem.collect { units ->
                _state.update { current ->
                    current.copy(
                        units = units,
                        secondaryReadout = SecondaryGaugeMapper.map(
                            current.secondary, current.motion, current.battery, units,
                            currentRangeA = current.currentRangeA, powerRangeW = current.powerRangeW
                        )
                    )
                }
            }
        }

        scope.launch {
            appPrefs.defaultDashboardStyle.collect { appDefault ->
                appDefaultStyle = appDefault
                _state.update { current -> current.copy(style = current.vehicle?.dashboardStyle ?: appDefault) }
            }
        }
    }

    override fun onPillClicked() { _state.update { it.copy(sheetOpen = !it.sheetOpen) } }
    override fun onSheetDismiss() { _state.update { it.copy(sheetOpen = false) } }

    override fun onSwitchVehicle(v: Vehicle) {
        scope.launch {
            _state.update { it.copy(sheetOpen = false) }
            bmsRepository.disconnect()
            bmsRepository.connect(v)
        }
    }

    override fun onAddVehicle() {
        // Mirrors DashboardComponent.onAddBattery — a real navigation hook the
        // sheet's "+ Add battery" affordance can invoke, rather than a
        // relabeled Settings shortcut. Clears the sheet first: it's about to
        // navigate away, same as onSwitchVehicle does before it (dis)connects.
        _state.update { it.copy(sheetOpen = false) }
        onAddVehicleRequested()
    }

    override fun onOpenGraph() {
        // Same reasoning as onOpenSettings — we're about to navigate away.
        _state.update { it.copy(sheetOpen = false) }
        onOpenGraphRequested()
    }

    override fun onOpenSettings() {
        // Returning from Settings must not find the vehicle sheet still open.
        _state.update { it.copy(sheetOpen = false) }
        onOpenSettingsRequested()
    }

    override fun onDisconnect() {
        scope.launch {
            bmsRepository.disconnect()
            onDisconnectRequested()
        }
    }
}
