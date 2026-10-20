package ru.sodovaya.volty.domain.radio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /** True when the last scan saw this community's radio. */
    fun radioNearby(community: String): Boolean
}

/** The ordinary server voice, which is exclusive with the radio. */
interface RadioServerVoice {
    fun isActive(): Boolean
    fun disconnect()
}

enum class RadioError { UNSUPPORTED_ANDROID, NO_KEY, STALE_KEY, NOT_FOUND, FULL, WIFI_FAILED }

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
 * A client reporting [RadioClientPhase.FULL] stays a [RadioState.Client] with that phase;
 * [RadioError.FULL] is reserved for a link that refuses the join outright.
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
    private var keyFetchedAt: Long = 0

    fun setMode(mode: RadioMode) {
        _mode.value = mode
        onModeChanged?.invoke(mode)
    }

    fun raise(actor: String, community: String) = start(actor, community) {
        when (val first = link.join(community, RAISE_PROBE_MILLIS)) {
            is RadioLinkResult.Joined -> _state.value = RadioState.Client(community, RadioClientPhase.JOINING)
            RadioLinkResult.NotFound -> host(community)
            else -> fail(community, first)
        }
    }

    fun join(actor: String, community: String) = start(actor, community) {
        when (val r = link.join(community, JOIN_TIMEOUT_MILLIS)) {
            is RadioLinkResult.Joined -> _state.value = RadioState.Client(community, RadioClientPhase.JOINING)
            RadioLinkResult.NotFound -> _state.value = RadioState.Error(community, notFoundReason())
            else -> fail(community, r)
        }
    }

    /** From [RadioState.Lost]: stop waiting for the old hub and become the hub. */
    fun reRaise() {
        val c = community ?: return
        if (_state.value !is RadioState.Lost) return
        job?.cancel()
        job = scope.launch { host(c) }
    }

    fun leave() {
        if (_state.value == RadioState.Idle) return
        job?.cancel()
        job = null
        community = null
        _state.value = RadioState.Idle
        scope.launch { link.leave() }
    }

    fun onClientPhase(phase: RadioClientPhase) {
        val c = community ?: return
        val current = _state.value
        if (current !is RadioState.Client && current !is RadioState.Lost) return
        if (phase == RadioClientPhase.LOST) {
            if (current is RadioState.Lost) return
            val until = clock() + REJOIN_WINDOW_MILLIS
            _state.value = RadioState.Lost(c, until)
            job?.cancel()
            job = scope.launch { rejoin(c, until) }
        } else if (current is RadioState.Client) {
            _state.value = RadioState.Client(c, phase)
        }
    }

    /** Starting the ordinary server voice ends the radio. */
    fun onServerVoiceStarted() = leave()

    private fun start(actor: String, community: String, body: suspend () -> Unit) {
        job?.cancel()
        this.community = community
        _state.value = RadioState.Starting(community)
        job = scope.launch {
            val stored = keys.get(actor, community)
            if (stored == null) {
                _state.value = RadioState.Error(community, RadioError.NO_KEY)
                return@launch
            }
            keyFetchedAt = stored.fetchedAtMillis
            if (serverVoice.isActive()) serverVoice.disconnect()
            body()
        }
    }

    private suspend fun host(community: String) {
        when (val r = link.host(community)) {
            is RadioLinkResult.Hosting -> _state.value = RadioState.Hub(community)
            else -> fail(community, r)
        }
    }

    private suspend fun rejoin(community: String, until: Long) {
        while (true) {
            val remaining = until - clock()
            if (remaining <= 0) {
                _state.value = RadioState.Error(community, notFoundReason())
                return
            }
            when (val r = link.join(community, REJOIN_ATTEMPT_MILLIS)) {
                is RadioLinkResult.Joined -> {
                    _state.value = RadioState.Client(community, RadioClientPhase.JOINING)
                    return
                }
                RadioLinkResult.NotFound -> delay(minOf(REJOIN_PAUSE_MILLIS, maxOf(until - clock(), 0L)))
                else -> {
                    fail(community, r)
                    return
                }
            }
        }
    }

    private fun notFoundReason(): RadioError =
        if (clock() - keyFetchedAt > STALE_KEY_AFTER_MILLIS) RadioError.STALE_KEY else RadioError.NOT_FOUND

    private fun fail(community: String, result: RadioLinkResult) {
        val reason = if (result is RadioLinkResult.Unsupported) RadioError.UNSUPPORTED_ANDROID else RadioError.WIFI_FAILED
        _state.value = RadioState.Error(community, reason)
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
