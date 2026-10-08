package ru.sodovaya.volty.backend.nearby

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class HttpFcmSenderTest {
    private val project = "test-project"
    private val bearer = FcmBearer("fake-test-bearer")
    private val target = PushDeviceTarget(id(), id(), 3, id(), "fake-device-token", "a".repeat(64))
    private val hint = CallPushHint(id(), 7)
    private fun id() = UUID.randomUUID().toString()

    @Test fun acceptedAttemptHasOnlyDataHintAndExplicitShortAndroidTtl() = Fixture().use { f ->
        f.body = """{"name":"projects/$project/messages/opaque-message"}"""
        assertEquals(FcmSendResult.Accepted, f.sender().send(bearer, target, hint, 19))
        val request = f.requests.single()
        assertEquals("POST", request.method); assertEquals("Bearer fake-test-bearer", request.auth)
        assertEquals("/v1/projects/$project/messages:send", request.path)
        val message = Json.parseToJsonElement(request.body).jsonObject.getValue("message").jsonObject
        assertEquals(setOf("token", "data", "android"), message.keys)
        assertEquals(target.token, message.getValue("token").jsonPrimitive.content)
        val data = message.getValue("data").jsonObject
        assertEquals(setOf("type", "callId", "revision", "bindingId", "generation"), data.keys)
        assertEquals("nearby_call", data.getValue("type").jsonPrimitive.content)
        assertEquals(hint.callId, data.getValue("callId").jsonPrimitive.content)
        assertEquals("7", data.getValue("revision").jsonPrimitive.content)
        assertEquals(target.bindingId, data.getValue("bindingId").jsonPrimitive.content)
        assertEquals("3", data.getValue("generation").jsonPrimitive.content)
        assertTrue(data.values.all { it is JsonPrimitive && it.isString })
        val android = message.getValue("android").jsonObject
        assertEquals("HIGH", android.getValue("priority").jsonPrimitive.content)
        assertEquals("19s", android.getValue("ttl").jsonPrimitive.content)
        assertEquals("ru.sodovaya.volty", android.getValue("restricted_package_name").jsonPrimitive.content)
    }

    @Test fun onlyTypedUnregisteredResponseCanRetireToken() = Fixture().use { f ->
        f.status = 404; f.body = error("UNREGISTERED")
        assertEquals(FcmSendResult.Unregistered, f.sender().send(bearer, target, hint, 20))
        f.body = """{"error":{"status":"NOT_FOUND"}}"""
        assertIs<FcmSendResult.Retry>(f.sender().send(bearer, target, hint, 20))
        f.status = 400; f.body = error("INVALID_ARGUMENT")
        assertEquals(FcmSendResult.InvalidRequest, f.sender().send(bearer, target, hint, 20))
        f.status = 403; f.body = error("SENDER_ID_MISMATCH")
        assertEquals(FcmSendResult.Retry(FcmRetryReason.CONFIGURATION, 60000), f.sender().send(bearer, target, hint, 20))
        f.status = 400; f.body = error("UNREGISTERED")
        assertEquals(FcmSendResult.InvalidRequest, f.sender().send(bearer, target, hint, 20))
        f.status = 404; f.body = error("UNREGISTERED").replace("google.firebase.fcm.v1.FcmError", "google.rpc.BadRequest")
        assertIs<FcmSendResult.Retry>(f.sender().send(bearer, target, hint, 20))
        assertEquals(6, f.requests.size)
    }

    @Test fun responseBytesAreBoundedAndMalformedSuccessIsNotAcknowledged() = Fixture().use { f ->
        for (body in listOf("not-json", "{}", """{"name":"projects/wrong/messages/id"}""", "x".repeat(16385))) {
            f.body = body
            assertEquals(FcmSendResult.Retry(FcmRetryReason.INVALID_RESPONSE), f.sender().send(bearer, target, hint, 20))
        }
        assertEquals(4, f.requests.size)
    }

    @Test fun retriesHonorSecondsDateAndQuotaMinimum() = Fixture().use { f ->
        f.status = 503; f.body = "{}"; f.retryAfter = "3"
        assertEquals(FcmSendResult.Retry(FcmRetryReason.UNAVAILABLE, 3000), f.sender().send(bearer, target, hint, 20))
        f.retryAfter = "Thu, 08 Oct 2026 00:00:05 GMT"
        assertEquals(FcmSendResult.Retry(FcmRetryReason.UNAVAILABLE, 5000), f.sender().send(bearer, target, hint, 20))
        f.status = 429; f.retryAfter = "2"
        assertEquals(FcmSendResult.Retry(FcmRetryReason.THROTTLED, 60000), f.sender().send(bearer, target, hint, 20))
        assertEquals(3, f.requests.size)
    }

    @Test fun invalidHintTtlOrBearerNeverIssuesHttpRequest() = Fixture().use { f ->
        for (ttl in listOf(0, 31)) assertEquals(FcmSendResult.InvalidRequest, f.sender().send(bearer, target, hint, ttl))
        assertEquals(FcmSendResult.InvalidRequest, f.sender().send(bearer, target, CallPushHint("bad", 7), 20))
        assertEquals(FcmSendResult.InvalidRequest, f.sender().send(FcmBearer("bad\r\nsecret"), target, hint, 20))
        assertTrue(f.requests.isEmpty())
    }

    @Test fun enormousRetryAfterCannotOverflowIntoEarlyRetry() = Fixture().use { f ->
        f.status = 503
        for (delay in listOf(Long.MAX_VALUE.toString(), "9".repeat(40))) {
            f.retryAfter = delay
            val result = assertIs<FcmSendResult.Retry>(f.sender().send(bearer, target, hint, 20))
            assertTrue(result.afterMillis > Long.MAX_VALUE / 2)
        }
        assertEquals(2, f.requests.size)
    }

    @Test fun redirectNeverForwardsBearerOrTokenToAnotherPath() = Fixture().use { f ->
        f.status = 302; f.location = "http://127.0.0.1:${f.port}/unexpected"
        assertEquals(FcmSendResult.Retry(FcmRetryReason.CONFIGURATION, 60000), f.sender().send(bearer, target, hint, 20))
        assertEquals(1, f.requests.size)
        assertEquals("/v1/projects/$project/messages:send", f.requests.single().path)
    }

    @Test fun incompleteResponseBodyHasTotalDeadlineAfterHeadersWereWritten() = Fixture().use { f ->
        f.stall = true
        val runner = Executors.newSingleThreadExecutor()
        try {
            val pending = runner.submit<FcmSendResult> { f.sender(Duration.ofMillis(500)).send(bearer, target, hint, 20) }
            assertTrue(f.headersWritten.await(3, TimeUnit.SECONDS), "Driver never reached partial response")
            assertEquals(FcmSendResult.Retry(FcmRetryReason.TRANSPORT), pending.get(3, TimeUnit.SECONDS))
            assertEquals(1, f.requests.size)
        } finally { f.release.countDown(); runner.shutdownNow(); assertTrue(runner.awaitTermination(3, TimeUnit.SECONDS)) }
    }

    private fun error(code: String) = """{"error":{"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$code"}]}}"""
    private data class Request(val method: String, val path: String, val auth: String?, val body: String)
    private inner class Fixture : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        @Volatile var status = 200
        @Volatile var body = "{}"
        @Volatile var retryAfter: String? = null
        @Volatile var location: String? = null
        @Volatile var stall = false
        val headersWritten = CountDownLatch(1); val release = CountDownLatch(1)
        private val executor = Executors.newFixedThreadPool(2)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Fixture.executor
            createContext("/") { exchange -> exchange.use {
                requests += Request(it.requestMethod, it.requestURI.path, it.requestHeaders.getFirst("Authorization"), it.requestBody.readAllBytes().toString(Charsets.UTF_8))
                retryAfter?.let { value -> it.responseHeaders.set("Retry-After", value) }
                location?.let { value -> it.responseHeaders.set("Location", value) }
                if (stall) {
                    it.sendResponseHeaders(200, 0); it.responseBody.write('{'.code); it.responseBody.flush()
                    headersWritten.countDown(); release.await(3, TimeUnit.SECONDS)
                } else {
                    val bytes = body.toByteArray(); it.sendResponseHeaders(status, bytes.size.toLong()); it.responseBody.write(bytes)
                }
            } }
            start()
        }
        private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        val port get() = server.address.port
        fun sender(timeout: Duration = Duration.ofSeconds(2)) = HttpFcmSender.forTests(project,
            URI("http://127.0.0.1:${server.address.port}/v1/projects/$project/messages:send"), http,
            Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC), timeout)
        override fun close() { release.countDown(); http.shutdownNow(); server.stop(0); executor.shutdownNow(); check(executor.awaitTermination(3, TimeUnit.SECONDS)) }
    }
}
