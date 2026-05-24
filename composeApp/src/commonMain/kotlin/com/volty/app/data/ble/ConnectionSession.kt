package com.volty.app.data.ble

import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.volty.app.data.bms.BmsProtocol
import com.volty.app.data.memory.SampleRingBuffer
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.model.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Owns the lifetime of a *single* peripheral connection attempt.
 *
 * One session per attempt — when the link drops or the user disconnects, the
 * session is torn down and the repo decides whether to spin up a new one.
 *
 * Responsibilities:
 *  - connect, subscribe, handshake, poll
 *  - state observer (link drop detection)
 *  - stale-sample watchdog (faster than BLE supervision)
 *  - clean teardown
 *
 * Reconnect orchestration lives in [KableBmsRepository], not here. A session
 * does not reconnect itself — it just reports drop conditions via
 * [onDropDetected] and lets the repo decide what to do.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalTime::class)
internal class ConnectionSession(
    private val parentScope: CoroutineScope,
    private val peripheral: Peripheral,
    private val protocol: BmsProtocol,
    private val vehicle: Vehicle?,
    private val ringBuffer: SampleRingBuffer,
    private val activeData: MutableStateFlow<BmsData>,
    private val connectionState: MutableStateFlow<ConnectionState>,
    /** Callback when a link drop is detected (state event or watchdog). */
    private val onDropDetected: suspend (reason: String) -> Unit
) {

    private val cancelMutex = Mutex()
    private var torn: Boolean = false

    private var observeJob: Job? = null
    private var pollingJob: Job? = null
    private var stateJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile
    private var lastSampleAtMs: Long = 0L

    val peripheralRef: Peripheral get() = peripheral

    /**
     * Attempt to bring the peripheral up. Throws on hard failure; the repo
     * decides whether to retry.
     *
     * Returns once the link is established AND notifications are subscribed.
     * Polling, state-watch and the watchdog continue running in the
     * background under [parentScope].
     */
    suspend fun connect(): Result<Unit> {
        val connectOk = withTimeoutOrNull(BleConfig.connectTimeoutMs) {
            peripheral.connect()
            true
        }
        if (connectOk == null) {
            return Result.failure(IllegalStateException("Connect timeout"))
        }

        val notifyChar = characteristicOf(
            service = Uuid.parse(protocol.uuids.serviceUuid),
            characteristic = Uuid.parse(protocol.uuids.notifyCharUuid)
        )
        val writeChar = characteristicOf(
            service = Uuid.parse(protocol.uuids.serviceUuid),
            characteristic = Uuid.parse(protocol.uuids.writeCharUuid)
        )

        observeJob = parentScope.launch {
            try {
                // Wait for service discovery to complete before subscribing.
                // peripheral.services is StateFlow<List<DiscoveredService>?> — null until discovered.
                peripheral.services.filterNotNull().first()
                var sampleCount = 0
                peripheral.observe(notifyChar).collect { data ->
                    protocol.onNotification(data)
                    protocol.latestData()?.let { bms ->
                        val sample = bms.copy(timestamp = Clock.System.now())
                        activeData.value = sample
                        ringBuffer.push(sample)
                        lastSampleAtMs = Clock.System.now().toEpochMilliseconds()
                        sampleCount++
                        if (sampleCount % 50 == 0) {
                            println("[VOLTY-BLE] sample #$sampleCount lastSampleAtMs=$lastSampleAtMs")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[VOLTY-BLE] observeJob: exception ${e::class.simpleName}: ${e.message}")
            }
        }

        delay(BleConfig.handshakeWarmupMs)

        for (cmd in protocol.handshakeCommands()) {
            peripheral.write(writeChar, cmd, WriteType.WithoutResponse)
            delay(BleConfig.writeSpacingMs.coerceAtLeast(100L))
        }

        val pollCmds = protocol.pollCommands()
        if (pollCmds.isNotEmpty()) {
            pollingJob = parentScope.launch {
                while (isActive) {
                    try {
                        for (cmd in pollCmds) {
                            peripheral.write(writeChar, cmd, WriteType.WithoutResponse)
                            delay(BleConfig.writeSpacingMs)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // retry next cycle
                    }
                    delay(protocol.pollIntervalMs)
                }
            }
        }

        stateJob = parentScope.launch {
            try {
                peripheral.state.collect { st ->
                    if (st is State.Disconnected) {
                        println("[VOLTY-BLE] stateJob: Disconnected event received")
                        if (!torn) onDropDetected("Link dropped")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[VOLTY-BLE] stateJob: exception ${e::class.simpleName}: ${e.message}")
            }
        }

        watchdogJob = parentScope.launch {
            val connectedAtMs = Clock.System.now().toEpochMilliseconds()
            println("[VOLTY-BLE] watchdog: launched at $connectedAtMs")
            delay(BleConfig.watchdogGraceMs)
            while (isActive) {
                delay(BleConfig.watchdogTickMs)
                if (torn) return@launch
                val state = connectionState.value
                val nowMs = Clock.System.now().toEpochMilliseconds()
                val timeSinceSample = nowMs - lastSampleAtMs
                val timeSinceConnect = nowMs - connectedAtMs
                if (state is ConnectionState.Connected) {
                    val stale = (lastSampleAtMs > 0 && timeSinceSample > BleConfig.staleSampleMs) ||
                                (lastSampleAtMs == 0L && timeSinceConnect > BleConfig.noSampleEverMs)
                    if (stale) {
                        println("[VOLTY-BLE] watchdog: STALE — sample age=${timeSinceSample}ms — triggering reconnect")
                        if (!torn) onDropDetected("No samples")
                        return@launch
                    }
                }
            }
        }

        return Result.success(Unit)
    }

    /**
     * Tear down the session: cancel all background jobs, disconnect the peripheral.
     *
     * Idempotent and safe from any coroutine. Held under [cancelMutex] so that
     * concurrent callers (user disconnect + watchdog firing simultaneously)
     * don't race.
     */
    suspend fun tearDown() {
        cancelMutex.withLock {
            if (torn) return@withLock
            torn = true
            pollingJob?.cancelAndJoin(); pollingJob = null
            observeJob?.cancelAndJoin(); observeJob = null
            stateJob?.cancelAndJoin(); stateJob = null
            watchdogJob?.cancelAndJoin(); watchdogJob = null
            try { peripheral.disconnect() } catch (_: Exception) {}
            protocol.reset()
        }
    }
}
