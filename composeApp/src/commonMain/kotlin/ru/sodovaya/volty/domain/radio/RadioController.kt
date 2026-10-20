package ru.sodovaya.volty.domain.radio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the Wi-Fi layer reported for one attempt to host or join the group. */
sealed interface RadioLinkResult {
    data class Hosting(val localPort: Int) : RadioLinkResult
    data class Joined(val hub: RadioAddress) : RadioLinkResult
    data object NotFound : RadioLinkResult
    data object Unsupported : RadioLinkResult
    data class Failed(val reason: String) : RadioLinkResult
}

/** The Wi-Fi layer as the controller sees it; implemented on Android by a later task. */
interface RadioLink {
    suspend fun host(community: String): RadioLinkResult
    suspend fun join(community: String, timeoutMillis: Long): RadioLinkResult
    suspend fun leave()

    /**
     * Set by the controller. The link calls it (on any thread) when a same-name hub from another
     * phone is confirmed and this hub should give way; the link never dissolves its group itself,
     * so the controller's mutex stays the only serialiser of link calls (ruling R14).
     */
    var onDuplicateHub: ((community: String) -> Unit)?

    /** True when the last scan saw this community's radio. */
    fun radioNearby(community: String): Boolean
}

/** The ordinary server voice, which is exclusive with the radio. */
interface RadioServerVoice {
    fun isActive(): Boolean
    fun disconnect()
}

enum class RadioError {
    UNSUPPORTED_ANDROID, NO_KEY, STALE_KEY, NOT_FOUND, FULL, WIFI_FAILED,

    /** The runtime could not start audio: microphone not granted, foreground service refused, or the recorder would not open. */
    AUDIO_UNAVAILABLE,
}

sealed interface RadioState {
    data object Idle : RadioState
    data class Starting(val community: String) : RadioState
    data class Hub(val community: String) : RadioState
    data class Client(val community: String, val phase: RadioClientPhase) : RadioState
    data class Lost(val community: String, val rejoinUntilMillis: Long) : RadioState
    data class Error(val community: String?, val reason: RadioError) : RadioState
}

/**
 * Decides raise versus join, recovers from a lost hub, and keeps the radio and the
 * server voice mutually exclusive. Pure: it only talks to [RadioLink].
 *
 * A key older than [STALE_KEY_AFTER_MILLIS] is not refused; it is only blamed when a join ends
 * in [RadioLinkResult.NotFound], because an old key is the one offline hint that the hub may be
 * on a newer one.
 *
 * A client reporting [RadioClientPhase.FULL] is told to leave the link and becomes
 * `Error(FULL)`: a full radio carries no audio, so there is nothing to stay connected to.
 *
 * Not thread-safe: the controller must be confined to a single-threaded scope (the main
 * dispatcher in production). Every link call goes through one mutex, so a cancelled call that is
 * still tearing down never overlaps the next one, and every state write is dropped unless it
 * comes from the current generation of work, so a stale coroutine cannot overwrite a newer state.
 */
class RadioController(
    private val link: RadioLink,
    private val keys: RadioKeyStore,
    private val serverVoice: RadioServerVoice,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RadioState>(RadioState.Idle)
    val state: StateFlow<RadioState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(RadioMode.BALANCED)

    /** The mode the next raise will use; changing it also reaches a running hub via [onModeChanged]. */
    val mode: StateFlow<RadioMode> = _mode.asStateFlow()

    /** Installed by the runtime while a hub runs, so a mode change reaches it live. */
    var onModeChanged: ((RadioMode) -> Unit)? = null

    private var job: Job? = null
    private var community: String? = null
    private var actor: String? = null
    private var keyFetchedAt: Long = 0
    private var generation = 0
    private val linkLock = Mutex()

    fun setMode(mode: RadioMode) {
        _mode.value = mode
        onModeChanged?.invoke(mode)
    }

    /** Allowed from Idle, Error or Lost (where it means [reRaise]); ignored otherwise. */
    fun raise(actor: String, community: String) {
        if (_state.value is RadioState.Lost) return reRaise()
        if (!canStart()) return
        start(actor, community) { g -> raiseBody(g, community) }
    }

    private suspend fun raiseBody(g: Int, community: String) {
        when (val first = linked { link.join(community, RAISE_PROBE_MILLIS) }) {
            is RadioLinkResult.Joined -> put(g, RadioState.Client(community, RadioClientPhase.JOINING))
            RadioLinkResult.NotFound -> host(g, community)
            else -> fail(g, community, first)
        }
    }

    /**
     * The link saw a same-name hub from another phone and this hub should give way (ruling R14).
     * Only meaningful while hosting: leaves through the serialised link path, then repeats the
     * raise semantics (short join probe, else host) for the same community. Ignored otherwise.
     */
    fun onDuplicateHub() {
        val c = community ?: return
        val who = actor ?: return
        if (_state.value != RadioState.Hub(c)) return
        start(who, c) { g ->
            linked { link.leave() }
            raiseBody(g, c)
        }
    }

    /** Allowed from Idle, Error or Lost; ignored otherwise. */
    fun join(actor: String, community: String) {
        if (!canStart()) return
        start(actor, community) { g ->
            when (val r = linked { link.join(community, JOIN_TIMEOUT_MILLIS) }) {
                is RadioLinkResult.Joined -> put(g, RadioState.Client(community, RadioClientPhase.JOINING))
                RadioLinkResult.NotFound -> put(g, RadioState.Error(community, notFoundReason()))
                else -> fail(g, community, r)
            }
        }
    }

    /** From [RadioState.Lost]: stop waiting for the old hub and become the hub. */
    fun reRaise() {
        val c = community ?: return
        if (_state.value !is RadioState.Lost) return
        val g = next()
        _state.value = RadioState.Starting(c)
        job = scope.launch { host(g, c) }
    }

    fun leave() {
        if (_state.value == RadioState.Idle) return
        next()
        community = null
        _state.value = RadioState.Idle
        scope.launch { linked { link.leave() } }
    }

    fun onClientPhase(phase: RadioClientPhase) {
        val c = community ?: return
        val current = _state.value
        if (current !is RadioState.Client && current !is RadioState.Lost) return
        when {
            phase == RadioClientPhase.LOST -> {
                if (current is RadioState.Lost) return
                val until = clock() + REJOIN_WINDOW_MILLIS
                val g = next()
                _state.value = RadioState.Lost(c, until)
                job = scope.launch { rejoin(g, c, until) }
            }
            phase == RadioClientPhase.FULL && current is RadioState.Client -> {
                next()
                _state.value = RadioState.Error(c, RadioError.FULL)
                scope.launch { linked { link.leave() } }
            }
            current is RadioState.Client -> _state.value = RadioState.Client(c, phase)
        }
    }

    /**
     * The runtime (audio, service) could not carry the radio. Leaves the link and reports
     * [reason]; ignored when there is nothing running to fail.
     */
    fun onRuntimeFailure(reason: RadioError) {
        val c = community ?: return
        if (_state.value == RadioState.Idle || _state.value is RadioState.Error) return
        next()
        _state.value = RadioState.Error(c, reason)
        scope.launch { linked { link.leave() } }
    }

    /** Starting the ordinary server voice ends the radio. */
    fun onServerVoiceStarted() = leave()

    private fun canStart(): Boolean = when (_state.value) {
        RadioState.Idle, is RadioState.Error, is RadioState.Lost -> true
        else -> false
    }

    /** Starts new work: invalidates the previous job's writes and cancels it. */
    private fun next(): Int {
        job?.cancel()
        job = null
        return ++generation
    }

    private suspend fun <T> linked(block: suspend () -> T): T = linkLock.withLock { block() }

    private fun put(g: Int, s: RadioState) {
        if (g == generation) _state.value = s
    }

    private fun start(actor: String, community: String, body: suspend (Int) -> Unit) {
        val g = next()
        this.community = community
        this.actor = actor
        _state.value = RadioState.Starting(community)
        job = scope.launch {
            val stored = keys.get(actor, community)
            if (stored == null) {
                put(g, RadioState.Error(community, RadioError.NO_KEY))
                return@launch
            }
            if (g != generation) return@launch
            keyFetchedAt = stored.fetchedAtMillis
            if (serverVoice.isActive()) serverVoice.disconnect()
            body(g)
        }
    }

    private suspend fun host(g: Int, community: String) {
        when (val r = linked { link.host(community) }) {
            is RadioLinkResult.Hosting -> put(g, RadioState.Hub(community))
            else -> fail(g, community, r)
        }
    }

    private suspend fun rejoin(g: Int, community: String, until: Long) {
        while (true) {
            val remaining = until - clock()
            if (remaining <= 0) {
                put(g, RadioState.Error(community, notFoundReason()))
                return
            }
            when (val r = linked { link.join(community, minOf(REJOIN_ATTEMPT_MILLIS, remaining)) }) {
                is RadioLinkResult.Joined -> {
                    put(g, RadioState.Client(community, RadioClientPhase.JOINING))
                    return
                }
                is RadioLinkResult.Unsupported -> {
                    fail(g, community, r)
                    return
                }
                // NotFound and Failed both mean "not yet": keep trying until the window closes.
                else -> delay(minOf(REJOIN_PAUSE_MILLIS, maxOf(until - clock(), 0L)))
            }
        }
    }

    private fun notFoundReason(): RadioError =
        if (clock() - keyFetchedAt > STALE_KEY_AFTER_MILLIS) RadioError.STALE_KEY else RadioError.NOT_FOUND

    private fun fail(g: Int, community: String, result: RadioLinkResult) {
        val reason = if (result is RadioLinkResult.Unsupported) RadioError.UNSUPPORTED_ANDROID else RadioError.WIFI_FAILED
        put(g, RadioState.Error(community, reason))
    }

    private companion object {
        const val RAISE_PROBE_MILLIS = 5_000L
        const val JOIN_TIMEOUT_MILLIS = 30_000L
        const val REJOIN_ATTEMPT_MILLIS = 5_000L
        const val REJOIN_PAUSE_MILLIS = 1_000L
        const val REJOIN_WINDOW_MILLIS = 120_000L
        const val STALE_KEY_AFTER_MILLIS = 24L * 3_600_000L
    }
}
