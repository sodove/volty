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
import ru.sodovaya.volty.domain.nearby.community.*

/** Explicit stable-operation removal; no retries, UI or production DI here. */
class CommunityRemovalHttpPort(client: HttpClient, baseUrl: String, private val bearer: suspend () -> String?) : CommunityRemovalPort {
    private val base = baseUrl.trimEnd('/').also {
        require('?' !in it && '#' !in it)
        val url = Url(it)
        require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.user.isNullOrEmpty() &&
            url.password.isNullOrEmpty() && url.fragment.isEmpty() && url.parameters.isEmpty())
    }
    private val http = client.config { followRedirects = false; expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun leave(community: String, operation: String, successor: String?): CommunityRemovalResult {
        id(community); id(operation); successor?.let(::id)
        return request(community, operation, "leave", json.encodeToString(LeaveBody(operation, successor)))
    }
    override suspend fun delete(community: String, operation: String): CommunityRemovalResult {
        id(community); id(operation)
        return request(community, operation, "delete", json.encodeToString(DeleteBody(operation)))
    }
    fun close() = http.close()

    private suspend fun request(community: String, operation: String, action: String, body: String): CommunityRemovalResult = try {
        withTimeoutOrNull(10_000) {
            val token = bearer()?.takeIf { it.isNotBlank() }
                ?: return@withTimeoutOrNull CommunityRemovalResult.Failed(CommunityRemovalFailure.UNAUTHORIZED)
            http.preparePost("$base/$community/$action") {
                bearerAuth(token); header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                contentType(ContentType.Application.Json); setBody(body)
            }.execute { response ->
                if (response.status.value != 200 && response.status.value != 409) return@execute CommunityRemovalResult.Failed(when (response.status.value) {
                    401 -> CommunityRemovalFailure.UNAUTHORIZED
                    403, 404 -> CommunityRemovalFailure.ACCESS_DENIED
                    else -> CommunityRemovalFailure.UNAVAILABLE
                })
                val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
                require(bytes.size <= MAX_RESPONSE_BYTES)
                val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                catch (_: Exception) { return@execute CommunityRemovalResult.Failed(CommunityRemovalFailure.INVALID_RESPONSE) }
                val root = json.parseToJsonElement(text)
                require(root is JsonObject)
                if (response.status.value == 409) {
                    val code = root["code"] as? JsonPrimitive
                    return@execute CommunityRemovalResult.Failed(when (code?.takeIf { it.isString }?.content) {
                        "successor_required" -> CommunityRemovalFailure.SUCCESSOR_REQUIRED
                        "delete_required" -> CommunityRemovalFailure.DELETE_REQUIRED
                        "invalid_successor" -> CommunityRemovalFailure.INVALID_SUCCESSOR
                        else -> CommunityRemovalFailure.CONFLICT
                    })
                }
                require(string(root, "communityId") == community && string(root, "operationId") == operation)
                val revision = primitive(root, "revision").let { require(!it.isString); requireNotNull(it.longOrNull).also { require(it >= 0) } }
                val pending = primitive(root, "cleanupPending").let { require(!it.isString); requireNotNull(it.booleanOrNull) }
                CommunityRemovalResult.Removed(CommunityRemovalReceipt(operation, community, revision, pending))
            }
        } ?: CommunityRemovalResult.Failed(CommunityRemovalFailure.UNAVAILABLE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        CommunityRemovalResult.Failed(CommunityRemovalFailure.INVALID_RESPONSE)
    } catch (_: Exception) {
        CommunityRemovalResult.Failed(CommunityRemovalFailure.UNAVAILABLE)
    }
    private fun primitive(root: JsonObject, name: String): JsonPrimitive {
        val value = root[name]; require(value is JsonPrimitive && value != JsonNull); return value
    }
    private fun string(root: JsonObject, name: String) = primitive(root, name).also { require(it.isString) }.content
    private fun id(value: String) { require(ID.matches(value)) }
    @Serializable private data class LeaveBody(val operationId: String, val successorId: String? = null)
    @Serializable private data class DeleteBody(val operationId: String)
    private companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024L
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
