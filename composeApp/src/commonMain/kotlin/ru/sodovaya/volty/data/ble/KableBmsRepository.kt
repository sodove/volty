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
import ru.sodovaya.volty.domain.model.DEMO_VEHICLE_ID
import ru.sodovaya.volty.domain.model.GUEST_VEHICLE_ID_PREFIX
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.bmsAddress
import ru.sodovaya.volty.domain.model.bmsType
import ru.sodovaya.volty.domain.model.cellCount
import ru.sodovaya.volty.domain.model.expandedTo
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.model.withCellCount
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.domain.stats.MovingAverage
import ru.sodovaya.volty.domain.stats.VoltageSocEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
         * The synthetic vehicle that powers "Try demo" mode. Its id is
         * [DEMO_VEHICLE_ID] (see [ru.sodovaya.volty.domain.model.isDemo]) so it is
         * never confused with a saved or guest vehicle and is never persisted.
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
        )

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
    private val ringBuffer = SampleRingBuffer()

    /** Lock guarding session swap + the userInitiatedDisconnect flag. */
    private val sessionLock = Mutex()

    private var currentSession: ConnectionSession? = null
    private var reconnectJob: Job? = null

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
        val type: BmsType
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
        if (vehicle.cellCount == n) return
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
        val knownAddresses: Map<String, Vehicle> =
            vehicleRepository.vehicles.first().associateBy { it.bmsAddress }
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
            val id = ad.identifier.toString()
            cacheAdvertisement(id, ad)
            emit(
                DiscoveredDevice(
                    address = id,
                    name = name,
                    rssi = ad.rssi,
                    bmsType = type,
                    knownVehicle = knownAddresses[id]
                )
            )
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun connect(vehicle: Vehicle): Result<Unit> {
        // If a caller hands a transient guest Vehicle back to connect(), route
        // it through the guest path so it stays unpersisted and the touch /
        // saved-vehicle observers leave it alone.
        if (vehicle.isGuest) return connectGuest(vehicle.bmsAddress, vehicle.bmsType)
        return doConnect(vehicle.bmsAddress, vehicle.bmsType, vehicle)
    }

    override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> =
        doConnect(address, type, vehicle = buildGuestVehicle(address, type))

    override suspend fun connectDemo(): Result<Unit> {
        println("[VOLTY-BLE] connectDemo: starting simulated session")
        return try {
            // Tear down any real session / reconnect loop / prior demo under the
            // lock so a concurrent disconnect or connect sees a consistent view.
            sessionLock.withLock {
                userInitiatedDisconnect = false
                currentSession?.tearDown()
                currentSession = null
                reconnectJob?.cancel()
                reconnectJob = null
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
                // No real link to resurrect on resume — there is no
                // ConnectionSession behind a demo connection.
                lastConnectionTarget = null
            }
            _activeVehicle.value = DEMO_VEHICLE
            ringBuffer.clear()
            _connectionState.value = ConnectionState.Connected(DEMO_VEHICLE)
            // Show the foreground notification too, so reviewers see the full
            // monitoring experience (the service feeds off activeData/activeVehicle).
            serviceStart()
            demoJob = scope.launch {
                DemoBmsSimulator().run { sample ->
                    ringBuffer.push(sample)
                    _activeData.value = sample
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
     */
    private fun storedPacks(vehicle: Vehicle?, address: String, type: BmsType): List<Pack> =
        vehicle?.packs?.takeIf { it.isNotEmpty() }
            ?: listOf(Pack(index = 0, label = "Battery", bmsType = type, bmsAddress = address))

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
        val extraSlots = discovered.drop(vehicle.packs.size)
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
     * [ConnectionSession]. Built as one unit so the funnel and the
     * orchestrator are guaranteed to agree on the pack list.
     */
    private class SamplePipeline(
        val orchestrator: VehicleConnection,
        val onSample: (packIndex: Int, sample: BmsData, sections: List<SectionState>) -> Unit
    )

    /**
     * Build the orchestrator and the onSample funnel for one connection
     * attempt. Shared between [doConnect] and the test seam
     * [installSampleFunnelForTest], so tests exercise the exact production
     * wiring rather than a copy that can drift.
     */
    private fun buildSamplePipeline(
        vehicle: Vehicle?,
        address: String,
        type: BmsType,
        protocol: BmsProtocol
    ): SamplePipeline {
        val stored = storedPacks(vehicle, address, type)
        val packs = stored.expandedTo(protocol.packCount)
        // The SoC estimator must see the SAME expanded pack list the
        // orchestrator is sized from. The stored vehicle can know fewer packs
        // than the protocol does (a one-pack Begode profile vs. two branches):
        // handed the stored list, the estimator's per-pack lookup misses for
        // the synthesised slot, that branch's Begode sample keeps its reported
        // soc = 0, and the parallel aggregate — a plain mean when no pack
        // reports capacity — HALVES the wheel's state of charge, feeding the
        // same halved value into the SOC_LOW / SOC_CUTOFF alerts.
        val socVehicle = vehicle?.copy(packs = packs)
        val orchestrator = VehicleConnection(
            packs = stored,
            // Protocol-synthesised slots stay invisible until they report:
            // a Begode without a smart BMS never fills its second branch,
            // and an eager slot would be a permanently-offline phantom.
            latentPacks = packs.drop(stored.size),
            topology = vehicle?.topology ?: PackTopology.PARALLEL,
            onVehicleData = { vd -> _activeVehicleData.value = vd }
        )
        val onSample: (Int, BmsData, List<SectionState>) -> Unit = { packIndex, sample, sections ->
            // Devices that report no SoC at all (a Begode wheel gives
            // voltage and cells only) get one estimated from average
            // cell voltage against the vehicle's configured cell-voltage
            // bounds — BEFORE aggregation, so per-pack SoC maths sees
            // it. The estimator keys on the PACK's BmsType (packIndex,
            // not the vehicle-level shortcut — a future BMS group can
            // mix types): a coulomb-counting BMS's sample passes
            // through untouched, including a genuine 0 % on a flat
            // pack (see VoltageSocEstimator).
            val enriched = VoltageSocEstimator.withEstimatedSoc(sample, socVehicle, packIndex)
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
            val forActive = vehicleConnection?.submit(packIndex, enriched, sections)?.aggregate ?: enriched
            // Ring buffer before activeData: the graph collector maps
            // over _activeData and reads the buffer, so announcing the
            // sample first would make every graph emit lag by one.
            ringBuffer.push(forActive)
            _activeData.value = forActive
        }
        return SamplePipeline(orchestrator, onSample)
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

    private suspend fun doConnect(address: String, type: BmsType, vehicle: Vehicle?): Result<Unit> {
        println("[VOLTY-BLE] doConnect: starting addr=$address type=$type vehicle=${vehicle?.name}")
        // Tracks the orchestrator THIS attempt installed, so every failure
        // path (including the catch-all below) can undo exactly its own
        // installation and nothing else's.
        var installedOrchestrator: VehicleConnection? = null
        return try {
            // User-initiated entry — clear the disconnect flag and tear down any
            // existing session under the lock so a concurrent disconnect() sees
            // a consistent view.
            sessionLock.withLock {
                userInitiatedDisconnect = false
                currentSession?.tearDown()
                currentSession = null
                // Connecting to a real BMS kills any running demo simulation.
                demoJob?.cancel()
                demoJob = null
            }
            // Connecting to a DIFFERENT device (picker switch without an explicit
            // disconnect, or a real connect right after demo) must not mix the
            // previous battery's samples into the new graph. Reconnects to the
            // same address keep the buffer so the graph survives link drops.
            val previousAddress = _activeVehicle.value?.bmsAddress
            if (previousAddress != null && previousAddress != address) {
                ringBuffer.clear()
                _activeData.value = BmsData()
            }
            _connectionState.value = ConnectionState.Connecting(vehicle)
            _activeVehicle.value = vehicle
            // The protocol is built BEFORE the orchestrator so the orchestrator
            // can be sized to it — see [connectionPacks]. It is a plain object
            // with no I/O, so constructing it this early is free and cannot
            // fail the connect.
            val protocol = createProtocol(type)
            val pipeline = buildSamplePipeline(vehicle, address, type, protocol)
            val orchestrator = pipeline.orchestrator
            installedOrchestrator = orchestrator
            // Install under the lock: the failure cleanup below is an
            // identity-guarded check-then-write, and it can only be safe if
            // the competing writer — a concurrent connect / reconnect attempt
            // installing ITS orchestrator — is serialised against it.
            sessionLock.withLock { vehicleConnection = orchestrator }

            val advertisement = resolveAdvertisement(address)
            if (advertisement == null) {
                _connectionState.value = ConnectionState.Failed("Device not found")
                clearOrchestratorAfterFailure(orchestrator)
                return Result.failure(IllegalStateException("Device not found"))
            }

            val peripheral = Peripheral(advertisement)
            val session = ConnectionSession(
                parentScope = scope,
                peripheral = peripheral,
                protocol = protocol,
                vehicle = vehicle,
                connectionState = _connectionState,
                onSample = pipeline.onSample,
                onDropDetected = { reason ->
                    // The session detected a drop. Schedule a reconnect — unless
                    // the user explicitly disconnected in the meantime.
                    onSessionDrop(reason, vehicle, address, type)
                }
            )

            // Install the session under the lock so a racing disconnect()
            // can't see a partially-set state.
            sessionLock.withLock {
                if (userInitiatedDisconnect) {
                    // Someone called disconnect() while we were preparing —
                    // honour it: don't even attempt to bring the link up.
                    println("[VOLTY-BLE] doConnect: aborted, userInitiatedDisconnect set during prep")
                    // Already inside sessionLock — use the locked variant, the
                    // mutex is not reentrant.
                    clearOrchestratorLocked(orchestrator)
                    return Result.failure(IllegalStateException("Disconnect requested"))
                }
                currentSession = session
                lastConnectionTarget = ConnectionTarget(vehicle, address, type)
            }

            val connectResult = session.connect()
            if (connectResult.isFailure) {
                val err = connectResult.exceptionOrNull()?.message ?: "Connection failed"
                _connectionState.value = ConnectionState.Failed(err)
                println("[VOLTY-BLE] doConnect: $err")
                sessionLock.withLock {
                    if (currentSession === session) currentSession = null
                }
                session.tearDown()
                clearOrchestratorAfterFailure(orchestrator)
                return Result.failure(IllegalStateException(err))
            }

            // Re-check after suspension: did the user disconnect while we were
            // mid-handshake?
            val shouldAbort = sessionLock.withLock {
                if (userInitiatedDisconnect) {
                    println("[VOLTY-BLE] doConnect: post-connect, disconnect was requested — tearing down")
                    if (currentSession === session) currentSession = null
                    true
                } else false
            }
            if (shouldAbort) {
                session.tearDown()
                clearOrchestratorAfterFailure(orchestrator)
                return Result.failure(IllegalStateException("Disconnect requested"))
            }

            _connectionState.value = ConnectionState.Connected(vehicle)
            println("[VOLTY-BLE] state -> Connected(${vehicle?.name ?: "guest"}) addr=$address")
            serviceStart()
            // Guests are transient — never write them to the saved-vehicle store.
            if (vehicle != null && !vehicle.isGuest) vehicleRepository.touch(vehicle.id)
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

    private suspend fun resolveAdvertisement(address: String): com.juul.kable.Advertisement? {
        val cached = cachedAdvertisement(address)
        if (cached != null) return cached
        val found = withTimeoutOrNull(BleConfig.advertisementSearchMs) {
            Scanner().advertisements.first { it.identifier.toString() == address }
        }
        if (found != null) cacheAdvertisement(address, found)
        return found
    }

    private suspend fun onSessionDrop(reason: String, vehicle: Vehicle?, address: String, type: BmsType) {
        // Suspending check + state mutation under the lock so user disconnect
        // racing with a watchdog can't both win.
        sessionLock.withLock {
            if (userInitiatedDisconnect) {
                println("[VOLTY-BLE] onSessionDrop ignored — user disconnected")
                return
            }
        }
        if (_connectionState.value is ConnectionState.Connected ||
            _connectionState.value is ConnectionState.Connecting) {
            // Leave Connected BEFORE starting the loop — its "already connected"
            // guard would otherwise short-circuit on the very first iteration and
            // the link would never come back. The dead session is NOT torn down
            // here: onDropDetected is invoked from inside the session's own
            // state/watchdog jobs, and tearDown() cancelAndJoin-ing the calling
            // job would deadlock. doConnect() in the loop tears it down safely.
            _connectionState.value = ConnectionState.Reconnecting(0, reason)
            startReconnectLoop(vehicle, address, type, initialReason = reason)
        }
    }

    private fun startReconnectLoop(
        vehicle: Vehicle?,
        address: String,
        type: BmsType,
        initialReason: String
    ) {
        reconnectJob?.cancel()
        println("[VOLTY-BLE] reconnect loop: starting reason=$initialReason")
        reconnectJob = scope.launch {
            var attempt = 0
            while (isActive) {
                // Honour user-initiated disconnect, vehicle clearance,
                // and "already connected by some other path".
                if (userInitiatedDisconnect) {
                    println("[VOLTY-BLE] reconnect loop: userInitiatedDisconnect — stopping")
                    return@launch
                }
                if (_activeVehicle.value == null && vehicle != null) {
                    println("[VOLTY-BLE] reconnect loop: vehicle cleared — stopping")
                    return@launch
                }
                if (_connectionState.value is ConnectionState.Connected) {
                    println("[VOLTY-BLE] reconnect loop: already connected — stopping")
                    return@launch
                }
                attempt++
                println("[VOLTY-BLE] reconnect loop: attempt #$attempt")
                val result = doConnect(address, type, vehicle)
                if (result.isSuccess) {
                    println("[VOLTY-BLE] reconnect loop: attempt #$attempt succeeded")
                    return@launch
                }
                if (userInitiatedDisconnect) {
                    println("[VOLTY-BLE] reconnect loop: disconnect requested mid-attempt — stopping")
                    return@launch
                }
                println("[VOLTY-BLE] reconnect loop: attempt #$attempt failed — ${result.exceptionOrNull()?.message}")
                // Settle into Reconnecting BETWEEN attempts so the UI sees a
                // stable "trying again, attempt #N" message instead of the
                // Connecting → Failed flicker that doConnect emits internally.
                _connectionState.value = ConnectionState.Reconnecting(attempt, initialReason)
                val delayMs = if (attempt < BleConfig.reconnectBackoffAfter)
                    BleConfig.reconnectDelayMs
                else
                    BleConfig.reconnectDelayAfter10Ms
                delay(delayMs)
            }
        }
    }

    override suspend fun disconnect() {
        // Atomically: flag the intent, cancel the reconnect loop, tear down the
        // session, clear vehicle, set Disconnected. Held under sessionLock so
        // doConnect / onSessionDrop running on another coroutine see this.
        val sessionToTear: ConnectionSession?
        val reconnectToCancel: Job?
        val demoToCancel: Job?
        sessionLock.withLock {
            userInitiatedDisconnect = true
            sessionToTear = currentSession
            currentSession = null
            reconnectToCancel = reconnectJob
            reconnectJob = null
            demoToCancel = demoJob
            demoJob = null
            // Forget the target so a later [onAppResumed] doesn't try to
            // resurrect a connection the user explicitly closed.
            lastConnectionTarget = null
        }
        reconnectToCancel?.cancel()
        demoToCancel?.cancel()
        sessionToTear?.tearDown()
        _activeData.value = BmsData()
        _activeVehicleData.value = VehicleData()
        // Fresh acquisition: the block above has already released the lock,
        // and tearDown() must not run while holding it.
        sessionLock.withLock { vehicleConnection = null }
        _activeVehicle.value = null
        ringBuffer.clear()
        _connectionState.value = ConnectionState.Disconnected
        serviceStop()
    }

    override suspend fun onAppResumed() {
        // Only meaningful if the repo still thinks it's connected. If we're
        // Idle / Disconnected / Failed / Connecting, the user's flow will sort
        // itself out without our help.
        val state = _connectionState.value
        if (state !is ConnectionState.Connected) return

        // Snapshot target under the lock so a concurrent disconnect doesn't
        // pull the rug. We tolerate a missing session (paper-trail Connected
        // state without a live session): the cached target is enough to drive
        // the drop pathway and the loop will spin up a fresh session.
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
        val lastSampleMs = currentSession?.lastSampleAtMs() ?: testLastSampleAtMsOverride ?: 0L
        val sampleAge = nowMs - lastSampleMs

        // Treat "never received a sample" the same as "long stale" — either way
        // the in-session watchdog should have caught it by now if the link were
        // healthy and the dispatcher were running.
        val isStale = lastSampleMs == 0L || sampleAge > BleConfig.staleSampleMs
        if (!isStale) return

        val reason = "Background drop (stale ${sampleAge}ms)"
        println("[VOLTY-BLE] onAppResumed: stale sample age=${sampleAge}ms (lastSampleAtMs=$lastSampleMs) — forcing reconnect")
        // Tear down any live session and transition out of Connected before
        // kicking the reconnect loop — the loop's "already connected" guard
        // would otherwise short-circuit before the first attempt. This mirrors
        // [simulateConnectionDropForTest] and the production watchdog flow,
        // where the link drop event has already changed the link state by the
        // time the loop runs.
        val sessionToTear = sessionLock.withLock { currentSession }
        sessionToTear?.tearDown()
        _connectionState.value = ConnectionState.Reconnecting(0, reason)
        startReconnectLoop(target.vehicle, target.address, target.type, initialReason = reason)
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

    private fun createProtocol(type: BmsType): BmsProtocol = when (type) {
        BmsType.JK_BMS -> JkBmsProtocol()
        BmsType.JBD_BMS -> JbdBmsProtocol()
        BmsType.ANT_BMS -> AntBmsProtocol()
        BmsType.DALY_BMS -> DalyBmsProtocol()
        BmsType.BEGODE -> BegodeProtocol()
    }

    fun close() {
        runCatching { scope.cancel() }
    }

    // ----- Test seams (package-private, used only by commonTest) -----

    /**
     * Test-only: simulate a link drop / stale-sample detection by driving the
     * REAL [onSessionDrop] pathway, exactly as a [ConnectionSession] state
     * observer / watchdog would. Lets unit tests exercise the
     * disconnect-vs-reconnect race without needing a real BLE stack.
     */
    internal fun simulateConnectionDropForTest(
        vehicle: Vehicle?,
        address: String,
        type: BmsType,
        reason: String
    ) {
        // Mimic pre-drop state: vehicle present, link believed Connected. The
        // production drop path (onSessionDrop) is then responsible for moving
        // the state machine to Reconnecting and spinning up the loop.
        _activeVehicle.value = vehicle
        _connectionState.value = ConnectionState.Connected(vehicle)
        scope.launch { onSessionDrop(reason, vehicle, address, type) }
    }

    /** Test-only: peek at the reconnect job so tests can await its termination. */
    internal fun reconnectJobForTest(): Job? = reconnectJob

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
    ): (packIndex: Int, sample: BmsData, sections: List<SectionState>) -> Unit {
        val pipeline = buildSamplePipeline(vehicle, address, type, createProtocol(type))
        vehicleConnection = pipeline.orchestrator
        return pipeline.onSample
    }

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
}
