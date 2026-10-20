package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.domain.radio.RadioKey

/** Fetches this account's radio key for one community. Any failure is `null`; callers keep their cache. */
class RadioKeyHttpPort(client: HttpClient, baseUrl: String, private val actor: String,
    private val bearer: suspend () -> String?) {
    private val base = nearbyHttpsApiBaseUrl(baseUrl)
    init { invitationDraftId(actor) }
    private val http = client.config { followRedirects = false; expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(community: String): RadioKey? {
        invitationDraftId(community)
        return try {
            withTimeoutOrNull(10_000) {
                val auth = bearer()?.takeIf { it.isNotBlank() } ?: return@withTimeoutOrNull null
                http.prepareGet("$base/communities/$community/radio-key") {
                    bearerAuth(auth); header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }.execute { response ->
                    if (response.status.value != 200) return@execute null
                    val bytes = response.bodyAsChannel().readRemaining(1024L + 1).readByteArray()
                    if (bytes.size > 1024) return@execute null
                    val root = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                        ?: return@execute null
                    if (string(root, "communityId") != community) return@execute null
                    val epochPrimitive = root["epoch"] as? JsonPrimitive ?: return@execute null
                    if (epochPrimitive.isString) return@execute null
                    val epoch = epochPrimitive.longOrNull?.takeIf { it >= 0 } ?: return@execute null
                    val key = Base64.Default.decode(string(root, "key"))
                    if (key.size != 32) return@execute null
                    RadioKey(community, epoch, key)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    fun close() = http.close()

    private fun string(root: JsonObject, key: String): String {
        val p = root[key] as? JsonPrimitive ?: throw IllegalArgumentException(key)
        require(p.isString)
        return p.content
    }
}
