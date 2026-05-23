package com.volty.app.data.ble

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.volty.app.data.bms.AntBmsProtocol
import com.volty.app.data.bms.BmsProtocol
import com.volty.app.data.bms.BmsTypeDetector
import com.volty.app.data.bms.DalyBmsProtocol
import com.volty.app.data.bms.JbdBmsProtocol
import com.volty.app.data.bms.JkBmsProtocol
import com.volty.app.data.memory.SampleRingBuffer
import com.volty.app.data.stats.MovingAvg
import com.volty.app.data.stats.MovingAverage
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.domain.repository.DiscoveredDevice
import com.volty.app.domain.repository.VehicleRepository
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
class KableBmsRepository(
    private val vehicleRepository: VehicleRepository,
    private val serviceController: com.volty.app.service.ServiceController
) : BmsRepository {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _activeData = MutableStateFlow(BmsData())
    override val activeData: StateFlow<BmsData> = _activeData.asStateFlow()

    private val _activeVehicle = MutableStateFlow<Vehicle?>(null)
    override val activeVehicle: StateFlow<Vehicle?> = _activeVehicle.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val ringBuffer = SampleRingBuffer(capacity = 30 * 60) // 30 min @ 1 Hz

    private var peripheral: Peripheral? = null
    private var protocol: BmsProtocol? = null
    private var observeJob: Job? = null
    private var pollingJob: Job? = null

    private val advertisementCache = mutableMapOf<String, com.juul.kable.Advertisement>()

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
        return try {
            disconnect()
            _connectionState.value = ConnectionState.Connecting(vehicle)
            _activeVehicle.value = vehicle

            val proto = createProtocol(type)
            protocol = proto

            var advertisement = advertisementCache[address]
            if (advertisement == null) {
                advertisement = withTimeoutOrNull(5_000L) {
                    Scanner().advertisements.first { it.identifier.toString() == address }
                }
                if (advertisement != null) advertisementCache[address] = advertisement
            }
            if (advertisement == null) {
                _connectionState.value = ConnectionState.Failed("Device not found")
                return Result.failure(IllegalStateException("Device not found"))
            }

            val p = Peripheral(advertisement)
            peripheral = p
            p.connect()

            val notifyChar = characteristicOf(
                service = Uuid.parse(proto.uuids.serviceUuid),
                characteristic = Uuid.parse(proto.uuids.notifyCharUuid)
            )

            observeJob = scope.launch {
                p.observe(notifyChar).collect { data ->
                    proto.onNotification(data)
                    proto.latestData()?.let { bms ->
                        val sample = bms.copy(timestamp = Clock.System.now())
                        _activeData.value = sample
                        ringBuffer.push(sample)
                    }
                }
            }

            delay(200)

            val writeChar = characteristicOf(
                service = Uuid.parse(proto.uuids.serviceUuid),
                characteristic = Uuid.parse(proto.uuids.writeCharUuid)
            )
            for (cmd in proto.handshakeCommands()) {
                p.write(writeChar, cmd, WriteType.WithoutResponse)
                delay(100)
            }

            val pollCmds = proto.pollCommands()
            if (pollCmds.isNotEmpty()) {
                pollingJob = scope.launch {
                    while (isActive) {
                        try {
                            for (cmd in pollCmds) {
                                p.write(writeChar, cmd, WriteType.WithoutResponse)
                                delay(50)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // retry next cycle
                        }
                        delay(proto.pollIntervalMs)
                    }
                }
            }

            _connectionState.value = ConnectionState.Connected(vehicle)
            serviceController.start()
            if (vehicle != null) vehicleRepository.touch(vehicle.id)
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(e.message ?: "Connection failed")
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        pollingJob?.cancel(); pollingJob = null
        observeJob?.cancel(); observeJob = null
        serviceController.stop()
        try { peripheral?.disconnect() } catch (_: Exception) {}
        peripheral = null
        protocol?.reset(); protocol = null
        _activeData.value = BmsData()
        _activeVehicle.value = null
        ringBuffer.clear()
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun samples(window: Duration): Flow<List<BmsData>> =
        _activeData.map { ringBuffer.within(window) }

    override fun movingAverage(window: Duration): StateFlow<MovingAvg> {
        val flow = MutableStateFlow(MovingAvg(0f, 0f, window))
        scope.launch {
            _activeData.collect {
                flow.value = MovingAverage.over(ringBuffer.within(window), window)
            }
        }
        return flow.asStateFlow()
    }

    private fun createProtocol(type: BmsType): BmsProtocol = when (type) {
        BmsType.JK_BMS -> JkBmsProtocol()
        BmsType.JBD_BMS -> JbdBmsProtocol()
        BmsType.ANT_BMS -> AntBmsProtocol()
        BmsType.DALY_BMS -> DalyBmsProtocol()
    }

    fun close() {
        runCatching { scope.cancel() }
    }
}
