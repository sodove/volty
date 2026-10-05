package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import ru.sodovaya.volty.domain.nearby.*

internal class ChannelSharingHttpPort(client: HttpClient, baseUrl: String,
    private val bearer: suspend () -> String?, private val monotonicMillis: () -> Long) {
    private val base = nearbyHttpsApiBaseUrl(baseUrl)
    private val http = client.config {followRedirects = false; expectSuccess = false}
    private val json = Json {ignoreUnknownKeys = true}
    private val retired = MutableStateFlow(false)
    private val requests = Mutex()
    private var ordinal = 0L

    suspend fun start(tag: ChannelSharingTag, mode: ChannelSharingMode, ttl: ChannelSharingTtl) =
        request(tag, "start", HttpMethod.Post, json.encodeToString(StartBody(mode.name, ttl.name)))
    suspend fun status(tag: ChannelSharingTag) = request(tag, "", HttpMethod.Get)
    suspend fun revoke(tag: ChannelSharingTag) = request(tag, "revoke", HttpMethod.Post)
    fun close() {if (retired.compareAndSet(false, true)) http.close()}

    private suspend fun request(tag: ChannelSharingTag, action: String, method: HttpMethod, body: String? = null): ChannelSharingReply = try {
        currentCoroutineContext().ensureActive()
        if (retired.value) failed(ChannelSharingFailure.RETIRED)
        else withTimeoutOrNull(10_000) {
            val token = bearer()?.takeIf {it.isNotBlank()} ?: return@withTimeoutOrNull failed(ChannelSharingFailure.UNAUTHORIZED)
            currentCoroutineContext().ensureActive()
            if (retired.value) return@withTimeoutOrNull failed(ChannelSharingFailure.RETIRED)
            val (id, started) = requests.withLock {
                check(ordinal < Long.MAX_VALUE)
                ordinal++ to monotonicMillis().also {require(it >= 0)}
            }
            val suffix = if (action.isEmpty()) "" else "/$action"
            http.prepareRequest("$base/sharing/${tag.sessionId}/${tag.generation}$suffix") {
                this.method = method; bearerAuth(token); header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                if (body != null) {contentType(ContentType.Application.Json); setBody(body)}
            }.execute {response ->
                currentCoroutineContext().ensureActive()
                if (retired.value) return@execute failed(ChannelSharingFailure.RETIRED)
                when (response.status) {
                    HttpStatusCode.Unauthorized -> return@execute failed(ChannelSharingFailure.UNAUTHORIZED)
                    HttpStatusCode.NotFound, HttpStatusCode.Forbidden -> return@execute failed(ChannelSharingFailure.ACCESS_DENIED)
                    HttpStatusCode.OK -> Unit
                    else -> return@execute failed(ChannelSharingFailure.UNAVAILABLE)
                }
                val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
                require(bytes.size <= MAX_RESPONSE_BYTES)
                val text = try {bytes.decodeToString(throwOnInvalidSequence = true)}
                    catch (_: Exception) {throw IllegalArgumentException("Invalid sharing UTF-8")}
                val root = json.parseToJsonElement(text)
                require(root is JsonObject)
                currentCoroutineContext().ensureActive()
                if (retired.value) return@execute failed(ChannelSharingFailure.RETIRED)
                decode(root, tag, action == "revoke", id, started, monotonicMillis())
            }
        } ?: failed(ChannelSharingFailure.UNAVAILABLE)
    } catch (cancelled: CancellationException) {throw cancelled}
      catch (_: IllegalArgumentException) {failed(ChannelSharingFailure.INVALID_RESPONSE)}
      catch (_: Exception) {failed(if (retired.value) ChannelSharingFailure.RETIRED else ChannelSharingFailure.UNAVAILABLE)}

    private fun decode(root: JsonObject, tag: ChannelSharingTag, negative: Boolean, id: Long, started: Long, received: Long): ChannelSharingReply {
        require(received >= started && started >= 0)
        require(root.keys.none {it in setOf("actorId", "deadlineEpochMillis", "credentials", "participantToken", "operationId", "latitude", "longitude")})
        require(root.string("sessionId") == tag.sessionId && root.string("generation") == tag.generation)
        val phase = root.string("phase")
        val credit = (root["remainingMillis"] as? JsonPrimitive)?.also {require(!it.isString)}?.longOrNull
            ?: throw IllegalArgumentException("Invalid sharing credit")
        if (negative) {
            require(credit == 0L && (root["mode"] == null || root["mode"] == JsonNull))
            return ChannelSharingReply.Negative(tag, ChannelSharingNegativePhase.valueOf(phase))
        }
        require(phase == "ACTIVE" && credit in 1..ChannelSharingTtl.EIGHT_HOURS.millis)
        val mode = ChannelSharingMode.valueOf(root.string("mode"))
        require(started <= Long.MAX_VALUE - credit)
        val deadline = started + credit
        if (deadline <= received) return failed(ChannelSharingFailure.EXPIRED)
        return ChannelSharingReply.Active(ChannelSharingObservation(tag, mode, id, started, received, deadline))
    }
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf {it.isString}?.content
        ?: throw IllegalArgumentException("Invalid sharing field")
    private fun failed(reason: ChannelSharingFailure) = ChannelSharingReply.Failed(reason)
    @Serializable private data class StartBody(val mode: String, val ttl: String)
    private companion object {const val MAX_RESPONSE_BYTES = 8192L}
}
