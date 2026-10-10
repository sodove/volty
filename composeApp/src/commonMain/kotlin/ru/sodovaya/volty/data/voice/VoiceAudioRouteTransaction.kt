package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.*
import kotlinx.coroutines.*

/** Restore the original native pair after rejection/cancellation, while the session still owns it. */
internal suspend fun voiceAudioRouteTransaction(requested: VoiceAudioPreferences, previous: VoiceAudioPreferences,
    owns: () -> Boolean, apply: suspend (VoiceAudioPreferences, () -> Boolean) -> VoiceAudioChangeResult,
    settled: (Boolean) -> Unit): VoiceAudioChangeResult {
    var safe = false
    try {
        val request = currentCoroutineContext()[Job]
        val result = apply(requested) { owns() && request?.isActive == true }
        currentCoroutineContext().ensureActive()
        safe = result == VoiceAudioChangeResult.REQUESTED && owns()
        return result
    } finally {
        if (!safe && owns()) withContext(NonCancellable) {
            safe = try { apply(previous, owns) == VoiceAudioChangeResult.REQUESTED && owns() }
                catch (_: Exception) { false }
        }
        settled(safe)
    }
}
