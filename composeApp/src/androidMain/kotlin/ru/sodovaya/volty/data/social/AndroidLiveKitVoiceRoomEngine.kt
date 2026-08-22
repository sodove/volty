package ru.sodovaya.volty.data.social

import android.Manifest
import android.app.Application
import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.VoiceParticipant
import ru.sodovaya.volty.domain.social.VoiceRoomEngine

class AndroidLiveKitVoiceRoomEngine(
    appContext: Context,
) : VoiceRoomEngine {
    override val requiredPermissions: List<String> = listOf(Manifest.permission.RECORD_AUDIO)
    private val application = appContext.applicationContext as Application
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val _participants = MutableStateFlow<List<VoiceParticipant>>(emptyList())
    override val participants = _participants.asStateFlow()

    private var room: Room? = null
    private var eventsJob: Job? = null

    override suspend fun connect(serverUrl: String, participantToken: String): Result<Unit> = runCatching {
        disconnect()
        val liveRoom = LiveKit.create(appContext = application)
        room = liveRoom
        eventsJob = scope.launch {
            liveRoom.events.collect {
                _participants.value = liveRoom.remoteParticipants.values.map(::mapParticipant)
            }
        }
        liveRoom.connect(serverUrl, participantToken)
        liveRoom.localParticipant.setMicrophoneEnabled(true)
        _participants.value = liveRoom.remoteParticipants.values.map(::mapParticipant)
    }.onFailure {
        runCatching { disconnect() }
    }

    override suspend fun disconnect() {
        val liveRoom = room ?: run {
            _participants.value = emptyList()
            return
        }
        room = null
        eventsJob?.cancel()
        eventsJob = null
        _participants.value = emptyList()
        runCatching { liveRoom.disconnect() }
        runCatching { liveRoom.release() }
    }

    override suspend fun setMuted(muted: Boolean): Result<Unit> = runCatching {
        val liveRoom = room ?: error("Voice room is not connected")
        liveRoom.localParticipant.setMicrophoneEnabled(!muted)
    }

    private fun mapParticipant(participant: io.livekit.android.room.participant.RemoteParticipant): VoiceParticipant {
        val identity = participant.identity?.toString()?.ifBlank { null }
            ?: participant.sid.toString()
        val displayName = participant.name?.ifBlank { null } ?: identity
        return VoiceParticipant(
            userId = SocialUserId(identity),
            displayName = displayName,
            isSpeaking = participant.isSpeaking,
        )
    }
}
