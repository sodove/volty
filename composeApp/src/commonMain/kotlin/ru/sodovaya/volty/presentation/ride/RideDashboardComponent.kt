package ru.sodovaya.volty.presentation.ride

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.GaugeScale
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.domain.stats.PeakTracker
import ru.sodovaya.volty.domain.stats.RideEnergy
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
import kotlin.time.Duration.Companion.seconds

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

    /** One controller/BMS fault in the dashboard's newest-first stack. */
    data class FaultEntry(val message: String, val occurrences: Int = 1, val active: Boolean = true)

    @OptIn(ExperimentalTime::class)
    data class State(
        val vehicle: Vehicle? = null,
        val motion: ControllerData = ControllerData(),
        val battery: BmsData = BmsData(),
        /** true when some controller is offline and [motion] covers only the rest. */
        val motionPartial: Boolean = false,
        /**
         * The connection state a renderer can describe without guessing from a
         * vehicle's configured sources.  In particular, a controller name is
         * present only after that controller has actually reported telemetry.
         */
        val connectionSummary: RideConnectionSummary = RideConnectionSummary(),
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
         * True when [sessionWhPerKm] was **integrated from power** rather than
         * read off counters the protocol keeps — see
         * [ru.sodovaya.volty.domain.stats.MotionReadings.SessionConsumption].
         *
         * A separate field rather than a flipped `hasEnergyCounters`, which is
         * the point of the whole thing: the motion sample on this same state
         * still says, truthfully, that the wheel counts no watt-hours, so
         * every consumer that has not heard of synthesis keeps reading the
         * measurement contract it was written against. Only the one renderer
         * that knows ([CleanMetricMapper.sessionConsumptionValue]) marks it.
         */
        val sessionWhPerKmSynthesised: Boolean = false,
        /**
         * Elapsed time since the first motion sample of the current
         * connection — no ticker involved. See [DefaultRideDashboardComponent].
         */
        val uptimeSeconds: Long = 0L,
        /** Observed motion-arrival rate; null until two timestamps earn one. */
        val sampleRateHz: Float? = null,
        /** Whether the observed stream is still in its connection warm-up window. */
        val sampleRatePhase: SampleCadencePhase = SampleCadencePhase.NO_SAMPLES,
        val faults: List<FaultEntry> = emptyList(),
        val savedVehicles: List<Vehicle> = emptyList(),
        val sheetOpen: Boolean = false
    )
}

/**
 * The rider-facing meaning of a vehicle-level connection fold.
 *
 * A [ConnectionState.Connected] only means that *some* link is up.  This
 * summary keeps that true while making an unhealthy controller independently
 * visible, and records only source types backed by live telemetry for the
 * vehicle pill.  It is pure so the Compose renderer cannot acquire a second
 * interpretation of Task M's per-link diagnostics.
 */
data class RideConnectionSummary(
    val kind: Kind = Kind.CONNECTED,
    val controllerIssue: ControllerIssue? = null,
    val controllerIssueReason: String? = null,
    val motionPartial: Boolean = false,
    val pillSource: PillSource = PillSource.NEUTRAL,
    val reportedControllerType: ControllerType? = null,
    val reportedBatteryType: BmsType? = null
) {
    enum class Kind { CONNECTED, MIXED, CONTROLLER_UNAVAILABLE }
    enum class ControllerIssue { RECONNECTING, WRITE_FAILED, NOT_UNDERSTOOD, PARTIAL, NOT_REPORTED }
    enum class PillSource { CONTROLLER, BATTERY, NEUTRAL }

    companion object {
        fun from(
            vehicle: Vehicle?,
            vehicleData: VehicleData,
            connection: ConnectionState
        ): RideConnectionSummary {
            val controllers = vehicleData.controllers.filter { it.isOnline }
            val packs = vehicleData.packs.filter { it.isOnline }
            val reportedController = controllers.minByOrNull { it.controller.index }?.controller
            val reportedPack = packs.minByOrNull { it.pack.index }?.pack
            val controllerAddresses = vehicle?.controllers?.map { it.address }?.toSet().orEmpty()
            val connected = connection as? ConnectionState.Connected
            val controllerReconnect = connected?.linkReconnecting
                ?.firstOrNull { it.address in controllerAddresses }
            val controllerIssue = when {
                controllerReconnect != null -> ControllerIssue.RECONNECTING
                connected?.linkWriteFailures?.any { it.address in controllerAddresses } == true ->
                    ControllerIssue.WRITE_FAILED
                connected?.linkNotUnderstood?.any { it.address in controllerAddresses } == true ->
                    ControllerIssue.NOT_UNDERSTOOD
                vehicleData.motionPartial -> ControllerIssue.PARTIAL
                vehicle?.controllers?.isNotEmpty() == true && reportedController == null ->
                    ControllerIssue.NOT_REPORTED
                else -> null
            }
            val kind = when {
                controllerIssue == null -> Kind.CONNECTED
                reportedPack != null -> Kind.MIXED
                else -> Kind.CONTROLLER_UNAVAILABLE
            }
            val pillSource = when {
                reportedController != null -> PillSource.CONTROLLER
                reportedPack != null -> PillSource.BATTERY
                else -> PillSource.NEUTRAL
            }
            return RideConnectionSummary(
                kind = kind,
                controllerIssue = controllerIssue,
                controllerIssueReason = controllerReconnect?.reason,
                motionPartial = vehicleData.motionPartial,
                pillSource = pillSource,
                reportedControllerType = reportedController?.controllerType,
                reportedBatteryType = reportedPack?.bmsType
            )
        }
    }
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
    private var faultDisplayDurationSec: Int = appPrefs.faultDisplayDurationSec.value

    private enum class FaultSource { CONTROLLER, BATTERY }

    private data class FaultRecord(
        val message: String,
        val occurrences: Int,
        val activeSources: Set<FaultSource>,
        val clearedAt: Instant?,
        val order: Long
    ) {
        val active: Boolean get() = activeSources.isNotEmpty()
    }

    private val faultHistory = LinkedHashMap<String, FaultRecord>()
    private val sourceFaults = mutableMapOf<FaultSource, Set<String>>()
    private var nextFaultOrder = 0L
    private var faultVehicleId: String? = bmsRepository.activeVehicle.value?.id

    /**
     * Fold one source sample into the dashboard fault stack. Controller and battery streams are
     * independent, so their active identities are tracked separately: a battery update must not
     * count a controller fault twice, and clearing one source must not clear a same-named fault
     * still reported by the other. The sample timestamp is the clock; there is deliberately no
     * delayed job here, so tests and a stopped stream cannot be wedged by a timer.
     */
    private fun observeFaults(
        source: FaultSource,
        messages: List<String>,
        now: Instant
    ): List<RideDashboardComponent.FaultEntry> {
        val present = messages.toSet()
        val previousSource = sourceFaults[source].orEmpty()
        previousSource.forEach { message ->
            if (message !in present) {
                val previous = faultHistory[message] ?: return@forEach
                val remainingSources = previous.activeSources - source
                faultHistory[message] = previous.copy(
                    activeSources = remainingSources,
                    clearedAt = if (remainingSources.isEmpty()) now else previous.clearedAt
                )
            }
        }
        present.forEach { message ->
            val previous = faultHistory[message]
            faultHistory[message] = when {
                previous == null || !previous.active ->
                    FaultRecord(message, 1, setOf(source), null, nextFaultOrder++)
                else -> previous.copy(
                    occurrences = previous.occurrences + 1,
                    activeSources = previous.activeSources + source,
                    clearedAt = null
                )
            }
        }
        sourceFaults[source] = present
        return renderFaults(now)
    }

    private fun clearFaultHistory() {
        faultHistory.clear()
        sourceFaults.clear()
        nextFaultOrder = 0L
    }

    /** Re-render/prune against a new duration without counting the current sample twice. */
    private fun renderFaults(now: Instant): List<RideDashboardComponent.FaultEntry> {
        val linger = faultDisplayDurationSec.seconds
        faultHistory.entries.removeIf { (_, record) ->
            !record.active && (linger == 0.seconds || record.clearedAt?.let { now - it >= linger } == true)
        }
        return faultHistory.values
            .sortedByDescending { it.order }
            .map { RideDashboardComponent.FaultEntry(it.message, it.occurrences, it.active) }
    }

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

    /**
     * Consumption **integrated** from the motion stream's power over the
     * retained window, in Wh/km — or null while the stream has offered fewer
     * than two samples carrying a measured power, or has covered no distance.
     *
     * Already divided when it gets here ([RideEnergy.synthesisedWhPerKm]),
     * because its numerator and its divisor are both bounded by what the ring
     * buffer still holds and must be taken over the same samples. Holding the
     * watt-hours alone and dividing by `motion.tripKm` here would pair a
     * windowed numerator with a session total.
     *
     * A plain `var` rather than a State field, exactly like the peak trackers
     * above and for the same reason: it is not what the screen draws. What the
     * screen draws is [RideDashboardComponent.State.sessionWhPerKm], which is
     * this gated by [MotionReadings.sessionConsumption] — and on any vehicle
     * that keeps real counters this number is computed and then ignored,
     * because a measurement wins.
     */
    private var synthesisedWhPerKm: Float? = null

    /**
     * The session consumption for [motion] against whatever
     * [synthesisedWhPerKm] currently holds, with its provenance.
     *
     * Called from exactly one place — the [BmsRepository.motionSamples]
     * collector, which is the **single writer** of
     * [RideDashboardComponent.State.sessionWhPerKm] and its `…Synthesised`
     * twin. That flow emits once per motion sample by contract, so the
     * synthesised figure and the sample its measured rival is read from arrive
     * together and neither needs a second collector to notice it.
     */
    private fun sessionConsumptionOf(motion: ControllerData) =
        MotionReadings.sessionConsumption(motion, synthesisedWhPerKm)

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
     * The learned dial widths as [VehicleRepository.gaugePeaks] last published them, keyed by vehicle
     * id. A vehicle with no entry has learned nothing ([GaugePeaks.NONE]).
     *
     * Held so that [adoptStoredPeaks] can be handed the database's own answer on a vehicle CHANGE
     * without waiting for the flow to happen to re-emit. It used to be handed `activeVehicle`'s
     * snapshot there, which contradicted its own documented rule and was safe only because
     * `touch()` makes the table change on nearly every connect — a coincidence nothing pinned.
     */
    private var storedPeaks: Map<String, GaugePeaks> = emptyMap()

    /**
     * False until [VehicleRepository.gaugePeaks] has answered for the first time — and, while it is
     * false, [persistPeaksIfRungChanged] writes nothing.
     *
     * **This exists because Task 9 took the peaks off [Vehicle].** They used to arrive synchronously
     * on the `activeVehicle` snapshot the `_state` initializer reads, so the trackers were seeded
     * with the stored range before the first sample could be folded. They cannot be now: the only
     * authority is a flow, and a flow answers on its own schedule. Without this gate a connect made
     * mid-acceleration could fold five samples, cross a rung, and persist a *fresh* median over a
     * range learned across weeks — the very loss this feature exists to prevent — in the milliseconds
     * before the first emission arrived to correct it.
     *
     * Not a `null` [storedPeaks]: the map being empty is a legitimate answer (nobody has ridden
     * anything), and the question here is whether the database has spoken at all.
     */
    private var storedPeaksSeen = false

    /**
     * The connect-time seed: the vehicle's identity, and **nothing about its learned range**.
     *
     * A [BmsRepository.activeVehicle] snapshot cannot supply a stored peak any more — since `8.sqm`
     * it does not carry one — so this only tells the trackers which vehicle they belong to, and the
     * range arrives with the first [VehicleRepository.gaugePeaks] emission. That removed
     * [adoptStoredPeaks]' one documented exception; [storedPeaksSeen] is what covers the gap it left.
     */
    private fun seedPeaksAtConnect(v: Vehicle?) = adoptPeaks(id = v?.id, stored = GaugePeaks.NONE)

    /**
     * Adopt a vehicle's **stored** peaks, when they are not already the ones in hand.
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
     * **[stored] must come from [VehicleRepository.gaugePeaks], and every call site takes it from
     * [storedPeaks] — never from [BmsRepository.activeVehicle].**
     *
     * `activeVehicle` is a snapshot. It is *usually* fresh, because `KableBmsRepository` re-publishes
     * it from the vehicle flow — but there is a race window: its cell-count and pack auto-fills read
     * and write inside the interval before a peak write's own re-publication lands. Since `8.sqm` the
     * type system carries most of that rule for us — a [Vehicle] has no peak to be stale about — but
     * the id it supplies is still a snapshot's, which is why the two are separate parameters below.
     */
    private fun adoptStoredPeaks(id: String?, stored: GaugePeaks?) = adoptPeaks(id, stored)

    /**
     * [id] and [stored] are separate parameters because they come from different authorities: the
     * vehicle being ridden is whatever `activeVehicle` says, while its stored peaks are whatever the
     * database says — and for a guest, a demo, or a row this component has not seen yet, there IS no
     * stored row and `stored` is null while [id] is not. Collapsing them would make a guest reset its
     * own trackers on every re-emission, because [peakVehicleId] would never match.
     */
    private fun adoptPeaks(id: String?, stored: GaugePeaks?) {
        val storedCurrent = stored?.currentA ?: 0f
        val storedPower = stored?.powerW ?: 0f
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
        // Nothing may be written before the database has said what it already holds — see
        // [storedPeaksSeen]. Writing first would overwrite a range learned across weeks with a
        // median five samples old.
        if (!storedPeaksSeen) return
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

    /** Arrival timestamps only; configured poll intervals never enter this list. */
    private val cadenceTimestamps = ArrayDeque<Instant>()
    /** Start of the current observed connection window, independent of the bounded rate history. */
    private var cadenceStartedAt: Instant? = null

    private fun observeCadence(timestamp: Instant): SampleCadence {
        if (cadenceStartedAt == null) cadenceStartedAt = timestamp
        if (cadenceTimestamps.lastOrNull() != timestamp) {
            cadenceTimestamps.addLast(timestamp)
            while (cadenceTimestamps.size > SAMPLE_CADENCE_HISTORY) cadenceTimestamps.removeFirst()
        }
        return sampleCadence(cadenceTimestamps.toList(), warmupStartedAt = cadenceStartedAt)
    }

    /**
     * Push the two dial widths onto the state after an **adoption** has moved them.
     *
     * Its own function because an adoption is the one event that changes the ranges without a motion
     * sample: the trackers moved, so what the dials draw moved, and no other collector is going to
     * notice. Folds nothing — [displayRungs] answers for whatever the trackers hold now.
     */
    private fun republishRanges() {
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
                connectionSummary = RideConnectionSummary.from(
                    initialVehicle, initialVehicleData, bmsRepository.connectionState.value
                ),
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
                // The measured branch, unchanged: no samples have been collected
                // yet, so there is nothing to synthesise from.
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
                val cadence = if (motion.isConnected) {
                    observeCadence(motion.timestamp)
                } else {
                    sampleCadence(cadenceTimestamps.toList())
                }
                // Folded once per sample, OUTSIDE `_state.update` — that block can be re-run when
                // another collector wins the compare-and-set, and a tracker folded inside it would
                // count the same sample twice towards a median (`§9.2` item 2).
                val (currentRange, powerRange) = foldPeaks(motion)
                persistPeaksIfRungChanged(_state.value.vehicle)
                // NOT the session consumption: [BmsRepository.motionSamples] emits
                // once per motion sample, so the collector below runs for this same
                // sample with the fresh integral in hand and is the single writer of
                // those two fields. Computing them here as well was a second
                // derivation that the sweep showed nothing could tell from its
                // absence — whichever collector ran second decided the state, and
                // that was always the other one.
                val faults = observeFaults(FaultSource.CONTROLLER, motion.faults, motion.timestamp)
                _state.update { current ->
                    current.copy(
                        motion = motion,
                        uptimeSeconds = uptime,
                        currentRangeA = currentRange,
                        powerRangeW = powerRange,
                        sampleRateHz = cadence.rateHz,
                        sampleRatePhase = cadence.phase,
                        faults = faults,
                        secondaryReadout = SecondaryGaugeMapper.map(
                            current.secondary, motion, current.battery, current.units,
                            currentRangeA = currentRange, powerRangeW = powerRange
                        )
                    )
                }
            }
        }

        // The consumption figure (`I` Task 8), and the ONLY writer of the two
        // session fields — for every vehicle, measured or synthesised.
        //
        // Its own collector rather than a fold inside the motion one above,
        // because the repository re-reads the ring buffer per emission and the
        // integral is over the whole retained window: folding it in beside
        // `foldPeaks` would make it look like a per-sample accumulator, which it
        // is not — it is recomputed from scratch, so a re-run of `_state.update`
        // or a duplicated emission cannot double-count.
        //
        // **LAUNCHED AFTER THE MOTION COLLECTOR ABOVE, AND THAT ORDER IS
        // LOAD-BEARING.** `motionSamples` is derived from `activeMotion`
        // (BmsRepository.motionSamples' contract), so ONE emission wakes both of
        // these, on one dispatcher, in subscription order — i.e. launch order.
        // Launched first, this one would read `current.motion` before the
        // collector above had written it, and the MEASURED branch (which reads
        // `consumedWh` / `tripKm` off that sample) would trail the ride by one
        // notification. Swapping the two `scope.launch` blocks is therefore a
        // behaviour change, not a tidy-up. Pinned by
        // RideDashboardComponentTest's synthesis tests, whose fake derives
        // `motionSamples` from `activeMotion` for exactly this reason.
        scope.launch {
            bmsRepository.motionSamples(RideEnergy.SESSION_WINDOW).collect { samples ->
                // Bounded to THIS connection: the buffer deliberately survives a
                // reconnect to the same address, while the `tripKm` the distance
                // delta is taken from restarts with the protocol.
                synthesisedWhPerKm = RideEnergy.synthesisedWhPerKm(samples, since = sessionStartedAt)
                _state.update { current ->
                    val session = sessionConsumptionOf(current.motion)
                    current.copy(
                        sessionWhPerKm = session?.whPerKm,
                        sessionWhPerKmSynthesised = session?.synthesised == true
                    )
                }
            }
        }

        scope.launch {
            bmsRepository.activeVehicleData.collect { vd ->
                val faults = observeFaults(FaultSource.BATTERY, vd.aggregate.bmsFaults, vd.aggregate.timestamp)
                _state.update { current ->
                    current.copy(
                        battery = vd.aggregate,
                        motionPartial = vd.motionPartial,
                        connectionSummary = RideConnectionSummary.from(current.vehicle, vd, current.connection),
                        faults = faults,
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
                val vehicleChanged = vehicle?.id != faultVehicleId
                if (vehicleChanged) {
                    faultVehicleId = vehicle?.id
                    clearFaultHistory()
                }
                // IDENTITY only. This flow is a snapshot, and since `8.sqm` it carries no learned
                // range at all — so a vehicle CHANGE adopts by looking the new id up in
                // [storedPeaks], the database's own answer. That is [adoptStoredPeaks]' rule;
                // looking it up rather than awaiting a re-emission is also what stops this
                // depending on `touch()` happening to change the vehicle table.
                if (vehicle?.id != peakVehicleId) {
                    adoptStoredPeaks(id = vehicle?.id, stored = storedPeaks[vehicle?.id])
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
                        connectionSummary = RideConnectionSummary.from(
                            vehicle, bmsRepository.activeVehicleData.value, current.connection
                        ),
                        faults = if (vehicleChanged) emptyList() else current.faults,
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
                if (c !is ConnectionState.Connected) {
                    sessionStartedAt = null
                    cadenceTimestamps.clear()
                    cadenceStartedAt = null
                    clearFaultHistory()
                }
                _state.update {
                    it.copy(
                        connection = c,
                        connectionSummary = RideConnectionSummary.from(
                            it.vehicle, bmsRepository.activeVehicleData.value, c
                        ),
                        faults = if (c is ConnectionState.Connected) it.faults else emptyList(),
                        sampleRateHz = if (c is ConnectionState.Connected) it.sampleRateHz else null,
                        sampleRatePhase = if (c is ConnectionState.Connected) {
                            it.sampleRatePhase
                        } else {
                            SampleCadencePhase.NO_SAMPLES
                        }
                    )
                }
            }
        }

        scope.launch {
            vehicleRepository.vehicles.collect { list ->
                _state.update { it.copy(savedVehicles = list) }
            }
        }

        // The database's own truth about the STORED dial ranges — the only trustworthy source for
        // them, and the flow the composer's clear notifies. Its own collector since `8.sqm`, because
        // they are no longer a field of a vehicle: a rider renaming their scooter must not re-emit
        // its learned range, and a peak write must not re-emit the vehicle list.
        scope.launch {
            vehicleRepository.gaugePeaks.collect { peaks ->
                // Kept so a vehicle CHANGE can look its range up here instead of waiting for this
                // flow to happen to re-emit — see the activeVehicle collector above.
                storedPeaks = peaks
                // Whatever the map says about the vehicle in hand, INCLUDING that it says nothing:
                // absence is [GaugePeaks.NONE], which is what a vehicle nobody has ridden — and a
                // guest or a demo, which are never in the map at all — correctly adopt. For those
                // two, `adoptPeaks` finds the rungs already agree and returns without disturbing
                // anything, because nothing ever persists a rung for them.
                adoptStoredPeaks(id = peakVehicleId, stored = peaks[peakVehicleId])
                // Only now may a rung be written back. See [storedPeaksSeen].
                storedPeaksSeen = true
                republishRanges()
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

        scope.launch {
            appPrefs.faultDisplayDurationSec.collect { seconds ->
                faultDisplayDurationSec = seconds.coerceAtLeast(0)
                _state.update { current ->
                    current.copy(faults = renderFaults(current.motion.timestamp))
                }
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
