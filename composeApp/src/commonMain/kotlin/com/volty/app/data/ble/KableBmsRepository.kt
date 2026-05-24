package com.volty.app.data.ble

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.volty.app.data.bms.AntBmsProtocol
import com.volty.app.data.bms.BmsProtocol
import com.volty.app.data.bms.BmsTypeDetector
import com.volty.app.data.bms.DalyBmsProtocol
import com.volty.app.data.bms.JbdBmsProtocol
import com.volty.app.data.bms.JkBmsProtocol
import com.volty.app.data.memory.SampleRingBuffer
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.domain.repository.DiscoveredDevice
import com.volty.app.domain.repository.VehicleRepository
import com.volty.app.domain.stats.MovingAvg
import com.volty.app.domain.stats.MovingAverage
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
        serviceController: com.volty.app.service.ServiceController,
    ) : this(
        vehicleRepository = vehicleRepository,
        serviceStart = { serviceController.start() },
        serviceStop = { serviceController.stop() },
        coroutineContext = Dispatchers.Default,
    )

    internal companion object {
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

    private val ringBuffer = SampleRingBuffer(capacity = 30 * 60) // 30 min @ 1 Hz

    /** Lock guarding session swap + the userInitiatedDisconnect flag. */
    private val sessionLock = Mutex()

    private var currentSession: ConnectionSession? = null
    private var reconnectJob: Job? = null

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

    override suspend fun connect(vehicle: Vehicle): Result<Unit> =
        doConnect(vehicle.bmsAddress, vehicle.bmsType, vehicle)

    override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> =
        doConnect(address, type, vehicle = null)

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
            if (vehicle != null) vehicleRepository.touch(vehicle.id)
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
        sessionLock.withLock {
            userInitiatedDisconnect = true
            sessionToTear = currentSession
            currentSession = null
            reconnectToCancel = reconnectJob
            reconnectJob = null
        }
        reconnectToCancel?.cancel()
        sessionToTear?.tearDown()
        _activeData.value = BmsData()
        _activeVehicle.value = null
        ringBuffer.clear()
        _connectionState.value = ConnectionState.Disconnected
        serviceStop()
    }

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
}
