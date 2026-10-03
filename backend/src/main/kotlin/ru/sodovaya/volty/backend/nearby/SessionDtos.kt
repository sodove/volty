package ru.sodovaya.volty.backend.nearby

import kotlinx.serialization.Serializable

@Serializable data class SessionJoinRequest(val destinationId: String, val replace: Boolean = false)

/** CONNECTING is registered admission; CONNECTED is observed SFU transport, never device audibility. */
@Serializable data class SessionStateResponse(
    val phase: String,
    val sessionId: String? = null,
    val deadlineEpochMillis: Long? = null,
    val operationId: String? = null,
    val credentials: SessionCredentialDto? = null,
)

@Serializable data class SessionCredentialDto(
    val serverUrl: String, val participantToken: String, val roomEpoch: String,
) {
    override fun toString() = "SessionCredentialDto(participantToken=<redacted>)"
    companion object {
        fun from(value: SessionCredentials) = SessionCredentialDto(value.serverUrl, value.participantToken, value.roomEpoch)
    }
}
