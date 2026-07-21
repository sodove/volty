package ru.sodovaya.dumper

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Scans for BLE devices and streams one device's notifications out through a
 * callback.
 *
 * It NEVER writes to the characteristic. FFE1 on a Begode wheel is the command
 * channel — light, pedal mode, tiltback — so sending anything at it without
 * knowing the protocol could reconfigure the wheel under its rider. This tool
 * only listens.
 */
@OptIn(ExperimentalUuidApi::class)
class DumpRecorder(private val scope: CoroutineScope) {

    data class Device(val address: String, val name: String?, val rssi: Int)

    private companion object {
        const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val NOTIFY_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    }

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private var scanJob: Job? = null
    private var recordJob: Job? = null
    private var peripheral: Peripheral? = null

    /**
     * Lists every advertising device, unfiltered. A Begode module's local name
     * is not known in advance and it does not always advertise FFE0, so
     * filtering here would hide the very device we came for.
     */
    fun startScan() {
        scanJob?.cancel()
        _devices.value = emptyList()
        scanJob = scope.launch {
            val seen = LinkedHashMap<String, Device>()
            Scanner().advertisements.collect { ad ->
                val id = ad.identifier.toString()
                seen[id] = Device(address = id, name = ad.name, rssi = ad.rssi)
                _devices.value = seen.values.sortedByDescending { it.rssi }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * Connect and pump notifications into [onChunk] until [stop] is called.
     * Returns once the subscription is live; failures come back as
     * [Result.failure] so the screen can show them without crashing.
     */
    suspend fun record(device: Device, onChunk: (ByteArray) -> Unit): Result<Unit> = try {
        stopScan()
        val advertisement = Scanner().advertisements.first {
            it.identifier.toString() == device.address
        }
        val p = Peripheral(advertisement)
        peripheral = p
        p.connect()
        val notify = characteristicOf(
            service = Uuid.parse(SERVICE_UUID),
            characteristic = Uuid.parse(NOTIFY_UUID)
        )
        recordJob = scope.launch {
            // Wait for service discovery before subscribing: peripheral.services
            // is null until it completes.
            p.services.filterNotNull().first()
            p.observe(notify).collect { onChunk(it) }
        }
        Result.success(Unit)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun stop() {
        scope.launch {
            recordJob?.cancelAndJoin()
            recordJob = null
            runCatching { peripheral?.disconnect() }
            peripheral = null
        }
    }
}
