package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount

internal class PushRegistrationIntent(val installationId: String, val installationKey: String,
    val generation: Long, val bindingId: String, val token: String?) {
    override fun toString() = "PushRegistrationIntent(<redacted>)"
}
internal enum class PushRegistrationResult { APPLIED, STALE, TOKEN_CONFLICT, ACCESS_DENIED, INVALID, UNAVAILABLE, INVALID_RESPONSE }
/** Borrows repository authentication; owns only its HTTP child and response publication fence. */
internal class AccountPushRegistration(private val account: SocialAuthenticatedAccount, client: HttpClient, baseUrl: String) {
    private val base = nearbyHttpsApiBaseUrl(baseUrl)
    private val http = client.config {followRedirects = false; expectSuccess = false}
    private val retired = MutableStateFlow(false)
    suspend fun submit(intent: PushRegistrationIntent): PushRegistrationResult {
        ensureLive()
        if (!validPushRegistrationIntent(intent)) return PushRegistrationResult.INVALID
        val body = buildJsonObject {
            put("installationId", intent.installationId); put("installationKey", intent.installationKey)
            put("generation", intent.generation); put("bindingId", intent.bindingId)
            put("token", intent.token?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
        if (body.encodeToByteArray().size > 8192) return PushRegistrationResult.INVALID
        val result = try {
            withTimeoutOrNull(10_000) {
                val token = account.bearer(); ensureLive()
                if (token.isNullOrBlank()) return@withTimeoutOrNull PushRegistrationResult.ACCESS_DENIED
                http.prepareRequest("$base/push/device") {
                    method = HttpMethod.Put; bearerAuth(token)
                    contentType(ContentType.Application.Json); accept(ContentType.Application.Json); setBody(body)
                }.execute {response ->
                    when (response.status.value) {
                        204 -> PushRegistrationResult.APPLIED
                        401, 403 -> PushRegistrationResult.ACCESS_DENIED
                        400 -> PushRegistrationResult.INVALID
                        429, in 500..599 -> PushRegistrationResult.UNAVAILABLE
                        409 -> {
                            val bytes = response.bodyAsChannel().readRemaining(8193).readByteArray()
                            require(bytes.size <= 8192)
                            val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                            val code = (root?.get("code") as? JsonPrimitive)?.takeIf {it.isString}?.content
                            when (code) {
                                "push_binding_stale" -> PushRegistrationResult.STALE
                                "push_token_conflict" -> PushRegistrationResult.TOKEN_CONFLICT
                                else -> PushRegistrationResult.INVALID_RESPONSE
                            }
                        }
                        else -> PushRegistrationResult.INVALID_RESPONSE
                    }
                }
            } ?: PushRegistrationResult.UNAVAILABLE
        } catch (cancelled: CancellationException) {throw cancelled}
          catch (_: IllegalArgumentException) {PushRegistrationResult.INVALID_RESPONSE}
          catch (_: Exception) {PushRegistrationResult.UNAVAILABLE}
        ensureLive()
        return result
    }
    fun retire() {retired.value = true; http.close()}
    private suspend fun ensureLive() {
        currentCoroutineContext().ensureActive()
        if (retired.value || !account.job.isActive) throw CancellationException("Push account retired")
    }
}
internal fun validPushRegistrationIntent(r: PushRegistrationIntent) = r.generation > 0 &&
    PUSH_UUID.matches(r.installationId) && PUSH_UUID.matches(r.bindingId) && PUSH_KEY.matches(r.installationKey) &&
    (r.token == null || (r.token.length in 1..4096 && r.token.all {it.code in 33..126}))
private val PUSH_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val PUSH_KEY = Regex("[0-9a-f]{64}")
