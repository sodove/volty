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

/** Account-bound caller supplies auth; no automatic retry, UI or production DI here. */
class ChannelCommandsHttpPort(client: HttpClient, baseUrl: String, private val bearer: suspend () -> String?) : ChannelCommandsPort {
    private val base = baseUrl.trimEnd('/').also {
        val url = Url(it)
        require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.user.isNullOrEmpty() &&
            url.password.isNullOrEmpty() && url.fragment.isEmpty() && url.parameters.isEmpty())
    }
    private val http = client.config { followRedirects = false; expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun create(community: String, channel: String, name: String): ChannelCreationCommandResult {
        id(community); id(channel)
        val title = name.trim(); require(title.length in 1..80 && title.none(Char::isISOControl))
        return when (val reply = request(HttpMethod.Put, "$community/channels/$channel", json.encodeToString(CreateBody(title))) { root ->
            require(string(root, "communityId") == community)
            val item = root["channel"] as? JsonObject ?: errorInvalid()
            require(string(item, "id") == channel && string(item, "name") == title && !boolean(item, "isDefault"))
            val revision = revision(root).also { require(it > 0) }
            ChannelCreationReceipt(community, channel, title, revision)
        }) {
            is Reply.Success -> ChannelCreationCommandResult.Created(reply.value)
            is Reply.Failed -> ChannelCreationCommandResult.Failed(reply.reason)
        }
    }
    override suspend fun delete(community: String, channel: String, operation: String): ChannelDeletionCommandResult {
        id(community); id(channel); id(operation)
        return when (val reply = request(HttpMethod.Post, "$community/channels/$channel/delete", json.encodeToString(DeleteBody(operation))) { root ->
            require(string(root, "communityId") == community && string(root, "channelId") == channel && string(root, "operationId") == operation)
            ChannelDeletionReceipt(operation, community, channel, revision(root), boolean(root, "cleanupPending"))
        }) {
            is Reply.Success -> ChannelDeletionCommandResult.Deleted(reply.value)
            is Reply.Failed -> ChannelDeletionCommandResult.Failed(reply.reason)
        }
    }
    fun close() = http.close()

    private suspend fun <T> request(method: HttpMethod, path: String, body: String, decode: (JsonObject) -> T): Reply<T> = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
            val token = bearer()?.takeIf { it.isNotBlank() } ?: return@withTimeoutOrNull Reply.Failed(ChannelCommandFailure.UNAUTHORIZED)
            http.prepareRequest("$base/$path") {
                this.method = method; bearerAuth(token); header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                contentType(ContentType.Application.Json); setBody(body)
            }.execute { response ->
                if (response.status.value != 200 && response.status.value != 409) return@execute Reply.Failed(when (response.status.value) {
                    401 -> ChannelCommandFailure.UNAUTHORIZED
                    403, 404 -> ChannelCommandFailure.ACCESS_DENIED
                    else -> ChannelCommandFailure.UNAVAILABLE
                })
                val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
                require(bytes.size <= MAX_RESPONSE_BYTES)
                val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                catch (_: Exception) { return@execute Reply.Failed(ChannelCommandFailure.INVALID_RESPONSE) }
                val root = json.parseToJsonElement(text) as? JsonObject ?: errorInvalid()
                if (response.status.value == 409) {
                    val code = root["code"] as? JsonPrimitive
                    return@execute Reply.Failed(if (code?.takeIf { it.isString }?.content == "channel_limit_reached")
                        ChannelCommandFailure.LIMIT_REACHED else ChannelCommandFailure.CONFLICT)
                }
                Reply.Success(decode(root))
            }
        } ?: Reply.Failed(ChannelCommandFailure.UNAVAILABLE)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: IllegalArgumentException) { Reply.Failed(ChannelCommandFailure.INVALID_RESPONSE) }
      catch (_: Exception) { Reply.Failed(ChannelCommandFailure.UNAVAILABLE) }

    private fun primitive(root: JsonObject, key: String): JsonPrimitive = (root[key] as? JsonPrimitive)?.takeUnless { it == JsonNull } ?: errorInvalid()
    private fun string(root: JsonObject, key: String) = primitive(root, key).also { require(it.isString) }.content
    private fun boolean(root: JsonObject, key: String) = primitive(root, key).let { require(!it.isString); requireNotNull(it.booleanOrNull) }
    private fun revision(root: JsonObject) = primitive(root, "revision").let { require(!it.isString); requireNotNull(it.longOrNull).also { require(it >= 0) } }
    private fun errorInvalid(): Nothing = throw IllegalArgumentException("Invalid channel response")
    private fun id(value: String) { require(ID.matches(value)) }
    private sealed interface Reply<out T> {
        data class Success<T>(val value: T) : Reply<T>
        data class Failed(val reason: ChannelCommandFailure) : Reply<Nothing>
    }
    @Serializable private data class CreateBody(val name: String)
    @Serializable private data class DeleteBody(val operationId: String)
    private companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024L
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
