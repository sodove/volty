package ru.sodovaya.volty.data.social

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.SocialRuntimeState
import ru.sodovaya.volty.domain.social.SocialParticipantMarker
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.TelemetryShareProfile
import ru.sodovaya.volty.domain.social.VoiceRoomState

class SocialRuntimeStore(
    initial: SocialRuntimeState = SocialRuntimeState(),
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SocialRuntimeState> = _state.asStateFlow()

    fun update(transform: (SocialRuntimeState) -> SocialRuntimeState) {
        _state.value = transform(_state.value)
    }

    fun selectGroup(group: RideGroup) = update { current ->
        if (current.selectedGroup?.id == group.id) current.copy(selectedGroup = group)
        else current.copy(
            selectedGroup = group,
            liveEvent = null,
            markers = emptyList(),
            sharing = null,
            voice = current.voice,
            pendingVoicePermissionRequest = false,
        )
    }

    fun setLive(event: SocialLiveEvent?, markers: List<SocialParticipantMarker>) = update {
        it.copy(liveEvent = event, markers = markers)
    }

    fun setSharing(sharing: SharingSession?) = update { it.copy(sharing = sharing) }

    fun setShareProfile(profile: TelemetryShareProfile) = update { it.copy(shareProfile = profile) }

    fun setVoice(voice: VoiceRoomState) = update { it.copy(voice = voice) }

    fun setPendingVoicePermission(pending: Boolean) = update {
        it.copy(pendingVoicePermissionRequest = pending)
    }

    fun clear() {
        _state.value = SocialRuntimeState(voice = VoiceRoomState.Available)
    }
}
