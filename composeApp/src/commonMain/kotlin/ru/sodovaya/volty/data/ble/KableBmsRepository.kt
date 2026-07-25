package ru.sodovaya.volty.data.ble

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import ru.sodovaya.volty.data.bms.AntBmsProtocol
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.bms.BmsTypeDetector
import ru.sodovaya.volty.data.bms.DalyBmsProtocol
import ru.sodovaya.volty.data.bms.JbdBmsProtocol
import ru.sodovaya.volty.data.bms.JkBmsProtocol
import ru.sodovaya.volty.data.demo.DemoBmsSimulator
import ru.sodovaya.volty.data.memory.SampleRingBuffer
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DEMO_VEHICLE_ID
import ru.sodovaya.volty.domain.model.GUEST_VEHICLE_ID_PREFIX
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.bmsTypeOrNull
import ru.sodovaya.volty.domain.model.expandedTo
import ru.sodovaya.volty.domain.model.hasControllers
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.model.vehiclesByAddress
import ru.sodovaya.volty.domain.model.withCellCount
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.domain.stats.MovingAverage
import ru.sodovaya.volty.domain.stats.PackAggregator
import ru.sodovaya.volty.domain.stats.VoltageSocEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
class KableBmsRepository private constructor(
    private val vehicleRepository: VehicleRepository,
    private val serviceStart: () -> Unit,
    private val serviceStop: () -> Unit,
    /**
     * Coroutine context for repo-internal work. Production uses
     * [kotlinx.coroutines.Dispatchers.Default]; tests can inject a
     * [kotlinx.coroutines.test.TestDispatcher] so `runTest { advanceUntilIdle() }`
     * actually drives the reconnect / watchdog loops.
     */
    private val coroutineContext: kotlin.coroutines.CoroutineContext,
) : BmsRepository {

    /** Production constructor used by Koin. */
    constructor(
        vehicleRepository: VehicleRepository,
        serviceController: ru.sodovaya.volty.service.ServiceController,
    ) : this(
        vehicleRepository = vehicleRepository,
        serviceStart = { serviceController.start() },
        serviceStop = { serviceController.stop() },
        coroutineContext = Dispatchers.Default,
    )

    internal companion object {
        /**
         * Buffer of the sample funnel channel. BMS links sample at 1-6 Hz and
         * the consumer resumes inline on the sender's thread (see
         * [launchSampleConsumer]), so the buffer only ever holds samples that
         * raced a concurrent drain — 64 slots absorb a burst from several
         * links hundreds of times over. In practice `trySend` can therefore
         * fail only on a CLOSED channel (a late sample from a session already
         * torn down); a genuinely full buffer would mean the consumer stopped
         * draining, and the drop is logged either way, never silent.
         */
        const val SAMPLE_FUNNEL_CAPACITY = 64

        /**
         * Distinct BLE-style address for [DEMO_CONTROLLER]. The demo pack and
         * the demo controller are two different sources on the (synthetic)
         * vehicle, so they must NOT share [DEMO_VEHICLE_ID] as their address:
         * [planLinks] requires one address to resolve to exactly one
         * [ProtocolKind], and JK (pack) + VESC (controller) at the same
         * address would throw `IllegalArgumentException` if this vehicle ever
         * reached link planning. It doesn't today (`connectDemo` bypasses the
         * orchestrator entirely and the demo vehicle is never persisted, so it
         * can never reach `connect(vehicle)`), but the model should still be
         * coherent on its own terms rather than relying on that bypass forever.
         */
        private const val DEMO_CONTROLLER_ADDRESS = "demo-controller"

        /**
         * Synthetic controller backing "Try demo" mode's motion feed (Task 12):
         * a single VESC-shaped source so the demo vehicle has a coherent
         * controller behind the ride curve, the same way it has a stored pack
         * behind the battery curve — [ru.sodovaya.volty.domain.stats.MotionAggregator]
         * / [activeMotion] see a real (if synthetic) controller rather than an
         * empty list.
         */
        private val DEMO_CONTROLLER = Controller(
            index = 0,
            label = "Demo motor",
            controllerType = ControllerType.VESC,
            address = DEMO_CONTROLLER_ADDRESS
        )

        /**
         * The synthetic vehicle that powers "Try demo" mode. Its id is
         * [DEMO_VEHICLE_ID] (see [ru.sodovaya.volty.domain.model.isDemo]) so it is
         * never confused with a saved or guest vehicle and is never persisted.
         * Carries [DEMO_CONTROLLER] alongside its single pack so the demo
         * exercises the motion path end-to-end, not just the battery one. The
         * pack and controller use distinct addresses ([DEMO_VEHICLE_ID] vs.
         * [DEMO_CONTROLLER_ADDRESS]) so the model would still pass [planLinks]
         * if it ever reached link planning — see [DEMO_CONTROLLER_ADDRESS].
         */
        val DEMO_VEHICLE: Vehicle = singlePackVehicle(
            id = DEMO_VEHICLE_ID,
            name = "Demo battery",
            iconKey = "scooter",
            bmsType = BmsType.JK_BMS,
            bmsAddress = DEMO_VEHICLE_ID,
            chemistry = Chemistry.LI_ION_NMC,
            cellCount = DemoBmsSimulator.CELL_COUNT,
            createdAt = Clock.System.now()
        ).copy(controllers = listOf(DEMO_CONTROLLER))

        /**
         * Test-only factory: construct with noop start/stop callbacks and a
         * test dispatcher. Used by [KableBmsRepositoryDisconnectRaceTest] to
         * avoid the platform `ServiceController` expect/actual.
         */
        internal fun forTesting(
            vehicleRepository: VehicleRepository,
            serviceStart: () -> Unit,
            serviceStop: () -> Unit,
            coroutineContext: kotlin.coroutines.CoroutineContext,
        ): KableBmsRepository = KableBmsRepository(
            vehicleRepository = vehicleRepository,
            serviceStart = serviceStart,
            serviceStop = serviceStop,
            coroutineContext = coroutineContext,
        )
    }

    private val scope = CoroutineScope(coroutineContext + SupervisorJob())

    private val _activeData = MutableStateFlow(BmsData())
    override val activeData: StateFlow<BmsData> = _activeData.asStateFlow()

    private val _activeVehicleData = MutableStateFlow(VehicleData())
    override val activeVehicleData: StateFlow<VehicleData> = _activeVehicleData.asStateFlow()

    private val _activeMotion = MutableStateFlow(ControllerData())
    override val activeMotion: StateFlow<ControllerData> = _activeMotion.asStateFlow()

    /**
     * Orchestrator for the currently connected vehicle. Null when nothing is
     * connected.
     *
     * Every PRODUCTION write goes through [sessionLock], and every
     * check-then-write (the identity-guarded failure cleanup) is performed
     * inside a single critical section, so a failed attempt can never null out
     * the orchestrator a concurrent attempt installed in between. The writers
     * are: [doConnect] (install, and clear via [clearOrchestratorLocked]),
     * [connectDemo] and [disconnect] (clear) — all inside a
     * `sessionLock.withLock { }` block.
     *
     * The one exception is [installSampleFunnelForTest], which writes the field
     * unlocked. It has no production call site and runs on a single-threaded
     * test dispatcher, so it cannot race — but it does mean "every write is
     * locked" holds for production paths only. Keep it that way: a second
     * unlocked writer that production could reach would silently undo the
     * guarantee the rest of this doc describes.
     *
     * [sessionLock] is a plain non-reentrant [Mutex]. The two cleanup helpers
     * have opposite locking contracts: [clearOrchestratorLocked] ASSUMES the
     * lock is already held and never takes it itself, whereas
     * [clearOrchestratorAfterFailure] takes [sessionLock] itself and must
     * therefore never be called from inside a critical section — the mutex is
     * not reentrant, so doing so hangs silently instead of throwing.
     *
     * Reads are unlocked. The session's onSample callback accesses the field
     * null-safely and tolerates a concurrent swap (a sample routed through a
     * stale or absent orchestrator is dropped or passed through raw, never
     * crashed on).
     */
    private var vehicleConnection: VehicleConnection? = null

    private val _activeVehicle = MutableStateFlow<Vehicle?>(null)
    override val activeVehicle: StateFlow<Vehicle?> = _activeVehicle.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Default 4-hour time-based cap. Holds enough history for ALL / 1h graph
    // windows regardless of per-BMS poll rate (JK ~1Hz, ANT 2Hz, etc).
    private val ringBuffer = SampleRingBuffer<BmsData> { it.timestamp }

    // Motion twin of [ringBuffer]: retains the per-controller motion samples
    // funnelled through the SAME single consumer, keyed by their own timestamp.
    private val motionRingBuffer = SampleRingBuffer<ControllerData> { it.timestamp }

    /** Lock guarding session swap + the userInitiatedDisconnect flag. */
    private val sessionLock = Mutex()

    /**
     * The links of the current connection — one [PackLink] per distinct pack
     * address, successor of the single `currentSession` / `reconnectJob`
     * pair. A single-address vehicle (every stored vehicle until sub-project
     * B) holds exactly one link and behaves as the old fields did.
     *
     * The LIST reference follows the [vehicleConnection] locking discipline:
     * every production write ([doConnect] install, [disconnect] /
     * [connectDemo] clear, [onAppResumed] resurrect) happens inside a
     * `sessionLock.withLock` block; the test seams write it unlocked under
     * the same single sanctioned exception. Reads are unlocked — the list is
     * immutable, and a coroutine holding a stale reference only ever finds
     * links whose identity guards (`links.any { it === link }`) fail closed.
     */
    @Volatile
    private var links: List<PackLink> = emptyList()

    /**
     * Guards every link STATUS mutation and the fold that derives the
     * vehicle's [ConnectionState] from them, so the fold never reads a
     * half-updated link and two links' concurrent transitions cannot
     * interleave their state writes. Deliberately NOT [sessionLock]: status
     * changes happen on non-suspending paths (including inside `synchronized`
     * from drop callbacks) where taking a suspending mutex is impossible, and
     * the fold never touches the session / channel fields the mutex guards.
     */
    private val linkStateLock = Any()

    /**
     * The serialisation barrier of the sample funnel: sessions enrich on their
     * own coroutine and only SEND a [Sample] here — battery ([PackSample]) or
     * motion ([MotionSample]); the single consumer launched by
     * [launchSampleConsumer] owns every shared-state mutation
     * ([VehicleConnection.submit] / [VehicleConnection.submitMotion],
     * [ringBuffer] / [motionRingBuffer], [_activeData]). One channel +
     * consumer pair per connection, installed in the same [sessionLock]
     * critical section as [vehicleConnection] so the identity-guarded failure
     * cleanup covers both, and closed wherever the orchestrator is cleared.
     * Same locking discipline — and the same single unlocked test-seam
     * exception — as the [vehicleConnection] field itself.
     */
    private var sampleChannel: Channel<Sample>? = null
    private var sampleConsumerJob: Job? = null

    /**
     * Job driving the [DemoBmsSimulator] feed in "Try demo" mode. Lives entirely
     * outside the BLE session machinery: there is no [ConnectionSession], no
     * watchdog and no reconnect loop for demo. Cancelled on [disconnect], on a
     * real [doConnect], and before starting a fresh [connectDemo].
     */
    private var demoJob: Job? = null

    /**
     * Cached (address, type) of the most recent connection attempt. [onAppResumed]
     * replays this through the same reconnect pathway the watchdog uses, which is
     * the only way to re-fire the loop for a guest connection (whose Vehicle is
     * synthetic and not reconstructable from the [ConnectionState] alone).
     */
    private data class ConnectionTarget(
        val vehicle: Vehicle?,
        val address: String,
        /** Null for a vehicle with no stored pack — see [connect]. */
        val type: BmsType?
    )
    @Volatile
    private var lastConnectionTarget: ConnectionTarget? = null

    /**
     * Flag set by [disconnect] to prevent the watchdog / state observer /
     * reconnect loop from resurrecting a connection the user explicitly
     * closed. Cleared on the next user-initiated [connect].
     *
     * Atomic-ish via [sessionLock]; readers in coroutines must also re-check
     * after any suspension point.
     */
    @Volatile
    private var userInitiatedDisconnect: Boolean = false

    /** Guarded by [advertisementCacheLock]: written from the scan flow on
     *  Dispatchers.Default, read from connect paths on arbitrary coroutines. */
    private val advertisementCacheLock = Any()
    private val advertisementCache = mutableMapOf<String, com.juul.kable.Advertisement>()

    private fun cacheAdvertisement(id: String, ad: com.juul.kable.Advertisement) {
        synchronized(advertisementCacheLock) { advertisementCache[id] = ad }
    }

    private fun cachedAdvertisement(id: String): com.juul.kable.Advertisement? =
        synchronized(advertisementCacheLock) { advertisementCache[id] }

    init {
        scope.launch {
            vehicleRepository.vehicles.collect { list ->
                val current = _activeVehicle.value ?: return@collect
                val updated = list.firstOrNull { it.id == current.id }
                if (updated != null && updated != current) {
                    _activeVehicle.value = updated
                }
            }
        }
        scope.launch {
            _activeData.collect { data -> maybePersistCellCount(data) }
        }
        scope.launch {
            _activeVehicleData.collect { vd -> maybePersistPacks(vd) }
        }
    }

    // ----- Cell-count auto-fill -----

    /**
     * Consecutive samples with an identical cell count required before we
     * trust it. Multi-frame protocols emit partial lists mid-cycle (Daly
     * streams 3 cells per 0x95 frame), so a single sample can undercount.
     */
    private val cellCountStableSamples = 3

    private var observedCellCount = 0
    private var observedCellCountStreak = 0
    private var lastPersistedCellCount: Pair<String, Int>? = null

    /**
     * The profile's cell count is an auto-filled cache of live telemetry, not
     * user input: once the reported count is stable, write it back to the
     * saved vehicle so the UI can show "16s" before the first sample arrives
     * on later connects. Guests and demo are transient and never persisted.
     */
    private suspend fun maybePersistCellCount(data: BmsData) {
        val n = primaryPackCellCount() ?: data.cellVoltages.size
        if (!data.isConnected || n == 0) {
            observedCellCountStreak = 0
            return
        }
        if (n == observedCellCount) {
            observedCellCountStreak++
        } else {
            observedCellCount = n
            observedCellCountStreak = 1
        }
        if (observedCellCountStreak < cellCountStableSamples) return
        val vehicle = _activeVehicle.value ?: return
        if (vehicle.isGuest || vehicle.isDemo) return
        // packs.firstOrNull(), not the `Vehicle.cellCount` shim: that is
        // `packs.first()` and THROWS on a zero-pack (controller-only) vehicle.
        // This runs inside the _activeData collector on the repo's
        // SupervisorJob scope with no exception handler, so a throw here is
        // app-fatal on Android. Unreachable only by accident today (a derived
        // VESC pack carries no cells, so `n == 0` short-circuits above) —
        // exactly the kind of accident a later protocol change would undo.
        if (vehicle.packs.firstOrNull()?.cellCount == n) return
        if (lastPersistedCellCount == vehicle.id to n) return
        lastPersistedCellCount = vehicle.id to n
        println("[VOLTY-BLE] cell count auto-fill: ${vehicle.name} -> ${n}s")
        vehicleRepository.upsert(vehicle.withCellCount(n))
    }

    /**
     * Cell count of the PRIMARY pack, or null when the orchestrator has not
     * published one yet (then [maybePersistCellCount] falls back to the sample
     * it was handed).
     *
     * [Vehicle.cellCount] describes one pack, but [_activeData] carries the
     * vehicle-level AGGREGATE, whose `cellVoltages` is the union of every
     * online pack — 80 values for a two-branch Begode. Persisting that would
     * store a 40S wheel as "80s".
     *
     * INVARIANT: [_activeVehicleData] is assumed to describe the SAME
     * connection as [_activeVehicle], which [maybePersistCellCount] persists
     * into — nothing here checks it. The guarantee is [doConnect]'s teardown
     * ordering (previous session torn down under [sessionLock] before the
     * new vehicle identity is written); the device-switch branch resets
     * [_activeData] and the ring buffer but leaves [_activeVehicleData]
     * alone, so the safety rests entirely on that ordering. A violation
     * would persist one vehicle's branch cell count into another's profile.
     */
    private fun primaryPackCellCount(): Int? =
        _activeVehicleData.value.packs
            .firstOrNull()
            ?.takeIf { it.isOnline }
            ?.data?.cellVoltages?.size
            ?.takeIf { it > 0 }

    override fun scanAll(): Flow<DiscoveredDevice> = flow {
        // Keyed by EVERY address each vehicle can be recognised by — the same
        // index the Scanning and Picker screens use, see [vehiclesByAddress].
        // The primary pack alone is not enough: a controller-only vehicle
        // (legal since Part A) has no pack address at all, and a vehicle with
        // both sources was invisible whenever its controller was the thing
        // advertising.
        val knownAddresses: Map<String, Vehicle> =
            vehiclesByAddress(vehicleRepository.vehicles.first())
        // A scan can run WHILE a connection is live (the Picker seeds itself
        // with the connected device and keeps scanning for others). Don't let
        // it clobber the Connected / Connecting / Reconnecting state machine —
        // the watchdog only acts in Connected, so overwriting it would blind
        // the drop detection.
        when (_connectionState.value) {
            is ConnectionState.Connected,
            is ConnectionState.Connecting,
            is ConnectionState.Reconnecting -> Unit
            else -> _connectionState.value = ConnectionState.Scanning
        }
        val scanner = Scanner()
        scanner.advertisements.collect { ad ->
            val name = ad.name
            val serviceList = ad.uuids.map { it.toString().lowercase() }
            // May be null: the picker now lists every device and lets the user
            // pick the type manually, so we no longer drop unrecognized ads.
            val type = BmsTypeDetector.detect(name = name, serviceUuids = serviceList)
            val controllerType = BmsTypeDetector.detectController(name = name, serviceUuids = serviceList)
            val id = ad.identifier.toString()
            cacheAdvertisement(id, ad)
            emit(
                DiscoveredDevice(
                    address = id,
                    name = name,
                    rssi = ad.rssi,
                    bmsType = type,
                    controllerType = controllerType,
                    knownVehicle = knownAddresses[id]
                )
            )
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun connect(vehicle: Vehicle): Result<Unit> {
        // If a caller hands a transient guest Vehicle back to connect(), route
        // it through the guest path so it stays unpersisted and the touch /
        // saved-vehicle observers leave it alone.
        // bmsTypeOrNull, not the `packs.first()` shim, which would THROW on a
        // zero-pack vehicle. connectGuest() needs a pack template, and every
        // guest [buildGuestVehicle] can produce has exactly one pack, so this
        // is the same call as before for every guest that can exist. A
        // pack-less guest is an impossible state rather than a supported one:
        // fail loudly here instead of falling through to doConnect and
        // silently skipping connectGuest's setup.
        if (vehicle.isGuest) {
            val guestPackType = vehicle.bmsTypeOrNull
                ?: error(
                    "guest vehicle ${vehicle.id} has zero packs — connectGuest() " +
                        "needs a pack template, and buildGuestVehicle() always " +
                        "supplies one, so this guest was built by something else"
                )
            return connectGuest(vehicle.primaryAddress, guestPackType)
        }
        // primaryAddress / packs.firstOrNull(), NOT the bmsAddress / bmsType
        // shims: both are `packs.first()` and a controller-only vehicle (a
        // VESC whose battery is derived at runtime) legally stores ZERO packs
        // since Part A — the shims THROW on it before a single link is
        // planned. The links themselves are planned from the VEHICLE (packs
        // AND controllers, see [effectiveLinkSpecs]), so [address] is only
        // this connection's identity and [type] only the guest fallback's
        // pack template — null when there is no stored pack to describe.
        return doConnect(vehicle.primaryAddress, vehicle.packs.firstOrNull()?.bmsType, vehicle)
    }

    override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> =
        doConnect(address, type, vehicle = buildGuestVehicle(address, type))

    override suspend fun connectDemo(): Result<Unit> {
        println("[VOLTY-BLE] connectDemo: starting simulated session")
        return try {
            // Tear down every real link (session + reconnect loop) / prior demo
            // under the lock so a concurrent disconnect or connect sees a
            // consistent view.
            sessionLock.withLock {
                userInitiatedDisconnect = false
                for (l in links) {
                    l.session?.tearDown()
                    l.session = null
                    l.reconnectJob?.cancel()
                    l.reconnectJob = null
                }
                links = emptyList()
                demoJob?.cancel()
                demoJob = null
                // Demo bypasses the orchestrator entirely — the simulator
                // feeds _activeData directly and has no BLE lines to route.
                // Reset the vehicle-level flow together with the orchestrator so
                // a previous real connection's packs are not left published
                // while the demo runs. disconnect() reaches the same end state
                // by a different route — it clears the flow outside any lock and
                // takes sessionLock separately for the orchestrator, because it
                // must not hold the lock across tearDown(). Here both writes fit
                // in the one critical section this method already holds.
                vehicleConnection = null
                _activeVehicleData.value = VehicleData()
                // Reset the motion flow alongside the vehicle-level one so a
                // prior connection's motion does not linger before the demo's
                // own ride curve (Task 12) starts publishing its own.
                _activeMotion.value = ControllerData()
                // No orchestrator, no funnel: close any previous connection's
                // channel so its consumer ends with it.
                closeSampleFunnelLocked()
                // No real link to resurrect on resume — there is no
                // ConnectionSession behind a demo connection.
                lastConnectionTarget = null
            }
            _activeVehicle.value = DEMO_VEHICLE
            ringBuffer.clear()
            motionRingBuffer.clear()
            _connectionState.value = ConnectionState.Connected(DEMO_VEHICLE)
            // Show the foreground notification too, so reviewers see the full
            // monitoring experience (the service feeds off activeData/activeVehicle).
            serviceStart()
            demoJob = scope.launch {
                DemoBmsSimulator().run { sample, motion ->
                    ringBuffer.push(sample)
                    _activeData.value = sample
                    // Motion twin of the two lines above: demo bypasses the
                    // orchestrator entirely (no VehicleConnection, no funnel),
                    // so the ride curve is fed straight into the SAME flows a
                    // real connection's onVehicleData hook would publish —
                    // activeMotion directly, and the motion ring buffer /
                    // vehicle-level snapshot alongside it. See DEMO_CONTROLLER:
                    // the demo vehicle carries one, so this controller state
                    // is coherent with the vehicle it is published under.
                    motionRingBuffer.push(motion)
                    _activeMotion.value = motion
                    // Battery twin of the same idea: without this, packs/aggregate
                    // stay at VehicleData()'s all-zero default for the whole demo
                    // session (only _activeData — the Battery tab's own flow — saw
                    // sample), so the Ride dashboard's BATTERY tile (which reads
                    // activeVehicleData.aggregate, not activeData) would be stuck
                    // at "0% / 0.0V" forever instead of tracking the demo's SoC
                    // curve. Mirrors VehicleConnection.snapshot()'s battery half via
                    // the same PackAggregator a real single-pack connection uses —
                    // an identity transform here, but it keeps the demo on the one
                    // true path rather than a bespoke shortcut.
                    val demoPackState = PackState(
                        pack = DEMO_VEHICLE.packs.first(),
                        data = sample,
                        isOnline = true,
                        lastSeenAt = sample.timestamp
                    )
                    val demoBattery = PackAggregator.build(listOf(demoPackState), PackTopology.PARALLEL)
                    _activeVehicleData.value = _activeVehicleData.value.copy(
                        packs = demoBattery.packs,
                        aggregate = demoBattery.aggregate,
                        topology = demoBattery.topology,
                        isPartial = demoBattery.isPartial,
                        controllers = listOf(
                            ControllerState(controller = DEMO_CONTROLLER, data = motion, isOnline = true)
                        ),
                        motion = motion,
                        motionPartial = false
                    )
                }
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(e.message ?: "Demo failed")
            Result.failure(e)
        }
    }

    /**
     * Build a transient [Vehicle] that powers the dashboard pill / charge bars
     * for an ad-hoc (guest) connection. The id uses [GUEST_VEHICLE_ID_PREFIX]
     * as a sentinel — see [isGuest] — and the entity is never written to the
     * saved-vehicle store.
     */
    private fun buildGuestVehicle(address: String, type: BmsType): Vehicle {
        val advName = cachedAdvertisement(address)?.name?.takeIf { it.isNotBlank() }
        return singlePackVehicle(
            id = "$GUEST_VEHICLE_ID_PREFIX$address",
            name = advName ?: "Guest BMS",
            iconKey = "battery",
            bmsType = type,
            bmsAddress = address,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Clock.System.now()
        )
    }

    /**
     * The pack list the orchestrator is built from: the vehicle's own packs,
     * grown to [protocolPackCount] slots when the protocol knows about more
     * batteries than the stored profile does.
     *
     * A Begode wheel is two parallel branches multiplexed over ONE BLE link and
     * its [BmsProtocol.packCount] is 2, but the vehicle was created as a
     * single-pack profile. Without this, slot 1 does not exist, `submit(1, ...)`
     * hits [VehicleConnection]'s unknown-index path and the second branch's
     * samples are silently dropped — which does not look like a missing pack in
     * the UI, it looks like HALF the wheel's current and power (parallel,
     * near-identical branches make the voltage and cells look fine).
     *
     * Sized once, at construction time, on purpose: [VehicleConnection] is not
     * thread-safe by design and the sample funnel calls into it from the
     * session's own coroutine, so resizing or rebuilding it mid-session would
     * race with the samples flowing through it.
     */
    private fun connectionPacks(
        vehicle: Vehicle?,
        address: String,
        type: BmsType,
        protocolPackCount: Int
    ): List<Pack> = storedPacks(vehicle, address, type).expandedTo(protocolPackCount)

    /**
     * The packs the profile actually knows about — the vehicle's own list, or
     * a single default slot for a guest. These are published from the first
     * snapshot; the slots [connectionPacks] synthesises beyond them are handed
     * to [VehicleConnection] as LATENT and appear only once they report, so a
     * Begode without a smart BMS (which never fills its second branch) shows
     * one pack instead of a permanently-offline phantom "Pack 2".
     *
     * [type] is the fallback slot's BMS type and may be null: a controller-only
     * vehicle names no BMS at all. Such a vehicle gets NO fallback slot — a
     * fabricated pack would sit at the controller's own address and
     * [planLinks] would then reject the vehicle outright ("conflicting
     * protocol kinds"), since a BMS type can never resolve to a controller
     * kind. Its battery, when the controller derives one, arrives as a derived
     * slot from [planLinkPacks] instead.
     */
    private fun storedPacks(vehicle: Vehicle?, address: String, type: BmsType?): List<Pack> {
        val stored = vehicle?.packs.orEmpty()
        if (stored.isNotEmpty()) return stored
        if (vehicle?.hasControllers == true || type == null) return emptyList()
        return listOf(Pack(index = 0, label = "Battery", bmsType = type, bmsAddress = address))
    }

    // ----- Discovered-pack auto-fill -----

    /** Vehicle id whose extended pack list was already written back. */
    private var lastPersistedPackVehicleId: String? = null

    /**
     * Sibling of [maybePersistCellCount] for the pack list: once a pack the
     * protocol invented has actually produced data, write the extended list
     * back into the saved vehicle so the profile matches the battery on later
     * launches (and the pack cards keep their names).
     *
     * Same terms as the cell-count auto-fill: proof before writing (there, a
     * stable count over consecutive samples; here, the extra pack reporting —
     * a slot that never comes online is a guess and must not reach the
     * database), never for guests or demo, and never a rewrite of what is
     * already stored.
     *
     * INVARIANT: [vd] (from [_activeVehicleData]) is assumed to describe the
     * SAME connection as [_activeVehicle] — nothing here checks it. The
     * guarantee is [doConnect]'s teardown ordering: the previous session is
     * torn down under [sessionLock] BEFORE the new vehicle identity is
     * written, so a snapshot from the old orchestrator can no longer arrive
     * once [_activeVehicle] has moved on. Note the device-switch branch in
     * [doConnect] resets [_activeData] and the ring buffer but deliberately
     * leaves [_activeVehicleData] alone, so this safety rests entirely on
     * that ordering. If it is ever violated, this method appends one
     * vehicle's discovered pack to ANOTHER vehicle's saved profile.
     */
    private suspend fun maybePersistPacks(vd: VehicleData) {
        val discovered = vd.packs
        if (discovered.isEmpty()) return
        val vehicle = _activeVehicle.value ?: return
        if (vehicle.isGuest || vehicle.isDemo) return
        if (discovered.size <= vehicle.packs.size) return
        // Only the slots this vehicle does not know about need proving; the
        // stored ones are user configuration and may legitimately be offline.
        //
        // A slot only counts as DISCOVERED HARDWARE when it sits behind a link
        // the profile already names a pack on — which is exactly what
        // [expandedTo] synthesises, and what this auto-fill has always meant.
        // A slot behind a CONTROLLER link (a VESC's derived battery, see
        // [planLinkPacks]) is computed from the controller's own telemetry,
        // not a battery the profile is missing: persisting it would store a
        // pack at the controller's address whose BmsType can never resolve to
        // the controller's protocol kind, and the NEXT connect's [planLinks]
        // would reject the vehicle outright.
        val extraSlots = discovered.drop(vehicle.packs.size)
            .filter { slot -> vehicle.packs.any { it.bmsAddress == slot.pack.bmsAddress } }
        if (extraSlots.isEmpty()) return
        if (extraSlots.any { !it.isOnline }) return
        if (lastPersistedPackVehicleId == vehicle.id) return
        lastPersistedPackVehicleId = vehicle.id
        // Only APPEND the newly discovered slots. The vehicle's own packs are
        // user configuration plus the cell-count auto-fill, both of which can
        // have changed since the orchestrator was built — taking the
        // orchestrator's copies wholesale would silently revert them.
        val packs = vehicle.packs + extraSlots.map { it.pack }
        println("[VOLTY-BLE] pack auto-fill: ${vehicle.name} -> ${packs.size} packs")
        vehicleRepository.upsert(vehicle.copy(packs = packs))
    }

    /**
     * The orchestrator plus the per-sample funnel [doConnect] wires into a
     * [ConnectionSession]. Built as one unit so the funnel, the channel and
     * the orchestrator are guaranteed to agree on the pack list.
     */
    private class SamplePipeline(
        val orchestrator: VehicleConnection,
        val channel: Channel<Sample>,
        val onSample: (packIndex: Int, sample: BmsData, sections: List<SectionState>) -> Unit
    )

    /**
     * Build the orchestrator, the funnel channel and the onSample enrichment
     * stage for one connection attempt. Shared between [doConnect] and the
     * test seam [installSampleFunnelForTest], so tests exercise the exact
     * production wiring rather than a copy that can drift.
     *
     * [localToGlobal] translates the session's LOCAL pack index (0-based
     * within its own protocol) to the vehicle-global index the orchestrator
     * is keyed by. With a single link the two are identical — the default —
     * so this is only the seam the multi-link fan-out will populate from its
     * [LinkSpec.globalPackIndex].
     */
    private fun buildSamplePipeline(
        vehicle: Vehicle?,
        address: String,
        type: BmsType?,
        protocol: BmsProtocol,
        localToGlobal: (localIndex: Int) -> Int = { it }
    ): SamplePipeline {
        val orchestrator = buildOrchestrator(vehicle, address, type)
        val channel = Channel<Sample>(SAMPLE_FUNNEL_CAPACITY)
        val onSample = makeLinkOnSample(
            protocol = protocol,
            socVehicle = socVehicleFor(vehicle, address, type),
            channel = channel,
            localToGlobal = localToGlobal
        )
        return SamplePipeline(orchestrator, channel, onSample)
    }

    /**
     * The vehicle's pack list expanded PER LINK: each distinct address's
     * stored packs grown to that link's protocol [BmsProtocol.packCount]
     * (a Begode link owns two branch slots, an ANT link one). For a
     * single-address vehicle this is exactly the old
     * `stored.expandedTo(protocol.packCount)`.
     *
     * Includes the DERIVED slot of a controller link that backs a battery of
     * its own — see [planLinkPacks], which is where both this and
     * [effectiveLinkSpecs] come from.
     *
     * Known limit, still deliberate: [expandedTo] synthesises global indices
     * after the LINK's own highest index, which is collision-free while
     * multi-address vehicles only combine single-pack-protocol links (no way
     * to CREATE anything else exists yet). Derived slots do not share that
     * limit — [planLinkPacks] numbers them after every expanded index.
     */
    private fun expandedVehiclePacks(vehicle: Vehicle?, address: String, type: BmsType?): List<Pack> =
        planLinkPacks(vehicle, address, type).flatMap { it.packs }.sortedBy { it.index }

    /**
     * The link plan for one connection, with each link's owned indices grown
     * to its protocol's pack count — [planLinks] only sees the STORED packs,
     * but a Begode session speaks local indices up to packCount - 1, so the
     * link must own the synthesised branch slots too or
     * [LinkSpec.globalPackIndex] could not translate them. The controllers a
     * link owns come straight from [planLinks] — this is what makes
     * [ConnectionSession.onMotionSample] fire at all.
     *
     * Each surviving slot keeps the [OwnedSource] [planLinks] built for it,
     * matched BY GLOBAL INDEX rather than by position. Rebuilding them as a
     * bare `OwnedSource(index)` — as this did until Part C — silently discarded
     * `canId` and `kind`. That was invisible while no pack could carry either,
     * but a gateway's HOSTED battery is exactly such a source: stripped of its
     * `kind = VESC_BMS` tag it is indistinguishable from a controller-derived
     * slot, and [LinkSpec.isGatewayLink] would then answer differently here
     * than it does inside [planLinkPacks] — the protocol that SIZES the pack
     * list and the protocol the session speaks would disagree. Slots that
     * [expandedTo] / the derived-slot pass synthesised have no planned
     * counterpart and stay untagged, which is exactly what they are.
     */
    private fun effectiveLinkSpecs(vehicle: Vehicle?, address: String, type: BmsType?): List<LinkSpec> =
        planLinkPacks(vehicle, address, type).map { (spec, packs) ->
            val planned = spec.ownedPacks.associateBy { it.globalIndex }
            spec.copy(ownedPacks = packs.map { planned[it.index] ?: OwnedSource(it.index) })
        }

    /** One link's spec paired with the pack slots it is responsible for. */
    private data class LinkPacks(val spec: LinkSpec, val packs: List<Pack>)

    /**
     * THE single source of truth for "which links does this vehicle have and
     * which pack slots does each own" — [expandedVehiclePacks] and
     * [effectiveLinkSpecs] are both projections of it, so the orchestrator's
     * slots and the links' index translation can never drift apart.
     *
     * Planning runs over the vehicle's packs AND its controllers, so a
     * controller shares its address's link with the packs behind it (a Begode
     * multiplexes both over one address) or raises its own (a VESC beside a
     * separate BMS).
     *
     * Two passes, because pack indices are vehicle-global and must stay unique:
     *  1. every link that owns STORED packs grows them to its protocol's
     *     [BmsProtocol.packCount] — bit-for-bit the pre-controller behaviour;
     *  2. a link with NO stored pack behind it but whose protocol still backs
     *     one (a VESC with `providesDerivedBattery`) gets freshly numbered
     *     DERIVED slots, allocated after every index pass 1 produced so they
     *     can never collide with an [expandedTo] slot. They reach
     *     [VehicleConnection] as latent slots and materialise on their first
     *     sample, exactly like a Begode's second branch.
     */
    private fun planLinkPacks(vehicle: Vehicle?, address: String, type: BmsType?): List<LinkPacks> {
        val stored = storedPacks(vehicle, address, type)
        val specs = planLinks(stored, vehicle?.controllers ?: emptyList())
        val counts = specs.map { createProtocol(it, vehicle).packCount }
        val sized = specs.mapIndexed { i, spec ->
            LinkPacks(
                spec = spec,
                packs = stored.filter { it.bmsAddress == spec.address }
                    .sortedBy { it.index }
                    .expandedTo(counts[i])
            )
        }
        var nextDerivedIndex = (sized.flatMap { it.packs }.maxOfOrNull { it.index } ?: -1) + 1
        return sized.mapIndexed { i, linkPacks ->
            if (linkPacks.packs.isNotEmpty() || counts[i] == 0) return@mapIndexed linkPacks
            linkPacks.copy(
                packs = List(counts[i]) {
                    val index = nextDerivedIndex++
                    Pack(
                        index = index,
                        label = if (index == 0) "Battery" else "Pack ${index + 1}",
                        // The controller IS the battery source for a derived
                        // pack; VESC_BMS is the closest honest label. It is
                        // never persisted (see [maybePersistPacks]) so it can
                        // never be fed back into [planLinks].
                        bmsType = BmsType.VESC_BMS,
                        bmsAddress = linkPacks.spec.address
                    )
                }
            )
        }
    }

    /**
     * The vehicle the SoC estimator sees: the SAME expanded pack list the
     * orchestrator is sized from. The stored vehicle can know fewer packs
     * than the protocols do (a one-pack Begode profile vs. two branches):
     * handed the stored list, the estimator's per-pack lookup misses for
     * the synthesised slot, that branch's Begode sample keeps its reported
     * soc = 0, and the parallel aggregate — a plain mean when no pack
     * reports capacity — HALVES the wheel's state of charge, feeding the
     * same halved value into the SOC_LOW / SOC_CUTOFF alerts.
     */
    private fun socVehicleFor(vehicle: Vehicle?, address: String, type: BmsType?): Vehicle? =
        vehicle?.copy(packs = expandedVehiclePacks(vehicle, address, type))

    /**
     * Build the ONE orchestrator of a connection, sized from the full vehicle
     * pack list ([expandedVehiclePacks]) regardless of how many links feed it.
     */
    private fun buildOrchestrator(vehicle: Vehicle?, address: String, type: BmsType?): VehicleConnection {
        val stored = storedPacks(vehicle, address, type)
        val expanded = expandedVehiclePacks(vehicle, address, type)
        return VehicleConnection(
            packs = stored,
            // Protocol-synthesised slots stay invisible until they report:
            // a Begode without a smart BMS never fills its second branch,
            // and an eager slot would be a permanently-offline phantom.
            latentPacks = expanded.filter { ep -> stored.none { it.index == ep.index } },
            // The vehicle's controllers, so submitMotion has a slot to route
            // into. Named arg: two defaulted params sit before `topology`
            // (latentControllers / the controller list) so positional would
            // bind the wrong slot. No latent controllers in Part A — nothing
            // synthesises controller slots beyond the stored profile yet.
            controllers = vehicle?.controllers ?: emptyList(),
            topology = vehicle?.topology ?: PackTopology.PARALLEL,
            onVehicleData = { vd ->
                _activeVehicleData.value = vd
                // The motion aggregate rides the same snapshot; publish it on
                // the motion StateFlow beside the vehicle-level one.
                _activeMotion.value = vd.motion
            },
            clock = orchestratorClockForTest ?: { Clock.System.now() }
        )
    }

    /**
     * The per-link enrichment stage: everything that must run on the SESSION
     * side of the channel because it depends on this link's protocol state.
     * Each connect attempt builds a fresh one around its own protocol
     * instance and the connection's CURRENT channel; [localToGlobal] is the
     * link's [LinkSpec.globalPackIndex] (identity for a single link).
     */
    private fun makeLinkOnSample(
        protocol: BmsProtocol,
        socVehicle: Vehicle?,
        channel: Channel<Sample>,
        localToGlobal: (localIndex: Int) -> Int
    ): (Int, BmsData, List<SectionState>) -> Unit =
        { localIndex, sample, sections ->
            // Translate to the vehicle-global index FIRST: the scaler and the
            // estimator both look the pack up by its global index in the
            // expanded vehicle, and the orchestrator downstream of the
            // channel is keyed by it too.
            val packIndex = localToGlobal(localIndex)
            // A Begode without a smart BMS publishes its synthetic pack with
            // voltage = 0 and offers the live-frame reading (on the 67.2 V
            // scale) separately — the protocol cannot scale it because the
            // multiplier needs a cell count it must not invent. This is the
            // one place where the sample meets the vehicle profile, whose
            // cell count is user-set or auto-filled from a prior smart-BMS
            // connect — so the scaling lands here, before SoC estimation.
            // When no cell count is known the voltage honestly stays 0
            // ("unknown") rather than reading 59 V on a 168 V pack.
            val scaled = withScaledBegodeLiveVoltage(protocol, packIndex, sample, socVehicle)
            // Devices that report no SoC at all (a Begode wheel gives
            // voltage and cells only) get one estimated from average
            // cell voltage against the vehicle's configured cell-voltage
            // bounds — BEFORE aggregation, so per-pack SoC maths sees
            // it. The estimator keys on the PACK's BmsType (packIndex,
            // not the vehicle-level shortcut — a future BMS group can
            // mix types): a coulomb-counting BMS's sample passes
            // through untouched, including a genuine 0 % on a flat
            // pack (see VoltageSocEstimator).
            val enriched = VoltageSocEstimator.withEstimatedSoc(scaled, socVehicle, packIndex)
            // Enrichment ends the per-link work: it depends on THIS link's
            // protocol state, so it must run here on the session's own
            // coroutine. Everything that mutates shared state crosses the
            // channel to the single consumer (see [launchSampleConsumer]).
            // trySend, not send — onSample is not suspending, and the buffer
            // is sized so a drop can only mean a closed (torn-down) funnel
            // or a stopped consumer; either way it must be visible.
            val sent = channel.trySend(PackSample(packIndex, enriched, sections))
            if (sent.isFailure) {
                println("[VOLTY-BLE] sample funnel: dropped sample for pack=$packIndex (channel closed or full)")
            }
        }

    /**
     * The motion twin of [makeLinkOnSample]: translate the session's LOCAL
     * controller index to the vehicle-global one (through this link's
     * [LinkSpec.ownedControllers]) and funnel it as a [MotionSample] into the
     * SAME channel, so battery and motion cross one serialisation barrier.
     *
     * Fires only when the link's protocol is a
     * [ru.sodovaya.volty.data.bms.MotionSource] — a battery-only link's
     * `ownedControllers` is empty and this is never invoked for it. Factored
     * out for the same reason [makeLinkOnSample] is: the test seam drives the
     * production lambda rather than a copy that can drift.
     */
    private fun makeLinkOnMotionSample(
        spec: LinkSpec,
        channel: Channel<Sample>
    ): (controllerIndex: Int, data: ControllerData) -> Unit =
        { localCtrlIndex, data ->
            val sent = channel.trySend(
                MotionSample(spec.globalControllerIndex(localCtrlIndex), data)
            )
            if (sent.isFailure) {
                println("[VOLTY-BLE] motion funnel: dropped sample for controller=$localCtrlIndex (channel closed or full)")
            }
        }

    /**
     * Launch the single consumer coroutine that owns every shared-state
     * mutation of one connection: [VehicleConnection.submit], the ring
     * buffer, [_activeData]. N session coroutines funnel their enriched
     * samples into [channel]; because one coroutine drains it, no two
     * submits can ever overlap — [VehicleConnection] keeps its
     * single-threaded-by-construction invariant without growing a lock.
     *
     * [Dispatchers.Unconfined] + [CoroutineStart.UNDISPATCHED] on purpose:
     * the consumer resumes INLINE on the sender's thread, so with a single
     * link the submit → ring buffer → _activeData sequence still executes
     * synchronously inside the session's onSample call, on the same thread,
     * exactly as before the channel existed — behaviour-identical, no added
     * latency, and the single-threaded test seam observes the effects
     * immediately. This is safe because the body suspends ONLY at the
     * channel receive and never touches [sessionLock]; and it stays a true
     * serialisation barrier because a coroutine is sequential regardless of
     * where it resumes — a second sender's trySend while the consumer is
     * mid-drain just buffers, with the channel providing the happens-before
     * edge between the threads.
     */
    private fun launchSampleConsumer(channel: Channel<Sample>): Job =
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            for (sample in channel) {
                when (sample) {
                    is PackSample -> {
                        // The aggregate is a true identity for a single pack
                        // (cell voltages included), so every vehicle routes
                        // through it. submit() returns the snapshot it just
                        // emitted, so the aggregate is built once per sample.
                        // Fall back to the enriched sample — not the raw one — if
                        // the orchestrator was swapped out mid-flight, so a Begode
                        // keeps its estimated SoC even on that path. The section
                        // breakdown rides beside the sample into the pack state; the
                        // vehicle-level aggregate has no section field, so nothing of
                        // it survives the fallback path — dropped, not misattributed.
                        val forActive = vehicleConnection
                            ?.submit(sample.globalPackIndex, sample.data, sample.sections)
                            ?.aggregate
                            ?: sample.data
                        // Ring buffer before activeData: the graph collector maps
                        // over _activeData and reads the buffer, so announcing the
                        // sample first would make every graph emit lag by one.
                        ringBuffer.push(forActive)
                        _activeData.value = forActive
                    }
                    is MotionSample -> {
                        // Motion twin of the battery branch: route into the
                        // orchestrator's controller state and retain it. The
                        // orchestrator publishes the fresh motion aggregate
                        // through onVehicleData, which sets _activeMotion — so
                        // nothing sets it here (mirrors how _activeData rides
                        // off the battery submit's snapshot, not a direct write).
                        vehicleConnection?.submitMotion(sample.globalControllerIndex, sample.data)
                        motionRingBuffer.push(sample.data)
                    }
                }
            }
        }

    /**
     * Close the sample funnel and forget it. The consumer drains whatever
     * the channel still buffers and its job then completes on its own —
     * [Channel.close] does not suspend and the consumer never takes
     * [sessionLock], so calling this inside a critical section cannot
     * deadlock teardown.
     *
     * PRECONDITION: caller holds [sessionLock] — the funnel fields share the
     * [vehicleConnection] locking discipline, including its one unlocked
     * test-seam exception ([installProtocolPipelineForTest]).
     */
    private fun closeSampleFunnelLocked() {
        sampleChannel?.close()
        sampleChannel = null
        sampleConsumerJob = null
    }

    /**
     * Scale the synthetic no-BMS Begode pack's live-frame voltage to real
     * pack volts, when — and only when — the protocol is currently offering
     * one ([BegodeProtocol.liveVoltageOn672ScaleV] is non-null exactly while
     * the synthetic pack is active) and this pack's profile knows its cell
     * count. Every other sample passes through untouched: a smart-BMS branch
     * sample (the protocol retires the live voltage on the first BMS frame),
     * any non-Begode protocol, and the synthetic pack of a profile with no
     * cell count — for that last one the voltage stays at the protocol's 0
     * ("unknown"), which downstream renders as no voltage instead of the raw
     * 58.92 V reading masquerading as pack volts.
     *
     * Same single-funnel guarantee as the sections read in
     * [routePackSamples]: this runs on the session's own coroutine right
     * after the decode, so the protocol state it reads and the sample it
     * scales describe one moment.
     */
    private fun withScaledBegodeLiveVoltage(
        protocol: BmsProtocol,
        packIndex: Int,
        sample: BmsData,
        socVehicle: Vehicle?
    ): BmsData {
        if (protocol !is BegodeProtocol || packIndex != 0) return sample
        val liveV = protocol.liveVoltageOn672ScaleV() ?: return sample
        val cellCount = socVehicle?.packs?.firstOrNull { it.index == packIndex }?.cellCount
            ?: return sample
        if (cellCount <= 0) return sample
        val voltage = BegodeProtocol.scaleLiveVoltage(liveV, cellCount)
        // Power is voltage-derived and was equally unknown in the sample.
        return sample.copy(voltage = voltage, power = voltage * sample.current)
    }

    /**
     * Failure-path cleanup for [doConnect]: drop the orchestrator installed by
     * the failed attempt and reset the vehicle-level flow, so the field's
     * "null when nothing is connected" contract holds and a failed connect
     * never leaves the previous vehicle's packs published. Identity-guarded:
     * a concurrent connect that already installed its own orchestrator is
     * left alone.
     *
     * PRECONDITION: the caller holds [sessionLock]. The identity check and the
     * write must be one critical section — otherwise a competing attempt can
     * install its orchestrator between them and have it wiped. [sessionLock]
     * is not reentrant, so this helper must never take it; callers that do not
     * already hold it go through [clearOrchestratorAfterFailure].
     */
    private fun clearOrchestratorLocked(installed: VehicleConnection?) {
        if (installed == null) return
        if (vehicleConnection === installed) {
            vehicleConnection = null
            _activeVehicleData.value = VehicleData()
            // The funnel was installed in the same critical section as the
            // orchestrator, so it belongs to the same failed attempt — the
            // identity guard above covers both.
            closeSampleFunnelLocked()
        }
    }

    /**
     * [clearOrchestratorLocked] for callers that do NOT hold [sessionLock].
     * Every [doConnect] failure path except the prep-abort one (which already
     * runs inside a locked section) uses this.
     */
    private suspend fun clearOrchestratorAfterFailure(installed: VehicleConnection?) {
        if (installed == null) return
        sessionLock.withLock { clearOrchestratorLocked(installed) }
    }

    // ----- Link state fold -----

    /**
     * Move one link to [status] and refold the vehicle's [ConnectionState] —
     * THE one place a link status becomes a vehicle state. The fold:
     *
     *  - any link ONLINE → [ConnectionState.Connected];
     *  - none online, any CONNECTING → [ConnectionState.Connecting];
     *  - all down, any RECONNECTING → [ConnectionState.Reconnecting] with the
     *    first retrying link's attempt / reason;
     *  - all FAILED → [ConnectionState.Failed] with the first link's reason.
     *
     * A single-link vehicle degenerates to exactly the pre-multi-link state
     * machine: its one link's transitions produce the same writes, in the
     * same order, that used to be inlined at each call site.
     *
     * The fold only fires while [link] is still installed — a straggler
     * transition on a superseded link (an old loop outliving a new connect,
     * a late drop report after [disconnect]) must never clobber the state the
     * current owner wrote.
     *
     * @return true when this transition moved the vehicle INTO Connected —
     * the moment the old code ran its `Connected → serviceStart → touch`
     * tail, so a link coming online behind an already-Connected vehicle does
     * not restart the service.
     */
    private fun setLinkState(
        link: PackLink,
        status: LinkStatus,
        attempt: Int = 0,
        reason: String? = null
    ): Boolean = synchronized(linkStateLock) {
        link.status = status
        link.reconnectAttempt = attempt
        if (reason != null) link.lastReason = reason
        val current = links
        if (current.none { it === link }) return@synchronized false
        refoldConnectionStateLocked(current)
    }

    /**
     * The fold body itself, factored out of [setLinkState] so a structural
     * change to the link list — [disconnectLink] dropping one link — can
     * recompute [ConnectionState] from the surviving links without
     * hand-rolling a second copy of the fold. [current] must be non-empty
     * (an empty link list is the "nothing connected" state, which
     * [disconnectLink] handles by degenerating to [disconnect] instead of
     * calling this).
     *
     * PRECONDITION: caller holds [linkStateLock] — the fold must never read a
     * half-updated link, exactly as [setLinkState]'s callers require.
     *
     * @return true when this recompute moved the vehicle INTO Connected.
     */
    private fun refoldConnectionStateLocked(current: List<PackLink>): Boolean {
        val wasConnected = _connectionState.value is ConnectionState.Connected
        val vehicle = current.first().vehicle
        _connectionState.value = when {
            current.any { it.status == LinkStatus.ONLINE } ->
                ConnectionState.Connected(vehicle)
            current.any { it.status == LinkStatus.CONNECTING } ->
                ConnectionState.Connecting(vehicle)
            current.any { it.status == LinkStatus.RECONNECTING } ->
                current.first { it.status == LinkStatus.RECONNECTING }
                    .let { ConnectionState.Reconnecting(it.reconnectAttempt, it.lastReason) }
            else ->
                ConnectionState.Failed(current.first().lastReason.ifEmpty { "Connection failed" })
        }
        return !wasConnected && _connectionState.value is ConnectionState.Connected
    }

    // ----- Connect: one vehicle, N links -----

    private suspend fun doConnect(address: String, type: BmsType?, vehicle: Vehicle?): Result<Unit> {
        println("[VOLTY-BLE] doConnect: starting addr=$address type=$type vehicle=${vehicle?.name}")
        // Tracks the orchestrator THIS attempt installed, so every failure
        // path (including the catch-all below) can undo exactly its own
        // installation and nothing else's.
        var installedOrchestrator: VehicleConnection? = null
        return try {
            // User-initiated entry — clear the disconnect flag and tear down
            // every existing link's session under the lock so a concurrent
            // disconnect() sees a consistent view. The outgoing links'
            // reconnect loops are deliberately NOT cancelled here (the old
            // code could not cancel its single loop either — this method may
            // be reached from a stack the loop owns); they stop on their own
            // via the link-identity guard once the new list is installed.
            sessionLock.withLock {
                userInitiatedDisconnect = false
                for (l in links) {
                    l.session?.tearDown()
                    l.session = null
                }
                // Connecting to a real BMS kills any running demo simulation.
                demoJob?.cancel()
                demoJob = null
            }
            // Connecting to a DIFFERENT device (picker switch without an explicit
            // disconnect, or a real connect right after demo) must not mix the
            // previous battery's samples into the new graph. Reconnects to the
            // same address keep the buffer so the graph survives link drops.
            // primaryAddress, the same identity [connect] hands us as
            // [address] — and the only one that does not throw when the
            // previously active vehicle stores no packs.
            val previousAddress = _activeVehicle.value?.primaryAddress
            if (previousAddress != null && previousAddress != address) {
                ringBuffer.clear()
                _activeData.value = BmsData()
                // Same reasoning on the motion side: a different device's
                // motion must not bleed into the new vehicle's graph / flow.
                motionRingBuffer.clear()
                _activeMotion.value = ControllerData()
                // Same reasoning on the vehicle-level snapshot: without this,
                // a stale demo (or previous real vehicle's) packs/aggregate
                // survive into the new connection and RideDashboardComponent's
                // BATTERY tile shows the WRONG vehicle's SoC/voltage until the
                // new vehicle's first sample overwrites it. Reachable via the
                // Picker's "+ Add battery" from a live demo session, which
                // connects without disconnecting first.
                _activeVehicleData.value = VehicleData()
            }
            // Initial state, written directly: the links are not installed yet,
            // so the fold cannot own this first transition. From installation
            // on, every state write goes through [setLinkState].
            _connectionState.value = ConnectionState.Connecting(vehicle)
            _activeVehicle.value = vehicle

            val specs = effectiveLinkSpecs(vehicle, address, type)
            val newLinks = specs.map { PackLink(spec = it, vehicle = vehicle) }
            val orchestrator = buildOrchestrator(vehicle, address, type)
            installedOrchestrator = orchestrator
            val channel = Channel<Sample>(SAMPLE_FUNNEL_CAPACITY)
            // Install under the lock: the failure cleanup below is an
            // identity-guarded check-then-write, and it can only be safe if
            // the competing writer — a concurrent connect / reconnect attempt
            // installing ITS orchestrator — is serialised against it. The
            // funnel and the link list are installed in the SAME critical
            // section (replacing the previous connection's, whose consumer
            // ends when its channel closes) so orchestrator, funnel and links
            // always belong to the same attempt; launching inside the section
            // is fine — the consumer runs undispatched only to its first
            // channel receive and never takes the lock.
            sessionLock.withLock {
                vehicleConnection = orchestrator
                closeSampleFunnelLocked()
                sampleChannel = channel
                sampleConsumerJob = launchSampleConsumer(channel)
                links = newLinks
                lastConnectionTarget = ConnectionTarget(vehicle, address, type)
            }

            // Raise the links. A single link runs INLINE in this coroutine —
            // the exact pre-multi-link call shape, preserving its cancellation
            // and ordering behaviour to the byte. Several links run
            // concurrently, each started linkStaggerMs after the previous
            // (simultaneous GATT connects are flaky on Android), inside
            // coroutineScope so a superseding connect / disconnect cancelling
            // THIS coroutine takes the whole fan-out down with it.
            val results: List<Result<Unit>> =
                if (newLinks.size == 1) {
                    listOf(connectLinkAttempt(newLinks[0], isReconnectAttempt = false))
                } else {
                    coroutineScope {
                        newLinks.mapIndexed { i, link ->
                            async {
                                if (i > 0) delay(i * BleConfig.linkStaggerMs)
                                connectLinkAttempt(link, isReconnectAttempt = false)
                            }
                        }.awaitAll()
                    }
                }

            if (newLinks.none { it.status == LinkStatus.ONLINE }) {
                // Every link failed (or the connect was aborted): the fold has
                // already published Failed / left Connecting for disconnect()
                // to overwrite — mirror the old failure tail: drop what this
                // attempt installed and report the first error.
                val firstFailure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?: IllegalStateException("Connection failed")
                clearOrchestratorAfterFailure(orchestrator)
                return Result.failure(firstFailure)
            }
            // Partial initial connect: at least one link answered, so the
            // vehicle is Connected (the fold already says so). The links that
            // did not answer are pushed by their own background loops instead
            // of failing the whole connect — no "wait for all".
            startRetriesForMissingLinks(newLinks)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Deliberately NO orchestrator cleanup here: cancellation means a
            // newer connect / disconnect superseded this attempt and owns the
            // field now; clearing from a late-running cancelled coroutine
            // could clobber the successor's freshly installed orchestrator.
            throw e
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(e.message ?: "Connection failed")
            clearOrchestratorAfterFailure(installedOrchestrator)
            Result.failure(e)
        }
    }

    /**
     * One connect attempt for ONE link — the per-address body of what
     * doConnect used to inline for its single session, preserved
     * transition-for-transition (state writes now via [setLinkState], whose
     * single-link fold produces the identical sequence): resolve the
     * advertisement, raise the session, install it under [sessionLock] with
     * the userInitiatedDisconnect prep check, connect, re-check the flag
     * after the suspension, then Connected → serviceStart → touch.
     *
     * [isReconnectAttempt] gates the pipeline rebuild: every pre-multi-link
     * reconnect attempt rebuilt the orchestrator and funnel from scratch, and
     * the degenerate single-link path must keep doing so — but only when NO
     * sibling link is up or mid-attempt, because a live sibling is actively
     * feeding the current pipeline and replacing it would orphan that link's
     * channel. Initial attempts never rebuild: doConnect just installed the
     * pipeline they feed.
     */
    private suspend fun connectLinkAttempt(
        link: PackLink,
        isReconnectAttempt: Boolean
    ): Result<Unit> {
        val vehicle = link.vehicle
        val address = link.spec.address
        // NOT `protocolKind.toBmsType()`: that THROWS for a controller kind by
        // design. Downstream this value is only the guest fallback's pack
        // template, which a controller link never needs.
        val type = link.spec.protocolKind.batteryBmsTypeOrNull()
        // Tracks the orchestrator THIS attempt installed (rebuild path only),
        // so every failure path can undo exactly its own installation.
        var installedOrchestrator: VehicleConnection? = null
        return try {
            // Tear down this link's previous (dead) session under the lock,
            // as the old doConnect preamble did for the single session.
            sessionLock.withLock {
                link.session?.tearDown()
                link.session = null
            }
            setLinkState(link, LinkStatus.CONNECTING)
            // The protocol instance is shared by the enrichment funnel and
            // the session — both must read the same decode state. Plain
            // object, no I/O; constructing it this early cannot fail. Built
            // from the SPEC (not a bare BmsType) so a controller link gets its
            // controller's own VescProtocol — a MotionSource, which is what
            // makes ConnectionSession.onMotionSample fire.
            val protocol = createProtocol(link.spec, vehicle)
            val channel: Channel<Sample> = sessionLock.withLock {
                if (links.none { it === link }) {
                    return Result.failure(IllegalStateException("Link superseded"))
                }
                if (isReconnectAttempt && links.none {
                        it !== link &&
                            (it.status == LinkStatus.ONLINE ||
                                it.status == LinkStatus.CONNECTING ||
                                it.session != null)
                    }
                ) {
                    installedOrchestrator = rebuildPipelineLocked(vehicle, address, type)
                }
                sampleChannel
                    ?: return Result.failure(IllegalStateException("No sample funnel"))
            }
            val onSample = makeLinkOnSample(
                protocol = protocol,
                socVehicle = socVehicleFor(vehicle, address, type),
                channel = channel,
                // THIS is where the local→global seam is populated: the
                // session speaks indices local to its protocol, the shared
                // funnel is keyed by the vehicle's global pack indices.
                localToGlobal = link.spec::globalPackIndex
            )

            val advertisement = resolveAdvertisement(address)
            if (advertisement == null) {
                setLinkState(link, LinkStatus.FAILED, reason = "Device not found")
                clearOrchestratorAfterFailure(installedOrchestrator)
                return Result.failure(IllegalStateException("Device not found"))
            }

            val peripheral = Peripheral(advertisement)
            val session = ConnectionSession(
                parentScope = scope,
                peripheral = peripheral,
                protocol = protocol,
                vehicle = vehicle,
                connectionState = _connectionState,
                onSample = onSample,
                onMotionSample = makeLinkOnMotionSample(link.spec, channel),
                onDropDetected = { reason ->
                    // The session detected a drop. Schedule THIS link's
                    // reconnect — unless the user disconnected in the meantime.
                    onLinkDrop(link, reason)
                }
            )

            // Install the session under the lock so a racing disconnect()
            // can't see a partially-set state.
            sessionLock.withLock {
                if (userInitiatedDisconnect) {
                    // Someone called disconnect() while we were preparing —
                    // honour it: don't even attempt to bring the link up.
                    println("[VOLTY-BLE] link $address: aborted, userInitiatedDisconnect set during prep")
                    // Already inside sessionLock — use the locked variant, the
                    // mutex is not reentrant.
                    clearOrchestratorLocked(installedOrchestrator)
                    return Result.failure(IllegalStateException("Disconnect requested"))
                }
                if (links.none { it === link }) {
                    clearOrchestratorLocked(installedOrchestrator)
                    return Result.failure(IllegalStateException("Link superseded"))
                }
                link.session = session
            }

            val connectResult = session.connect()
            if (connectResult.isFailure) {
                val err = connectResult.exceptionOrNull()?.message ?: "Connection failed"
                setLinkState(link, LinkStatus.FAILED, reason = err)
                println("[VOLTY-BLE] link $address: $err")
                sessionLock.withLock {
                    if (link.session === session) link.session = null
                }
                session.tearDown()
                clearOrchestratorAfterFailure(installedOrchestrator)
                return Result.failure(IllegalStateException(err))
            }

            // Re-check after suspension: did the user disconnect while we were
            // mid-handshake?
            val shouldAbort = sessionLock.withLock {
                if (userInitiatedDisconnect) {
                    println("[VOLTY-BLE] link $address: post-connect, disconnect was requested — tearing down")
                    if (link.session === session) link.session = null
                    true
                } else false
            }
            if (shouldAbort) {
                session.tearDown()
                clearOrchestratorAfterFailure(installedOrchestrator)
                return Result.failure(IllegalStateException("Disconnect requested"))
            }

            val becameConnected = setLinkState(link, LinkStatus.ONLINE)
            println("[VOLTY-BLE] link $address up (${vehicle?.name ?: "guest"})")
            if (becameConnected) {
                serviceStart()
                // Guests are transient — never write them to the saved-vehicle store.
                if (vehicle != null && !vehicle.isGuest) vehicleRepository.touch(vehicle.id)
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Deliberately NO cleanup here — same contract as doConnect's.
            throw e
        } catch (e: Exception) {
            setLinkState(link, LinkStatus.FAILED, reason = e.message ?: "Connection failed")
            clearOrchestratorAfterFailure(installedOrchestrator)
            Result.failure(e)
        }
    }

    /**
     * Replace the orchestrator + funnel with fresh ones — what every
     * pre-multi-link reconnect attempt did by re-running doConnect. Called
     * only from [connectLinkAttempt] when no sibling link is feeding the
     * current pipeline.
     *
     * PRECONDITION: caller holds [sessionLock] — this is the same install
     * block doConnect runs, minus the link list (the links persist across
     * their attempts).
     */
    private fun rebuildPipelineLocked(vehicle: Vehicle?, address: String, type: BmsType?): VehicleConnection {
        val orchestrator = buildOrchestrator(vehicle, address, type)
        val channel = Channel<Sample>(SAMPLE_FUNNEL_CAPACITY)
        vehicleConnection = orchestrator
        closeSampleFunnelLocked()
        sampleChannel = channel
        sampleConsumerJob = launchSampleConsumer(channel)
        return orchestrator
    }

    /**
     * The initial-partial tail of [doConnect]: at least one link is online,
     * so the links that failed their first attempt are handed to their own
     * background reconnect loops instead of failing the connect. Never
     * called when the whole connect failed — that path keeps the old
     * "Failed, no retry" semantics a single-link vehicle always had.
     */
    private fun startRetriesForMissingLinks(newLinks: List<PackLink>) {
        if (userInitiatedDisconnect) return
        for (l in newLinks) {
            if (l.status != LinkStatus.FAILED) continue
            val reason = l.lastReason.ifEmpty { "Initial connect failed" }
            setLinkState(l, LinkStatus.RECONNECTING, attempt = 0, reason = reason)
            startLinkReconnectLoop(l, initialReason = reason)
        }
    }

    private suspend fun resolveAdvertisement(address: String): com.juul.kable.Advertisement? {
        val cached = cachedAdvertisement(address)
        if (cached != null) return cached
        val found = withTimeoutOrNull(BleConfig.advertisementSearchMs) {
            Scanner().advertisements.first { it.identifier.toString() == address }
        }
        if (found != null) cacheAdvertisement(address, found)
        return found
    }

    private suspend fun onLinkDrop(link: PackLink, reason: String) {
        // Suspending check + state mutation under the lock so user disconnect
        // racing with a watchdog can't both win.
        sessionLock.withLock {
            if (userInitiatedDisconnect) {
                println("[VOLTY-BLE] onLinkDrop ignored — user disconnected")
                return
            }
        }
        // React only while THIS link is still installed and believed up — the
        // per-link translation of the old Connected/Connecting guard, which
        // also dedups a double drop report (state event + watchdog firing for
        // the same death). A sibling link's state is irrelevant here: its
        // packs, its loop, its business.
        val believedUp = synchronized(linkStateLock) {
            links.any { it === link } &&
                (link.status == LinkStatus.ONLINE || link.status == LinkStatus.CONNECTING)
        }
        if (believedUp) {
            // Leave the up state BEFORE starting the loop — its "already
            // online" guard would otherwise short-circuit on the very first
            // iteration and the link would never come back. The dead session
            // is NOT torn down here: onDropDetected is invoked from inside
            // the session's own state/watchdog jobs, and tearDown()
            // cancelAndJoin-ing the calling job would deadlock. The next
            // attempt in the loop tears it down safely. The fold keeps the
            // vehicle Connected while any sibling is still up.
            setLinkState(link, LinkStatus.RECONNECTING, attempt = 0, reason = reason)
            startLinkReconnectLoop(link, initialReason = reason)
        }
    }

    private fun startLinkReconnectLoop(link: PackLink, initialReason: String) {
        val address = link.spec.address
        link.reconnectJob?.cancel()
        println("[VOLTY-BLE] reconnect loop[$address]: starting reason=$initialReason")
        link.reconnectJob = scope.launch {
            var attempt = 0
            while (isActive) {
                // Honour user-initiated disconnect, vehicle clearance,
                // supersession by a newer connect, and "already online by
                // some other path".
                if (userInitiatedDisconnect) {
                    println("[VOLTY-BLE] reconnect loop[$address]: userInitiatedDisconnect — stopping")
                    return@launch
                }
                if (_activeVehicle.value == null && link.vehicle != null) {
                    println("[VOLTY-BLE] reconnect loop[$address]: vehicle cleared — stopping")
                    return@launch
                }
                if (links.none { it === link }) {
                    println("[VOLTY-BLE] reconnect loop[$address]: link superseded — stopping")
                    return@launch
                }
                if (link.status == LinkStatus.ONLINE) {
                    println("[VOLTY-BLE] reconnect loop[$address]: already online — stopping")
                    return@launch
                }
                attempt++
                println("[VOLTY-BLE] reconnect loop[$address]: attempt #$attempt")
                val result = connectLinkAttempt(link, isReconnectAttempt = true)
                if (result.isSuccess) {
                    println("[VOLTY-BLE] reconnect loop[$address]: attempt #$attempt succeeded")
                    return@launch
                }
                if (userInitiatedDisconnect) {
                    println("[VOLTY-BLE] reconnect loop[$address]: disconnect requested mid-attempt — stopping")
                    return@launch
                }
                println("[VOLTY-BLE] reconnect loop[$address]: attempt #$attempt failed — ${result.exceptionOrNull()?.message}")
                // Settle into Reconnecting BETWEEN attempts so the UI sees a
                // stable "trying again, attempt #N" message instead of the
                // Connecting → Failed flicker the attempt emits internally
                // (the fold masks that flicker entirely while a sibling link
                // keeps the vehicle Connected).
                setLinkState(link, LinkStatus.RECONNECTING, attempt = attempt, reason = initialReason)
                val delayMs = if (attempt < BleConfig.reconnectBackoffAfter)
                    BleConfig.reconnectDelayMs
                else
                    BleConfig.reconnectDelayAfter10Ms
                delay(delayMs)
            }
        }
    }

    override suspend fun disconnect() {
        // Atomically: flag the intent, take every link (sessions + reconnect
        // loops) out of the shared state, clear vehicle, set Disconnected.
        // Held under sessionLock so doConnect / onLinkDrop running on another
        // coroutine see this. The lock is taken ONCE for the whole sweep —
        // never nested, it is not reentrant.
        val sessionsToTear: List<ConnectionSession>
        val reconnectsToCancel: List<Job>
        val demoToCancel: Job?
        sessionLock.withLock {
            userInitiatedDisconnect = true
            val current = links
            sessionsToTear = current.mapNotNull { it.session }
            reconnectsToCancel = current.mapNotNull { it.reconnectJob }
            for (l in current) {
                l.session = null
                l.reconnectJob = null
            }
            links = emptyList()
            demoToCancel = demoJob
            demoJob = null
            // Forget the target so a later [onAppResumed] doesn't try to
            // resurrect a connection the user explicitly closed.
            lastConnectionTarget = null
        }
        reconnectsToCancel.forEach { it.cancel() }
        demoToCancel?.cancel()
        sessionsToTear.forEach { it.tearDown() }
        _activeData.value = BmsData()
        _activeVehicleData.value = VehicleData()
        _activeMotion.value = ControllerData()
        // Fresh acquisition: the block above has already released the lock,
        // and tearDown() must not run while holding it. The funnel closes
        // beside the orchestrator: every session was torn down (observe loops
        // joined) above, so no sample can hit the closed channel, and closing
        // does not suspend — teardown cannot deadlock here.
        sessionLock.withLock {
            vehicleConnection = null
            closeSampleFunnelLocked()
        }
        _activeVehicle.value = null
        ringBuffer.clear()
        motionRingBuffer.clear()
        // Direct write, not the fold: the link list is empty (the fold no
        // longer owns the state) and any straggler link transition is
        // identity-guarded against the emptied list, so nothing can clobber
        // this.
        _connectionState.value = ConnectionState.Disconnected
        serviceStop()
    }

    /**
     * The three outcomes [disconnectLink]'s lookup can reach, decided inside
     * one [sessionLock] critical section and acted on AFTER it releases —
     * [ConnectionSession.tearDown] is suspending and, like every other
     * teardown in this class, must never run while the lock is held.
     */
    private sealed interface LinkRemoval {
        /** No link at that address is installed — [disconnectLink] no-ops. */
        object NotFound : LinkRemoval

        /**
         * The address named the vehicle's ONLY link — degenerate to the full
         * [disconnect] sweep rather than leaving the repository half torn
         * down (funnel/orchestrator/ring buffers still holding a connection
         * with zero links behind it).
         */
        object LastLink : LinkRemoval

        /** The link was removed from [links]; [remaining] feeds the fold. */
        data class Removed(
            val session: ConnectionSession?,
            val reconnectJob: Job?,
            val remaining: List<PackLink>
        ) : LinkRemoval
    }

    override suspend fun disconnectLink(address: String) {
        val outcome: LinkRemoval = sessionLock.withLock {
            val link = links.firstOrNull { it.spec.address == address }
                ?: return@withLock LinkRemoval.NotFound
            if (links.size == 1) return@withLock LinkRemoval.LastLink
            val session = link.session
            val reconnectJob = link.reconnectJob
            link.session = null
            link.reconnectJob = null
            val remaining = links.filterNot { it === link }
            links = remaining
            LinkRemoval.Removed(session, reconnectJob, remaining)
        }
        when (outcome) {
            is LinkRemoval.NotFound -> Unit
            // Reuse the existing full teardown path instead of duplicating
            // it: stops the funnel/consumer, clears the orchestrator,
            // _activeData / _activeMotion / _activeVehicleData, the ring
            // buffers, and sets ConnectionState.Disconnected.
            is LinkRemoval.LastLink -> disconnect()
            is LinkRemoval.Removed -> {
                println("[VOLTY-BLE] disconnectLink: dropping $address, ${outcome.remaining.size} link(s) remain")
                outcome.reconnectJob?.cancel()
                outcome.session?.tearDown()
                // Reuse the existing fold — do not hand-roll a second copy of
                // it. Guarded by linkStateLock, same as every other fold
                // write, so a sibling link's concurrent status transition
                // can't interleave with this recompute.
                synchronized(linkStateLock) {
                    refoldConnectionStateLocked(outcome.remaining)
                }
            }
        }
    }

    override suspend fun onAppResumed() {
        // Only meaningful if the repo still thinks it's connected. If we're
        // Idle / Disconnected / Failed / Connecting, the user's flow will sort
        // itself out without our help.
        val state = _connectionState.value
        if (state !is ConnectionState.Connected) return

        // Snapshot target under the lock so a concurrent disconnect doesn't
        // pull the rug. We tolerate a missing link structure (paper-trail
        // Connected state without live links): the cached target is enough to
        // rebuild the links and let their loops spin up fresh sessions.
        //
        // Demo mode is inherently safe here: connectDemo() clears
        // lastConnectionTarget, so even though the state is Connected the
        // snapshot below is null and we return early — no BLE reconnect is ever
        // attempted for the simulated session, and the demoJob keeps emitting.
        val target = sessionLock.withLock {
            if (userInitiatedDisconnect) return@withLock null
            lastConnectionTarget
        } ?: return

        val nowMs = Clock.System.now().toEpochMilliseconds()
        val currentLinks = links

        if (currentLinks.isEmpty()) {
            // Paper-trail Connected with no link structure behind it (a state
            // restored without sessions). One freshness verdict for the whole
            // vehicle, exactly as before multi-link: never sampled counts as
            // long stale.
            val lastSampleMs = testLastSampleAtMsOverride ?: 0L
            val sampleAge = nowMs - lastSampleMs
            val isStale = lastSampleMs == 0L || sampleAge > BleConfig.staleSampleMs
            if (!isStale) return
            val reason = "Background drop (stale ${sampleAge}ms)"
            println("[VOLTY-BLE] onAppResumed: stale sample age=${sampleAge}ms (lastSampleAtMs=$lastSampleMs) — forcing reconnect")
            val newLinks = effectiveLinkSpecs(target.vehicle, target.address, target.type)
                .map { PackLink(spec = it, vehicle = target.vehicle) }
            sessionLock.withLock {
                if (userInitiatedDisconnect) return
                links = newLinks
            }
            for (l in newLinks) {
                // Transition out of Connected before kicking each loop — the
                // loop's "already online" guard would otherwise short-circuit
                // before the first attempt.
                setLinkState(l, LinkStatus.RECONNECTING, attempt = 0, reason = reason)
                startLinkReconnectLoop(l, initialReason = reason)
            }
            return
        }

        // Live link structure: re-check each link's freshness independently
        // and reconnect only the stale ones — a healthy sibling stays
        // untouched and keeps the vehicle Connected through the fold.
        for (link in currentLinks) {
            // Links already retrying (or still on their initial attempt) have
            // an owner; only believed-online links need the staleness check.
            if (link.status != LinkStatus.ONLINE) continue
            val lastSampleMs = link.session?.lastSampleAtMs() ?: testLastSampleAtMsOverride ?: 0L
            val sampleAge = nowMs - lastSampleMs
            // Treat "never received a sample" the same as "long stale" —
            // either way the in-session watchdog should have caught it by now
            // if the link were healthy and the dispatcher were running.
            val isStale = lastSampleMs == 0L || sampleAge > BleConfig.staleSampleMs
            if (!isStale) continue
            val reason = "Background drop (stale ${sampleAge}ms)"
            println("[VOLTY-BLE] onAppResumed[${link.spec.address}]: stale sample age=${sampleAge}ms (lastSampleAtMs=$lastSampleMs) — forcing reconnect")
            // Tear down the stale session and transition the LINK out of
            // online before kicking its loop — mirrors the watchdog drop flow,
            // where the link state has already changed by the time the loop
            // runs. tearDown() outside the lock, as everywhere.
            val sessionToTear = sessionLock.withLock {
                val s = link.session
                link.session = null
                s
            }
            sessionToTear?.tearDown()
            setLinkState(link, LinkStatus.RECONNECTING, attempt = 0, reason = reason)
            startLinkReconnectLoop(link, initialReason = reason)
        }
    }

    /**
     * Test-only override of [ConnectionSession.lastSampleAtMs] when no real
     * session exists in the test harness. Production code never reads this.
     */
    @Volatile
    private var testLastSampleAtMsOverride: Long? = null

    override fun samples(window: Duration): Flow<List<BmsData>> =
        _activeData.map { ringBuffer.within(window) }

    /**
     * Cold flow — one collector per consumer, cancelled with the consumer's
     * scope. Previously this returned a hot StateFlow whose collector was
     * tied to the repo's lifetime, leaking one collector per Dashboard init.
     */
    override fun movingAverage(window: Duration): Flow<MovingAvg> =
        _activeData.map { MovingAverage.over(ringBuffer.within(window), window) }

    /**
     * The decode protocol ONE link speaks — the controller-aware factory.
     *
     * A controller kind has no [BmsType] at all ([ProtocolKind.toBmsType]
     * throws for it by design), so the controller factory must be asked BEFORE
     * any `toBmsType()` call: a VESC link built through the battery factory
     * would crash there. The controller behind the link is the one this link
     * owns (its first [LinkSpec.ownedControllers] entry, matched against the
     * vehicle's controllers by index), so its own motor geometry and
     * derived-battery choice reach the protocol.
     *
     * **Adding a controller protocol? Add it to [controllerMotionProtocol]
     * (`ControllerProtocols.kt`), not here.** That function is the single
     * statement of controller coverage: this factory builds every controller
     * link from it, and the picker refuses a pick it has no answer for. Adding
     * an arm there is what makes a newly supported type connectable AND
     * offerable in one edit — writing a protocol into this `when` instead would
     * leave the picker refusing a controller that works.
     *
     * Every battery kind falls through (null) to [createProtocol] (BmsType)
     * unchanged.
     *
     * The whole [spec] goes to the factory, not just its first controller's
     * kind: a GATEWAY link (CAN-forwarded controllers, a hosted battery)
     * resolves there to the multiplexer instead of the single-source protocol.
     * `deriveBattery` / `motor` below still describe the first owned controller
     * because that is what the single-source branch needs; the gateway branch
     * reads every controller's geometry through `motorFor`.
     */
    private fun createProtocol(spec: LinkSpec, vehicle: Vehicle?): BmsProtocol {
        val controller = spec.ownedControllers.firstOrNull()?.globalIndex
            ?.let { idx -> vehicle?.controllers?.firstOrNull { it.index == idx } }
        controllerMotionProtocol(
            kind = spec.protocolKind,
            // A lone controller with no battery source of its own backs a
            // derived pack; the composer (Part G) turns this off once a real
            // BMS covers the same battery. Written as `== true ||` rather than
            // `?:` so the no-packs fallback stays LIVE even though `controller`
            // is never null for a planned controller link in practice (every
            // one of them is found in `vehicle.controllers`) — a plain elvis on
            // a non-null Boolean can never reach its right-hand side, which
            // would silently strand a controller-only vehicle with
            // `providesDerivedBattery = false` (its own default) at
            // `packCount = 0` and the derived-slot machinery off.
            deriveBattery = controller?.providesDerivedBattery == true ||
                vehicle?.packs.isNullOrEmpty(),
            motor = controller?.motor ?: MotorConfig(),
            // A gateway link carries several sources, so the factory needs the
            // whole spec (which CAN ids, which hosted battery) and every
            // controller's own geometry — not just the first one's. Both are
            // ignored for a plain single-source link, which still builds the
            // exact VescProtocol it always did.
            link = spec,
            motorFor = { idx ->
                vehicle?.controllers?.firstOrNull { it.index == idx }?.motor ?: MotorConfig()
            }
        )?.let { return it }
        return createProtocol(spec.protocolKind.toBmsType())
    }

    /**
     * The [BmsType] a link's battery half decodes with, or null for a
     * controller kind that has none. The non-throwing sibling of
     * [ProtocolKind.toBmsType] — the branches must stay in step: every kind
     * `toBmsType` rejects is a kind that must land in null here.
     *
     * Deliberately exhaustive with NO `else` branch: `toBmsType()` is itself
     * exhaustive over [ProtocolKind], so an `else -> toBmsType()` here would
     * silently route any future controller kind through the throwing branch
     * instead of failing to compile — exactly the pairing this KDoc promises
     * stays in step. Adding a [ProtocolKind] entry now forces a decision here
     * at compile time, not a runtime `error()` inside [connectLinkAttempt].
     */
    private fun ProtocolKind.batteryBmsTypeOrNull(): BmsType? = when (this) {
        ProtocolKind.VESC, ProtocolKind.FARDRIVER, ProtocolKind.KELLY -> null
        ProtocolKind.JK -> BmsType.JK_BMS
        ProtocolKind.JBD -> BmsType.JBD_BMS
        ProtocolKind.ANT -> BmsType.ANT_BMS
        ProtocolKind.DALY -> BmsType.DALY_BMS
        ProtocolKind.BEGODE -> BmsType.BEGODE
        ProtocolKind.VESC_BMS -> BmsType.VESC_BMS
    }

    private fun createProtocol(type: BmsType): BmsProtocol = when (type) {
        BmsType.JK_BMS -> JkBmsProtocol()
        BmsType.JBD_BMS -> JbdBmsProtocol()
        BmsType.ANT_BMS -> AntBmsProtocol()
        BmsType.DALY_BMS -> DalyBmsProtocol()
        BmsType.BEGODE -> BegodeProtocol()
        // VESC BMS decode is out of scope for the Part A foundation work that
        // introduced this enum entry (see the vehicle-platform-A-foundation
        // plan) — a later part wires the real protocol in.
        BmsType.VESC_BMS -> error("VESC BMS protocol decode is not implemented yet")
    }

    fun close() {
        runCatching { scope.cancel() }
    }

    // ----- Test seams (package-private, used only by commonTest) -----

    /**
     * Test-only: simulate a link drop / stale-sample detection by driving the
     * REAL [onLinkDrop] pathway, exactly as a [ConnectionSession] state
     * observer / watchdog would. Lets unit tests exercise the
     * disconnect-vs-reconnect race without needing a real BLE stack.
     */
    internal fun simulateConnectionDropForTest(
        vehicle: Vehicle?,
        address: String,
        type: BmsType,
        reason: String
    ) {
        // Mimic pre-drop state: vehicle present, one believed-online link —
        // exactly what a live single-link connection holds. The production
        // drop path (onLinkDrop) is then responsible for moving the link (and
        // through the fold the state machine) to Reconnecting and spinning up
        // the loop. Unlocked link-list write — the sanctioned test-seam
        // exception, see [vehicleConnection].
        _activeVehicle.value = vehicle
        _connectionState.value = ConnectionState.Connected(vehicle)
        val link = PackLink(spec = effectiveLinkSpecs(vehicle, address, type).first(), vehicle = vehicle)
        link.status = LinkStatus.ONLINE
        links = listOf(link)
        scope.launch { onLinkDrop(link, reason) }
    }

    /** Test-only: peek at the (first) reconnect job so tests can await its termination. */
    internal fun reconnectJobForTest(): Job? = links.firstNotNullOfOrNull { it.reconnectJob }

    /** Test-only: peek at the user-disconnect flag. */
    internal fun isUserInitiatedDisconnectForTest(): Boolean = userInitiatedDisconnect

    /** Test-only: push a sample into the activeData pipeline (drives the
     *  cell-count auto-fill collector without a real BLE session). */
    internal fun emitActiveDataForTest(sample: BmsData) { _activeData.value = sample }

    /** Test-only: push a vehicle snapshot into the pack auto-fill collector. */
    internal fun emitVehicleDataForTest(vd: VehicleData) { _activeVehicleData.value = vd }

    /**
     * Test-only: the pack list [doConnect] would build for this target,
     * including the protocol lookup that decides how many slots exist.
     */
    internal fun connectionPacksForTest(vehicle: Vehicle?, address: String, type: BmsType): List<Pack> =
        connectionPacks(vehicle, address, type, createProtocol(type).packCount)

    /**
     * Test-only: build and install the exact sample pipeline [doConnect]
     * wires into a [ConnectionSession] — expanded pack list, SoC-estimation
     * vehicle, orchestrator — without a BLE link, and return the onSample
     * funnel so tests can push per-pack samples through the production path.
     *
     * [ConnectionSession] requires a real Kable [Peripheral], so this seam is
     * the only way commonTest can reach the funnel. It shares
     * [buildSamplePipeline] with [doConnect] on purpose: the test drives the
     * production wiring itself, not a copy that can drift. The unlocked field
     * write is fine here — tests run single-threaded on a test dispatcher.
     */
    internal fun installSampleFunnelForTest(
        vehicle: Vehicle?,
        address: String,
        type: BmsType
    ): (packIndex: Int, sample: BmsData, sections: List<SectionState>) -> Unit =
        installProtocolPipelineForTest(vehicle, address, type).second

    /**
     * [installSampleFunnelForTest] that also exposes the protocol instance the
     * pipeline captured. Lets a test drive the REAL decode → scale → estimate
     * chain — feed the protocol raw notifications, route its packs through
     * the returned funnel via [routePackSamples] — which is the only way to
     * exercise the Begode live-voltage scaling: it reads the protocol's own
     * state, so a hand-built sample cannot reach it.
     */
    internal fun installProtocolPipelineForTest(
        vehicle: Vehicle?,
        address: String,
        type: BmsType
    ): Pair<BmsProtocol, (packIndex: Int, sample: BmsData, sections: List<SectionState>) -> Unit> {
        val protocol = createProtocol(type)
        val pipeline = buildSamplePipeline(vehicle, address, type, protocol)
        // Unlocked writes — the one sanctioned test-seam exception to the
        // sessionLock discipline, see [vehicleConnection]. The REAL channel
        // consumer is launched too: samples pushed through the returned
        // funnel cross the production serialisation barrier, not a shortcut.
        vehicleConnection = pipeline.orchestrator
        closeSampleFunnelLocked()
        sampleChannel = pipeline.channel
        sampleConsumerJob = launchSampleConsumer(pipeline.channel)
        return protocol to pipeline.onSample
    }

    /**
     * Test-only: the live funnel channel, so tests can inject a fully
     * enriched [Sample] directly — a [PackSample] (the exact shape a second
     * link's session produces once the fan-out lands) or a [MotionSample] (a
     * controller session's output, which Part A has no protocol to emit) —
     * and prove the consumer routes it into the shared state by its global
     * pack / controller index.
     */
    internal fun sampleFunnelChannelForTest(): SendChannel<Sample>? = sampleChannel

    /**
     * Test-only: prime a "stuck Connected" state — as if the app had been
     * connected pre-background and dispatchers were just unfrozen on resume.
     * No real [ConnectionSession] is involved; [lastSampleAtMs] is faked via
     * [testLastSampleAtMsOverride].
     */
    internal fun primeConnectedForTest(
        vehicle: Vehicle?,
        address: String,
        type: BmsType,
        lastSampleAtMs: Long
    ) {
        _activeVehicle.value = vehicle
        _connectionState.value = ConnectionState.Connected(vehicle)
        lastConnectionTarget = ConnectionTarget(vehicle, address, type)
        testLastSampleAtMsOverride = lastSampleAtMs
    }

    // ----- Multi-link test seams -----

    /**
     * Test-only override of the orchestrator's time source, so tests can
     * drive the per-pack staleness sweep (12 s of real time at production
     * values) under virtual time. Null in production — [buildOrchestrator]
     * falls back to the system clock. Set BEFORE installing a pipeline; the
     * clock is captured at orchestrator construction.
     */
    @Volatile
    internal var orchestratorClockForTest: (() -> Instant)? = null

    /**
     * Test-only: install the exact multi-link wiring [doConnect] builds —
     * effective link specs, one orchestrator sized from the full vehicle pack
     * list, one channel + consumer, one [PackLink] per distinct address — and
     * return each link's session funnel (LOCAL pack indices, translated to
     * global through that link's [LinkSpec.globalPackIndex], in link order).
     *
     * [ConnectionSession] requires a real Kable peripheral, so this seam is
     * the only way commonTest can reach the fan-out. It shares
     * [effectiveLinkSpecs] / [buildOrchestrator] / [makeLinkOnSample] with
     * [doConnect] on purpose: the test drives the production wiring itself,
     * not a copy that can drift. Links start CONNECTING, as after doConnect's
     * install block. Unlocked writes — the sanctioned test-seam exception,
     * see [vehicleConnection].
     */
    internal fun installLinksForTest(
        vehicle: Vehicle?,
        address: String,
        type: BmsType?
    ): List<(packIndex: Int, sample: BmsData, sections: List<SectionState>) -> Unit> {
        val newLinks = effectiveLinkSpecs(vehicle, address, type)
            .map { PackLink(spec = it, vehicle = vehicle) }
        val orchestrator = buildOrchestrator(vehicle, address, type)
        val channel = Channel<Sample>(SAMPLE_FUNNEL_CAPACITY)
        vehicleConnection = orchestrator
        closeSampleFunnelLocked()
        sampleChannel = channel
        sampleConsumerJob = launchSampleConsumer(channel)
        links = newLinks
        lastConnectionTarget = ConnectionTarget(vehicle, address, type)
        _activeVehicle.value = vehicle
        _connectionState.value = ConnectionState.Connecting(vehicle)
        val socVehicle = socVehicleFor(vehicle, address, type)
        return newLinks.map { link ->
            makeLinkOnSample(
                protocol = createProtocol(link.spec, vehicle),
                socVehicle = socVehicle,
                channel = channel,
                localToGlobal = link.spec::globalPackIndex
            )
        }
    }

    /**
     * Test-only: the MOTION funnels of the installed links, in link order —
     * the motion twin of what [installLinksForTest] returns for the battery
     * side. Built through the production [makeLinkOnMotionSample] against the
     * live channel, so a test drives the exact lambda a [ConnectionSession]
     * is handed. Empty when no funnel is installed.
     */
    internal fun linkMotionFunnelsForTest(): List<(controllerIndex: Int, data: ControllerData) -> Unit> {
        val channel = sampleChannel ?: return emptyList()
        return links.map { makeLinkOnMotionSample(it.spec, channel) }
    }

    /**
     * Test-only: the specs of the installed links — how the vehicle's packs
     * and controllers were actually planned into BLE links.
     */
    internal fun linkSpecsForTest(): List<LinkSpec> = links.map { it.spec }

    /**
     * Test-only: the protocol [connectLinkAttempt] would build for one link,
     * through the production controller-aware factory.
     */
    internal fun createProtocolForTest(spec: LinkSpec, vehicle: Vehicle?): BmsProtocol =
        createProtocol(spec, vehicle)

    /** Test-only: how many links the current connection holds. */
    internal fun linkCountForTest(): Int = links.size

    /** Test-only: one link's reconnect loop job, or null when it has none. */
    internal fun linkReconnectJobForTest(address: String): Job? =
        links.firstOrNull { it.spec.address == address }?.reconnectJob

    /**
     * Test-only: drive the production link-online transition (what a
     * successful [connectLinkAttempt] performs after its session handshake)
     * so tests can exercise the fold without a real peripheral.
     */
    internal fun markLinkOnlineForTest(address: String) {
        setLinkState(links.first { it.spec.address == address }, LinkStatus.ONLINE)
    }

    /**
     * Test-only: drive the production link-failure transition (what a failed
     * [connectLinkAttempt] performs) so tests can exercise the fold's
     * Failed branch without a real peripheral.
     */
    internal fun markLinkFailedForTest(address: String, reason: String) {
        setLinkState(links.first { it.spec.address == address }, LinkStatus.FAILED, reason = reason)
    }

    /**
     * Test-only: run [doConnect]'s initial-partial tail — failed links are
     * promoted to background retry — against the installed link list.
     */
    internal fun settleInitialPartialForTest() {
        startRetriesForMissingLinks(links)
    }

    /**
     * Test-only: simulate one link's drop by driving the REAL [onLinkDrop]
     * pathway, exactly as that link's [ConnectionSession] state observer /
     * watchdog would — the multi-link sibling of
     * [simulateConnectionDropForTest].
     */
    internal fun simulateLinkDropForTest(address: String, reason: String) {
        val link = links.first { it.spec.address == address }
        scope.launch { onLinkDrop(link, reason) }
    }
}

/**
 * One enriched sample crossing the serialisation barrier between a link's
 * session coroutine and the single consumer that owns the shared state. The
 * funnel carries battery OR motion: a link's battery stream and its controller
 * stream enter the SAME single-consumer channel, so the consumer serialises
 * every mutation of [VehicleConnection] regardless of which side produced it.
 */
internal sealed interface Sample

/**
 * A battery sample. By the time it enters the channel its enrichment (Begode
 * live-voltage scaling, SoC estimation) is done and [globalPackIndex] is the
 * VEHICLE-global pack index — the session's local index has already been
 * translated.
 */
internal data class PackSample(
    val globalPackIndex: Int,
    val data: BmsData,
    val sections: List<SectionState>
) : Sample

/**
 * A motion sample. [globalControllerIndex] is the VEHICLE-global controller
 * index — the session's local index has already been translated through the
 * link's [LinkSpec.globalControllerIndex]. No controller protocol emits these
 * in Part A; the path is exercised by tests and the demo until Part B wires a
 * real [ru.sodovaya.volty.data.bms.MotionSource] session in.
 */
internal data class MotionSample(
    val globalControllerIndex: Int,
    val data: ControllerData
) : Sample
