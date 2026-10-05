package ru.sodovaya.volty.backend.nearby

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ru.sodovaya.volty.backend.ApiException
import ru.sodovaya.volty.backend.AppDependencies

@Serializable
data class ChannelSharingStartRequest(val mode: ChannelSharingMode, val ttl: ChannelSharingTtl)
@Serializable
data class ChannelSharingResponse(val sessionId: String, val generation: String, val phase: String,
    val mode: String?, val remainingMillis: Long)

/** Own consent only. Channel coordinate audience is a separate session-authorized boundary. */
fun Route.installNearbyChannelSharingRoutes(dependencies: AppDependencies) {
    authenticate("auth-jwt") {
        route("/v2/nearby/sharing/{sessionId}/{generation}") {
            get {
                val actor = call.nearbyActor(dependencies)
                val session = communityCanonicalId(call.parameters["sessionId"] ?: "")
                val generation = communityCanonicalId(call.parameters["generation"] ?: "")
                val port = dependencies.nearbyChannelSharing ?: sharingUnavailable()
                val result = withContext(Dispatchers.IO) {port.status(actor, session, generation)}
                call.sharingResponse(result, actor, session, generation, negative = false)
            }
            post("/start") {
                val actor = call.nearbyActor(dependencies)
                val session = communityCanonicalId(call.parameters["sessionId"] ?: "")
                val generation = communityCanonicalId(call.parameters["generation"] ?: "")
                val port = dependencies.nearbyChannelSharing ?: sharingUnavailable()
                val request = call.communityBody<ChannelSharingStartRequest>(dependencies)
                val result = withContext(Dispatchers.IO) {port.start(actor, session, generation, request.mode, request.ttl)}
                call.sharingResponse(result, actor, session, generation, negative = false)
            }
            post("/revoke") {
                val actor = call.nearbyActor(dependencies)
                val session = communityCanonicalId(call.parameters["sessionId"] ?: "")
                val generation = communityCanonicalId(call.parameters["generation"] ?: "")
                val port = dependencies.nearbyChannelSharing ?: sharingUnavailable()
                // No positive-access/status preflight: this exact negative survives disconnect.
                val result = withContext(Dispatchers.IO) {port.revoke(actor, session, generation)}
                call.sharingResponse(result, actor, session, generation, negative = true)
            }
        }
    }
}

private suspend fun ApplicationCall.sharingResponse(result: ChannelSharingDecision?, actor: String,
    session: String, generation: String, negative: Boolean) {
    result ?: throw ApiException(HttpStatusCode.NotFound, "sharing_unavailable", "Sharing unavailable")
    val consent = result.consent
    val active = consent.phase == ChannelSharingPhase.ACTIVE
    val valid = consent.actorId == actor && consent.sessionId == session && consent.generation == generation &&
        (consent.mode == null) == (consent.deadlineEpochMillis == null) &&
        (consent.deadlineEpochMillis == null || consent.deadlineEpochMillis >= 0) &&
        (if (negative) !active && result.remainingMillis == 0L
        else active && consent.mode != null && result.remainingMillis in 1..ChannelSharingTtl.EIGHT_HOURS.millis)
    if (!valid) sharingUnavailable()
    respond(ChannelSharingResponse(session, generation, consent.phase.name,
        consent.mode?.name?.takeIf {active}, result.remainingMillis))
}
private fun sharingUnavailable(): Nothing = throw ApiException(HttpStatusCode.ServiceUnavailable, "nearby_unavailable", "Nearby sharing unavailable")
