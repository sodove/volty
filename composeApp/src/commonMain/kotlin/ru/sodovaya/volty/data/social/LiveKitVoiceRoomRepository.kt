package ru.sodovaya.volty.data.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialRepository
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.VoiceRoomEngine
import ru.sodovaya.volty.domain.social.VoiceRoomFailureReason
import ru.sodovaya.volty.domain.social.VoiceRoomRepository
import ru.sodovaya.volty.domain.social.VoiceRoomState
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.reduce

class LiveKitVoiceRoomRepository(
    private val socialRepository: SocialRepository,
    private val engine: VoiceRoomEngine,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : VoiceRoomRepository {
    override val requiredPermissions: List<String>
        get() = engine.requiredPermissions

    private val _state = MutableStateFlow<VoiceRoomState>(VoiceRoomState.Unavailable)
    override val state: StateFlow<VoiceRoomState> = _state.asStateFlow()

    private var activeGroupId: RideGroupId? = null

    init {
        scope.launch {
            socialRepository.session.collect { session ->
                if (session is SocialSession.Authenticated) {
                    if (activeGroupId == null && _state.value !is VoiceRoomState.Joining && _state.value !is VoiceRoomState.Failed) {
                        refreshAvailability()
                    }
                } else if (activeGroupId == null) {
                    _state.value = VoiceRoomState.Unavailable
                }
            }
        }
        scope.launch {
            engine.participants.collect { participants ->
                _state.update { current ->
                    if (activeGroupId == null) {
                        current
                    } else {
                        val muted = (current as? VoiceRoomState.Joined)?.muted ?: false
                        VoiceRoomState.Joined(
                            muted = muted,
                            participants = participants,
                        )
                    }
                }
            }
        }
    }

    override suspend fun join(groupId: RideGroupId): SocialResult<Unit> {
        if (_state.value == VoiceRoomState.Unavailable) {
            refreshAvailability()
        }
        if (_state.value == VoiceRoomState.Unavailable) {
            return SocialResult.Failure(SocialFailure.InvalidRequest("Voice provider is unavailable"))
        }
        if (activeGroupId != null && activeGroupId != groupId) {
            leave()
        }
        _state.value = _state.value.reduce(ru.sodovaya.volty.domain.social.VoiceRoomEvent.JoinRequested)
        return when (val credentials = socialRepository.joinVoice(groupId)) {
            is SocialResult.Failure -> failWith(credentials.error)
            is SocialResult.Success -> connect(groupId, credentials.value)
        }
    }

    override suspend fun leave(): SocialResult<Unit> {
        val groupId = activeGroupId
        if (groupId == null) {
            if (_state.value != VoiceRoomState.Unavailable) _state.value = VoiceRoomState.Available
            return SocialResult.Success(Unit)
        }
        activeGroupId = null
        runCatching { engine.disconnect() }
        runCatching { socialRepository.leaveVoice(groupId) }
        _state.value = VoiceRoomState.Available
        return SocialResult.Success(Unit)
    }

    override suspend fun setMuted(muted: Boolean): SocialResult<Unit> {
        if (_state.value !is VoiceRoomState.Joined) {
            return SocialResult.Failure(SocialFailure.InvalidRequest("Voice room is not joined"))
        }
        return engine.setMuted(muted).fold(
            onSuccess = {
                _state.update { current ->
                    (current as? VoiceRoomState.Joined)?.copy(muted = muted) ?: current
                }
                SocialResult.Success(Unit)
            },
            onFailure = {
                failWith(SocialFailure.Network(it.message ?: "Voice connection failed"))
            },
        )
    }

    private suspend fun connect(
        groupId: RideGroupId,
        credentials: ru.sodovaya.volty.domain.social.VoiceRoomCredentials,
    ): SocialResult<Unit> {
        if (credentials.provider.lowercase() != "livekit") {
            return failWith(SocialFailure.InvalidRequest("Unsupported voice provider"))
        }
        return engine.connect(credentials.serverUrl, credentials.participantToken).fold(
            onSuccess = {
                activeGroupId = groupId
                _state.value = VoiceRoomState.Joined(
                    muted = false,
                    participants = engine.participants.value,
                )
                SocialResult.Success(Unit)
            },
            onFailure = {
                runCatching { socialRepository.leaveVoice(groupId) }
                failWith(SocialFailure.Network(it.message ?: "Voice connection failed"))
            },
        )
    }

    private fun failWith(error: SocialFailure): SocialResult.Failure {
        _state.value = VoiceRoomState.Failed(
            reason = VoiceRoomFailureReason.CONNECTION_FAILED,
        )
        return SocialResult.Failure(error)
    }

    private suspend fun refreshAvailability() {
        if (activeGroupId != null) return
        when (val result = socialRepository.getVoiceProvider()) {
            is SocialResult.Success -> {
                if (activeGroupId != null || _state.value is VoiceRoomState.Joining || _state.value is VoiceRoomState.Failed) return
                val provider = result.value
                _state.value = if (provider.available && provider.provider.equals("livekit", ignoreCase = true)) {
                    VoiceRoomState.Available
                } else {
                    VoiceRoomState.Unavailable
                }
            }
            is SocialResult.Failure -> {
                if (activeGroupId == null && _state.value !is VoiceRoomState.Joining && _state.value !is VoiceRoomState.Failed) {
                    _state.value = VoiceRoomState.Unavailable
                }
            }
        }
    }
}
