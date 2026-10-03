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

/** Secret stays in bounded body; caller owns entropy, stable creation draft and operation ID. */
class InvitationHttpPort(client: HttpClient, baseUrl: String, private val bearer: suspend () -> String?) : InvitationPort {
    private val base = baseUrl.trimEnd('/').also {
        val url = Url(it)
        require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.user.isNullOrEmpty() &&
            url.password.isNullOrEmpty() && url.fragment.isEmpty() && url.parameters.isEmpty())
    }
    private val http = client.config { followRedirects = false; expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun create(community: String, invitation: String, token: String, ttlMillis: Long, maxUses: Int): InvitationCreationCommandResult {
        id(community); id(invitation); token(token)
        require(ttlMillis in 900_000L..604_800_000L && maxUses in 1..1000)
        return when (val reply = request(HttpMethod.Put, "communities/$community/invitations/$invitation", json.encodeToString(CreateBody(token, ttlMillis, maxUses))) { root ->
            require(string(root, "communityId") == community && string(root, "invitationId") == invitation)
            val created = number(root, "createdAtMillis"); val expires = number(root, "expiresAtMillis")
            require(created >= 0 && expires > created && expires - created == ttlMillis)
            require(number(root, "maxUses") == maxUses.toLong())
            val uses = number(root, "uses").also { require(it in 0L..maxUses.toLong()) }.toInt()
            require(root["revokedAtMillis"] == JsonNull && root["revocationRevision"] == JsonNull)
            InvitationCreationReceipt(invitation, community, created, expires, maxUses, uses, positiveRevision(root, "creationRevision"))
        }) {
            is Reply.Success -> InvitationCreationCommandResult.Created(reply.value)
            is Reply.Failed -> InvitationCreationCommandResult.Failed(reply.reason)
        }
    }
    override suspend fun revoke(community: String, invitation: String): InvitationRevocationCommandResult {
        id(community); id(invitation)
        return when (val reply = request(HttpMethod.Post, "communities/$community/invitations/$invitation/revoke", null) { root ->
            require(string(root, "communityId") == community && string(root, "invitationId") == invitation)
            InvitationRevocationReceipt(invitation, community, positiveRevision(root, "revision"))
        }) {
            is Reply.Success -> InvitationRevocationCommandResult.Revoked(reply.value)
            is Reply.Failed -> InvitationRevocationCommandResult.Failed(reply.reason)
        }
    }
    override suspend fun accept(operation: String, token: String): InvitationAcceptanceCommandResult {
        id(operation); token(token)
        return when (val reply = request(HttpMethod.Post, "invitations/accept", json.encodeToString(AcceptBody(operation, token))) { root ->
            require(string(root, "operationId") == operation)
            val invitation = string(root, "invitationId").also(::id); val community = string(root, "communityId").also(::id)
            val revision = number(root, "revision").also { require(it >= 0) }
            InvitationAcceptanceReceipt(operation, invitation, community, revision, boolean(root, "membershipChanged"))
        }) {
            is Reply.Success -> InvitationAcceptanceCommandResult.Accepted(reply.value)
            is Reply.Failed -> InvitationAcceptanceCommandResult.Failed(reply.reason)
        }
    }
    fun close() = http.close()

    private suspend fun <T> request(method: HttpMethod, path: String, body: String?, decode: (JsonObject) -> T): Reply<T> = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
            val auth = bearer()?.takeIf { it.isNotBlank() } ?: return@withTimeoutOrNull Reply.Failed(InvitationCommandFailure.UNAUTHORIZED)
            http.prepareRequest("$base/$path") {
                this.method = method; bearerAuth(auth); header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }.execute { response ->
                if (response.status.value != 200 && response.status.value != 409) return@execute Reply.Failed(when (response.status.value) {
                    401 -> InvitationCommandFailure.UNAUTHORIZED
                    403, 404 -> InvitationCommandFailure.ACCESS_DENIED
                    else -> InvitationCommandFailure.UNAVAILABLE
                })
                val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
                require(bytes.size <= MAX_RESPONSE_BYTES)
                val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                catch (_: Exception) { return@execute Reply.Failed(InvitationCommandFailure.INVALID_RESPONSE) }
                val root = json.parseToJsonElement(text) as? JsonObject ?: invalidResponse()
                if (response.status.value == 409) return@execute Reply.Failed(InvitationCommandFailure.CONFLICT)
                Reply.Success(decode(root))
            }
        } ?: Reply.Failed(InvitationCommandFailure.UNAVAILABLE)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: IllegalArgumentException) { Reply.Failed(InvitationCommandFailure.INVALID_RESPONSE) }
      catch (_: Exception) { Reply.Failed(InvitationCommandFailure.UNAVAILABLE) }

    private fun primitive(root: JsonObject, key: String) = (root[key] as? JsonPrimitive)?.takeUnless { it == JsonNull } ?: invalidResponse()
    private fun string(root: JsonObject, key: String) = primitive(root, key).also { require(it.isString) }.content
    private fun number(root: JsonObject, key: String) = primitive(root, key).let { require(!it.isString); requireNotNull(it.longOrNull) }
    private fun boolean(root: JsonObject, key: String) = primitive(root, key).let { require(!it.isString); requireNotNull(it.booleanOrNull) }
    private fun positiveRevision(root: JsonObject, key: String) = number(root, key).also { require(it > 0) }
    private fun invalidResponse(): Nothing = throw IllegalArgumentException("Invalid invitation response")
    private fun id(value: String) { require(ID.matches(value)) }
    private fun token(value: String) {
        // 32 bytes encode to43 URL-safe characters. Last sextet has two unused zero bits.
        require(TOKEN.matches(value) && value.last() in "AEIMQUYcgkosw048")
    }
    private sealed interface Reply<out T> {
        data class Success<T>(val value: T) : Reply<T>
        data class Failed(val reason: InvitationCommandFailure) : Reply<Nothing>
    }
    @Serializable private data class CreateBody(val token: String, val ttlMillis: Long, val maxUses: Int)
    @Serializable private data class AcceptBody(val operationId: String, val token: String)
    private companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024L
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val TOKEN = Regex("[A-Za-z0-9_-]{43}")
    }
}
