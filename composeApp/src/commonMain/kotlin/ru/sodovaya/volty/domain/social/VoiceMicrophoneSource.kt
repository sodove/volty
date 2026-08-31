package ru.sodovaya.volty.domain.social

/**
 * Preferred microphone route for the Nearby voice room.
 *
 * Android may fall back to the phone when a requested headset is not present.
 */
enum class VoiceMicrophoneSource {
    AUTO,
    PHONE,
    HEADSET,
    ;

    companion object {
        fun fromPersisted(value: String?): VoiceMicrophoneSource =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}
