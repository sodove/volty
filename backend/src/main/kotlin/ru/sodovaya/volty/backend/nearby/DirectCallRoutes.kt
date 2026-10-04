package ru.sodovaya.volty.backend.nearby

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.backend.ApiException
import ru.sodovaya.volty.backend.AppDependencies
import java.util.UUID

fun Route.installNearbyDirectCallRoutes(dependencies: AppDependencies) {
    authenticate("auth-jwt") {
        route("/v2/nearby/calls") {
            get("/incoming") {
                val actor = call.nearbyActor(dependencies)
                val port = dependencies.nearbyCalls ?: unavailableCalls()
                val raw = call.request.queryParameters["limit"]
                val limit = if (raw == null) 20 else raw.toIntOrNull() ?: invalidCallRequest()
                if (limit !in 1..50) invalidCallRequest()
                val cursor = call.request.queryParameters["after"]?.let(::callId)
                val page = withContext(Dispatchers.IO) {port.incoming(actor, limit, cursor)}
                call.respond(IncomingCallsResponse(page.calls.map(DirectCallSnapshotDto::from), page.nextCursor))
            }
            get("/{callId}") {
                val actor = call.nearbyActor(dependencies); val id = call.nearbyId("callId")
                val port = dependencies.nearbyCalls ?: unavailableCalls()
                call.directCallResponse(withContext(Dispatchers.IO) {port.status(actor, id)})
            }
            post("/{callId}/create") {
                val actor = call.nearbyActor(dependencies); val id = call.nearbyId("callId")
                val port = dependencies.nearbyCalls ?: unavailableCalls()
                val request = call.receive<DirectCallCreateRequest>()
                val callee = callId(request.calleeId); val key = callId(request.idempotencyKey); val operation = callId(request.operationId)
                if (actor == callee) invalidCallRequest()
                call.directCallResponse(withContext(Dispatchers.IO) {port.create(actor, callee, id, key, operation)})
            }
            post("/{callId}/accept") {
                val actor = call.nearbyActor(dependencies); val id = call.nearbyId("callId")
                val port = dependencies.nearbyCalls ?: unavailableCalls()
                val request = call.receive<DirectCallAcceptRequest>(); val operation = callId(request.operationId)
                if (request.expectedRevision < 0) invalidCallRequest()
                call.directCallResponse(withContext(Dispatchers.IO) {port.accept(actor, id, operation, request.expectedRevision)})
            }
            post("/{callId}/resume") {
                val actor = call.nearbyActor(dependencies); val id = call.nearbyId("callId")
                val port = dependencies.nearbyCalls ?: unavailableCalls()
                val operation = callId(call.receive<DirectCallResumeRequest>().operationId)
                call.directCallResponse(withContext(Dispatchers.IO) {port.resume(actor, id, operation)})
            }
            post("/{callId}/decline") {
                val actor = call.nearbyActor(dependencies); val id = call.nearbyId("callId")
                val port = dependencies.nearbyCalls ?: unavailableCalls()
                call.directCallResponse(withContext(Dispatchers.IO) {port.decline(actor, id)})
            }
            post("/{callId}/stop") {
                val actor = call.nearbyActor(dependencies); val id = call.nearbyId("callId")
                val port = dependencies.nearbyCalls ?: unavailableCalls()
                call.directCallResponse(withContext(Dispatchers.IO) {port.stop(actor, id)})
            }
            post("/{callId}/cancel-outgoing") {
                val actor = call.nearbyActor(dependencies); val id = call.nearbyId("callId")
                val port = dependencies.nearbyCalls ?: unavailableCalls()
                val request = call.receive<DirectCallCreateRequest>()
                val callee = callId(request.calleeId); val key = callId(request.idempotencyKey); val operation = callId(request.operationId)
                if (actor == callee) invalidCallRequest()
                call.directCallResponse(withContext(Dispatchers.IO) {port.cancelOutgoing(actor, callee, id, key, operation)})
            }
        }
    }
}

private fun callId(value: String): String = try {
    require(value.length == 36); UUID.fromString(value).toString()
} catch (_: IllegalArgumentException) {invalidCallRequest()}
private fun invalidCallRequest(): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "Valid call UUID, bounds and revision required")
private fun unavailableCalls(): Nothing = throw ApiException(HttpStatusCode.ServiceUnavailable, "nearby_unavailable", "Nearby calls unavailable")
