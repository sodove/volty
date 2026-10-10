package ru.sodovaya.volty.domain.voice

/** Only SDK codes cross this boundary; native error messages may contain private URLs. */
enum class VoiceDisconnectCause {
    UNKNOWN, CLIENT_INITIATED, DUPLICATE_IDENTITY, SERVER_SHUTDOWN, PARTICIPANT_REMOVED,
    ROOM_DELETED, STATE_MISMATCH, JOIN_FAILURE, MIGRATION, SIGNAL_CLOSE, ROOM_CLOSED,
    USER_UNAVAILABLE, USER_REJECTED, SIP_TRUNK_FAILURE, CONNECTION_TIMEOUT, MEDIA_FAILURE,
}

fun voiceDisconnectCause(code: String): VoiceDisconnectCause =
    VoiceDisconnectCause.entries.firstOrNull { it.name == code } ?: VoiceDisconnectCause.UNKNOWN
