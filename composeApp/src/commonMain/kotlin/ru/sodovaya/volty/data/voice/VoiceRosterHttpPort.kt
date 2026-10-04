package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.domain.voice.*

/** Account-bound snapshot transport; caller owns subscription generation and retirement. */
class VoiceRosterHttpPort(client: HttpClient, baseUrl: String, private val bearer: suspend () -> String?,
                          private val monotonicMillis: () -> Long) : VoiceRosterSnapshotPort {
    private val base = nearbyHttpsApiBaseUrl(baseUrl)
    private val http = client.config { followRedirects = false; expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMutex = Mutex()
    private var nextOrdinal = 0L

    override suspend fun snapshot(tag: VoiceRosterTag): VoiceRosterSnapshotReply {
        id(tag.channelId)
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
                val auth = bearer()?.takeIf { it.isNotBlank() } ?: return@withTimeoutOrNull failed(VoiceRosterFailure.UNAUTHORIZED)
                val (ordinal, started) = requestMutex.withLock {
                    check(nextOrdinal < Long.MAX_VALUE)
                    VoiceLeaseRequestId(nextOrdinal++) to monotonicMillis().also { require(it >= 0) }
                }
                http.prepareGet("$base/channels/${tag.channelId}/roster") {
                    bearerAuth(auth); header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }.execute { response ->
                    if (response.status.value != 200) return@execute failed(when (response.status.value) {
                        401 -> VoiceRosterFailure.UNAUTHORIZED
                        403, 404 -> VoiceRosterFailure.ACCESS_DENIED
                        else -> VoiceRosterFailure.UNAVAILABLE
                    })
                    val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
                    require(bytes.size <= MAX_RESPONSE_BYTES)
                    val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                    catch (_: Exception) { return@execute failed(VoiceRosterFailure.INVALID_RESPONSE) }
                    val root = json.parseToJsonElement(text) as? JsonObject ?: invalid()
                    require(string(root, "channelId") == tag.channelId)
                    val revision = number(root, "revision").also { require(it >= 0) }
                    val serverTime = number(root, "serverTimeEpochMillis").also { require(it >= 0) }
                    val rows = root["peers"] as? JsonArray ?: invalid()
                    val peers = rows.map { readPeer(it as? JsonObject ?: invalid()) }.sortedBy { it.actorId }
                    require(peers.map { it.actorId }.distinct().size == peers.size && peers.map { it.sessionId }.distinct().size == peers.size)
                    val deadlines = peers.associate { peer ->
                        val remaining = (peer.deadlineEpochMillis - serverTime).coerceAtLeast(0)
                        require(remaining <= MAX_LEASE_MILLIS && started <= Long.MAX_VALUE - remaining)
                        peer.sessionId to started + remaining
                    }
                    val received = monotonicMillis().also { require(it >= started) }
                    VoiceRosterSnapshotReply.Received(VoiceRosterObservation(VoiceRosterUpdate(tag, revision, peers),
                        ordinal, started, received, serverTime, deadlines))
                }
            } ?: failed(VoiceRosterFailure.UNAVAILABLE)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: IllegalArgumentException) { failed(VoiceRosterFailure.INVALID_RESPONSE) }
          catch (_: Exception) { failed(VoiceRosterFailure.UNAVAILABLE) }
    }
    fun close() = http.close()
    private fun readPeer(root: JsonObject): VoiceRosterPeer {
        val actor = string(root, "actorId").also(::id)
        val session = VoiceSessionId(string(root, "sessionId").also(::id))
        val operation = VoiceOperationId(string(root, "operationId").also(::id))
        val phase = VoiceRosterPhase.valueOf(string(root, "phase"))
        val deadline = number(root, "deadlineEpochMillis").also { require(it >= 0) }
        val media = string(root, "mediaPresence").also { require(it == "UNKNOWN" || it == "CONNECTED") }
        val confirmed = media == "CONNECTED"
        val sid = root["participantSid"]?.takeUnless { it == JsonNull }?.let {
            require(it is JsonPrimitive && it.isString)
            it.content.also { value -> require(value.length in 1..256 && value.isNotBlank() && value.none(Char::isISOControl)) }
        }
        require((phase == VoiceRosterPhase.CONNECTED) == confirmed && (!confirmed || sid != null))
        return VoiceRosterPeer(actor, session, operation, phase, deadline, sid, confirmed)
    }
    private fun primitive(root: JsonObject, key: String) = (root[key] as? JsonPrimitive)?.takeUnless { it == JsonNull } ?: invalid()
    private fun string(root: JsonObject, key: String) = primitive(root, key).also { require(it.isString) }.content
    private fun number(root: JsonObject, key: String) = primitive(root, key).let { require(!it.isString); requireNotNull(it.longOrNull) }
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid voice roster")
    private fun id(value: String) { require(ID.matches(value)) }
    private fun failed(reason: VoiceRosterFailure) = VoiceRosterSnapshotReply.Failed(reason)
    private companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024L
        const val MAX_LEASE_MILLIS = 30_000L
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
