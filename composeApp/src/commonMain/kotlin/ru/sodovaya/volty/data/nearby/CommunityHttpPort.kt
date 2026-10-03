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

/** Bounded authenticated client boundary; no DI/product or screen-owner wiring. */
class CommunityHttpPort(client: HttpClient, baseUrl: String, private val bearer: suspend () -> String?) : CommunityPort {
    private val base = baseUrl.trimEnd('/').also {
        require('?' !in it && '#' !in it)
        val url = Url(it)
        require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() && url.user.isNullOrEmpty() &&
            url.password.isNullOrEmpty() && url.fragment.isEmpty() && url.parameters.isEmpty())
    }
    private val http = client.config { followRedirects = false; expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun memberships(after: String?, limit: Int): CommunityResult<List<CommunitySummary>> {
        pageInput(after, limit)
        return request(HttpMethod.Get, "", pageQuery(after, limit)) { page(it, after, limit, publicOnly = false) }
    }
    override suspend fun catalog(query: String, after: String?, limit: Int): CommunityResult<List<CommunitySummary>> {
        pageInput(after, limit); require(query.length <= 80)
        return request(HttpMethod.Get, "/catalog", pageQuery(after, limit) + ("q" to query)) { page(it, after, limit, publicOnly = true) }
    }
    override suspend fun detail(community: String): CommunityResult<CommunityDetails> {
        id(community)
        return request(HttpMethod.Get, "/$community") { details(it, community) }
    }
    override suspend fun create(community: String, name: String, description: String, visibility: CommunityVisibility): CommunityResult<CommunityDetails> {
        id(community); title(name); require(description.length <= 1000 && '\u0000' !in description)
        return request(HttpMethod.Put, "/$community", body = json.encodeToString(CreateBody(name, description, visibility.name))) { details(it, community) }
    }
    override suspend fun joinPublic(community: String): CommunityResult<CommunityDetails> {
        id(community)
        return request(HttpMethod.Post, "/$community/join") { details(it, community) }
    }
    fun close() = http.close()

    private suspend fun <T> request(method: HttpMethod, path: String, query: Map<String, String> = emptyMap(),
                                    body: String? = null, decode: (JsonElement) -> T): CommunityResult<T> = try {
        withTimeoutOrNull(10_000) {
            val token = bearer()?.takeIf { it.isNotBlank() }
                ?: return@withTimeoutOrNull CommunityResult.Failed(CommunityFailure.UNAUTHORIZED)
            http.prepareRequest("$base$path") {
                this.method = method
                bearerAuth(token); header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                url { query.forEach { (key, value) -> parameters.append(key, value) } }
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }.execute { response ->
                if (response.status.value !in setOf(200, 201)) return@execute CommunityResult.Failed(when (response.status.value) {
                    401 -> CommunityFailure.UNAUTHORIZED
                    403, 404 -> CommunityFailure.ACCESS_DENIED
                    409 -> CommunityFailure.CONFLICT
                    else -> CommunityFailure.UNAVAILABLE
                })
                val bytes = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1).readByteArray()
                require(bytes.size <= MAX_RESPONSE_BYTES)
                val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                catch (_: Exception) { return@execute CommunityResult.Failed(CommunityFailure.INVALID_RESPONSE) }
                CommunityResult.Ready(decode(json.parseToJsonElement(text)))
            }
        } ?: CommunityResult.Failed(CommunityFailure.UNAVAILABLE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        CommunityResult.Failed(CommunityFailure.INVALID_RESPONSE)
    } catch (_: Exception) {
        CommunityResult.Failed(CommunityFailure.UNAVAILABLE)
    }

    private fun page(document: JsonElement, after: String?, limit: Int, publicOnly: Boolean): List<CommunitySummary> {
        val items = field(obj(document), "items")
        require(items is JsonArray && items.size <= limit)
        val summaries = items.map { summary(obj(it)) }
        require(summaries.zipWithNext().all { (a, b) -> a.id < b.id })
        require(after == null || summaries.all { it.id > after })
        require(!publicOnly || summaries.all { it.visibility == CommunityVisibility.PUBLIC })
        return summaries
    }
    private fun details(document: JsonElement, expected: String): CommunityDetails {
        val root = obj(document)
        val community = summary(obj(field(root, "community")))
        require(community.id == expected)
        val revision = long(root, "revision").also { require(it >= 0) }
        val owner = string(root, "ownerId").also(::id)
        val wireChannels = field(root, "channels")
        require(wireChannels is JsonArray && wireChannels.size <= 128)
        val channels = wireChannels.map { element ->
            val channel = obj(element)
            val channelId = string(channel, "id").also(::id)
            val name = string(channel, "name").also(::title)
            val default = primitive(channel, "isDefault")
            require(!default.isString)
            CommunityChannel(channelId, name, requireNotNull(default.booleanOrNull))
        }
        require(channels.map { it.id }.distinct().size == channels.size && channels.count { it.isDefault } <= 1)
        return CommunityDetails(revision, community, owner, channels)
    }
    private fun summary(root: JsonObject): CommunitySummary {
        val communityId = string(root, "id").also(::id)
        val name = string(root, "name").also(::title)
        val description = string(root, "description").also { require(it.length <= 1000 && '\u0000' !in it) }
        val visibility = CommunityVisibility.valueOf(string(root, "visibility"))
        val created = long(root, "createdAtEpochMillis").also { require(it >= 0) }
        return CommunitySummary(communityId, name, description, visibility, created)
    }
    private fun obj(value: JsonElement): JsonObject { require(value is JsonObject); return value }
    private fun field(root: JsonObject, key: String) = requireNotNull(root[key])
    private fun primitive(root: JsonObject, key: String): JsonPrimitive {
        val value = field(root, key); require(value is JsonPrimitive && value != JsonNull); return value
    }
    private fun string(root: JsonObject, key: String) = primitive(root, key).also { require(it.isString) }.content
    private fun long(root: JsonObject, key: String) = primitive(root, key).let { require(!it.isString); requireNotNull(it.longOrNull) }
    private fun title(value: String) { require(value.isNotBlank() && value.length <= 80 && value.none(Char::isISOControl)) }
    private fun id(value: String) { require(ID.matches(value)) }
    private fun pageInput(after: String?, limit: Int) { after?.let(::id); require(limit in 1..50) }
    private fun pageQuery(after: String?, limit: Int) = buildMap { put("limit", limit.toString()); after?.let { put("after", it) } }
    @Serializable private data class CreateBody(val name: String, val description: String, val visibility: String)
    private companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024L
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
