package ru.sodovaya.volty.data.ble

import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.CanBusScanner
import ru.sodovaya.volty.data.bms.MotionSource
import ru.sodovaya.volty.data.bms.SerialPollSource
import ru.sodovaya.volty.data.bms.VescProtocol
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
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
    /** Live advertisement evidence wins over any remembered type correction. */
    private val liveBegodeAdvertisement: Boolean = false,
    private val connectionState: MutableStateFlow<ConnectionState>,
    /** Per-link write health: the repository owns folding it into vehicle state. */
    private val onBurstPollWriteFailure: (Exception) -> Unit = {},
    private val onBurstPollWriteSuccess: () -> Unit = {},
    /** Per-link plain-VESC diagnostic: notifications arrive but no reply decodes. */
    private val onPlainVescNotificationsNotUnderstood: () -> Unit = {},
    /** A later plain-VESC decode clears [onPlainVescNotificationsNotUnderstood]'s state. */
    private val onPlainVescDecode: () -> Unit = {},
    /**
     * Called for every parsed sample. The session does not own where samples
     * go: with more than one pack behind a link there is no single
     * destination, so routing and aggregation belong to the caller.
     */
    private val onSample: (packIndex: Int, data: BmsData, sections: List<SectionState>) -> Unit,
    /**
     * Called for every parsed controller (motion) sample, when [protocol]
     * also implements [MotionSource]. Defaults to a no-op so existing
     * construction sites — none of which care about motion yet — still
     * compile unchanged.
     */
    private val onMotionSample: (controllerIndex: Int, data: ControllerData) -> Unit = { _, _ -> },
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

    /** The observer, poller and watchdog share one accounted view of a plain VESC link. */
    private val noSampleEverActivity = NoSampleEverWatchdogActivity(protocol)

    /**
     * The write characteristic, hoisted out of [doConnect] so [scanCanBus] can
     * reach it — the one place anything outside the poll loop needs to put
     * bytes on this link. Null until the link is up, which is exactly when a
     * scan has nothing to talk to.
     */
    @Volatile
    private var writeChar: com.juul.kable.Characteristic? = null

    val peripheralRef: Peripheral get() = peripheral

    /**
     * Run one `COMM_PING_CAN` on this link (`G §3` flow 4).
     *
     * Null when this link cannot be asked at all — its protocol is not a
     * [CanBusScanner] (anything that is not VESC), or it is not up yet — and
     * null again when it was asked and stayed silent. The two are the same
     * sentence to a rider ("we could not look"), and the repository, which knows
     * the link's [LinkSpec.protocolKind], is where they are told apart.
     *
     * The session does not decide *how* the scan reaches the wire: a gateway
     * link hands it to its own serial loop, a plain VESC link writes it here.
     * See [CanBusScanner.scanCanBus].
     */
    suspend fun scanCanBus(): List<Int>? {
        val scanner = protocol as? CanBusScanner ?: return null
        val ch = writeChar ?: return null
        return scanner.scanCanBus { cmd -> writeCommand(ch, cmd) }
    }

    /**
     * The last write guard for the one characteristic that can reconfigure a
     * wheel. A remembered type may select a command-sending protocol for a
     * device whose fresh advertisement still identifies Begode; the write
     * path, not the picker, is the safety boundary.
     */
    private suspend fun writeCommand(
        characteristic: com.juul.kable.Characteristic,
        command: ByteArray
    ) {
        if (!shouldWriteProtocolCommand(protocol, liveBegodeAdvertisement)) return
        peripheral.write(characteristic, command, WriteType.WithoutResponse)
    }

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

        // Best effort: Android may reject this hint, and some peripherals do
        // not expose the Android transport controls at all. The request is
        // deliberately outside the protocol path so either outcome leaves
        // the exact same handshake and poll dialogue running.
        requestBleConnectionPriority(
            peripheral,
            BleConnectionTuning.priorityFor(foreground = true)
        )
        // MTU negotiation is also best-effort. The accumulator below remains
        // deliberately unchanged because a peripheral may refuse this
        // request or still fragment notifications at any negotiated MTU.
        requestBleMtu(peripheral, BleConnectionTuning.requestedMtu)

        val notifyChar = characteristicOf(
            service = Uuid.parse(protocol.uuids.serviceUuid),
            characteristic = Uuid.parse(protocol.uuids.notifyCharUuid)
        )
        val writeChar = characteristicOf(
            service = Uuid.parse(protocol.uuids.serviceUuid),
            characteristic = Uuid.parse(protocol.uuids.writeCharUuid)
        )
        // Published for [scanCanBus]; every write below still uses the local,
        // so the poll path is byte-for-byte what it was.
        this.writeChar = writeChar

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
                // Created once here — NOT inside collect — so the gate's
                // per-controller "last seen instance" state persists across
                // notifications, exactly like sampleGate above. Zero when the
                // protocol isn't a MotionSource: routeControllerSamples then
                // has nothing to iterate and always reports not-alive.
                val motionGate = MotionSampleGate((protocol as? MotionSource)?.controllerCount ?: 0)
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
                            writeCommand(writeChar, cmd)
                            delay(BleConfig.writeSpacingMs.coerceAtLeast(100L))
                        }
                    }
                ).collect { data ->
                    // This is deliberately at the accumulator boundary, before
                    // decoding. A VESC can notify a valid transport frame that
                    // answers neither opcode we know; redialling that healthy
                    // GATT link cannot make the frame understandable.
                    processObservedSessionNotification(protocol, data, noSampleEverActivity)
                    val routed = routeObservedSessionSamples(
                        protocol = protocol,
                        packGate = sampleGate,
                        motionGate = motionGate,
                        activity = noSampleEverActivity,
                        onNewSample = { packIndex, bms, sections ->
                            onSample(packIndex, bms.copy(timestamp = Clock.System.now()), sections)
                        },
                        onNewMotion = { controllerIndex, motion ->
                            onMotionSample(controllerIndex, motion.copy(timestamp = Clock.System.now()))
                        }
                    )
                    // Battery protocols retain their historic cached-decode
                    // liveness rule. A plain VESC is different: an unknown
                    // notification after one valid reply still leaves the old
                    // ControllerData cached, and must not mint a fresh decode.
                    if (routed.producedNewDecode ||
                        (protocol !is VescProtocol && routed.hasCachedDecode)
                    ) {
                        lastSampleAtMs = Clock.System.now().toEpochMilliseconds()
                        sampleCount++
                        if (sampleCount % 50 == 0) {
                            println("[VOLTY-BLE] sample #$sampleCount lastSampleAtMs=$lastSampleAtMs")
                        }
                    }
                    if (protocol is VescProtocol && routed.producedNewDecode) {
                        onPlainVescDecode()
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
        //
        // Two poll shapes, and the protocol picks which one it is. A
        // [SerialPollSource] owns its own request/reply dialogue — a CAN
        // gateway must keep exactly ONE forwarded request in flight and match
        // each bare reply to what it asked for, which a fire-and-forget burst
        // cannot express. Everything else keeps the burst path bit-for-bit:
        // `as?` is null for every protocol that does not opt in.
        val serial = protocol as? SerialPollSource
        if (serial != null) {
            pollingJob = parentScope.launch {
                try {
                    serial.runPollLoop { cmd ->
                            writeCommand(writeChar, cmd)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The loop paces itself around write failures; anything
                    // that still escapes ends polling and lets the watchdog
                    // judge the link, rather than spinning here.
                    println("[VOLTY-BLE] serial poll loop: ${e::class.simpleName}: ${e.message}")
                }
            }
        } else {
            pollingJob = parentScope.launch {
                while (isActive) {
                    try {
                        // Read commands inside the cycle: plain VESC begins
                        // with a short two-opcode probe and a reply changes
                        // its next request. Keeping this list outside the
                        // loop would make that selection unreachable.
                        if (!runSessionBurstPollCycle(
                                protocol,
                                activity = noSampleEverActivity,
                                write = { cmd -> writeCommand(writeChar, cmd) },
                                wait = { delay(it) },
                                onWriteFailure = onBurstPollWriteFailure,
                                onWriteSuccess = onBurstPollWriteSuccess
                            )
                        ) return@launch
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
                    val staleAfterSample = lastSampleAtMs > 0 && timeSinceSample > BleConfig.staleSampleMs
                    val noSampleEver = lastSampleAtMs == 0L && timeSinceConnect > BleConfig.noSampleEverMs
                    if (staleAfterSample || noSampleEver) {
                        when (evaluateStaleSessionActivity(
                            noSampleEverActivity,
                            onPlainVescNotificationsNotUnderstood
                        )) {
                            NoSampleEverWatchdogDecision.REDIAL -> {
                                println("[VOLTY-BLE] watchdog: STALE — no fresh samples — triggering reconnect")
                                if (!torn) onDropDetected("No samples")
                                return@launch
                            }
                            NoSampleEverWatchdogDecision.NOT_UNDERSTOOD -> {
                                println("[VOLTY-BLE] watchdog: VESC notifications are not understood; keeping link up")
                            }
                            NoSampleEverWatchdogDecision.WRITE_FAILED ->
                                println("[VOLTY-BLE] watchdog: VESC poll writes are failing; keeping link up")
                        }
                    }
                }
            }
        }

        return Result.success(Unit)
    }

    /**
     * Update the radio hint without touching protocol state. Called by the
     * repository's app lifecycle bridge; a refusal is intentionally ignored.
     */
    internal fun setForeground(foreground: Boolean) {
        requestBleConnectionPriority(
            peripheral,
            BleConnectionTuning.priorityFor(foreground)
        )
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
            // Before reset(), which is what answers a scan parked on this link:
            // a scan that reached the wire after the peripheral was gone would
            // wait out its whole window for a reply that cannot arrive.
            writeChar = null
            try { peripheral.disconnect() } catch (_: Exception) {}
            protocol.reset()
        }
    }
}

/** Pure seam for the write boundary; tests can kill either half independently. */
internal fun shouldWriteProtocolCommand(
    protocol: BmsProtocol,
    liveBegodeAdvertisement: Boolean
): Boolean = !liveBegodeAdvertisement && protocol !is BegodeProtocol

/**
 * The no-decode watchdog has a special case only for the direct VESC UART
 * protocol. Gateway links implement [SerialPollSource] and all battery
 * protocols retain their exact historic redial path.
 */
internal enum class NoSampleEverWatchdogDecision { REDIAL, NOT_UNDERSTOOD, WRITE_FAILED }

/**
 * The small concurrent accumulator behind the no-sample-ever watchdog branch.
 * It intentionally counts all direct-VESC notifications before decoding, then
 * lets the watchdog classify the absence of a sample without touching serial
 * gateways or battery protocols.
 */
internal class NoSampleEverWatchdogActivity(private val protocol: BmsProtocol) {
    @Volatile
    private var notificationArrived: Boolean = false

    @Volatile
    private var hasPollWriteFailure: Boolean = false

    fun recordNotificationArrival() {
        if (protocol is VescProtocol) notificationArrived = true
    }

    fun recordPollWriteFailure() {
        if (protocol is VescProtocol) hasPollWriteFailure = true
    }

    fun recordPollWriteSuccess() {
        if (protocol is VescProtocol) hasPollWriteFailure = false
    }

    /** A genuinely new decode starts a fresh notification-classification window. */
    fun recordDecodedSample() {
        if (protocol is VescProtocol) notificationArrived = false
    }

    fun decision(): NoSampleEverWatchdogDecision {
        if (protocol !is VescProtocol) return NoSampleEverWatchdogDecision.REDIAL
        return when {
            hasPollWriteFailure -> NoSampleEverWatchdogDecision.WRITE_FAILED
            notificationArrived -> NoSampleEverWatchdogDecision.NOT_UNDERSTOOD
            else -> NoSampleEverWatchdogDecision.REDIAL
        }
    }
}

/**
 * Level-trigger the reason publication: repository state can temporarily
 * replace NOT_UNDERSTOOD with WRITE_FAILED, so the session must be able to
 * publish the former again once writes recover and it remains current.
 */
internal fun evaluateStaleSessionActivity(
    activity: NoSampleEverWatchdogActivity,
    onNotUnderstood: () -> Unit
): NoSampleEverWatchdogDecision = activity.decision().also { decision ->
    if (decision == NoSampleEverWatchdogDecision.NOT_UNDERSTOOD) onNotUnderstood()
}

/**
 * The exact observer boundary used by [ConnectionSession]: account arrival
 * before the protocol has an opportunity to discard an undecodable frame.
 */
internal fun processObservedSessionNotification(
    protocol: BmsProtocol,
    data: ByteArray,
    activity: NoSampleEverWatchdogActivity
) {
    activity.recordNotificationArrival()
    protocol.onNotification(data)
}

/** What the current notification proved independently of protocol caches. */
internal data class ObservedSessionSamples(
    val hasCachedDecode: Boolean,
    val producedNewDecode: Boolean
)

/**
 * The exact post-decoder routing seam used by [ConnectionSession]. The gates,
 * rather than non-null cached values, decide whether this notification
 * produced a new decode. That distinction drives the plain-VESC watchdog.
 */
internal fun routeObservedSessionSamples(
    protocol: BmsProtocol,
    packGate: PackSampleGate,
    motionGate: MotionSampleGate,
    activity: NoSampleEverWatchdogActivity,
    onNewSample: (packIndex: Int, data: BmsData, sections: List<SectionState>) -> Unit,
    onNewMotion: (controllerIndex: Int, data: ControllerData) -> Unit
): ObservedSessionSamples {
    var producedNewDecode = false
    val packCached = routePackSamples(protocol, packGate) { packIndex, data, sections ->
        producedNewDecode = true
        onNewSample(packIndex, data, sections)
    }
    val motionCached = (protocol as? MotionSource)?.let { motion ->
        routeControllerSamples(motion, motionGate) { controllerIndex, data ->
            producedNewDecode = true
            onNewMotion(controllerIndex, data)
        }
    } ?: false
    if (producedNewDecode) activity.recordDecodedSample()
    return ObservedSessionSamples(
        hasCachedDecode = packCached || motionCached,
        producedNewDecode = producedNewDecode
    )
}

/**
 * The exact non-serial poll boundary used by [ConnectionSession]. Write
 * outcomes update the watchdog's view before the per-link state callbacks
 * publish their existing Task 2 diagnostic.
 */
internal suspend fun runSessionBurstPollCycle(
    protocol: BmsProtocol,
    activity: NoSampleEverWatchdogActivity,
    write: suspend (ByteArray) -> Unit,
    wait: suspend (Long) -> Unit,
    onWriteFailure: (Exception) -> Unit = {},
    onWriteSuccess: () -> Unit = {}
): Boolean = runBurstPollCycle(
    protocol = protocol,
    write = write,
    wait = wait,
    onWriteFailure = { failure ->
        activity.recordPollWriteFailure()
        onWriteFailure(failure)
    },
    onWriteSuccess = {
        activity.recordPollWriteSuccess()
        onWriteSuccess()
    }
)

/**
 * One non-serial poll burst. The command list belongs inside this function so
 * stateful plain protocols can change what the next live cycle writes.
 *
 * The serial gateway path deliberately does not use this: its request/reply
 * accounting is owned by [SerialPollSource.runPollLoop].
 */
internal suspend fun runBurstPollCycle(
    protocol: BmsProtocol,
    write: suspend (ByteArray) -> Unit,
    wait: suspend (Long) -> Unit,
    onWriteFailure: (Exception) -> Unit = {},
    onWriteSuccess: () -> Unit = {}
): Boolean {
    val commands = protocol.pollCommands()
    if (commands.isEmpty()) return false
    for (command in commands) {
        try {
            write(command)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onWriteFailure(e)
            println("[VOLTY-BLE] burst poll write: ${e::class.simpleName}: ${e.message}")
            return true
        }
        onWriteSuccess()
        wait(BleConfig.writeSpacingMs)
    }
    return true
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

/**
 * The motion twin of [routePackSamples] — same two-diets shape, one
 * [MotionSource] controller at a time instead of one pack at a time.
 *
 * The returned Boolean is LINK liveness for motion: true whenever any
 * controller has a decode cached at all, new or not. [ConnectionSession]
 * ORs this with battery link-liveness so a controller-only device (no packs
 * behind the link) still keeps [ConnectionSession.lastSampleAtMs] fed and
 * out of the watchdog's reconnect path.
 *
 * [onNewMotion] fires only when [gate] confirms a genuinely new decode for
 * that controller — the same new-instance discriminator [PackSampleGate]
 * uses for packs.
 */
internal fun routeControllerSamples(
    protocol: MotionSource,
    gate: MotionSampleGate,
    onNewMotion: (controllerIndex: Int, data: ControllerData) -> Unit
): Boolean {
    var alive = false
    for (i in 0 until protocol.controllerCount) {
        val m = protocol.latestMotion(i) ?: continue
        alive = true
        if (!gate.advance(i, m)) continue
        onNewMotion(i, m)
    }
    return alive
}
