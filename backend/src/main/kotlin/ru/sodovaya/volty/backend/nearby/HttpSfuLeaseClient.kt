package ru.sodovaya.volty.backend.nearby

import io.livekit.server.AccessToken
import io.livekit.server.RoomAdmin
import io.livekit.server.RoomName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** No response text, bearer token or transport exception is exposed as a failure. */
class HttpSfuLeaseClient(
    private val origin: URI,
    private val apiKey: String,
    private val apiSecret: String,
    private val clock: Clock = Clock.systemUTC(),
    private val requestTimeout: Duration = Duration.ofSeconds(6),
) : SfuLeaseClient, SfuConnectedLeaseClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    init {
        require(origin.scheme in setOf("http", "https") && origin.host != null && origin.userInfo == null &&
            origin.rawQuery == null && origin.rawFragment == null && origin.rawPath in setOf("", "/")) { "SFU control origin required" }
        require(apiKey.isNotBlank() && apiSecret.isNotBlank()) { "SFU credentials required" }
        require(!requestTimeout.isNegative && requestTimeout >= Duration.ofMillis(100) && requestTimeout <= Duration.ofSeconds(10))
    }

    override fun inspect(room: String): SfuReply<SfuNodeStatus> {
        val response = post("readiness", room, json.encodeToString(ReadinessRequest(room)))
        if (response is SfuReply.Rejected) return response
        val wire = (response as SfuReply.Accepted).value
        if (wire.status != 200) return rejectStatus(wire.status)
        val value = try { json.decodeFromString<ReadinessResponse>(wire.body) } catch (_: Exception) {
            return SfuReply.Rejected(SfuFailure.INVALID_RESPONSE)
        }
        if (value.protocolVersion != 4 || !value.singleNode || !value.nodeEpoch.matches(Regex("[a-f0-9]{32}")) ||
            value.capacity !in 1..1_000_000 || value.slotsUsed !in 0..value.capacity || value.maximumLeaseSeconds != 30 ||
            value.lastSweepAgeMillis < -1 || (value.ready && value.lastSweepAgeMillis !in 0..1999)) {
            return SfuReply.Rejected(SfuFailure.INVALID_RESPONSE)
        }
        return SfuReply.Accepted(SfuNodeStatus(value.nodeEpoch, value.ready, value.slotsUsed < value.capacity, value.singleNode))
    }

    override fun register(room: String, session: String, expectedNodeEpoch: String) = lease("register", room, session, expectedNodeEpoch)
    override fun renew(room: String, session: String, expectedNodeEpoch: String) = lease("renew", room, session, expectedNodeEpoch)

    override fun renewConnected(room: String, session: String, expectedNodeEpoch: String, expectedParticipantSid: String): SfuReply<LeaseReceipt> {
        require(expectedParticipantSid.isNotBlank() && expectedParticipantSid.length <= 256)
        return lease("renew-connected", room, session, expectedNodeEpoch, expectedParticipantSid)
    }

    override fun observeParticipant(room: String, session: String, expectedNodeEpoch: String): SfuReply<SfuParticipantObservation> {
        require(session.isNotBlank() && session.length <= 256 && expectedNodeEpoch.matches(Regex("[a-f0-9]{32}")))
        val response = post("participant", room, json.encodeToString(ParticipantRequest(room, session, expectedNodeEpoch)))
        if (response is SfuReply.Rejected) return response
        val wire = (response as SfuReply.Accepted).value
        if (wire.status != 200) return rejectStatus(wire.status)
        val value = try { json.decodeFromString<ParticipantResponse>(wire.body) } catch (_: Exception) {
            return SfuReply.Rejected(SfuFailure.INVALID_RESPONSE)
        }
        if (value.nodeEpoch != expectedNodeEpoch || value.sid.length > 256 ||
            value.sid.isEmpty() != value.state.isEmpty() || (value.sid.isNotEmpty() && value.sid.isBlank())) {
            return SfuReply.Rejected(SfuFailure.INVALID_RESPONSE)
        }
        if (value.sid.isEmpty()) return SfuReply.Accepted(SfuParticipantObservation(null, null))
        val state = SfuParticipantState.entries.firstOrNull { it.name == value.state }
            ?: return SfuReply.Rejected(SfuFailure.INVALID_RESPONSE)
        return SfuReply.Accepted(SfuParticipantObservation(value.sid, state))
    }

    override fun revoke(room: String, session: String, expectedNodeEpoch: String): SfuReply<Unit> {
        val response = post("revoke", room, json.encodeToString(ControlRequest(room, session, 0, expectedNodeEpoch)))
        if (response is SfuReply.Rejected) return response
        val wire = (response as SfuReply.Accepted).value
        return if (wire.status == 204) SfuReply.Accepted(Unit) else rejectStatus(wire.status)
    }

    private fun lease(action: String, room: String, session: String, epoch: String, participantSid: String? = null): SfuReply<LeaseReceipt> {
        val requestStartedAt = clock.millis()
        val response = post(action, room, json.encodeToString(ControlRequest(room, session, 30, epoch, participantSid)))
        if (response is SfuReply.Rejected) return response
        val wire = (response as SfuReply.Accepted).value
        if (wire.status != 204) return rejectStatus(wire.status)
        val remaining = wire.remainingMillis
        if (remaining == null || remaining !in 1..30_000) return SfuReply.Rejected(SfuFailure.INVALID_RESPONSE)
        return SfuReply.Accepted(LeaseReceipt(Math.addExact(requestStartedAt, remaining)))
    }

    private fun post(action: String, room: String, body: String): SfuReply<WireReply> {
        require(room.isNotBlank() && room.length <= 256)
        return try {
            val bearer = AccessToken(apiKey, apiSecret).apply {
                expiration = Date(Math.addExact(clock.millis(), 60_000))
                addGrants(RoomAdmin(true), RoomName(room))
            }.toJwt()
            val request = HttpRequest.newBuilder(origin.resolve("/volty/leases/$action"))
                .timeout(requestTimeout).header("Authorization", "Bearer $bearer")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val receiver = LimitedHttpBody(4096)
            val pending = http.sendAsync(request, HttpResponse.BodyHandler { receiver })
            try {
                val response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                SfuReply.Accepted(WireReply(response.statusCode(), response.body().toString(Charsets.UTF_8),
                    response.headers().firstValue("X-Volty-Lease-Remaining-Millis").orElse(null)?.toLongOrNull()))
            } finally {
                // Request timeout alone can finish at headers; bound the complete body too.
                if (!pending.isDone) { receiver.abort(); pending.cancel(true) }
            }
        } catch (_: HttpTimeoutException) {
            SfuReply.Rejected(SfuFailure.TIMEOUT)
        } catch (_: TimeoutException) {
            SfuReply.Rejected(SfuFailure.TIMEOUT)
        } catch (failure: ExecutionException) {
            SfuReply.Rejected(when (failure.cause) {
                is OversizedHttpBody -> SfuFailure.INVALID_RESPONSE
                is HttpTimeoutException -> SfuFailure.TIMEOUT
                else -> SfuFailure.UNAVAILABLE
            })
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            SfuReply.Rejected(SfuFailure.INTERRUPTED)
        } catch (_: Exception) {
            SfuReply.Rejected(SfuFailure.UNAVAILABLE)
        }
    }

    private fun rejectStatus(status: Int) = SfuReply.Rejected(when (status) {
        404 -> SfuFailure.UNSUPPORTED
        401, 403 -> SfuFailure.FORBIDDEN
        409 -> SfuFailure.REJECTED
        503 -> SfuFailure.UNAVAILABLE
        in 500..599 -> SfuFailure.UNAVAILABLE
        else -> SfuFailure.INVALID_RESPONSE
    })

    private data class WireReply(val status: Int, val body: String, val remainingMillis: Long?)
    @Serializable private data class ReadinessRequest(val room: String)
    @Serializable private data class ControlRequest(val room: String, val identity: String, val ttlSeconds: Int, val nodeEpoch: String, val participantSid: String? = null)
    @Serializable private data class ParticipantRequest(val room: String, val identity: String, val nodeEpoch: String)
    @Serializable private data class ParticipantResponse(val nodeEpoch: String, val sid: String, val state: String)
    @Serializable private data class ReadinessResponse(
        val protocolVersion: Int, val nodeEpoch: String, val ready: Boolean, val slotsUsed: Int,
        val capacity: Int, val maximumLeaseSeconds: Int, val lastSweepAgeMillis: Long, val singleNode: Boolean,
    )
}
