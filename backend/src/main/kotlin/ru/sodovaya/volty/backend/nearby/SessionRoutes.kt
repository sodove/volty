package ru.sodovaya.volty.backend.nearby

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.backend.ApiException
import ru.sodovaya.volty.backend.AppDependencies
import java.util.UUID

/** Ownership belongs to the authenticated actor; no client-provided room or account. */
interface SessionEndpointPort {
    fun join(actor: String, operation: String, destination: String, replace: Boolean = false): SessionAction
    fun status(actor: String, operation: String): SessionAction?
    fun cancel(actor: String, operation: String): SessionAction
    fun heartbeat(actor: String, session: String): SessionAction?
    fun disconnect(actor: String, session: String): SessionAction?
}

fun Route.installNearbySessionRoutes(dependencies: AppDependencies) {
    authenticate("auth-jwt") {
        route("/v2/nearby") {
            post("/operations/{operationId}/join") {
                val actor = call.nearbyActor(dependencies)
                val operation = call.nearbyId("operationId")
                val port = dependencies.nearbySessions ?: unavailable()
                val request = call.receive<SessionJoinRequest>()
                val destination = canonicalId(request.destinationId)
                call.sessionResponse(withContext(Dispatchers.IO) { port.join(actor, operation, destination, request.replace) })
            }
            get("/operations/{operationId}") {
                val actor = call.nearbyActor(dependencies)
                val operation = call.nearbyId("operationId")
                val port = dependencies.nearbySessions ?: unavailable()
                call.sessionResponse(withContext(Dispatchers.IO) { port.status(actor, operation) })
            }
            post("/operations/{operationId}/cancel") {
                val actor = call.nearbyActor(dependencies)
                val operation = call.nearbyId("operationId")
                val port = dependencies.nearbySessions ?: unavailable()
                call.sessionResponse(withContext(Dispatchers.IO) { port.cancel(actor, operation) })
            }
            post("/sessions/{sessionId}/heartbeat") {
                val actor = call.nearbyActor(dependencies)
                val session = call.nearbyId("sessionId")
                val port = dependencies.nearbySessions ?: unavailable()
                call.sessionResponse(withContext(Dispatchers.IO) { port.heartbeat(actor, session) })
            }
            post("/sessions/{sessionId}/disconnect") {
                val actor = call.nearbyActor(dependencies)
                val session = call.nearbyId("sessionId")
                val port = dependencies.nearbySessions ?: unavailable()
                call.sessionResponse(withContext(Dispatchers.IO) { port.disconnect(actor, session) })
            }
        }
    }
}

internal fun ApplicationCall.nearbyActor(dependencies: AppDependencies): String {
    response.header(HttpHeaders.CacheControl, "no-store")
    val subject = principal<JWTPrincipal>()?.payload?.subject
        ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Authentication required")
    return dependencies.store.findUserById(subject)?.id
        ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Account unavailable")
}

internal fun ApplicationCall.nearbyId(name: String) = canonicalId(parameters[name] ?: "")
private fun canonicalId(value: String): String = try {
    require(value.length == 36)
    UUID.fromString(value).toString()
} catch (_: IllegalArgumentException) {
    throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "UUID required")
}
private fun unavailable(): Nothing = throw ApiException(HttpStatusCode.ServiceUnavailable, "nearby_unavailable", "Nearby unavailable")

private suspend fun ApplicationCall.sessionResponse(action: SessionAction?) {
    if (action == null) throw ApiException(HttpStatusCode.NotFound, "session_not_found", "Session unavailable")
    when (action) {
        is SessionAction.Ready -> respond(SessionStateResponse("CONNECTING", action.credentials.sessionId,
            action.credentials.deadlineEpochMillis, credentials = SessionCredentialDto.from(action.credentials),
            remainingLeaseMillis = action.remainingMillis))
        is SessionAction.Confirmed -> respond(SessionStateResponse(
            if (action.media.state == SfuParticipantState.ACTIVE) "CONNECTED" else "CONNECTING", action.sessionId,
            action.deadlineEpochMillis, remainingLeaseMillis = action.remainingMillis))
        is SessionAction.Pending -> respond(HttpStatusCode.Accepted, SessionStateResponse("PENDING", action.state.operation.sessionId,
            action.state.operation.deadlineEpochMillis, action.state.operation.operationId))
        is SessionAction.Ended -> respond(SessionStateResponse(action.state.operation.phase.name, action.state.operation.sessionId,
            action.state.operation.deadlineEpochMillis, action.state.operation.operationId))
        is SessionAction.Unavailable -> unavailable()
        is SessionAction.Denied -> throw when (action.reason) {
            SessionDenial.MEMBERSHIP -> ApiException(HttpStatusCode.Forbidden, "membership_required", "Channel membership required")
            SessionDenial.OPERATION_CONFLICT -> ApiException(HttpStatusCode.Conflict, "operation_conflict", "Operation conflict")
            SessionDenial.BUSY -> ApiException(HttpStatusCode.Conflict, "voice_busy", "Already connected")
        }
    }
}
