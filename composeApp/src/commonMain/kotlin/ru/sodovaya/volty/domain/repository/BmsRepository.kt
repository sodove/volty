package ru.sodovaya.volty.domain.repository

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.stats.MovingAvg
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Auto-detected BMS type, or `null` when the scanner did not recognize the device. */
    val bmsType: BmsType?,
    /**
     * Auto-detected controller type — a candidate signal, separate from
     * [bmsType], since a device can never be both a battery and a controller
     * (see [ru.sodovaya.volty.data.bms.BmsTypeDetector.detectController]).
     */
    val controllerType: ControllerType? = null,
    val knownVehicle: Vehicle? = null
)

interface BmsRepository {
    /**
     * Per-pack view of the active vehicle plus the derived aggregate.
     * [activeData] is the aggregate of this — kept as a separate property so
     * the dashboard, notification and alert engine need no changes.
     */
    val activeVehicleData: StateFlow<VehicleData>

    val activeData: StateFlow<BmsData>

    /**
     * The vehicle-level motion aggregate (speed, currents, power, …) of the
     * active vehicle's controllers — the motion twin of [activeData]. Defaults
     * to an empty [ControllerData] when nothing is connected or the vehicle
     * has no controllers.
     */
    val activeMotion: StateFlow<ControllerData>
    val activeVehicle: StateFlow<Vehicle?>
    val connectionState: StateFlow<ConnectionState>

    fun scanAll(): Flow<DiscoveredDevice>
    suspend fun connect(vehicle: Vehicle): Result<Unit>
    suspend fun connectGuest(address: String, type: BmsType): Result<Unit>

    /**
     * Connect to a simulated BMS. No BLE is involved: a [ru.sodovaya.volty.data.demo.DemoBmsSimulator]
     * feeds synthetic data through the same pipeline (Dashboard / Graph / cells /
     * notification) so reviewers and new users can exercise the full UI without
     * hardware. The synthetic demo vehicle (see [ru.sodovaya.volty.domain.model.isDemo])
     * is NEVER persisted.
     *
     * [profile] picks WHICH synthetic vehicle comes up — a VESC scooter or a
     * Begode wheel. They are different machines, not the same curve renamed:
     * see [DemoProfile].
     */
    suspend fun connectDemo(profile: DemoProfile = DemoProfile.SCOOTER): Result<Unit>

    suspend fun disconnect()

    /**
     * Tear down ONE link of the active vehicle — its session and reconnect
     * job — and drop it from the fold, leaving every other link live. The
     * vehicle's [connectionState] is recomputed from the remaining links.
     *
     * A no-op when no link at [address] is currently installed (including
     * when nothing is connected at all). When [address] names the vehicle's
     * LAST link, this degenerates to a full [disconnect] rather than leaving
     * the repository half torn down.
     *
     * This is the ride-time BMS -> head-unit handoff primitive: dropping one
     * link of a multi-link vehicle while its sibling links stay live. It does
     * not touch the sample funnel or the orchestrator directly — the dropped
     * link simply stops submitting, and its sources go stale / offline
     * through the existing per-pack staleness sweep.
     */
    suspend fun disconnectLink(address: String)

    fun samples(window: Duration): Flow<List<BmsData>>

    /**
     * Motion twin of [samples]: the retained **vehicle-level** motion aggregates
     * over the given [window].
     *
     * **Emits once per motion sample, alongside [activeMotion].** That pairing is
     * part of the contract rather than an implementation accident:
     * `RideDashboardComponent` publishes the session consumption from this flow
     * alone, and the divisor it uses is the `tripKm` of the sample [activeMotion]
     * just carried. An implementation that emitted on some other schedule would
     * leave the two halves of that division from different moments.
     *
     * Vehicle-level, not per-controller, and that is the load-bearing part: the
     * one consumer integrates `power × dt` over this list
     * ([ru.sodovaya.volty.domain.stats.RideEnergy]), and a two-controller
     * vehicle interleaves its contributors — a trapezoid over the interleave
     * would read one controller's power as the whole vehicle's and report half
     * the energy. These are the same folds [activeMotion] publishes.
     *
     * The existence of this accessor is the point: the buffer behind it has been
     * written since Part A and read nowhere, which is why a wheel that keeps no
     * watt-hour counters had a blank consumption readout while the Graph screen
     * integrated the identical quantity correctly.
     */
    fun motionSamples(window: Duration): Flow<List<ControllerData>>

    /**
     * Cold flow of [MovingAvg] over the given [window], emitting on each new
     * sample. Callers should [kotlinx.coroutines.flow.stateIn] this into their
     * own [kotlinx.coroutines.CoroutineScope] so the collector is cancelled
     * with the consumer's lifecycle.
     */
    fun movingAverage(window: Duration): Flow<MovingAvg>

    /**
     * Called when the app comes back to the foreground. The repo verifies
     * sample freshness and forces a reconnect if the in-session watchdog didn't
     * catch a background drop (Doze, App-Standby, foreground service killed).
     * Idempotent — safe to call on every lifecycle ON_START.
     */
    suspend fun onAppResumed()
}
