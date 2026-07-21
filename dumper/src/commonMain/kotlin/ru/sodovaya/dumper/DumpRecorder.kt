package ru.sodovaya.dumper

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CancellationException
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

    /**
     * Last failure as human-readable text, or null. Background jobs (scan,
     * notification collection) fail long after their launching call returned,
     * so a return value cannot carry those errors — this flow can. Cleared on
     * the next scan or record attempt.
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var scanJob: Job? = null
    private var recordJob: Job? = null
    private var peripheral: Peripheral? = null

    /**
     * True from the moment [record] starts until [stop]. Guards against a
     * second tap racing the first over [peripheral] and [recordJob]. All
     * access happens on the single-threaded UI scope, so a plain field is
     * enough.
     */
    private var recordActive = false

    /**
     * Lists every advertising device, unfiltered. A Begode module's local name
     * is not known in advance and it does not always advertise FFE0, so
     * filtering here would hide the very device we came for.
     */
    fun startScan() {
        scanJob?.cancel()
        _devices.value = emptyList()
        _error.value = null
        scanJob = scope.launch {
            try {
                val seen = LinkedHashMap<String, Device>()
                Scanner().advertisements.collect { ad ->
                    val id = ad.identifier.toString()
                    seen[id] = Device(address = id, name = ad.name, rssi = ad.rssi)
                    _devices.value = seen.values.sortedByDescending { it.rssi }
                }
            } catch (e: CancellationException) {
                // Normal stop (stopScan or scope teardown) — not an error.
                throw e
            } catch (e: Exception) {
                // Bluetooth switched off mid-scan, adapter error, … — surface
                // as text instead of taking the process down.
                _error.value = "Scan failed: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /**
     * Connect and pump notifications into [onChunk] until [stop] is called.
     * Suspends through connect, then returns once the collection job is
     * *launched* — service discovery and the subscription itself complete in
     * the background, so the first bytes may arrive seconds later. Connect
     * failures come back as [Result.failure]; anything that fails after this
     * returns (disconnect, wheel powered off) surfaces through [error].
     */
    suspend fun record(device: Device, onChunk: (ByteArray) -> Unit): Result<Unit> {
        // Reentrancy guard: a second call while one is in flight must not
        // clobber the connection the first one is building.
        if (recordActive) {
            return Result.failure(IllegalStateException("Already connecting or recording"))
        }
        recordActive = true
        _error.value = null
        return try {
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
                try {
                    // Wait for service discovery before subscribing:
                    // peripheral.services is null until it completes.
                    p.services.filterNotNull().first()
                    p.observe(notify).collect { onChunk(it) }
                } catch (e: CancellationException) {
                    // Normal stop — not an error.
                    throw e
                } catch (e: Exception) {
                    // Mid-recording failure: walked out of range, wheel powered
                    // off, Bluetooth toggled. Everything already handed to the
                    // writer is on disk; report instead of crashing.
                    _error.value = "Recording interrupted: ${e.message ?: e::class.simpleName}"
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            recordActive = false
            throw e
        } catch (e: Exception) {
            recordActive = false
            runCatching { peripheral?.disconnect() }
            peripheral = null
            _error.value = "Connect failed: ${e.message ?: e::class.simpleName}"
            Result.failure(e)
        }
    }

    fun stop() {
        // Reset fields synchronously so a new record() can start immediately;
        // tear the old connection down on captured references in the background.
        val job = recordJob
        val p = peripheral
        recordJob = null
        peripheral = null
        recordActive = false
        scope.launch {
            job?.cancelAndJoin()
            runCatching { p?.disconnect() }
        }
    }
}
