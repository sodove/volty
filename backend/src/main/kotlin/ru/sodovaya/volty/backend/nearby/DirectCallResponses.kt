package ru.sodovaya.volty.backend.nearby

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import ru.sodovaya.volty.backend.ApiException

internal suspend fun ApplicationCall.directCallResponse(decision: DirectCallDecision) = directCallResponse(DirectCallAction(decision))

internal suspend fun ApplicationCall.directCallResponse(action: DirectCallAction) {
    val decision = action.decision
    when (decision.rejection) {
        DirectCallRejection.ACCESS_DENIED -> throw ApiException(HttpStatusCode.NotFound, "call_unavailable", "Call unavailable")
        DirectCallRejection.NOT_FRIENDS -> throw ApiException(HttpStatusCode.Forbidden, "friendship_required", "Accepted friendship required")
        DirectCallRejection.IDENTITY_MISMATCH -> throw ApiException(HttpStatusCode.Conflict, "operation_conflict", "Original call binding required")
        DirectCallRejection.CLAIM_BUSY -> throw ApiException(HttpStatusCode.Conflict, "voice_busy", "Another voice conversation is active")
        DirectCallRejection.REVISION_MISMATCH -> throw ApiException(HttpStatusCode.Conflict, "call_revision_conflict", "Call revision changed")
        DirectCallRejection.NOT_RINGING -> throw ApiException(HttpStatusCode.Conflict, "call_not_ringing", "Call is not ringing")
        DirectCallRejection.TERMINAL, null -> Unit
    }
    val original = decision.call ?: throw ApiException(HttpStatusCode.NotFound, "call_unavailable", "Call unavailable")
    val failure = action.failure ?: (action.session as? SessionAction.Unavailable)?.reason
    // Earlier mapping trusted a child result even when its parent was terminal or failed.
    // Only an accepted, successful parent can expose positive media admission evidence.
    val permittedSession = action.session.takeIf {
        it is SessionAction.Ended ||
            (original.phase == DirectCallPhase.ACCEPTED && decision.rejection == null && failure == null)
    }
    val session = when (val value = permittedSession) {
        is SessionAction.Ready -> SessionStateResponse("CONNECTING", value.credentials.sessionId,
            value.credentials.deadlineEpochMillis, credentials = SessionCredentialDto.from(value.credentials), remainingLeaseMillis = value.remainingMillis)
        is SessionAction.Confirmed -> SessionStateResponse(if (value.media.state == SfuParticipantState.ACTIVE) "CONNECTED" else "CONNECTING",
            value.sessionId, value.deadlineEpochMillis, remainingLeaseMillis = value.remainingMillis)
        is SessionAction.Pending -> SessionStateResponse("PENDING", value.state.operation.sessionId,
            value.state.operation.deadlineEpochMillis, value.state.operation.operationId)
        is SessionAction.Ended -> SessionStateResponse(value.state.operation.phase.name, value.state.operation.sessionId,
            value.state.operation.deadlineEpochMillis, value.state.operation.operationId, cleanupPending = value.state.cleanupPending)
        is SessionAction.Denied -> throw ApiException(HttpStatusCode.Conflict, "operation_conflict", "Original participant operation required")
        is SessionAction.Unavailable, null -> null
    }
    val status = if (failure != null) HttpStatusCode.ServiceUnavailable
        else if (permittedSession is SessionAction.Pending) HttpStatusCode.Accepted else HttpStatusCode.OK
    respond(status, DirectCallResponse(DirectCallSnapshotDto.from(original), session, failure?.name))
}
