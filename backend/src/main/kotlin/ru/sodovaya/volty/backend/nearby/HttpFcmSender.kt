package ru.sodovaya.volty.backend.nearby

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/** Borrowed HTTP client; one bounded attempt, no delivery/notification claim. */
internal class HttpFcmSender private constructor(
    private val project: String, private val endpoint: URI, private val http: HttpClient,
    private val clock: Clock, private val timeout: Duration,
) {
    init {
        require(project.matches(Regex("[a-z][a-z0-9-]{0,127}"))) { "Firebase project ID required" }
        require(http.followRedirects() == HttpClient.Redirect.NEVER) { "FCM redirects must be disabled" }
        require(timeout in Duration.ofMillis(100)..Duration.ofSeconds(10)) { "Bounded FCM timeout required" }
    }

    fun send(bearer: FcmBearer, target: PushDeviceTarget, hint: CallPushHint, ttlSeconds: Int): FcmSendResult {
        if (ttlSeconds !in 1..30 || hint.revision < 0 || target.generation <= 0 ||
            !pushDeviceCanonicalId(hint.callId) || !pushDeviceCanonicalId(target.bindingId) ||
            !pushDeviceCanonicalId(target.actorId) || !pushDeviceCanonicalId(target.installationId) ||
            !printable(bearer.value, 8192) || !printable(target.token, 4096)) return FcmSendResult.InvalidRequest
        val body = buildJsonObject { putJsonObject("message") {
            put("token", target.token)
            putJsonObject("data") {
                put("type", "nearby_call"); put("callId", hint.callId); put("revision", hint.revision.toString())
                put("bindingId", target.bindingId); put("generation", target.generation.toString())
            }
            putJsonObject("android") { put("priority", "HIGH"); put("ttl", "${ttlSeconds}s"); put("restricted_package_name", "ru.sodovaya.volty") }
        } }.toString()
        val receiver = LimitedHttpBody(MAXIMUM_RESPONSE_BYTES)
        val info = AtomicReference<HttpResponse.ResponseInfo>()
        return try {
            val request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization", "Bearer ${bearer.value}")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val pending = http.sendAsync(request) { headers -> info.set(headers); receiver }
            try {
                val response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                classify(response.statusCode(), response.headers(), response.body().toString(Charsets.UTF_8))
            } finally {
                // HttpRequest.timeout can end at headers; the future bounds the complete body.
                if (!pending.isDone) { receiver.abort(); pending.cancel(true) }
            }
        } catch (failure: ExecutionException) {
            if (failure.cause is OversizedHttpBody) {
                val headers = info.get()
                if (headers == null) retry(FcmRetryReason.INVALID_RESPONSE) else classify(headers.statusCode(), headers.headers(), null)
            } else retry(FcmRetryReason.TRANSPORT)
        } catch (_: TimeoutException) { retry(FcmRetryReason.TRANSPORT) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); retry(FcmRetryReason.TRANSPORT) }
        catch (_: Exception) { retry(FcmRetryReason.TRANSPORT) }
    }

    private fun classify(status: Int, headers: HttpHeaders, body: String?): FcmSendResult {
        if (status == 429) return FcmSendResult.Retry(FcmRetryReason.THROTTLED, retryDelay(headers, 60000))
        if (status in 500..599) return FcmSendResult.Retry(FcmRetryReason.UNAVAILABLE, retryDelay(headers, 1000))
        if (status in setOf(401, 403) || status in 300..399) return FcmSendResult.Retry(FcmRetryReason.CONFIGURATION, 60000)
        if (status == 400) return FcmSendResult.InvalidRequest
        val value = try { body?.let { Json.parseToJsonElement(it) as? JsonObject } } catch (_: Exception) { null }
        if (status == 200) {
            val name = (value?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val prefix = "projects/$project/messages/"
            return if (name != null && name.startsWith(prefix) && name.length - prefix.length in 1..512)
                FcmSendResult.Accepted else retry(FcmRetryReason.INVALID_RESPONSE)
        }
        val error = value?.get("error") as? JsonObject
        val details = error?.get("details") as? JsonArray
        if (status == 404 && details?.any { detail ->
            val entry = detail as? JsonObject
            (entry?.get("@type") as? JsonPrimitive)?.contentOrNull == "type.googleapis.com/google.firebase.fcm.v1.FcmError" &&
                (entry["errorCode"] as? JsonPrimitive)?.contentOrNull == "UNREGISTERED"
        } == true) return FcmSendResult.Unregistered
        return retry(FcmRetryReason.INVALID_RESPONSE)
    }

    private fun retryDelay(headers: HttpHeaders, minimum: Long): Long {
        val raw = headers.firstValue("Retry-After").orElse("").trim()
        val seconds = if (raw.isNotEmpty() && raw.all { it in '0'..'9' }) raw.toLongOrNull() ?: Long.MAX_VALUE else null
        // Excessive server delays expire short-lived hints rather than retrying early.
        val delay = if (seconds != null) {
            if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
        }
        else runCatching {
            val deadline = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            Math.subtractExact(deadline, clock.millis())
        }.getOrDefault(minimum)
        return maxOf(minimum, delay)
    }
    private fun printable(value: String, maximum: Int) = value.length in 1..maximum && value.all { it.code in 33..126 }
    private fun retry(reason: FcmRetryReason) = FcmSendResult.Retry(reason)

    companion object {
        private const val MAXIMUM_RESPONSE_BYTES = 16384
        fun create(project: String, http: HttpClient, clock: Clock = Clock.systemUTC(), timeout: Duration = Duration.ofSeconds(5)): HttpFcmSender {
            require(project.matches(Regex("[a-z][a-z0-9-]{0,127}"))) { "Firebase project ID required" }
            return HttpFcmSender(project, URI("https://fcm.googleapis.com/v1/projects/$project/messages:send"), http, clock, timeout)
        }
        fun forTests(project: String, endpoint: URI, http: HttpClient, clock: Clock = Clock.systemUTC(), timeout: Duration = Duration.ofSeconds(5)): HttpFcmSender {
            require(endpoint.scheme == "http" && endpoint.host == "127.0.0.1" && endpoint.userInfo == null &&
                endpoint.rawQuery == null && endpoint.rawFragment == null) { "Loopback test endpoint required" }
            return HttpFcmSender(project, endpoint, http, clock, timeout)
        }
    }
}
