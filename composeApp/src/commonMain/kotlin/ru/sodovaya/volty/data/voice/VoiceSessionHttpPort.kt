package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.sodovaya.volty.domain.voice.*

/**
 * Owned HTTP child, no DI/product cutover. Caller owns generation retirement and
 * effect scheduling. Clock must include sleep: Android elapsedRealtime, not wall
 * time. The injected bearer supplier owns secure auth/refresh, never UI code.
 */
class VoiceSessionHttpPort(
    client: HttpClient,
    baseUrl: String,
    private val bearer: suspend () -> String?,
    private val monotonicMillis: () -> Long,
) : VoiceSessionControlPort {
    private val base = baseUrl.trimEnd('/').also {
        val url = Url(it)
        require(url.protocol == URLProtocol.HTTPS && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty() && url.parameters.isEmpty())
    }
    private val http = client.config { followRedirects = false; expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMutex = Mutex()
    private var nextOrdinal = 0L

    override suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean): VoiceHttpReply {
        requireId(operation.value); requireId(channel)
        return request(HttpMethod.Post, "operations/${operation.value}/join", generation, operation,
            body = json.encodeToString(JoinBody(channel, replace)), allowCredentials = true)
    }
    override suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply {
        requireId(operation.value)
        return request(HttpMethod.Get, "operations/${operation.value}", generation, operation)
    }
    override suspend fun heartbeat(tag: VoiceSessionTag): VoiceHttpReply {
        requireId(tag.sessionId.value)
        return request(HttpMethod.Post, "sessions/${tag.sessionId.value}/heartbeat", tag.generation, expectedSession = tag.sessionId)
    }
    override suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply {
        requireId(operation.value)
        return request(HttpMethod.Post, "operations/${operation.value}/cancel", generation, operation, terminalOnly = true)
    }
    override suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply {
        requireId(tag.sessionId.value)
        return request(HttpMethod.Post, "sessions/${tag.sessionId.value}/disconnect", tag.generation, expectedSession = tag.sessionId, terminalOnly = true)
    }
    fun close() = http.close()

    private suspend fun request(method: HttpMethod, path: String, generation: VoiceGeneration,
                                operation: VoiceOperationId? = null, expectedSession: VoiceSessionId? = null,
                                body: String? = null, allowCredentials: Boolean = false, terminalOnly: Boolean = false): VoiceHttpReply {
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT) {
                val token = bearer()?.takeIf { it.isNotBlank() }
                    ?: return@withTimeoutOrNull VoiceHttpReply.Failed(VoiceHttpFailure.UNAUTHORIZED)
                val (ordinal, started) = requestMutex.withLock {
                    check(nextOrdinal < Long.MAX_VALUE)
                    VoiceLeaseRequestId(nextOrdinal++) to monotonicMillis().also { require(it >= 0) }
                }
                http.prepareRequest("$base/$path") {
                    this.method = method
                    bearerAuth(token)
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                }.execute { response ->
                    if (response.status == HttpStatusCode.Conflict) {
                        val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
                        if (bytes.size > MAX_RESPONSE_BYTES) return@execute VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE)
                        val code = try { json.decodeFromString<ErrorBody>(bytes.decodeToString(throwOnInvalidSequence = true)).code }
                        catch (_: IllegalArgumentException) { null }
                        return@execute VoiceHttpReply.Failed(if (code == "voice_busy") VoiceHttpFailure.BUSY else VoiceHttpFailure.CONFLICT)
                    }
                    if (response.status.value !in setOf(200, 202)) return@execute VoiceHttpReply.Failed(when (response.status.value) {
                        401 -> VoiceHttpFailure.UNAUTHORIZED
                        403 -> VoiceHttpFailure.ACCESS_DENIED
                        404 -> VoiceHttpFailure.ACCESS_DENIED
                        else -> VoiceHttpFailure.UNAVAILABLE
                    })
                    val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
                    if (bytes.size > MAX_RESPONSE_BYTES) return@execute VoiceHttpReply.Failed(VoiceHttpFailure.INVALID_RESPONSE)
                    val wire = json.decodeFromString<StateBody>(bytes.decodeToString(throwOnInvalidSequence = true))
                    val received = monotonicMillis()
                    decode(wire, generation, operation, expectedSession, allowCredentials, terminalOnly, ordinal, started, received)
                }
            } ?: VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            VoiceHttpReply.Failed(VoiceHttpFailure.INVALID_RESPONSE)
        } catch (_: Exception) {
            VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE)
        }
    }

    private fun decode(wire: StateBody, generation: VoiceGeneration, expectedOperation: VoiceOperationId?, expectedSession: VoiceSessionId?,
                       allowCredentials: Boolean, terminalOnly: Boolean, ordinal: VoiceLeaseRequestId, started: Long, received: Long): VoiceHttpReply {
        require(received >= started)
        wire.operationId?.let { requireId(it); require(expectedOperation == null || it == expectedOperation.value) }
        wire.sessionId?.let(::requireId)
        require(expectedSession == null || wire.sessionId == expectedSession.value)
        wire.deadlineEpochMillis?.let { require(it >= 0) } // Validated, never used as the phone's clock.
        val session = wire.sessionId?.let(::VoiceSessionId)
        if (wire.phase in setOf("CANCELLED", "EXPIRED")) {
            require(wire.credentials == null && wire.remainingLeaseMillis == null)
            return VoiceHttpReply.Ended(generation, session, wire.phase)
        }
        require(!terminalOnly)
        if (wire.phase == "PENDING") {
            require(wire.credentials == null && wire.remainingLeaseMillis == null)
            return VoiceHttpReply.Pending(generation, session, wire.operationId?.let(::VoiceOperationId))
        }
        require(wire.phase == "CONNECTING" || wire.phase == "CONNECTED")
        val tag = VoiceSessionTag(requireNotNull(session), generation)
        val lease = VoiceLeaseGrant(ordinal, started, received, requireNotNull(wire.remainingLeaseMillis))
        val credentials = wire.credentials
        if (allowCredentials) require(credentials != null)
        if (credentials != null) {
            require(allowCredentials && wire.phase == "CONNECTING")
            val url = Url(credentials.serverUrl)
            require(url.protocol == URLProtocol.WSS && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty())
            require(credentials.participantToken.length in 1..8192 && credentials.participantToken.isNotBlank())
            require(credentials.roomEpoch.isNotBlank() && credentials.roomEpoch.length <= 128)
        }
        if (lease.expiredOnReceipt) return VoiceHttpReply.Expired(tag)
        return if (credentials != null) VoiceHttpReply.Credentials(tag, VoiceConnectionCredentials(credentials.serverUrl, credentials.participantToken), lease)
        else VoiceHttpReply.Acknowledged(tag, lease, wire.phase == "CONNECTED")
    }

    private fun requireId(value: String) { require(ID.matches(value)) }
    @Serializable private data class JoinBody(val destinationId: String, val replace: Boolean)
    @Serializable private data class ErrorBody(val code: String? = null)
    @Serializable private data class StateBody(
        val phase: String, val sessionId: String? = null, val operationId: String? = null,
        val deadlineEpochMillis: Long? = null, val remainingLeaseMillis: Long? = null, val credentials: CredentialBody? = null,
    )
    @Serializable private data class CredentialBody(val serverUrl: String, val participantToken: String, val roomEpoch: String) {
        override fun toString() = "CredentialBody(redacted)"
    }
    private companion object {
        const val REQUEST_TIMEOUT = 10_000L
        const val MAX_RESPONSE_BYTES = 16 * 1024L
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
