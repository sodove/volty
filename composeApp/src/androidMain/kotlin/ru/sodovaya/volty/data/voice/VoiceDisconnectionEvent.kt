package ru.sodovaya.volty.data.voice

import io.livekit.android.events.DisconnectReason
import ru.sodovaya.volty.domain.voice.VoiceSessionEvent
import ru.sodovaya.volty.domain.voice.VoiceSessionTag

internal fun voiceDisconnectionEvent(tag: VoiceSessionTag, reason: DisconnectReason): VoiceSessionEvent.Tagged =
    when (reason) {
        // SDK 2.25.3 carries explicit server closure separately from network/media failures:
        // https://github.com/livekit/client-sdk-android/blob/v2.25.3/livekit-android-sdk/src/main/java/io/livekit/android/events/RoomEvent.kt
        DisconnectReason.CLIENT_INITIATED, DisconnectReason.PARTICIPANT_REMOVED,
        DisconnectReason.ROOM_DELETED, DisconnectReason.ROOM_CLOSED -> VoiceSessionEvent.EngineClosed(tag)
        else -> VoiceSessionEvent.EngineFailed(tag)
    }
