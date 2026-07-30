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
import kotlinx.coroutines.CancellationException
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

    /** Which vehicle [currentPeak] / [powerPeak] describe. See [adoptStoredPeaks]. */
    private var peakVehicleId: String? = null
    private var currentPeak = PeakTracker()
    private var powerPeak = PeakTracker()

    /**
     * The rungs the STORED peaks resolve to — what the database already says, not what the trackers
     * currently hold.
     *
     * The write condition is a change in these (`§9.2` item 4: "written back only when the rung
     * changes"), which is what turns a per-notification stream into a handful of writes over a
     * vehicle's life. It doubles as the reseed condition — see [adoptStoredPeaks].
     */
    private var persistedCurrentRungA = GaugeScale.CURRENT_RUNGS_A.first()
    private var persistedPowerRungW = GaugeScale.POWER_RUNGS_W.first()

    /**
     * The widest rung each dial has DISPLAYED since the last adoption.
     *
     * Separate from the persisted rungs because they answer different questions: these are what the
     * rider is currently looking at, including a live excursion the median has not confirmed, and
     * they are the floor that makes the displayed width monotone — see [GaugeScale.displayRung].
     * Without the floor a noisy vehicle would step between rungs, and on the POWER dial between tick
     * units, frame to frame — the animation the whole quantisation exists to prevent.
     */
    private var displayedCurrentRungA = GaugeScale.CURRENT_RUNGS_A.first()
    private var displayedPowerRungW = GaugeScale.POWER_RUNGS_W.first()

    /**
     * True while an [updateGaugePeaks] call is outstanding.
     *
     * The booking of [persistedCurrentRungA] happens only once the write has actually returned (see
     * [persistPeaksIfRungChanged]), so without this a burst of samples during a fast climb would
     * each see the unbooked rung and fan out into duplicate writes.
     */
    private var peakWriteInFlight = false

    /**
     * The rung pair the last write was ATTEMPTED for, successful or not — the throttle.
     *
     * Distinct from [persistedCurrentRungA], which is what the database is known to hold. Booking
     * only on success means a failing write leaves the rung unbooked and therefore eligible again on
     * the very next sample, which at 5-10 Hz is a database call and a log line ten times a second
     * for the rest of the ride. **A learned rung is worth almost nothing and must never cost a
     * ride's battery or fill its log.** So: at most one attempt per rung value.
     *
     * Chosen over an exponential back-off because there is nothing to be gained by trying the same
     * rung again — the *decision* is quantised, so a retry would write a different float but land on
     * the same rung, which is the only thing anything downstream reads — and because
     * a back-off needs a clock, and a clock in here is the delayed-loop shape that wedges `runTest`
     * instead of failing it. Nothing is lost either: the rung only ever grows, so the NEXT crossing
     * writes a value that subsumes the one that failed, and a rung lost for a whole ride is
     * re-learned from the stored seed on the next one.
     */
    private var attemptedCurrentRungA: Float? = null
    private var attemptedPowerRungW: Float? = null

    /**
     * The saved-vehicle rows as [VehicleRepository.vehicles] last published them.
     *
     * Held so that [adoptStoredPeaks] can be handed a row from the database on a vehicle CHANGE
     * without waiting for the flow to happen to re-emit. It used to be handed `activeVehicle`'s
     * snapshot there, which contradicted its own documented rule and was safe only because
     * `touch()` makes the table change on nearly every connect — a coincidence nothing pinned.
     */
    private var storedVehicles: List<Vehicle> = emptyList()

    /**
     * The connect-time seed, and **the one place a [BmsRepository.activeVehicle] snapshot may supply
     * a stored peak.**
     *
     * Sound exactly here and nowhere else: this runs in the `_state` initializer, before any
     * collector and therefore before this component has written anything, so the snapshot the
     * connect just read cannot be behind the database. It exists as its own function so that
     * [adoptStoredPeaks]' rule has no exceptions to document — see there.
     */
    private fun seedPeaksAtConnect(v: Vehicle?) = adoptPeaks(id = v?.id, stored = v)

    /**
     * Adopt [v]'s **stored** peaks, when they are not already the ones in hand.
     *
     * Two events reach this, and the condition covers both without a second change-detector:
     *
     *  - **a different vehicle.** Its peaks describe its own hardware;
     *  - **the same vehicle whose stored peaks now resolve to a different rung.** That is the
     *    composer having cleared them (`§9.2` item 7) while this component sat in the back stack.
     *    It cannot be triggered by our OWN write: we only write when the rung changes, and we book
     *    [persistedCurrentRungA] to the rung we wrote, so the re-emission that follows agrees.
     *
     * Comparing RUNGS rather than raw peaks is what makes the second case safe. Raw peaks disagree
     * constantly — ours grows within a rung while the stored one stays put — so a raw comparison
     * would reseed on almost every sample and discard everything just learned.
     *
     * **[v] must come from [VehicleRepository.vehicles], and every call site takes it from
     * [storedVehicles] — never from [BmsRepository.activeVehicle].**
     *
     * `activeVehicle` is a snapshot. It is *usually* fresh, because `KableBmsRepository` re-publishes
     * it from this same repository flow — but there is a race window: its cell-count and pack
     * auto-fills read and write inside the interval before a peak write's own re-publication lands,
     * and a snapshot from inside that window carries the older peak. Adopting it there looks exactly
     * like a composer clear and discards the live trackers. So the rule is not "prefer the
     * repository flow", it is "a snapshot flow is never the authority on a value that changes
     * underneath it", and what holds it is both call sites reading [storedVehicles] plus the two
     * tests that pin them — not this paragraph, and not the type system: [seedPeaksAtConnect] passes
     * a snapshot by design, so the rule cannot be structurally enforced while that exception is
     * sound. Adding a call site is therefore a decision, not a detail.
     */
    private fun adoptStoredPeaks(id: String?, stored: Vehicle?) = adoptPeaks(id, stored)

    /**
     * [id] and [stored] are separate parameters because they come from different authorities: the
     * vehicle being ridden is whatever `activeVehicle` says, while its stored peaks are whatever the
     * database says — and for a guest, a demo, or a row this component has not seen yet, there IS no
     * stored row and `stored` is null while [id] is not. Collapsing them would make a guest reset its
     * own trackers on every re-emission, because [peakVehicleId] would never match.
     */
    private fun adoptPeaks(id: String?, stored: Vehicle?) {
        val storedCurrent = stored?.gaugePeakCurrentA ?: 0f
        val storedPower = stored?.gaugePeakPowerW ?: 0f
        val storedCurrentRung = GaugeScale.rungFor(storedCurrent, GaugeScale.CURRENT_RUNGS_A)
        val storedPowerRung = GaugeScale.rungFor(storedPower, GaugeScale.POWER_RUNGS_W)
        if (id == peakVehicleId &&
            storedCurrentRung == persistedCurrentRungA &&
            storedPowerRung == persistedPowerRungW
        ) {
            return
        }
        peakVehicleId = id
        currentPeak = PeakTracker.seededAt(storedCurrent)
        powerPeak = PeakTracker.seededAt(storedPower)
        persistedCurrentRungA = storedCurrentRung
        persistedPowerRungW = storedPowerRung
        // An adoption ends the display's monotone chain: a different vehicle, or a peak the composer
        // cleared, is precisely when the dial is allowed to narrow again.
        displayedCurrentRungA = storedCurrentRung
        displayedPowerRungW = storedPowerRung
        // A new vehicle, or a cleared peak, is also a new throttle budget: whatever attempt failed
        // was about hardware or a peak that is no longer this dial's.
        attemptedCurrentRungA = null
        attemptedPowerRungW = null
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

    /**
     * The two rungs for [motion] against whatever the trackers hold now — folds no sample into a
     * tracker, but does **ratchet** the displayed widths, which only ever grow between adoptions.
     */
    private fun displayRungs(motion: ControllerData): Pair<Float, Float> {
        displayedCurrentRungA = GaugeScale.currentDisplayRungA(
            displayedCurrentRungA, currentPeak.learnedPeak, motion.batteryCurrentA
        )
        displayedPowerRungW = GaugeScale.powerDisplayRungW(
            displayedPowerRungW, powerPeak.learnedPeak, MotionReadings.powerW(motion) ?: 0f
        )
        return displayedCurrentRungA to displayedPowerRungW
    }

    /**
     * Write the learned peaks back if — and only if — the rung one of them resolves to has moved.
     *
     * **The MEDIAN-FILTERED peak is what gets stored, never the live excursion** ([foldPeaks]'s
     * return value): the display may widen for one frame on a single raw sample, and persisting that
     * would make one corrupt frame permanent, which is the entire reason [PeakTracker] exists.
     *
     * Guarded on the vehicle being real and persisted. A guest has no row to update and a demo must
     * never touch the saved-vehicle store — the same refusal every other writer in the app makes.
     *
     * **The rung is booked AFTER the write returns, not before.** [scope] is cancelled by
     * `doOnDestroy`, so booking first meant a rung that changed as the screen closed was marked
     * saved and then never written — and, because the booking is also the write condition, never
     * retried. Booking after means the field says what it claims ("the database holds this rung").
     * The alternative — book optimistically and un-book on failure — has to guess whether a later
     * booking has already superseded the one it is reverting; this does not.
     *
     * **Retrying is throttled to one attempt per rung value** ([attemptedCurrentRungA]), because
     * "not booked" would otherwise mean "eligible again on the very next sample" — a database call
     * and a log line ten times a second for the rest of a ride whose disk is failing. A rung is
     * worth almost nothing; a ride's battery is not.
     *
     * A rung crossing in the last frames before destroy can still be lost, because the only scope
     * available dies with the screen. That loss is self-healing: the stored peak is the next
     * session's seed, so the same reading re-crosses the same rung and writes then.
     */
    private fun persistPeaksIfRungChanged(v: Vehicle?) {
        if (v == null || v.isGuest || v.isDemo) return
        if (peakWriteInFlight) return
        val currentRung = GaugeScale.rungFor(currentPeak.learnedPeak, GaugeScale.CURRENT_RUNGS_A)
        val powerRung = GaugeScale.rungFor(powerPeak.learnedPeak, GaugeScale.POWER_RUNGS_W)
        if (currentRung == persistedCurrentRungA && powerRung == persistedPowerRungW) return
        // The throttle. Distinct from the booking above: that one says the database HOLDS this rung,
        // this one says we have already asked it to.
        if (currentRung == attemptedCurrentRungA && powerRung == attemptedPowerRungW) return
        attemptedCurrentRungA = currentRung
        attemptedPowerRungW = powerRung
        val currentA = currentPeak.learnedPeak
        val powerW = powerPeak.learnedPeak
        peakWriteInFlight = true
        scope.launch {
            try {
                vehicleRepository.updateGaugePeaks(v.id, currentA, powerW)
                persistedCurrentRungA = currentRung
                persistedPowerRungW = powerRung
            } catch (t: Throwable) {
                // CancellationException must always propagate — swallowing it breaks the coroutine
                // machinery. Anything else is swallowed on purpose: [scope] carries a SupervisorJob
                // and NO exception handler, so a throw out of here is app-fatal on Android, and a
                // transient storage error must not take the dashboard down mid-ride. The rung was
                // not booked, so the next sample simply tries again.
                if (t is CancellationException) throw t
                println("[VOLTY-RIDE] gauge peak write failed for rung ${currentRung}A/${powerRung}W: $t")
            } finally {
                peakWriteInFlight = false
            }
        }
    }

    private val _state: MutableStateFlow<RideDashboardComponent.State> = run {
        val initialVehicle = bmsRepository.activeVehicle.value
        // Its own function, not `adoptStoredPeaks`, so that rule has no exception to document.
        seedPeaksAtConnect(initialVehicle)
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
                // IDENTITY only. This flow is a snapshot — usually fresh, because
                // KableBmsRepository re-publishes it from `vehicleRepository.vehicles`, but its
                // cell-count and pack auto-fills read and write inside the race window before a peak
                // write's re-publication lands, and a snapshot from inside that window carries the
                // older peak. So a vehicle CHANGE adopts the stored row by looking the new id up in
                // [storedVehicles] — the database's own rows — and never reads `vehicle`'s own
                // `gaugePeak*` fields. That is [adoptStoredPeaks]' rule, enforced rather than
                // documented; looking it up rather than awaiting a re-emission is also what stops
                // this depending on `touch()` happening to change the table.
                if (vehicle?.id != peakVehicleId) {
                    adoptStoredPeaks(
                        id = vehicle?.id,
                        stored = storedVehicles.firstOrNull { it.id == vehicle?.id }
                    )
                }
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
                // Kept so a vehicle CHANGE can look its stored row up here instead of waiting for
                // this flow to happen to re-emit — see the activeVehicle collector above.
                storedVehicles = list
                // The database's own truth about the active vehicle's STORED peaks — the only
                // trustworthy source for them, and the flow the composer's clear notifies. A vehicle
                // absent from this list (a guest, a demo) has no stored peaks to adopt, so it keeps
                // whatever the connect-time seed gave it.
                list.firstOrNull { it.id == peakVehicleId }?.let { stored ->
                    adoptStoredPeaks(id = stored.id, stored = stored)
                    val (currentRange, powerRange) = displayRungs(_state.value.motion)
                    _state.update { current ->
                        current.copy(
                            currentRangeA = currentRange,
                            powerRangeW = powerRange,
                            secondaryReadout = SecondaryGaugeMapper.map(
                                current.secondary, current.motion, current.battery, current.units,
                                currentRangeA = currentRange, powerRangeW = powerRange
                            )
                        )
                    }
                }
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
