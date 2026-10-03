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

/** Unwired until the coordinated v2 cutover. Membership and trip disconnect are separate commands. */
fun Route.installNearbyChannelCommandsRoutes(dependencies: AppDependencies) {
    authenticate("auth-jwt") {
        route("/v2/nearby/communities/{communityId}/channels/{channelId}") {
            put {
                val actor = call.nearbyActor(dependencies)
                val community = communityCanonicalId(call.parameters["communityId"] ?: "")
                val channel = communityCanonicalId(call.parameters["channelId"] ?: "")
                val port = dependencies.nearbyChannelCommands ?: channelDisabled()
                val request = call.communityBody<ChannelCreateRequest>(dependencies)
                val title = request.name.trim()
                if (title.length !in 1..80 || title.any(Char::isISOControl)) channelInvalid()
                when (val result = withContext(Dispatchers.IO) { port.create(actor, community, channel, title) }) {
                    is ChannelCreationResult.Created -> call.respond(ChannelCreationResponse(community,
                        CommunityChannelDto(result.channel.id, result.channel.name, result.channel.isDefault), result.revision))
                    ChannelCreationResult.Denied -> channelDenied()
                    ChannelCreationResult.Conflict -> channelConflict("channel_conflict")
                    ChannelCreationResult.LimitReached -> channelConflict("channel_limit_reached")
                }
            }
            post("/delete") {
                val actor = call.nearbyActor(dependencies)
                val community = communityCanonicalId(call.parameters["communityId"] ?: "")
                val channel = communityCanonicalId(call.parameters["channelId"] ?: "")
                val port = dependencies.nearbyChannelCommands ?: channelDisabled()
                val request = call.communityBody<ChannelDeleteRequest>(dependencies)
                communityCanonicalId(request.operationId)
                when (val result = withContext(Dispatchers.IO) { port.delete(actor, request.operationId, community, channel) }) {
                    is ChannelDeletionResult.Deleted -> call.respond(ChannelDeletionResponse(request.operationId,
                        result.communityId, result.channelId, result.revision, result.cleanupSessions.any { it.cleanupPending }))
                    ChannelDeletionResult.Denied -> channelDenied()
                    ChannelDeletionResult.Conflict -> channelConflict("channel_conflict")
                }
            }
        }
    }
}
private fun channelDisabled(): Nothing = throw ApiException(HttpStatusCode.ServiceUnavailable, "nearby_unavailable", "Nearby unavailable")
private fun channelDenied(): Nothing = throw ApiException(HttpStatusCode.NotFound, "channel_unavailable", "Channel unavailable")
private fun channelInvalid(): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "Request is invalid")
private fun channelConflict(code: String): Nothing = throw ApiException(HttpStatusCode.Conflict, code, "Channel request conflicts with current state")
