package ru.sodovaya.volty.backend.nearby

import io.livekit.server.AccessToken
import io.livekit.server.CanPublish
import io.livekit.server.CanPublishSources
import io.livekit.server.CanSubscribe
import io.livekit.server.RoomJoin
import io.livekit.server.RoomName
import java.util.Date

data class SessionCredentials(
    val serverUrl: String, val participantToken: String, val sessionId: String,
    val roomEpoch: String, val deadlineEpochMillis: Long,
) {
    override fun toString() = "SessionCredentials(sessionId=$sessionId, participantToken=<redacted>, deadlineEpochMillis=$deadlineEpochMillis)"
}

/** No I/O: issuance holds original actor ownership, including both participants for calls. */
fun interface SessionCredentialIssuer { fun issue(row: SessionJournalEntry): SessionCredentials }

class JwtSessionCredentialIssuer(private val serverUrl: String, private val apiKey: String, private val secret: String) : SessionCredentialIssuer {
    override fun issue(row: SessionJournalEntry): SessionCredentials {
        check(row.operation.phase == OperationPhase.ACTIVE)
        val session = checkNotNull(row.operation.sessionId)
        val room = checkNotNull(row.sfuEpoch).roomEpoch
        val deadline = checkNotNull(row.operation.deadlineEpochMillis)
        val bearer = AccessToken(apiKey, secret).apply {
            identity = session
            expiration = Date(deadline)
            addGrants(RoomJoin(true), RoomName(room), CanPublish(true), CanPublishSources(listOf("microphone")), CanSubscribe(true))
        }.toJwt()
        return SessionCredentials(serverUrl, bearer, session, room, deadline)
    }
}
