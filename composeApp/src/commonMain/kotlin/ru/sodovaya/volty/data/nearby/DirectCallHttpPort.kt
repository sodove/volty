package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import ru.sodovaya.volty.domain.voice.*
import ru.sodovaya.volty.data.voice.VoiceLeaseRequestSequence

/** Owned bounded HTTP child; caller owns account retirement and original call intent. */
class DirectCallHttpPort(private val actor: String, client: HttpClient, baseUrl: String,
    private val bearer: suspend () -> String?, private val monotonicMillis: () -> Long,
    leaseRequests: VoiceLeaseRequestSequence? = null) : DirectCallControlPort {
    private val base = nearbyHttpsApiBaseUrl(baseUrl)
    init {directCallId(actor)}
    private val http = client.config {followRedirects = false; expectSuccess = false}
    private val json = Json {ignoreUnknownKeys = true}
    private val decoder = DirectCallResponseDecoder(actor)
    private val requests = leaseRequests ?: VoiceLeaseRequestSequence()

    override suspend fun create(call: String, callee: String, key: String, operation: VoiceOperationId) =
        outgoing(call, callee, key, operation, "create")
    override suspend fun cancelOutgoing(call: String, callee: String, key: String, operation: VoiceOperationId) =
        outgoing(call, callee, key, operation, "cancel-outgoing")
    private suspend fun outgoing(call: String, callee: String, key: String, operation: VoiceOperationId, action: String): DirectCallReply {
        directCallId(callee); directCallId(key); directCallId(operation.value); require(actor != callee)
        return action(call, action, callee = callee, terminalOnly = action == "cancel-outgoing",
            body = json.encodeToString(CreateBody(callee, key, operation.value)))
    }
    override suspend fun status(call: String) = action(call, "", method = HttpMethod.Get)
    override suspend fun decline(call: String) = action(call, "decline", terminalOnly = true)
    override suspend fun stop(call: String) = action(call, "stop", terminalOnly = true)
    override suspend fun accept(call: String, operation: VoiceOperationId, generation: VoiceGeneration, revision: Long): DirectCallReply {
        directCallId(operation.value); require(revision >= 0)
        return action(call, "accept", accepting = true, generation = generation, operation = operation,
            body = json.encodeToString(AcceptBody(operation.value, revision)))
    }
    override suspend fun resume(call: String, operation: VoiceOperationId, generation: VoiceGeneration): DirectCallReply {
        directCallId(operation.value)
        return action(call, "resume", generation = generation, operation = operation, body = json.encodeToString(ResumeBody(operation.value)))
    }
    override suspend fun incoming(after: String?, limit: Int): IncomingCallReply {
        after?.let(::directCallId); require(limit in 1..50)
        return request(HttpMethod.Get, "incoming", query = buildMap {put("limit", limit.toString()); after?.let {put("after", it)}},
            failure = IncomingCallReply::Failed) {root, status, _, _, _ ->
            require(status == HttpStatusCode.OK); decoder.incoming(root, after, limit)
        }
    }
    fun close() = http.close()

    private suspend fun action(call: String, action: String, method: HttpMethod = HttpMethod.Post, body: String? = null,
        callee: String? = null, accepting: Boolean = false, terminalOnly: Boolean = false,
        generation: VoiceGeneration? = null, operation: VoiceOperationId? = null): DirectCallReply {
        directCallId(call)
        val path = call + if (action.isEmpty()) "" else "/$action"
        return request(method, path, body, failure = DirectCallReply::Failed) {root, status, ordinal, started, received ->
            decoder.action(root, call, callee, accepting, generation != null, terminalOnly, generation, operation, status, ordinal, started, received)
        }
    }

    private suspend fun <T> request(method: HttpMethod, path: String, body: String? = null,
        query: Map<String, String> = emptyMap(), failure: (DirectCallFailure) -> T,
        decode: (JsonObject, HttpStatusCode, VoiceLeaseRequestId, Long, Long) -> T): T = try {
        withTimeoutOrNull(10_000) {
            val token = bearer()?.takeIf {it.isNotBlank()} ?: return@withTimeoutOrNull failure(DirectCallFailure.UNAUTHORIZED)
            val (requestId, started) = requests.reserve(monotonicMillis)
            http.prepareRequest("$base/calls/$path") {
                this.method = method; bearerAuth(token); header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                url {query.forEach {(key, value) -> parameters.append(key, value)}}
                if (body != null) {contentType(ContentType.Application.Json); setBody(body)}
            }.execute {response ->
                if (response.status == HttpStatusCode.Unauthorized) return@execute failure(DirectCallFailure.UNAUTHORIZED)
                if (response.status == HttpStatusCode.NotFound) return@execute failure(DirectCallFailure.ACCESS_DENIED)
                if (response.status.value !in setOf(200, 202, 403, 409, 503)) return@execute failure(DirectCallFailure.UNAVAILABLE)
                val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
                require(bytes.size <= MAX_RESPONSE_BYTES)
                val root = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
                require(root is JsonObject)
                if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.Conflict) {
                    val code = (root["code"] as? JsonPrimitive)?.takeIf {it.isString}?.content
                    return@execute failure(when {
                        response.status == HttpStatusCode.Forbidden && code == "friendship_required" -> DirectCallFailure.FRIENDSHIP_REQUIRED
                        response.status == HttpStatusCode.Forbidden -> DirectCallFailure.ACCESS_DENIED
                        code == "voice_busy" -> DirectCallFailure.BUSY
                        else -> DirectCallFailure.CONFLICT
                    })
                }
                if (response.status == HttpStatusCode.ServiceUnavailable && root["call"] == null)
                    return@execute failure(DirectCallFailure.UNAVAILABLE)
                decode(root, response.status, requestId, started, monotonicMillis())
            }
        } ?: failure(DirectCallFailure.UNAVAILABLE)
    } catch (cancelled: CancellationException) {throw cancelled}
      catch (_: IllegalArgumentException) {failure(DirectCallFailure.INVALID_RESPONSE)}
      catch (_: Exception) {failure(DirectCallFailure.UNAVAILABLE)}

    @Serializable private data class CreateBody(val calleeId: String, val idempotencyKey: String, val operationId: String)
    @Serializable private data class AcceptBody(val operationId: String, val expectedRevision: Long)
    @Serializable private data class ResumeBody(val operationId: String)
    private companion object {const val MAX_RESPONSE_BYTES = 64 * 1024L}
}
