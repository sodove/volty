package ru.sodovaya.volty.backend.nearby

enum class RosterConnectionPhase { JOINING, CONNECTING, CONNECTED }
enum class MediaPresence { UNKNOWN, CONNECTED }

/** Admission state and transport observation are independent; neither proves audibility. */
data class ChannelRosterPeer(
    val actorId: String, val sessionId: String, val operationId: String,
    val phase: RosterConnectionPhase, val deadlineEpochMillis: Long,
    val mediaPresence: MediaPresence = MediaPresence.UNKNOWN,
    val participantSid: String? = null,
)

data class ChannelRosterSnapshot(
    val channelId: String, val revision: Long, val serverTimeEpochMillis: Long,
    val peers: List<ChannelRosterPeer>,
)

data class DirectCallRosterSnapshot(
    val callId: String, val revision: Long, val serverTimeEpochMillis: Long,
    val peers: List<ChannelRosterPeer>,
)
