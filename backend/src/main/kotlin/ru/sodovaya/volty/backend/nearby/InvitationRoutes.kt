package ru.sodovaya.volty.backend.nearby

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.backend.ApiException
import ru.sodovaya.volty.backend.AppDependencies

/** Raw invitation secret exists only in a bounded request body, never server URL or response. */
fun Route.installNearbyInvitationRoutes(dependencies: AppDependencies) {
    authenticate("auth-jwt") {
        route("/v2/nearby/communities/{communityId}/invitations/{invitationId}") {
            put {
                val actor = call.nearbyActor(dependencies)
                val community = communityCanonicalId(call.parameters["communityId"] ?: "")
                val invitation = communityCanonicalId(call.parameters["invitationId"] ?: "")
                val port = dependencies.nearbyInvitations ?: invitationDisabled()
                val request = call.communityBody<InvitationCreateRequest>(dependencies)
                validateInvitationToken(request.token)
                if (request.ttlMillis !in 900_000L..604_800_000L || request.maxUses !in 1..1000) invitationInvalid()
                when (val result = withContext(Dispatchers.IO) { port.create(actor, community, invitation, request.token, request.ttlMillis, request.maxUses) }) {
                    is InvitationCreationResult.Created -> call.respond(result.invitation.dto())
                    InvitationCreationResult.Denied -> invitationDenied()
                    InvitationCreationResult.Conflict -> invitationConflict()
                }
            }
            post("/revoke") {
                val actor = call.nearbyActor(dependencies)
                val community = communityCanonicalId(call.parameters["communityId"] ?: "")
                val invitation = communityCanonicalId(call.parameters["invitationId"] ?: "")
                val port = dependencies.nearbyInvitations ?: invitationDisabled()
                when (val result = withContext(Dispatchers.IO) { port.revoke(actor, community, invitation) }) {
                    is InvitationRevocationResult.Revoked -> call.respond(InvitationRevocationResponse(result.invitationId, result.communityId, result.revision))
                    InvitationRevocationResult.Denied -> invitationDenied()
                }
            }
        }
        post("/v2/nearby/invitations/accept") {
            val actor = call.nearbyActor(dependencies)
            val port = dependencies.nearbyInvitations ?: invitationDisabled()
            val request = call.communityBody<InvitationAcceptRequest>(dependencies)
            communityCanonicalId(request.operationId); validateInvitationToken(request.token)
            when (val result = withContext(Dispatchers.IO) { port.accept(actor, request.operationId, request.token) }) {
                is InvitationAcceptanceResult.Accepted -> result.receipt.let {
                    call.respond(InvitationAcceptanceResponse(it.operationId, it.invitationId, it.communityId, it.revision, it.membershipChanged))
                }
                InvitationAcceptanceResult.Denied -> invitationDenied()
                InvitationAcceptanceResult.Conflict -> invitationConflict()
            }
        }
    }
}
private fun validateInvitationToken(token: String) {
    try { InvitationTokens.hash(token) } catch (_: IllegalArgumentException) { invitationInvalid() }
}
private fun invitationInvalid(): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "Request is invalid")
private fun invitationDisabled(): Nothing = throw ApiException(HttpStatusCode.ServiceUnavailable, "nearby_unavailable", "Nearby unavailable")
private fun invitationDenied(): Nothing = throw ApiException(HttpStatusCode.NotFound, "invitation_unavailable", "Invitation unavailable")
private fun invitationConflict(): Nothing = throw ApiException(HttpStatusCode.Conflict, "invitation_conflict", "Invitation request conflicts with current state")
