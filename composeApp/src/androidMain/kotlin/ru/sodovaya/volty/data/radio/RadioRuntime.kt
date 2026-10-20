package ru.sodovaya.volty.data.radio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.ArrayBlockingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.sodovaya.volty.domain.radio.RadioAddress
import ru.sodovaya.volty.domain.radio.RadioClientPhase
import ru.sodovaya.volty.domain.radio.RadioClientSession
import ru.sodovaya.volty.domain.radio.RadioController
import ru.sodovaya.volty.domain.radio.RadioDatagram
import ru.sodovaya.volty.domain.radio.RadioDiagnostics
import ru.sodovaya.volty.domain.radio.RadioLoad
import ru.sodovaya.volty.domain.radio.RadioLossCounter
import ru.sodovaya.volty.domain.radio.RadioTickStats
import ru.sodovaya.volty.domain.radio.RadioMemberStat
import ru.sodovaya.volty.domain.radio.RadioMessage
import ru.sodovaya.volty.domain.radio.RadioRole
import ru.sodovaya.volty.domain.radio.radioRequiredPermissions
import ru.sodovaya.volty.domain.radio.shouldAutoJoin
import ru.sodovaya.volty.domain.radio.RadioError
import ru.sodovaya.volty.domain.radio.RadioHubSession
import ru.sodovaya.volty.domain.radio.RadioKeyStore
import ru.sodovaya.volty.domain.radio.RadioLink
import ru.sodovaya.volty.domain.radio.RadioLinkResult
import ru.sodovaya.volty.domain.radio.RadioMember
import ru.sodovaya.volty.domain.radio.RadioMode
import ru.sodovaya.volty.domain.radio.RadioState
import ru.sodovaya.volty.domain.radio.RadioVoiceGate

/** Who this phone is on the radio: the signed-in Volty account. */
data class RadioIdentity(val userId: String, val name: String)

/**
 * Passes everything through to [android] and remembers the hub address of the last successful
 * join, because [RadioState.Client] does not carry it and the runtime has to send to it.
 */
class RecordingRadioLink(val android: AndroidRadioLink) : RadioLink {
    @Volatile
    var lastHub: RadioAddress? = null
        private set

    override var onDuplicateHub: ((community: String) -> Unit)?
        get() = android.onDuplicateHub
        set(value) {
            android.onDuplicateHub = value
        }

    override suspend fun host(community: String): RadioLinkResult {
        lastHub = null
        return android.host(community)
    }

    override suspend fun join(community: String, timeoutMillis: Long): RadioLinkResult {
        val result = android.join(community, timeoutMillis)
        lastHub = (result as? RadioLinkResult.Joined)?.hub
        return result
    }

    override suspend fun leave() {
        lastHub = null
        android.leave()
    }

    override suspend fun radioNearby(community: String): Boolean = android.radioNearby(community)
}

/**
 * Owns the transport, the hub or client session and the audio for the current [RadioState],
 * and starts/stops [NearbyRadioService] around it.
 *
 * Threading: [controller] is confined to [scope] (a single-threaded scope, the main dispatcher in
 * production); everything here that touches the controller or the [RadioState] observer runs on
 * it. The hub and client sessions are NOT thread-safe and live only on the audio thread: inbound
 * datagrams (receiver thread) and mode changes (main) are queued and drained at the start of every
 * audio tick, and only the audio thread calls `receive` / `tick`.
 *
 * Restart rule: a run is kept while consecutive states are the same kind for the same community;
 * `Starting` and `Lost` -> `Client(JOINING)` rebuild the transport (a new Wi-Fi attach may have a
 * new address) and the session, with a fresh stream id (rulings R8/R15). `Lost` ->
 * `Client(CONNECTED)` keeps the run: that transition comes from the kept run's own session hearing
 * the hub again over the old link (I2). A run's asynchronous reports (phase, socket or audio
 * failure) reach the controller only while that run is still the current one.
 *
 * Framework threads, sockets, audio and services cannot be unit-tested in this repo; verified only
 * on a device.
 */
class RadioRuntime(
    private val context: Context,
    private val controller: RadioController,
    private val link: RecordingRadioLink,
    private val keys: RadioKeyStore,
    private val identity: () -> RadioIdentity?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The rider's "join radio automatically" switch; read on every poll. */
    private val autoJoin: () -> Boolean = { false },
    /** True while one of the app's activities is resumed (ruling R20). */
    private val appVisible: () -> Boolean = { false },
    /** True while this phone's ordinary server voice is connected or connecting (I1: never auto-join over it). */
    private val serverVoiceActive: () -> Boolean = { false },
) {
    private val app = context.applicationContext ?: context
    private val _members = MutableStateFlow<List<RadioMember>>(emptyList())

    /** Everyone on the radio including this phone, refreshed about twice a second. */
    val members: StateFlow<List<RadioMember>> = _members.asStateFlow()

    private val _rttMillis = MutableStateFlow<Long?>(null)

    /** Client only: round trip to the hub. */
    val rttMillis: StateFlow<Long?> = _rttMillis.asStateFlow()

    private val _activeMode = MutableStateFlow<RadioMode?>(null)

    /** The mode the running radio actually uses (a client follows its hub); null with no radio. */
    val activeMode: StateFlow<RadioMode?> = _activeMode.asStateFlow()

    private var attached = false
    @Volatile
    private var autoJoinSuppressedUntil = 0L

    /** What the last radio measured; kept after it ends so a field tester can still export it. */
    @Volatile
    private var frozen: RadioDiagnostics? = null
    private var previous: RadioState = RadioState.Idle
    private var startJob: Job? = null
    private var serviceStarted = false

    @Volatile
    private var active: Run? = null

    /** Begins reacting to [RadioController.state]; call once from the main thread. No-op below Android 10. */
    fun attach() {
        if (attached || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        attached = true
        link.onDuplicateHub = { scope.launch { controller.onDuplicateHub() } }
        controller.onModeChanged = { mode -> active?.requestMode(mode) }
        scope.launch {
            controller.state.collect { state ->
                val before = previous
                previous = state
                onState(before, state)
            }
        }
        scope.launch {
            while (true) {
                delay(AUTO_JOIN_POLL_MILLIS)
                try {
                    pollAutoJoin()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed scan or key read must not end auto-join for the rest of the process.
                    android.util.Log.w("RadioRuntime", "auto-join poll failed", e)
                }
            }
        }
    }

    /** After a manual leave the auto-join poll stays quiet for a while, so leaving is not undone 30 s later. */
    fun suppressAutoJoin() {
        autoJoinSuppressedUntil = clock() + AUTO_JOIN_SUPPRESS_MILLIS
    }

    private val _diagnosticsAvailable = MutableStateFlow(false)

    /** True once a radio has run since the app started, so its measurements can be exported. */
    val diagnosticsAvailable: StateFlow<Boolean> = _diagnosticsAvailable.asStateFlow()

    /** The current (or, once it has ended, the last) radio's measurements; null if none ran. */
    fun diagnostics(): RadioDiagnostics? = active?.snapshot() ?: frozen

    private fun scanPermitted(): Boolean =
        radioRequiredPermissions(Build.VERSION.SDK_INT, scanning = true).all {
            app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun mayAutoJoin(): Boolean =
        shouldAutoJoin(autoJoin(), controller.state.value, appVisible(), scanPermitted(), clock(), autoJoinSuppressedUntil, serverVoiceActive())

    /** One auto-join check: looks for a cached server's radio and joins the first one seen. */
    private suspend fun pollAutoJoin() {
        if (!mayAutoJoin()) return
        val who = identity() ?: return
        val hit = link.android.radioNearbyAny(keys.communities(who.userId)) ?: return
        // The scan and the key read suspended: the rider may have started something meanwhile.
        if (mayAutoJoin()) controller.join(who.userId, hit, auto = true)
    }

    private fun onState(before: RadioState, state: RadioState) {
        when (state) {
            RadioState.Idle, is RadioState.Error -> {
                stopRun()
                // The service stops itself on Idle/Error once it has called startForeground.
                serviceStarted = false
            }
            is RadioState.Starting -> {
                stopRun()
                ensureService()
            }
            // Keep the run (and its audio) while the controller re-attaches Wi-Fi. If the old link
            // comes back by itself the run's session reports CONNECTED and the run is kept; a
            // Lost -> Client(JOINING) transition (a fresh attach) rebuilds the transport and session
            // below, because the P2P interface address may have changed.
            is RadioState.Lost -> ensureService()
            is RadioState.Hub -> {
                if (!ensureService()) return
                val sameRun = before is RadioState.Hub && before.community == state.community && active != null
                if (!sameRun) startRun(state.community, hub = true)
            }
            is RadioState.Client -> {
                if (!ensureService()) return
                // Lost -> Client(CONNECTED) comes from the kept run's own session hearing the hub again
                // over the old link (I2): that run is fine and is kept. Lost -> Client(JOINING) is a fresh
                // Wi-Fi attach and rebuilds.
                val recovered = before is RadioState.Lost && before.community == state.community &&
                    state.phase == RadioClientPhase.CONNECTED
                val sameRun = ((before is RadioState.Client && before.community == state.community) || recovered) &&
                    active != null
                if (!sameRun) startRun(state.community, hub = false)
            }
        }
    }

    private fun startRun(community: String, hub: Boolean) {
        stopRun()
        startJob = scope.launch {
            val who = identity()
            if (who == null) return@launch fail(RadioError.NO_KEY)
            val stored = keys.get(who.userId, community) ?: return@launch fail(RadioError.NO_KEY)
            val address = link.android.awaitLocalAddress() ?: return@launch fail(RadioError.WIFI_FAILED)
            val hubAddress = if (hub) null else (link.lastHub ?: return@launch fail(RadioError.WIFI_FAILED))
            // audioKey does not depend on the day, so any day derives the same one.
            val audioKey = RadioCredentialDerivation.derive(stored.key, clock() / DAY_MILLIS).audioKey
            val run = Run(who, address, audioKey, hubAddress, if (hub) controller.mode.value else RadioMode.BALANCED)
            if (!run.start()) return@launch fail(if (run.bindFailed) RadioError.WIFI_FAILED else RadioError.AUDIO_UNAVAILABLE)
            active = run
            _diagnosticsAvailable.value = true
        }
    }

    private fun fail(reason: RadioError) {
        controller.onRuntimeFailure(reason)
    }

    private fun stopRun() {
        startJob?.cancel()
        startJob = null
        val run = active ?: return
        active = null
        frozen = run.snapshot()
        run.stop()
        _members.value = emptyList()
        _rttMillis.value = null
        _activeMode.value = null
    }

    /** Starts the foreground service (microphone | connectedDevice); fails the radio if it cannot. */
    private fun ensureService(): Boolean {
        if (serviceStarted) return true
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            scope.launch { fail(RadioError.AUDIO_UNAVAILABLE) }
            return false
        }
        return try {
            ContextCompat.startForegroundService(app, Intent(app, NearbyRadioService::class.java))
            serviceStarted = true
            true
        } catch (_: Exception) {
            // Android 14+ refuses a microphone service started from the background, among others.
            scope.launch { fail(RadioError.AUDIO_UNAVAILABLE) }
            false
        }
    }

    private sealed interface Inbox {
        class Net(val datagram: RadioDatagram) : Inbox
    }

    /** One transport + session + audio. Created and stopped on the controller scope. */
    private inner class Run(
        private val who: RadioIdentity,
        private val bindAddress: java.net.InetAddress,
        private val audioKey: ByteArray,
        private val hubAddress: RadioAddress?,
        initialMode: RadioMode,
    ) {
        private val isHub = hubAddress == null
        private val inbox = ArrayBlockingQueue<Inbox>(INBOX_CAPACITY)
        private val io = RadioAudioIo(app)
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var transport: RadioUdpTransport? = null

        /** Set when [start] failed because the socket could not be bound (a Wi-Fi problem, not audio). */
        var bindFailed = false
            private set

        private val startedAt = clock()
        private val batteryStart = batteryPercent()

        /** Receive loss per sender slot; written on the audio thread, published as a copy by [publish]. */
        private val losses = HashMap<Int, RadioLossCounter>()

        @Volatile
        private var lossSnapshot: Map<Int, Int?> = emptyMap()

        /** Tick durations; audio thread only, published as [loadSnapshot] (I6). */
        private val tickStats = RadioTickStats()

        @Volatile
        private var loadSnapshot: RadioLoad? = null

        // Audio thread only from here on.
        private var hubSession: RadioHubSession? = null
        private var clientSession: RadioClientSession? = null
        private val gate = RadioVoiceGate()
        private var lastPhase = RadioClientPhase.JOINING
        private var ticks = 0L

        /** Read by the transport's threads, written only on the audio thread. */
        @Volatile
        private var mode: RadioMode = initialMode

        @Volatile
        private var slotForHeader: Int = if (isHub) 0 else RadioUdpTransport.UNASSIGNED_SENDER

        @Volatile
        private var pendingMode: RadioMode? = null

        /** Latest requested hub mode; applied at the next tick, never dropped by a full queue. */
        fun requestMode(mode: RadioMode) {
            if (isHub) pendingMode = mode
        }

        fun post(item: Inbox) {
            if (!inbox.offer(item)) {
                inbox.poll()
                inbox.offer(item)
            }
        }

        fun start(): Boolean {
            val t = try {
                RadioUdpTransport(
                    codec = RadioPacketCodec(audioKey),
                    mode = { mode },
                    bindAddress = bindAddress,
                    ownSender = { slotForHeader },
                    clock = clock,
                )
            } catch (_: Exception) {
                bindFailed = true
                return false
            }
            t.onFailure = { scope.launch { if (active === this@Run) controller.onRuntimeFailure(RadioError.WIFI_FAILED) } }
            transport = t
            if (isHub) {
                hubSession = RadioHubSession(who.userId, who.name, mode, hubStreamId = t.session)
            } else {
                clientSession = RadioClientSession(who.userId, who.name, streamId = t.session)
            }
            ioScope.launch { t.inbound.collect { post(Inbox.Net(it)) } }
            val audioFailed = { scope.launch { if (active === this@Run) controller.onRuntimeFailure(RadioError.AUDIO_UNAVAILABLE) } }
            if (!io.start(onFailure = { audioFailed() }, onFrame = ::tick)) {
                stop()
                return false
            }
            return true
        }

        fun stop() {
            io.stop() // joins the audio thread first: nothing touches the sessions after this
            transport?.close()
            transport = null
            ioScope.cancel()
        }

        /** Runs on the audio thread once per 20 ms mic frame; measures how long the work takes. */
        private fun tick(mic: ShortArray) {
            val started = System.nanoTime()
            try {
                work(mic)
            } finally {
                tickStats.record(System.nanoTime() - started)
            }
        }

        private fun work(mic: ShortArray) {
            val t = transport ?: return
            val now = clock()
            val hubS = hubSession
            val clientS = clientSession
            val wanted = pendingMode
            if (hubS != null && wanted != null && wanted != mode) {
                hubS.setMode(wanted)
                mode = wanted
            }
            while (true) {
                when (val item = inbox.poll() ?: break) {
                    is Inbox.Net -> {
                        (item.datagram.message as? RadioMessage.Audio)?.let {
                            losses.getOrPut(it.slot) { RadioLossCounter() }.onSeq(it.seq)
                        }
                        if (hubS != null) hubS.receive(item.datagram, now).forEach(t::send)
                        else clientS?.receive(item.datagram.message, now)
                    }
                }
            }
            if (hubS != null) {
                val gated = if (gate.process(mic)) mic else null
                hubS.tick(now, gated).forEach(t::send)
                io.play(hubS.hubPlayback())
            } else if (clientS != null && hubAddress != null) {
                clientS.tick(now, mic).forEach { t.send(RadioDatagram(hubAddress, it)) }
                io.play(clientS.playback())
                mode = clientS.mode
                slotForHeader = clientS.slot ?: RadioUdpTransport.UNASSIGNED_SENDER
                reportPhase(clientS)
            }
            if (++ticks % PUBLISH_EVERY_TICKS == 0L) publish(hubS, clientS)
        }

        private fun reportPhase(session: RadioClientSession) {
            val phase = session.phase
            if (phase == lastPhase) return
            lastPhase = phase
            // Only the current run speaks for the radio: a stopped run's late report must not move a newer one.
            scope.launch { if (active === this@Run) controller.onClientPhase(phase) }
        }

        /** Main thread: what this run has measured so far. Everyone on the radio, this phone included. */
        fun snapshot(): RadioDiagnostics {
            val loss = lossSnapshot
            val rtt = _rttMillis.value
            return RadioDiagnostics(
                role = if (isHub) RadioRole.HUB else RadioRole.CLIENT,
                mode = _activeMode.value ?: mode,
                members = _members.value.map {
                    RadioMemberStat(it.name, if (!isHub && it.slot == 0) rtt else null, loss[it.slot])
                },
                batteryStartPercent = batteryStart,
                batteryNowPercent = batteryPercent(),
                elapsedMillis = clock() - startedAt,
                load = loadSnapshot,
            )
        }

        private fun publish(hubS: RadioHubSession?, clientS: RadioClientSession?) {
            lossSnapshot = losses.mapValues { it.value.lossPercent() }
            val t = transport
            loadSnapshot = RadioLoad(
                tickMaxMillis = tickStats.maxMillis(),
                tickP95Millis = tickStats.p95Millis(),
                lateTicks = tickStats.lateTicks,
                droppedEncodes = t?.droppedEncodes ?: 0,
                rejectedInbound = t?.rejectedInbound ?: 0,
                decodeFailures = t?.decodeFailures ?: 0,
                // The hub buffers per client slot; a client has one buffer, the hub's mix (slot 0).
                jitterDepthFrames = hubS?.jitterDepths ?: clientS?.let { mapOf(0 to it.jitterDepth) }.orEmpty(),
            )
            _activeMode.value = if (hubS != null) mode else clientS?.mode
            if (hubS != null) {
                val me = RadioMember(0, who.userId, who.name, clock(), null)
                _members.value = listOf(me) + hubS.members
            } else if (clientS != null) {
                // A lost client has not heard anyone for seconds: its old roster is not a number it has earned.
                _members.value = if (clientS.phase == RadioClientPhase.LOST) emptyList() else clientS.roster
                _rttMillis.value = clientS.rttMillis
            }
        }
    }

    /** Battery level in percent, or null when the device does not say. */
    private fun batteryPercent(): Int? {
        val manager = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 1..100 }
    }

    private companion object {
        const val AUTO_JOIN_POLL_MILLIS = 30_000L
        const val AUTO_JOIN_SUPPRESS_MILLIS = 10 * 60_000L
        const val DAY_MILLIS = 86_400_000L
        const val INBOX_CAPACITY = 512
        const val PUBLISH_EVERY_TICKS = 25L
    }
}
