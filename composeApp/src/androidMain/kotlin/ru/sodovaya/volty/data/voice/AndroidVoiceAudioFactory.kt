package ru.sodovaya.volty.data.voice

import android.content.Context
import ru.sodovaya.volty.domain.voice.VoiceAudioPreferences

/** Allocation provider only. Each account contour receives a separate configuration owner. */
internal class AndroidVoiceAudioFactory(context: Context) {
    private val application = context.applicationContext

    fun create(preferences: () -> VoiceAudioPreferences): VoiceSessionAudioFactory =
        VoiceSessionAudioFactory(preferences) { tag, chosen, diagnostics, emit ->
            AndroidVoiceSdkSession(application, tag, chosen, diagnostics, emit)
        }

    fun createPrepared(): VoiceSessionAudioFactory =
        VoiceSessionAudioFactory({ error("Prepared audio has no default preference source") }, requiresPreparation = true) { tag, chosen, diagnostics, emit ->
            AndroidVoiceSdkSession(application, tag, chosen, diagnostics, emit)
        }
}
