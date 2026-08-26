package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialRepository
import ru.sodovaya.volty.domain.social.SocialRideRuntime

data class SocialLiveState(
    val groupId: RideGroupId? = null,
    val liveEvent: SocialLiveEvent? = null,
    val markers: List<ParticipantMarker> = emptyList(),
)

/**
 * One live group subscription shared by the dashboard and the Nearby controls.
 * It deliberately lives above both screens so navigation cannot make friends
 * disappear from the Ride map.
 */
interface SocialLiveSession {
    val state: StateFlow<SocialLiveState>

    fun selectGroup(groupId: RideGroupId)
    fun clear()
    fun close()
}

class DefaultSocialLiveSession(
    private val socialRepository: SocialRepository,
    private val nowEpochMillis: () -> Long = { epochMillis() },
    private val freshnessTickerIntervalMillis: Long? = 1_000L,
) : SocialLiveSession {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(SocialLiveState())
    override val state: StateFlow<SocialLiveState> = _state.asStateFlow()
    private var groupJob: Job? = null
    private var freshnessJob: Job? = null
    private var cachedSnapshot: ru.sodovaya.volty.domain.social.LiveGroupSnapshot? = null

    override fun selectGroup(groupId: RideGroupId) {
        groupJob?.cancel()
        freshnessJob?.cancel()
        _state.value = SocialLiveState(groupId = groupId)
        cachedSnapshot = null
        freshnessJob = freshnessTickerIntervalMillis?.let { intervalMillis ->
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(intervalMillis)
                    val snapshot = cachedSnapshot ?: continue
                    _state.update { current ->
                        if (current.groupId != groupId) current
                        else current.copy(
                            liveEvent = SocialLiveEvent.Snapshot(snapshot),
                            markers = ParticipantMarkerMapper.map(snapshot.participants, nowEpochMillis()),
                        )
                    }
                }
            }
        }
        groupJob = scope.launch {
            socialRepository.observeGroup(groupId).collect { event ->
                _state.update { current ->
                    if (current.groupId != groupId) current
                    else when {
                        event is SocialLiveEvent.Snapshot -> {
                            cachedSnapshot = event.value
                            current.copy(
                                liveEvent = event,
                                markers = ParticipantMarkerMapper.map(event.value.participants, nowEpochMillis()),
                            )
                        }
                        event is SocialLiveEvent.Failure && event.error is SocialFailure.Network && cachedSnapshot != null ->
                            current.copy(
                                liveEvent = SocialLiveEvent.Snapshot(cachedSnapshot!!),
                                markers = ParticipantMarkerMapper.map(cachedSnapshot!!.participants, nowEpochMillis()),
                            )
                        else -> current.copy(
                            liveEvent = event,
                            markers = reduceParticipantMarkers(current.markers, event, nowEpochMillis()),
                        )
                    }
                }
            }
        }
    }

    override fun clear() {
        groupJob?.cancel()
        freshnessJob?.cancel()
        groupJob = null
        freshnessJob = null
        cachedSnapshot = null
        _state.value = SocialLiveState()
    }

    override fun close() {
        groupJob?.cancel()
        freshnessJob?.cancel()
        scope.cancel()
    }
}

/** Root-facing adapter for the app-scoped runtime. */
class RuntimeSocialLiveSession(
    private val runtime: SocialRideRuntime,
) : SocialLiveSession {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(SocialLiveState())
    override val state: StateFlow<SocialLiveState> = _state.asStateFlow()

    init {
        scope.launch {
            runtime.state.collect { current ->
                _state.value = SocialLiveState(
                    groupId = current.selectedGroup?.id,
                    liveEvent = current.liveEvent,
                    markers = current.markers.map {
                        ParticipantMarker(
                            userId = it.userId,
                            label = it.label,
                            latitude = it.latitude,
                            longitude = it.longitude,
                            accuracyMeters = it.accuracyMeters,
                            presence = it.presence,
                            stale = it.stale,
                        )
                    },
                )
            }
        }
    }

    override fun selectGroup(groupId: RideGroupId) = Unit

    override fun clear() = runtime.clearGroup()

    override fun close() {
        scope.cancel()
        runtime.close()
    }
}

internal fun reduceParticipantMarkers(
    current: List<ParticipantMarker>,
    event: SocialLiveEvent,
    nowEpochMillis: Long,
): List<ParticipantMarker> = when (event) {
    is SocialLiveEvent.Snapshot -> ParticipantMarkerMapper.map(event.value.participants, nowEpochMillis)
    is SocialLiveEvent.ShareRevoked -> current.filterNot { it.userId == event.userId.value }
    is SocialLiveEvent.ShareExpired -> current.filterNot { it.userId == event.userId.value }
    is SocialLiveEvent.Failure -> if (event.error is SocialFailure.Network) {
        current.map { it.copy(presence = ru.sodovaya.volty.domain.social.PresenceStatus.STALE, stale = true) }
    } else {
        emptyList()
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun epochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
