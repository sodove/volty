package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.VoiceSessionEvent
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

class VoiceConnectionCredentials(val serverUrl: String, val token: String) {
    override fun toString() = "VoiceConnectionCredentials(redacted)"
}

interface VoiceSdkSession {
    suspend fun connect(credentials: VoiceConnectionCredentials)
    suspend fun publishMicrophone(muted: Boolean): Boolean
    suspend fun setMuted(muted: Boolean): Boolean
    fun setDeafened(deafened: Boolean)
    suspend fun close()
}

fun interface VoiceSdkSessionFactory {
    fun create(tag: VoiceSessionTag, emit: (VoiceSessionEvent) -> Unit): VoiceSdkSession
}

enum class VoiceEngineError { CONNECTION, PUBLICATION, MUTE, CLEANUP, LEGACY_ACTIVE, AUTHORIZATION }

class VoiceEngineException(val code: VoiceEngineError) : Exception("voice_${code.name.lowercase()}")
