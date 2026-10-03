package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import ru.sodovaya.volty.domain.nearby.community.*

class InvitationListingHttpPort(client: HttpClient, baseUrl: String, private val bearer: suspend () -> String?) : InvitationListingPort {
    private val base = baseUrl.trimEnd('/').also {
        require('?' !in it && '#' !in it)
        val url = Url(it)
        require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.user.isNullOrEmpty() &&
            url.password.isNullOrEmpty() && url.fragment.isEmpty() && url.parameters.isEmpty())
    }
    private val http = client.config { followRedirects = false; expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun list(community: String, after: String?, limit: Int): InvitationListingCommandResult {
        id(community); after?.let(::id); require(limit in 1..50)
        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
                val auth = bearer()?.takeIf { it.isNotBlank() } ?: return@withTimeoutOrNull failed(InvitationCommandFailure.UNAUTHORIZED)
                http.prepareGet("$base/communities/$community/invitations") {
                    bearerAuth(auth); header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    parameter("limit", limit); after?.let { parameter("after", it) }
                }.execute { response ->
                    if (response.status.value != 200) return@execute failed(when (response.status.value) {
                        401 -> InvitationCommandFailure.UNAUTHORIZED
                        403, 404 -> InvitationCommandFailure.ACCESS_DENIED
                        else -> InvitationCommandFailure.UNAVAILABLE
                    })
                    val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
                    require(bytes.size <= MAX_RESPONSE_BYTES)
                    val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                    catch (_: Exception) { return@execute failed(InvitationCommandFailure.INVALID_RESPONSE) }
                    val root = json.parseToJsonElement(text) as? JsonObject ?: invalid()
                    val items = root["items"] as? JsonArray ?: invalid()
                    require(items.size <= limit)
                    val metadata = items.map { item -> read(item as? JsonObject ?: invalid(), community) }
                    val ids = metadata.map { it.invitationId }
                    require(ids.distinct().size == ids.size && ids == ids.sorted() && (after == null || ids.all { it > after }))
                    InvitationListingCommandResult.Listed(metadata)
                }
            } ?: failed(InvitationCommandFailure.UNAVAILABLE)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: IllegalArgumentException) { failed(InvitationCommandFailure.INVALID_RESPONSE) }
          catch (_: Exception) { failed(InvitationCommandFailure.UNAVAILABLE) }
    }
    fun close() = http.close()
    private fun read(root: JsonObject, community: String): InvitationMetadata {
        val invitation = string(root, "invitationId").also(::id)
        require(string(root, "communityId") == community)
        val created = number(root, "createdAtMillis"); val expires = number(root, "expiresAtMillis")
        require(created >= 0 && expires > created && expires - created in 900_000L..604_800_000L)
        val maxUses = number(root, "maxUses").also { require(it in 1L..1000L) }.toInt()
        val uses = number(root, "uses").also { require(it in 0L..maxUses.toLong()) }.toInt()
        val revision = number(root, "creationRevision").also { require(it > 0) }
        val revoked = nullableNumber(root, "revokedAtMillis"); val revocation = nullableNumber(root, "revocationRevision")
        require((revoked == null) == (revocation == null))
        if (revoked != null) require(revoked >= 0 && checkNotNull(revocation) > revision)
        return InvitationMetadata(invitation, community, created, expires, maxUses, uses, revoked, revision, revocation)
    }
    private fun primitive(root: JsonObject, key: String) = (root[key] as? JsonPrimitive)?.takeUnless { it == JsonNull } ?: invalid()
    private fun string(root: JsonObject, key: String) = primitive(root, key).also { require(it.isString) }.content
    private fun number(root: JsonObject, key: String) = primitive(root, key).let { require(!it.isString); requireNotNull(it.longOrNull) }
    private fun nullableNumber(root: JsonObject, key: String) = if (root[key] == JsonNull) null else number(root, key)
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid invitation metadata")
    private fun id(value: String) { require(ID.matches(value)) }
    private fun failed(reason: InvitationCommandFailure) = InvitationListingCommandResult.Failed(reason)
    private companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024L
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
