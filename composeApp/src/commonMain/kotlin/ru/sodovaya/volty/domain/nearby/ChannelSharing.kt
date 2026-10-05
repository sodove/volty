package ru.sodovaya.volty.domain.nearby

import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

enum class ChannelSharingMode { LOCATION, RIDE, FULL }
enum class ChannelSharingTtl(val millis: Long) { HALF_HOUR(1_800_000), TWO_HOURS(7_200_000), EIGHT_HOURS(28_800_000) }
data class ChannelSharingTag(val sessionId: String, val generation: String) {
    init {invitationDraftId(sessionId); invitationDraftId(generation)}
}
data class ChannelSharingObservation(val tag: ChannelSharingTag, val mode: ChannelSharingMode,
    val requestOrdinal: Long, val requestStartedMillis: Long, val responseReceivedMillis: Long, val deadlineMillis: Long) {
    init {require(requestOrdinal >= 0 && requestStartedMillis >= 0 && responseReceivedMillis >= requestStartedMillis && deadlineMillis > responseReceivedMillis)}
    fun isFresh(now: Long) = now >= responseReceivedMillis && now < deadlineMillis
}
enum class ChannelSharingFailure { UNAUTHORIZED, ACCESS_DENIED, UNAVAILABLE, INVALID_RESPONSE, EXPIRED, RETIRED }
enum class ChannelSharingNegativePhase { REVOKED, EXPIRED }
sealed interface ChannelSharingReply {
    data class Active(val observation: ChannelSharingObservation) : ChannelSharingReply
    data class Negative(val tag: ChannelSharingTag, val phase: ChannelSharingNegativePhase) : ChannelSharingReply
    data class Failed(val reason: ChannelSharingFailure) : ChannelSharingReply
}
