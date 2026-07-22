package ru.sodovaya.volty.data.ble

import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.Vehicle
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
    private val connectionState: MutableStateFlow<ConnectionState>,
    /**
     * Called for every parsed sample. The session does not own where samples
     * go: with more than one pack behind a link there is no single
     * destination, so routing and aggregation belong to the caller.
     */
    private val onSample: (packIndex: Int, data: BmsData, sections: List<SectionState>) -> Unit,
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
     * Most recent sample receipt time (epoch ms). 0 means no sample has been
     * received yet for this session. Used by [KableBmsRepository.onAppResumed]
     * to detect background-induced silent drops the in-session watchdog
     * couldn't catch because its dispatcher was suspended.
     */
    internal fun lastSampleAtMs(): Long = lastSampleAtMs

    /**
     * Attempt to bring the peripheral up. Throws on hard failure; the repo
     * decides whether to retry.
     *
     * Returns once the link is established AND notifications are subscribed.
     * Polling, state-watch and the watchdog continue running in the
     * background under [parentScope].
     */
    suspend fun connect(): Result<Unit> = try {
        doConnect()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // A failed handshake write (link dropped mid-setup, missing
        // characteristic, …) must surface as Result.failure so the repo's
        // failure branch tears the half-built session down instead of leaving
        // its background jobs running.
        Result.failure(e)
    }

    private suspend fun doConnect(): Result<Unit> {
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
                // Protocols cache their last decode, and this loop re-reads
                // every pack on every notification — the gate turns that into
                // "onSample fires only when the pack genuinely decoded
                // something new". Without it a silent pack would keep being
                // re-submitted with frozen data and could never go stale.
                // The gate does NOT feed the watchdog: lastSampleAtMs
                // refreshes on any cached decode (see routePackSamples).
                val sampleGate = PackSampleGate(protocol.packCount)
                peripheral.observe(
                    notifyChar,
                    // The handshake MUST go out only AFTER notifications are
                    // actually enabled (CCCD written). JK BMS streams cell data
                    // solely in response to a one-shot 0x96 and never repeats it
                    // (pollCommands is empty), so a handshake raced ahead of the
                    // live subscription is lost forever and the device looks
                    // connected-but-silent. A fixed pre-write delay couldn't
                    // guarantee this — service discovery + CCCD enable on Android
                    // routinely exceeds it. Kable's onSubscription fires exactly
                    // after notifications are enabled, matching the ordering the
                    // reference (fl4p/batmon-ha jikong.py) relies on:
                    // start_notify → then write 0x97/0x96. Polling BMS types
                    // (JBD/Daly/ANT) masked this by re-sending every cycle.
                    onSubscription = {
                        delay(BleConfig.handshakeWarmupMs)
                        for (cmd in protocol.handshakeCommands()) {
                            peripheral.write(writeChar, cmd, WriteType.WithoutResponse)
                            delay(BleConfig.writeSpacingMs.coerceAtLeast(100L))
                        }
                    }
                ).collect { data ->
                    protocol.onNotification(data)
                    val linkAlive = routePackSamples(protocol, sampleGate) { packIndex, bms, sections ->
                        onSample(packIndex, bms.copy(timestamp = Clock.System.now()), sections)
                    }
                    if (linkAlive) {
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

        // Note: the handshake is issued from the observe(onSubscription) hook
        // above — NOT here — so it can never race ahead of the live
        // notification subscription. Poll-based protocols still (re)send their
        // poll commands below; that path self-heals by design.
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

/**
 * Routes one notification's worth of per-pack protocol state to two consumers
 * with deliberately different diets:
 *
 *  - The returned Boolean is LINK liveness — true whenever any pack has a
 *    decode cached at all (`latestData` non-null), new or not. It feeds the
 *    session watchdog's `lastSampleAtMs`, exactly the behaviour the watchdog
 *    had before [PackSampleGate] existed: once decoding has started, every
 *    notification counts. A device that keeps notifying without producing new
 *    decodes — e.g. a JK BMS answering with settings/device-info frames,
 *    which never assign `lastData` — is a live link, not a dead one, and must
 *    not be torn down into a reconnect loop.
 *
 *  - [onNewSample] is PACK liveness — invoked only when [gate] confirms the
 *    protocol produced a genuinely new decode for that pack. It feeds
 *    [VehicleConnection]'s per-pack staleness sweep and the ring buffer.
 *    The pack's section breakdown rides along with each new sample: it is
 *    read here, from the same protocol state in the same single-threaded
 *    funnel, so sample and sections always describe ONE decode — and it is
 *    read only for gated samples, never rebuilt for a silent pack.
 */
internal fun routePackSamples(
    protocol: BmsProtocol,
    gate: PackSampleGate,
    onNewSample: (packIndex: Int, data: BmsData, sections: List<SectionState>) -> Unit
): Boolean {
    var linkAlive = false
    for (packIndex in 0 until protocol.packCount) {
        val bms = protocol.latestData(packIndex) ?: continue
        linkAlive = true
        // Gate BEFORE the timestamp-stamping copy the caller makes: the copy
        // creates a fresh instance every time and would defeat the identity
        // check.
        if (!gate.advance(packIndex, bms)) continue
        onNewSample(packIndex, bms, protocol.sections(packIndex))
    }
    return linkAlive
}
