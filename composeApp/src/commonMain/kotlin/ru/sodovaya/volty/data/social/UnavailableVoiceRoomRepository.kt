package ru.sodovaya.volty.data.social

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.VoiceRoomRepository
import ru.sodovaya.volty.domain.social.VoiceRoomState

/** Keeps the UI honest until the server's WebRTC/SFU adapter is supplied. */
class UnavailableVoiceRoomRepository : VoiceRoomRepository {
    private val _state = MutableStateFlow<VoiceRoomState>(VoiceRoomState.Unavailable)
    override val state: StateFlow<VoiceRoomState> = _state.asStateFlow()

    private fun unavailable(): SocialResult<Unit> = SocialResult.Failure(
        SocialFailure.Network("Voice provider is not configured")
    )

    override suspend fun join(groupId: RideGroupId): SocialResult<Unit> = unavailable()
    override suspend fun leave(): SocialResult<Unit> = SocialResult.Success(Unit)
    override suspend fun setMuted(muted: Boolean): SocialResult<Unit> = unavailable()
}
