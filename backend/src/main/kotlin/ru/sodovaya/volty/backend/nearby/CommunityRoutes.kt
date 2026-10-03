package ru.sodovaya.volty.backend.nearby

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import ru.sodovaya.volty.backend.ApiException
import ru.sodovaya.volty.backend.AppDependencies
import java.io.ByteArrayOutputStream
import java.util.UUID

/** New community routes stay unavailable until the dependency is explicitly wired. */
fun Route.installNearbyCommunityRoutes(dependencies: AppDependencies) {
    authenticate("auth-jwt") {
        route("/v2/nearby/communities") {
            get {
                val actor = call.nearbyActor(dependencies)
                val port = dependencies.nearbyCommunities ?: disabled()
                val page = call.page()
                val items = withContext(Dispatchers.IO) { port.memberships(actor, page.first, page.second) }
                call.respond(CommunityListResponse(items.map { it.dto() }))
            }
            get("/catalog") {
                val actor = call.nearbyActor(dependencies)
                val port = dependencies.nearbyCommunities ?: disabled()
                val page = call.page()
                val query = call.singleQuery("q") ?: ""
                if (query.length > 80) invalid()
                val items = withContext(Dispatchers.IO) { port.catalog(actor, query, page.first, page.second) }
                call.respond(CommunityListResponse(items.map { it.dto() }))
            }
            get("/{communityId}") {
                val actor = call.nearbyActor(dependencies)
                val community = call.nearbyId("communityId")
                val port = dependencies.nearbyCommunities ?: disabled()
                val detail = withContext(Dispatchers.IO) { port.detail(actor, community) } ?: denied()
                call.respond(detail.dto())
            }
            put("/{communityId}") {
                val actor = call.nearbyActor(dependencies)
                val community = call.nearbyId("communityId")
                val port = dependencies.nearbyCommunities ?: disabled()
                val request = call.communityBody<CommunityCreateRequest>(dependencies)
                val title = request.name.trim(); val about = request.description.trim()
                if (title.length !in 1..80 || title.any(Char::isISOControl) || about.length > 1000 || '\u0000' in about) invalid()
                val visibility = try { CommunityVisibility.valueOf(request.visibility) } catch (_: IllegalArgumentException) { invalid() }
                call.writeResponse(withContext(Dispatchers.IO) { port.create(actor, community, title, about, visibility) })
            }
            post("/{communityId}/join") {
                val actor = call.nearbyActor(dependencies)
                val community = call.nearbyId("communityId")
                val port = dependencies.nearbyCommunities ?: disabled()
                call.writeResponse(withContext(Dispatchers.IO) { port.join(actor, community) })
            }
            post("/{communityId}/leave") {
                val actor = call.nearbyActor(dependencies)
                val community = call.nearbyId("communityId")
                val port = dependencies.nearbyCommunityRemoval ?: disabled()
                val request = call.communityBody<CommunityLeaveRequest>(dependencies)
                canonicalId(request.operationId); request.successorId?.let(::canonicalId)
                when (val result = withContext(Dispatchers.IO) { port.leave(actor, request.operationId, community, request.successorId) }) {
                    is CommunityDepartureResult.Departed -> call.respond(CommunityRemovalResponse(request.operationId,
                        result.communityId, result.revision, result.cleanupSessions.any { it.cleanupPending }))
                    CommunityDepartureResult.SuccessorRequired -> removalConflict("successor_required", "Choose a successor before leaving")
                    CommunityDepartureResult.DeleteRequired -> removalConflict("delete_required", "Last owner must explicitly delete the community")
                    CommunityDepartureResult.InvalidSuccessor -> removalConflict("invalid_successor", "Successor is not eligible")
                    CommunityDepartureResult.Conflict -> removalConflict("community_conflict", "Request conflicts with current community")
                    CommunityDepartureResult.Denied -> denied()
                }
            }
            post("/{communityId}/delete") {
                val actor = call.nearbyActor(dependencies)
                val community = call.nearbyId("communityId")
                val port = dependencies.nearbyCommunityRemoval ?: disabled()
                val request = call.communityBody<CommunityDeleteRequest>(dependencies)
                canonicalId(request.operationId)
                when (val result = withContext(Dispatchers.IO) { port.delete(actor, request.operationId, community) }) {
                    is CommunityDeletionResult.Deleted -> call.respond(CommunityRemovalResponse(request.operationId,
                        result.communityId, result.revision, result.cleanupSessions.any { it.cleanupPending }))
                    CommunityDeletionResult.Conflict -> removalConflict("community_conflict", "Request conflicts with current community")
                    CommunityDeletionResult.Denied -> denied()
                }
            }
        }
    }
}

private suspend fun ApplicationCall.writeResponse(result: CommunityWriteResult) {
    when (result) {
        is CommunityWriteResult.Accepted -> respond(result.snapshot.dto())
        CommunityWriteResult.Denied -> denied()
        CommunityWriteResult.Conflict -> throw ApiException(HttpStatusCode.Conflict, "community_conflict", "Request conflicts with current community")
    }
}

private fun ApplicationCall.singleQuery(name: String): String? {
    val values = request.queryParameters.getAll(name) ?: return null
    if (values.size != 1) invalid()
    return values.single()
}
private fun ApplicationCall.page(): Pair<String?, Int> {
    val after = singleQuery("after")?.let {
        try { require(it.length == 36); UUID.fromString(it).toString() } catch (_: IllegalArgumentException) { invalid() }
    }
    val limit = singleQuery("limit")?.let { it.toIntOrNull() ?: invalid() } ?: 20
    if (limit !in 1..50) invalid()
    return after to limit
}

private suspend inline fun <reified T> ApplicationCall.communityBody(dependencies: AppDependencies): T {
    val maximum = 16 * 1024
    if ((request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > maximum) tooLarge()
    val bytes = withTimeoutOrNull(10_000) {
        val channel = receiveChannel(); val buffer = ByteArray(4096); val body = ByteArrayOutputStream()
        while (body.size() <= maximum) {
            currentCoroutineContext().ensureActive()
            val read = channel.readAvailable(buffer, 0, minOf(buffer.size, maximum + 1 - body.size()))
            if (read == -1) break
            if (read > 0) body.write(buffer, 0, read) else yield()
        }
        if (body.size() > maximum) tooLarge()
        body.toByteArray()
    } ?: throw ApiException(HttpStatusCode.RequestTimeout, "request_timeout", "Request body timed out")
    return try {
        // CharsetDecoder defaults to REPORT, never replacement characters that
        // silently change a name/idempotency payload.
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        dependencies.json.decodeFromString<T>(text)
    }
    catch (_: IllegalArgumentException) { invalid() }
    catch (_: java.nio.charset.CharacterCodingException) { invalid() }
}

private fun invalid(): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "Request is invalid")
private fun canonicalId(value: String) {
    try { if (value.length != 36 || UUID.fromString(value).toString() != value) invalid() }
    catch (_: IllegalArgumentException) { invalid() }
}
private fun removalConflict(code: String, message: String): Nothing = throw ApiException(HttpStatusCode.Conflict, code, message)
private fun denied(): Nothing = throw ApiException(HttpStatusCode.NotFound, "community_unavailable", "Community unavailable")
private fun disabled(): Nothing = throw ApiException(HttpStatusCode.ServiceUnavailable, "nearby_unavailable", "Nearby unavailable")
private fun tooLarge(): Nothing = throw ApiException(HttpStatusCode.PayloadTooLarge, "body_too_large", "Request body is too large")
