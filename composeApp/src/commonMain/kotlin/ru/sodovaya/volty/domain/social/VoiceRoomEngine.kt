package ru.sodovaya.volty.domain.social

import kotlinx.coroutines.flow.StateFlow

interface VoiceRoomEngine {
    val requiredPermissions: List<String>
        get() = emptyList()

    val participants: StateFlow<List<VoiceParticipant>>

    suspend fun connect(serverUrl: String, participantToken: String): Result<Unit>

    suspend fun disconnect()

    suspend fun setMuted(muted: Boolean): Result<Unit>
}
