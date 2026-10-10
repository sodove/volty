package ru.sodovaya.volty.backend.nearby

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ru.sodovaya.volty.backend.*
import java.util.UUID

internal const val CHANNEL_MAP_RETENTION_MILLIS = 90_000L

@Serializable
data class ChannelSharingPacketResponse(val sessionId: String, val generation: String, val sequence: Long, val remainingMillis: Long)
@Serializable
data class ChannelSharingAudienceResponse(val channelId: String, val serverTimeEpochMillis: Long, val peers: List<ChannelSharingPeerResponse>)
@Serializable
data class ChannelSharingPeerResponse(val actorId: String, val sessionId: String, val generation: String,
    val mode: String, val packet: ChannelSharingPacket, val remainingMillis: Long, val displayName: String? = null)

fun Route.installNearbySharingPacketRoutes(dependencies: AppDependencies) {
    authenticate("auth-jwt") {
        post("/v2/nearby/sharing/{sessionId}/{generation}/packet") {
            val actor = call.nearbyActor(dependencies)
            val session = communityCanonicalId(call.parameters["sessionId"] ?: "")
            val generation = communityCanonicalId(call.parameters["generation"] ?: "")
            val port = dependencies.nearbySharingPackets ?: unavailablePackets()
            val packet = call.communityBody<ChannelSharingPacket>(dependencies)
            try { validatePacketCapture(packet) } catch (_: IllegalArgumentException) {invalidPacket()}
            val ack = try {withContext(Dispatchers.IO) {port.publish(actor, session, generation, packet)}}
                catch (_: IllegalArgumentException) {invalidPacket()}
            ack ?: missingPackets()
            if (ack.sessionId != session || ack.generation != generation || ack.sequence != packet.sequence ||
                ack.remainingMillis !in 1..LIVE_LOCATION_FRESHNESS_MILLIS) unavailablePackets()
            call.respond(ChannelSharingPacketResponse(session, generation, ack.sequence, ack.remainingMillis))
        }
        get("/v2/nearby/channel-sessions/{sessionId}/sharing/audience") {
            val actor = call.nearbyActor(dependencies)
            val session = communityCanonicalId(call.parameters["sessionId"] ?: "")
            val port = dependencies.nearbySharingPackets ?: unavailablePackets()
            val lastKnown = call.request.queryParameters["lastKnown"] == "true"
            val snapshot = withContext(Dispatchers.IO) {
                if (lastKnown) port.mapAudience(actor, session) else port.audience(actor, session)
            } ?: missingPackets()
            val response = try { safeAudience(snapshot, lastKnown) } catch (_: IllegalArgumentException) {unavailablePackets()}
            call.respond(response)
        }
    }
}

private fun safeAudience(value: ChannelSharingAudience, lastKnown: Boolean): ChannelSharingAudienceResponse {
    canonical(value.channelId); require(value.serverTimeEpochMillis >= 0)
    val actors = mutableSetOf<String>(); val sessions = mutableSetOf<String>()
    val peers = value.peers.map {peer ->
        canonical(peer.actorId); canonical(peer.sessionId); canonical(peer.generation)
        require(actors.add(peer.actorId) && sessions.add(peer.sessionId))
        validatePacketCapture(peer.packet)
        val captured = peer.packet.capturedAtEpochMillis
        val window = if (lastKnown && peer.packet.location != null) CHANNEL_MAP_RETENTION_MILLIS else LIVE_LOCATION_FRESHNESS_MILLIS
        require(captured <= Long.MAX_VALUE - window && captured <= value.serverTimeEpochMillis && value.serverTimeEpochMillis - captured < window)
        require(peer.deadlineEpochMillis > value.serverTimeEpochMillis && peer.deadlineEpochMillis <= captured + window)
        peer.packet.location?.let {
            if (lastKnown) require(it.capturedAtEpochMillis <= Long.MAX_VALUE - window && peer.deadlineEpochMillis <= it.capturedAtEpochMillis + window)
            else require(it.staleAfterEpochMillis > value.serverTimeEpochMillis && peer.deadlineEpochMillis <= it.staleAfterEpochMillis)
        }
        val stale = lastKnown && peer.packet.location?.let { value.serverTimeEpochMillis >= it.staleAfterEpochMillis } == true
        val mode = if (stale) ChannelSharingMode.LOCATION else peer.mode
        val safe = peer.packet.copy(telemetry = if (stale) null else ChannelSharingTelemetryPolicy.sanitize(mode, peer.packet.telemetry))
        ChannelSharingPeerResponse(peer.actorId, peer.sessionId, peer.generation, mode.name, safe, peer.deadlineEpochMillis - value.serverTimeEpochMillis,
            peer.displayName?.takeIf {it.length in 2..80 && it.isNotBlank() && it.none(Char::isISOControl)})
    }
    return ChannelSharingAudienceResponse(value.channelId, value.serverTimeEpochMillis, peers)
}

internal fun validatePacketCapture(packet: ChannelSharingPacket) {
    require(packet.sequence >= 0 && packet.capturedAtEpochMillis in 0..Long.MAX_VALUE - LIVE_LOCATION_FRESHNESS_MILLIS)
    packet.location?.let {
        require(it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0)
        require(it.accuracyMeters.isFinite() && it.accuracyMeters >= 0)
        require(it.capturedAtEpochMillis in 0..packet.capturedAtEpochMillis)
        require(it.staleAfterEpochMillis > it.capturedAtEpochMillis && it.staleAfterEpochMillis - it.capturedAtEpochMillis <= LIVE_LOCATION_FRESHNESS_MILLIS)
    }
}
private fun canonical(value: String) {require(UUID.fromString(value).toString() == value)}
private fun invalidPacket(): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_packet", "Sharing packet invalid")
private fun missingPackets(): Nothing = throw ApiException(HttpStatusCode.NotFound, "sharing_unavailable", "Sharing unavailable")
private fun unavailablePackets(): Nothing = throw ApiException(HttpStatusCode.ServiceUnavailable, "nearby_unavailable", "Nearby sharing unavailable")
