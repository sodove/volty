package ru.sodovaya.volty.data.voice

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import ru.sodovaya.volty.domain.voice.VoiceAudioPreferences
import ru.sodovaya.volty.domain.audio.VoiceListenSettings

/** Allocation provider only. Each account contour receives a separate configuration owner. */
internal class AndroidVoiceAudioFactory(context: Context, private val listen: StateFlow<VoiceListenSettings>) {
    private val application = context.applicationContext

    fun create(preferences: () -> VoiceAudioPreferences): VoiceSessionAudioFactory =
        VoiceSessionAudioFactory(preferences) { tag, chosen, diagnostics, emit ->
            AndroidVoiceSdkSession(application, tag, chosen, listen, diagnostics, emit)
        }

    fun createPrepared(): VoiceSessionAudioFactory =
        VoiceSessionAudioFactory({ error("Prepared audio has no default preference source") }, requiresPreparation = true) { tag, chosen, diagnostics, emit ->
            AndroidVoiceSdkSession(application, tag, chosen, listen, diagnostics, emit)
        }
}
