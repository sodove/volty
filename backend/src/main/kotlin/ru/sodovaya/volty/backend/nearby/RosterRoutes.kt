package ru.sodovaya.volty.backend.nearby

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ru.sodovaya.volty.backend.ApiException
import ru.sodovaya.volty.backend.AppDependencies

/** Implementations authorize membership/ban/channel generation as part of their snapshot. */
fun interface RosterEndpointPort {
    fun snapshot(actor: String, channel: String): ChannelRosterSnapshot?
}

fun Route.installNearbyRosterRoutes(dependencies: AppDependencies) {
    authenticate("auth-jwt") {
        get("/v2/nearby/channels/{channelId}/roster") {
            val actor = call.nearbyActor(dependencies)
            val channel = call.nearbyId("channelId")
            val port = dependencies.nearbyRoster
                ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "nearby_unavailable", "Nearby unavailable")
            val snapshot = withContext(Dispatchers.IO) { port.snapshot(actor, channel) }
                ?: throw ApiException(HttpStatusCode.Forbidden, "membership_required", "Channel membership required")
            if (snapshot.channelId != channel) throw ApiException(HttpStatusCode.ServiceUnavailable, "nearby_unavailable", "Nearby unavailable")
            call.respond(RosterSnapshotDto(snapshot.channelId, snapshot.revision, snapshot.serverTimeEpochMillis,
                snapshot.peers.map { RosterPeerDto(it.actorId, it.sessionId, it.operationId, it.phase.name, it.deadlineEpochMillis, it.mediaPresence.name) }))
        }
    }
}

@Serializable private data class RosterSnapshotDto(
    val channelId: String, val revision: Long, val serverTimeEpochMillis: Long, val peers: List<RosterPeerDto>,
)
@Serializable private data class RosterPeerDto(
    val actorId: String, val sessionId: String, val operationId: String,
    val phase: String, val deadlineEpochMillis: Long, val mediaPresence: String,
)
