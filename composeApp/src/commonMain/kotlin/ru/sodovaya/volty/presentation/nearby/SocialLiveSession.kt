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
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialRepository

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
) : SocialLiveSession {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(SocialLiveState())
    override val state: StateFlow<SocialLiveState> = _state.asStateFlow()
    private var groupJob: Job? = null

    override fun selectGroup(groupId: RideGroupId) {
        groupJob?.cancel()
        _state.value = SocialLiveState(groupId = groupId)
        groupJob = scope.launch {
            socialRepository.observeGroup(groupId).collect { event ->
                _state.update { current ->
                    if (current.groupId != groupId) current
                    else current.copy(
                        liveEvent = event,
                        markers = reduceParticipantMarkers(
                            current = current.markers,
                            event = event,
                            nowEpochMillis = epochMillis(),
                        ),
                    )
                }
            }
        }
    }

    override fun clear() {
        groupJob?.cancel()
        groupJob = null
        _state.value = SocialLiveState()
    }

    override fun close() {
        groupJob?.cancel()
        scope.cancel()
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
    is SocialLiveEvent.Failure -> emptyList()
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun epochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
