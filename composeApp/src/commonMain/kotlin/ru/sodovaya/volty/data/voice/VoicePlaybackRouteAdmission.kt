package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.VoiceOutputPreference

/** Only an actual owned AudioTrack route may admit explicit-output PCM. */
internal fun voicePlaybackFrameAllowed(output: VoiceOutputPreference, preferredId: Int?, routedId: Int?): Boolean =
    output == VoiceOutputPreference.AUTO || (preferredId != null && routedId == preferredId)
