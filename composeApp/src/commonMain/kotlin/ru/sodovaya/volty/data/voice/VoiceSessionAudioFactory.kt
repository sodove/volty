package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.voice.*

/** Provenance of an allocation, never an active/audible-session claim. */
internal data class VoiceAudioAllocation(val tag: VoiceSessionTag, val diagnostics: VoiceDiagnostics)

internal class VoiceSessionAudioFactory(
    private val preferences: () -> VoiceAudioPreferences,
    private val allocate: (VoiceSessionTag, VoiceAudioPreferences, VoiceDiagnostics, (VoiceSessionEvent) -> Unit) -> VoiceSdkSession,
) : VoiceSdkSessionFactory {
    private val output = MutableStateFlow<VoiceAudioAllocation?>(null)
    val latestAllocation = output.asStateFlow()
    /** Called by the same queued serial engine owner; source must be ready and nonblocking. */
    override fun create(tag: VoiceSessionTag, emit: (VoiceSessionEvent) -> Unit): VoiceSdkSession {
        val chosen = preferences()
        val diagnostics = VoiceDiagnostics(tag.generation)
        diagnostics.update { it.copy(preferences = chosen) }
        val session = allocate(tag, chosen, diagnostics, emit)
        output.value = VoiceAudioAllocation(tag, diagnostics)
        return session
    }
}
