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
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount

/** Optional display metadata, never call or microphone authority. */
internal interface IncomingCallCallerNames {
    fun belongsTo(account: SocialAuthenticatedAccount): Boolean
    suspend fun lookup(caller: String): String?
    fun close()
}

internal class IncomingCallCallerNameLookup(private val account: SocialAuthenticatedAccount,
    client: HttpClient, baseUrl: String): IncomingCallCallerNames {
    private val root = nearbyHttpsApiBaseUrl(baseUrl)
    private val http = client.config {followRedirects=false;expectSuccess=false}
    private var closed = false
    override fun belongsTo(account: SocialAuthenticatedAccount) = this.account === account
    override suspend fun lookup(caller: String): String? {
        if (!live() || caller==account.userId.value || runCatching {invitationDraftId(caller)}.isFailure) return null
        return try {
            withTimeoutOrNull(2_000L) {
                currentCoroutineContext().ensureActive()
                val token=account.bearer()?.takeIf {it.isNotBlank()} ?: return@withTimeoutOrNull null
                if (!live()) return@withTimeoutOrNull null
                val name=http.prepareGet("$root/friends") {
                    bearerAuth(token);header(HttpHeaders.Accept,ContentType.Application.Json.toString())
                }.execute {response ->
                    if (response.status.value!=200 || !live()) return@execute null
                    // Optional lookup: large friend lists retain the generic title instead of unbounded reads.
                    val bytes=response.bodyAsChannel().readRemaining(64*1024L+1).readByteArray()
                    require(bytes.size<=64*1024)
                    currentCoroutineContext().ensureActive()
                    if (!live()) return@execute null
                    val rows=Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence=true)) as? JsonArray
                        ?: return@execute null
                    val matches=rows.map {it as? JsonObject ?: error("Invalid friend metadata")}
                        .filter {string(it,"userId")==caller}
                    val row=matches.singleOrNull() ?: return@execute null
                    if (string(row,"state")!="ACCEPTED") return@execute null
                    incomingCallDisplayName(string(row,"displayName"))
                }
                currentCoroutineContext().ensureActive()
                name.takeIf {live()}
            }
        } catch(cancelled:CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch(_:Exception) {null}
    }
    private fun live()=!closed && account.job.isActive
    private fun string(row:JsonObject,key:String):String = (row[key] as? JsonPrimitive)
        ?.takeIf {it.isString}?.content ?: error("Invalid friend metadata")
    override fun close() {if (!closed) {closed=true;http.close()}}
}

internal fun incomingCallDisplayName(value:String?):String? = value?.takeIf {
    it.length in 2..80 && it.isNotBlank() && it.none(Char::isISOControl)
}
