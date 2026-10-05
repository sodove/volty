package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.*
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import ru.sodovaya.volty.data.nearby.nearbyHttpsApiBaseUrl
import ru.sodovaya.volty.domain.voice.*

/** Strict call pair transport. Owns its HTTP child, never the borrowed account or parent client. */
class DirectCallRosterHttpPort(client: HttpClient, baseUrl: String, private val actor: String,
                              private val bearer: suspend () -> String?, private val monotonicMillis: () -> Long) : DirectCallRosterPort {
    private val base = nearbyHttpsApiBaseUrl(baseUrl)
    init {id(actor)}
    private val http = client.config {followRedirects = false; expectSuccess = false}
    private val sequence = VoiceLeaseRequestSequence()
    private val json = Json {ignoreUnknownKeys = true}

    override suspend fun snapshot(tag: DirectCallRosterTag): DirectCallRosterReply {
        currentCoroutineContext().ensureActive()
        id(tag.callId); id(tag.peerId); require(tag.peerId != actor)
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
                val token = bearer()?.takeIf {it.isNotBlank()} ?: return@withTimeoutOrNull failed(VoiceRosterFailure.UNAUTHORIZED)
                val (ordinal, started) = sequence.reserve(monotonicMillis)
                http.prepareGet("$base/calls/${tag.callId}/roster") {
                    bearerAuth(token); header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }.execute {response ->
                    if (response.status.value != 200) return@execute failed(when (response.status.value) {
                        401 -> VoiceRosterFailure.UNAUTHORIZED
                        403, 404 -> VoiceRosterFailure.ACCESS_DENIED
                        else -> VoiceRosterFailure.UNAVAILABLE
                    })
                    val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
                    require(bytes.size <= MAX_RESPONSE_BYTES)
                    val text = try {bytes.decodeToString(throwOnInvalidSequence = true)} catch (_: Exception) {invalid()}
                    val root = json.parseToJsonElement(text) as? JsonObject ?: invalid()
                    require(PRIVATE_FIELDS.none {it in root} && string(root, "callId") == tag.callId)
                    val revision = number(root, "revision").also {require(it >= 0)}
                    val serverTime = number(root, "serverTimeEpochMillis").also {require(it >= 0)}
                    val rows = root["peers"] as? JsonArray ?: invalid()
                    require(rows.size == 2)
                    val decoded = rows.map {readPeer(it as? JsonObject ?: invalid())}
                    val peers = decoded.map {it.first}.sortedBy {it.actorId}
                    require(peers.map {it.actorId}.toSet() == setOf(actor, tag.peerId) && peers.map {it.sessionId}.distinct().size == 2)
                    val deadlines = decoded.map {it.second}
                    require(deadlines.distinct().size == 1)
                    val deadline = deadlines.first()
                    require(deadline > serverTime)
                    val remaining = deadline - serverTime
                    require(remaining <= MAX_LEASE_MILLIS && started <= Long.MAX_VALUE - remaining)
                    val received = monotonicMillis().also {require(it >= started)}
                    DirectCallRosterReply.Received(DirectCallRosterObservation(tag, revision, peers, ordinal,
                        started, received, serverTime, started + remaining))
                }
            } ?: failed(VoiceRosterFailure.UNAVAILABLE)
        } catch (cancelled: CancellationException) {throw cancelled}
          catch (_: IllegalArgumentException) {failed(VoiceRosterFailure.INVALID_RESPONSE)}
          catch (_: Exception) {failed(VoiceRosterFailure.UNAVAILABLE)}
    }
    fun close() = http.close()
    private fun readPeer(root: JsonObject): Pair<DirectCallRosterPeer, Long> {
        require(PRIVATE_FIELDS.none {it in root})
        val owner = string(root, "actorId").also(::id)
        val session = VoiceSessionId(string(root, "sessionId").also(::id))
        val phase = VoiceRosterPhase.valueOf(string(root, "phase"))
        val media = string(root, "mediaPresence").also {require(it == "UNKNOWN" || it == "CONNECTED")}
        val sid = root["participantSid"]?.takeUnless {it == JsonNull}?.let {
            require(it is JsonPrimitive && it.isString)
            it.content.also {value -> require(value.length in 1..256 && value.isNotBlank() && value.none(Char::isISOControl))}
        }
        val confirmed = media == "CONNECTED"
        require((phase == VoiceRosterPhase.CONNECTED) == confirmed && (!confirmed || sid != null))
        return DirectCallRosterPeer(owner, session, phase, sid, confirmed) to number(root, "deadlineEpochMillis")
    }
    private fun primitive(root: JsonObject, key: String) = (root[key] as? JsonPrimitive)?.takeUnless {it == JsonNull} ?: invalid()
    private fun string(root: JsonObject, key: String) = primitive(root, key).also {require(it.isString)}.content
    private fun number(root: JsonObject, key: String) = primitive(root, key).let {require(!it.isString); requireNotNull(it.longOrNull)}
    private fun id(value: String) {require(ID.matches(value))}
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid direct call roster")
    private fun failed(reason: VoiceRosterFailure) = DirectCallRosterReply.Failed(reason)
    private companion object {
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val PRIVATE_FIELDS = setOf("operationId", "idempotencyKey", "credentials", "participantToken", "nodeEpoch", "channelId")
        const val MAX_LEASE_MILLIS = 30_000L
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val MAX_RESPONSE_BYTES = 64 * 1024L
    }
}
