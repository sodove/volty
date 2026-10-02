package ru.sodovaya.volty.domain.voice

import kotlin.jvm.JvmInline

@JvmInline
value class VoiceOperationId(val value: String)

@JvmInline
value class VoiceSessionId(val value: String)

@JvmInline
value class VoiceGeneration(val value: Long)

data class VoiceSessionTag(val sessionId: VoiceSessionId, val generation: VoiceGeneration)
