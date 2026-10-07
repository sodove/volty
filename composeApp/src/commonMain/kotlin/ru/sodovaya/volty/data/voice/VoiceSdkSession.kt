package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.VoiceSessionEvent
import ru.sodovaya.volty.domain.voice.VoiceSessionTag
import ru.sodovaya.volty.domain.voice.VoiceAudioPreferences

class VoiceConnectionCredentials(val serverUrl: String, val token: String) {
    override fun toString() = "VoiceConnectionCredentials(redacted)"
}

/** REQUESTED is an accepted preference request, never measured routing or audibility. */
enum class VoiceAudioChangeResult { REQUESTED, UNAVAILABLE, REQUIRES_RECONNECT, FAILED, STALE }

enum class VoiceMuteApplyResult { APPLIED, SUPERSEDED, REJECTED }

interface VoiceSdkSession {
    suspend fun connect(credentials: VoiceConnectionCredentials)
    suspend fun publishMicrophone(muted: Boolean): Boolean
    suspend fun setMuted(muted: Boolean): Boolean
    /** Recheck current intent after any dispatcher/lock wait, immediately before native writes. */
    suspend fun setMutedIfCurrent(muted: Boolean, current: () -> Boolean): VoiceMuteApplyResult =
        if (!current()) VoiceMuteApplyResult.SUPERSEDED
        else if (setMuted(muted)) VoiceMuteApplyResult.APPLIED else VoiceMuteApplyResult.REJECTED
    fun setDeafened(deafened: Boolean)
    suspend fun configureAudio(preferences: VoiceAudioPreferences): VoiceAudioChangeResult = VoiceAudioChangeResult.REQUIRES_RECONNECT
    suspend fun close()
}

fun interface VoiceSdkSessionFactory {
    fun create(tag: VoiceSessionTag, emit: (VoiceSessionEvent) -> Unit): VoiceSdkSession
}

enum class VoiceEngineError { CONNECTION, PUBLICATION, MUTE, CLEANUP, LEGACY_ACTIVE, AUTHORIZATION }

class VoiceEngineException(val code: VoiceEngineError) : Exception("voice_${code.name.lowercase()}")
