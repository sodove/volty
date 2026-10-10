package ru.sodovaya.volty.domain.voice

enum class VoiceAudioCapturePath { SDK, PHONE_ISOLATED }
fun voiceAudioCapturePath(input: VoiceInputPreference): VoiceAudioCapturePath =
    if (input == VoiceInputPreference.PHONE) VoiceAudioCapturePath.PHONE_ISOLATED else VoiceAudioCapturePath.SDK
