package ru.sodovaya.volty.data.ble

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import ru.sodovaya.volty.data.bms.AntBmsProtocol
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
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.isGuest
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.domain.stats.MovingAverage
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
        val DEMO_VEHICLE: Vehicle = Vehicle(
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

    private val advertisementCache = mutableMapOf<String, com.juul.kable.Advertisement>()

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
    }

    override fun scanAll(): Flow<DiscoveredDevice> = flow {
        val knownAddresses: Map<String, Vehicle> =
            vehicleRepository.vehicles.first().associateBy { it.bmsAddress }
        _connectionState.value = ConnectionState.Scanning
        val scanner = Scanner()
        scanner.advertisements.collect { ad ->
            val name = ad.name
            val serviceList = ad.uuids.map { it.toString().lowercase() }
            val type = BmsTypeDetector.detect(name = name, serviceUuids = serviceList) ?: return@collect
            val id = ad.identifier.toString()
            advertisementCache[id] = ad
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
        val advName = advertisementCache[address]?.name?.takeIf { it.isNotBlank() }
        return Vehicle(
            id = "$GUEST_VEHICLE_ID_PREFIX$address",
            name = advName ?: "Guest BMS",
            iconKey = "battery",
            bmsType = type,
            bmsAddress = address,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Clock.System.now()
        )
    }

    private suspend fun doConnect(address: String, type: BmsType, vehicle: Vehicle?): Result<Unit> {
        println("[VOLTY-BLE] doConnect: starting addr=$address type=$type vehicle=${vehicle?.name}")
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
            _connectionState.value = ConnectionState.Connecting(vehicle)
            _activeVehicle.value = vehicle

            val advertisement = resolveAdvertisement(address)
            if (advertisement == null) {
                _connectionState.value = ConnectionState.Failed("Device not found")
                return Result.failure(IllegalStateException("Device not found"))
            }

            val peripheral = Peripheral(advertisement)
            val protocol = createProtocol(type)
            val session = ConnectionSession(
                parentScope = scope,
                peripheral = peripheral,
                protocol = protocol,
                vehicle = vehicle,
                ringBuffer = ringBuffer,
                activeData = _activeData,
                connectionState = _connectionState,
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
                return Result.failure(IllegalStateException("Disconnect requested"))
            }

            _connectionState.value = ConnectionState.Connected(vehicle)
            println("[VOLTY-BLE] state -> Connected(${vehicle?.name ?: "guest"}) addr=$address")
            serviceStart()
            // Guests are transient — never write them to the saved-vehicle store.
            if (vehicle != null && !vehicle.isGuest) vehicleRepository.touch(vehicle.id)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(e.message ?: "Connection failed")
            Result.failure(e)
        }
    }

    private suspend fun resolveAdvertisement(address: String): com.juul.kable.Advertisement? {
        val cached = advertisementCache[address]
        if (cached != null) return cached
        val found = withTimeoutOrNull(BleConfig.advertisementSearchMs) {
            Scanner().advertisements.first { it.identifier.toString() == address }
        }
        if (found != null) advertisementCache[address] = found
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
    }

    fun close() {
        runCatching { scope.cancel() }
    }

    // ----- Test seams (package-private, used only by commonTest) -----

    /**
     * Test-only: simulate a link drop / stale-sample detection by directly
     * invoking the reconnect orchestration as if a [ConnectionSession] had
     * reported one. Lets unit tests exercise the disconnect-vs-reconnect race
     * without needing a real BLE stack.
     */
    internal fun simulateConnectionDropForTest(
        vehicle: Vehicle?,
        address: String,
        type: BmsType,
        reason: String
    ) {
        // Mimic post-drop state: vehicle present, connectionState NOT in
        // Connected (otherwise the loop's "already connected" guard would
        // short-circuit and return immediately).
        _activeVehicle.value = vehicle
        _connectionState.value = ConnectionState.Reconnecting(0, reason)
        startReconnectLoop(vehicle, address, type, initialReason = reason)
    }

    /** Test-only: peek at the reconnect job so tests can await its termination. */
    internal fun reconnectJobForTest(): Job? = reconnectJob

    /** Test-only: peek at the user-disconnect flag. */
    internal fun isUserInitiatedDisconnectForTest(): Boolean = userInitiatedDisconnect

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
