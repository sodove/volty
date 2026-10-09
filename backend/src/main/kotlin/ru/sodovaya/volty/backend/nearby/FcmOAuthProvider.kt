package ru.sodovaya.volty.backend.nearby

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal sealed interface FcmAuthorization {
    class Ready(val bearer: FcmBearer) : FcmAuthorization { override fun toString() = "FcmAuthorization.Ready(<redacted>)" }
    data class Retry(val reason: FcmRetryReason, val afterMillis: Long = 1000) : FcmAuthorization
}
/** One application-owned serial caller; borrowed HTTP client, no SDK network or background refresh. */
internal class FcmOAuthProvider private constructor(private val account: FcmServiceAccount, private val endpoint: URI,
    private val http: HttpClient, private val clock: Clock, private val nanoTime: () -> Long, private val timeout: Duration) {
    private var cached: FcmBearer? = null
    private var cachedAt = 0L
    private var cachedFor = 0L
    fun invalidate(bearer: FcmBearer) { if (cached === bearer) cached = null }
    init {
        require(http.followRedirects() == HttpClient.Redirect.NEVER) { "OAuth redirects must be disabled" }
        require(timeout in Duration.ofMillis(100)..Duration.ofSeconds(5)) { "Bounded OAuth timeout required" }
    }
    fun authorize(): FcmAuthorization {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("OAuth interrupted")
        val started = nanoTime()
        val elapsed = started - cachedAt
        cached?.let { if (elapsed >= 0 && elapsed < cachedFor) return FcmAuthorization.Ready(it) }
        cached = null
        val receiver = LimitedHttpBody(16384)
        val receivedHeaders = AtomicReference<HttpResponse.ResponseInfo>()
        return try {
            val assertion = assertion()
            val body = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) +
                "&assertion=" + URLEncoder.encode(assertion, Charsets.UTF_8)
            val request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val pending = http.sendAsync(request) { receivedHeaders.set(it); receiver }
            try {
                val response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                val status = response.statusCode()
                if (status == 429) return FcmAuthorization.Retry(FcmRetryReason.THROTTLED, fcmRetryDelay(response.headers(), clock, 60000))
                if (status in 500..599) return FcmAuthorization.Retry(FcmRetryReason.UNAVAILABLE, fcmRetryDelay(response.headers(), clock, 1000))
                if (status != 200) return FcmAuthorization.Retry(FcmRetryReason.CONFIGURATION, 60000)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString()
                val value = Json.parseToJsonElement(text).jsonObject
                val token = (value["access_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val type = (value["token_type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val lifetime = (value["expires_in"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                if (token == null || token.length !in 1..8192 || token.any { it.code !in 33..126 } ||
                    !type.equals("Bearer", ignoreCase = true) || lifetime == null || lifetime !in 61..3600) return invalid()
                val bearer = FcmBearer(token)
                // Count network time conservatively; monotonic clock prevents a wall-clock rollback extending credit.
                val credit = TimeUnit.SECONDS.toNanos(lifetime - 60)
                val spent = nanoTime() - started
                if (spent < 0 || spent >= credit) return invalid()
                cachedAt = started; cachedFor = credit; cached = bearer
                FcmAuthorization.Ready(bearer)
            } finally {
                if (!pending.isDone) { receiver.abort(); pending.cancel(true) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (_: TimeoutException) { FcmAuthorization.Retry(FcmRetryReason.TRANSPORT) }
        catch (failure: ExecutionException) {
            if (failure.cause is OversizedHttpBody) {
                val headers = receivedHeaders.get()
                when (headers?.statusCode()) {
                    429 -> FcmAuthorization.Retry(FcmRetryReason.THROTTLED, fcmRetryDelay(headers.headers(), clock, 60000))
                    in 500..599 -> FcmAuthorization.Retry(FcmRetryReason.UNAVAILABLE, fcmRetryDelay(checkNotNull(headers).headers(), clock, 1000))
                    else -> invalid()
                }
            } else FcmAuthorization.Retry(FcmRetryReason.TRANSPORT)
        } catch (_: Exception) { invalid() }
    }
    private fun invalid() = FcmAuthorization.Retry(FcmRetryReason.INVALID_RESPONSE)
    private fun assertion(): String {
        val issued = clock.instant().epochSecond
        require(issued in 0..Long.MAX_VALUE - 3600)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun encode(value: JsonObject) = encoder.encodeToString(value.toString().toByteArray(Charsets.UTF_8))
        val header = encode(buildJsonObject {
            put("alg", "RS256"); put("typ", "JWT"); account.credentials.privateKeyId?.let { put("kid", it) }
        })
        val claims = encode(buildJsonObject {
            put("iss", account.credentials.clientEmail); put("scope", "https://www.googleapis.com/auth/firebase.messaging")
            put("aud", "https://oauth2.googleapis.com/token"); put("iat", issued); put("exp", issued + 3600)
        })
        val unsigned = "$header.$claims"
        // Google Auth Library owns RSA cryptography and key parsing; only transport is replaced to bound its lifetime/body.
        return "$unsigned.${encoder.encodeToString(account.credentials.sign(unsigned.toByteArray(Charsets.UTF_8)))}"
    }
    companion object {
        fun create(account: FcmServiceAccount, http: HttpClient, clock: Clock = Clock.systemUTC(),
                   nanoTime: () -> Long = System::nanoTime, timeout: Duration = Duration.ofSeconds(5)) =
            FcmOAuthProvider(account, URI("https://oauth2.googleapis.com/token"), http, clock, nanoTime, timeout)
        fun forTests(account: FcmServiceAccount, endpoint: URI, http: HttpClient, clock: Clock,
                     nanoTime: () -> Long, timeout: Duration = Duration.ofSeconds(2)): FcmOAuthProvider {
            require(endpoint.scheme == "http" && endpoint.host == "127.0.0.1" && endpoint.userInfo == null &&
                endpoint.rawQuery == null && endpoint.rawFragment == null) { "Loopback test endpoint required" }
            return FcmOAuthProvider(account, endpoint, http, clock, nanoTime, timeout)
        }
    }
}
