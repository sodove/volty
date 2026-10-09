package ru.sodovaya.volty.backend.nearby

import com.google.auth.oauth2.ServiceAccountCredentials
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.*
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class FcmOAuthProviderTest {
    @Test fun issuedAssertionHasVerifiedGoogleSignatureAndOnlyMessagingClaims() = Fixture().use { f ->
        val provider = f.provider()
        assertEquals("test-access-token", assertIs<FcmAuthorization.Ready>(provider.authorize()).bearer.value)
        val request = f.requests.single()
        assertEquals("POST", request.method); assertEquals("application/x-www-form-urlencoded", request.contentType)
        val form = request.body.split('&').associate { field -> field.split('=', limit = 2).let { URLDecoder.decode(it[0], Charsets.UTF_8) to URLDecoder.decode(it[1], Charsets.UTF_8) } }
        assertEquals(setOf("grant_type", "assertion"), form.keys)
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
        val jwt = assertNotNull(form["assertion"]).split('.')
        assertEquals(3, jwt.size)
        val decoder = Base64.getUrlDecoder()
        val header = Json.parseToJsonElement(decoder.decode(jwt[0]).toString(Charsets.UTF_8)).jsonObject
        assertEquals("RS256", header.getValue("alg").jsonPrimitive.content)
        assertEquals("JWT", header.getValue("typ").jsonPrimitive.content)
        val claims = Json.parseToJsonElement(decoder.decode(jwt[1]).toString(Charsets.UTF_8)).jsonObject
        assertEquals(setOf("iss", "scope", "aud", "iat", "exp"), claims.keys)
        assertEquals("sender@test-project.iam.gserviceaccount.com", claims.getValue("iss").jsonPrimitive.content)
        assertEquals("https://www.googleapis.com/auth/firebase.messaging", claims.getValue("scope").jsonPrimitive.content)
        assertEquals("https://oauth2.googleapis.com/token", claims.getValue("aud").jsonPrimitive.content)
        assertEquals(f.clock.instant().epochSecond, claims.getValue("iat").jsonPrimitive.long)
        assertEquals(3600L, claims.getValue("exp").jsonPrimitive.long - claims.getValue("iat").jsonPrimitive.long)
        val verification = Signature.getInstance("SHA256withRSA").apply { initVerify(keys.public); update("${jwt[0]}.${jwt[1]}".toByteArray(Charsets.UTF_8)) }
        assertTrue(verification.verify(decoder.decode(jwt[2])))
    }

    @Test fun cacheRefreshUsesMonotonicTimeAndConservativeSixtySecondMargin() = Fixture().use { f ->
        val provider = f.provider()
        val first = assertIs<FcmAuthorization.Ready>(provider.authorize())
        f.nano = TimeUnit.SECONDS.toNanos(59)
        assertSame(first.bearer, assertIs<FcmAuthorization.Ready>(provider.authorize()).bearer)
        assertEquals(1, f.requests.size)
        f.nano = TimeUnit.SECONDS.toNanos(60)
        assertIs<FcmAuthorization.Ready>(provider.authorize()); assertEquals(2, f.requests.size)
        assertEquals("FcmAuthorization.Ready(<redacted>)", first.toString())
    }

    @Test fun malformedOrOversizedTokenResponsesNeverBecomeCachedBearer() = Fixture().use { f ->
        val provider = f.provider()
        val invalid = listOf("{}", """{"access_token":"private","token_type":"Basic","expires_in":120}""",
            """{"access_token":"private space","token_type":"Bearer","expires_in":120}""",
            """{"access_token":"private","token_type":"Bearer","expires_in":0}""",
            """{"access_token":"private","token_type":"Bearer","expires_in":1}""",
            """{"access_token":"private","token_type":"Bearer","expires_in":60}""",
            """{"access_token":"private","token_type":"Bearer","expires_in":999999999}""", "x".repeat(20000))
        invalid.forEachIndexed { n, body ->
            f.body = body; assertIs<FcmAuthorization.Retry>(provider.authorize()); assertEquals(n + 1, f.requests.size)
        }
        f.body = Fixture.success
        assertIs<FcmAuthorization.Ready>(provider.authorize()); assertEquals(invalid.size + 1, f.requests.size)
    }

    @Test fun quotaAndServiceDelayAreRespectedAndRedirectNeverFollowed() = Fixture().use { f ->
        val provider = f.provider()
        f.status = 429; f.retryAfter = "120"
        assertEquals(FcmAuthorization.Retry(FcmRetryReason.THROTTLED, 120000), provider.authorize())
        f.status = 503; f.retryAfter = "9".repeat(40)
        assertEquals(FcmAuthorization.Retry(FcmRetryReason.UNAVAILABLE, Long.MAX_VALUE), provider.authorize())
        f.status = 302; f.location = "http://127.0.0.1:${f.port}/redirect"
        assertEquals(FcmAuthorization.Retry(FcmRetryReason.CONFIGURATION, 60000), provider.authorize())
        assertEquals(3, f.requests.size); assertTrue(f.requests.all { it.path == "/token" })
    }

    @Test fun totalDeadlineIncludesBodyAfterHeaders() = Fixture().use { f ->
        f.stall = true
        val result = f.provider(Duration.ofMillis(200)).authorize()
        assertTrue(f.headers.await(1, TimeUnit.SECONDS)); assertEquals(1, f.requests.size)
        assertEquals(FcmAuthorization.Retry(FcmRetryReason.TRANSPORT), result)
    }

    @Test fun oversizedQuotaBodyStillPreservesProviderPause() = Fixture().use { f ->
        f.status = 429; f.retryAfter = "120"; f.body = "private".repeat(4000)
        assertEquals(FcmAuthorization.Retry(FcmRetryReason.THROTTLED, 120000), f.provider().authorize())
        assertEquals(1, f.requests.size)
    }

    @Test fun acquisitionTimeCannotConsumeRefreshMarginAndStillReturnReadyToken() = Fixture().use { f ->
        f.nanoAfterRequest = TimeUnit.SECONDS.toNanos(90)
        assertIs<FcmAuthorization.Retry>(f.provider().authorize())
        assertEquals(1, f.requests.size)
    }

    @Test fun invalidationDropsOnlyTheBearerThatActuallyFailed() = Fixture().use { f ->
        val provider = f.provider()
        val first = assertIs<FcmAuthorization.Ready>(provider.authorize()).bearer
        provider.invalidate(first)
        val second = assertIs<FcmAuthorization.Ready>(provider.authorize()).bearer
        assertEquals(2, f.requests.size); assertNotSame(first, second)
        provider.invalidate(first)
        assertSame(second, assertIs<FcmAuthorization.Ready>(provider.authorize()).bearer)
        assertEquals(2, f.requests.size)
        provider.invalidate(second)
        assertIs<FcmAuthorization.Ready>(provider.authorize()); assertEquals(3, f.requests.size)
    }

    @Test fun preparedSendUsesMintedBearerAndUnauthorizedReceiptForcesNewAuthorization() = Fixture().use { f ->
        val preparation = FcmCallPushPreparation(f.provider(), f.sender())
        val target = PushDeviceTarget(id(), id(), 1, id(), "device-token", "a".repeat(64))
        val claim = PushDeliveryClaim(1, id(), 1, target, CallPushHint(id(), 0), 30000)
        f.sendStatus = 401
        val first = assertIs<PreparedCallPush.Ready>(preparation.prepare())
        assertEquals(FcmSendResult.Retry(FcmRetryReason.CONFIGURATION, 60000), first.attempt.send(claim, 20))
        f.sendStatus = 200
        val second = assertIs<PreparedCallPush.Ready>(preparation.prepare())
        assertEquals(FcmSendResult.Accepted, second.attempt.send(claim, 20))
        assertEquals(listOf("/token", "/send", "/token", "/send"), f.requests.map { it.path })
        assertTrue(f.requests.filter { it.path == "/send" }.all { it.authorization == "Bearer test-access-token" })
    }

    private fun id() = UUID.randomUUID().toString()

    @Test fun interruptionAbortsRequestAndPreservesInterruptedThread() = Fixture().use { f ->
        f.stall = true
        val preserved = AtomicBoolean(); val finished = CountDownLatch(1)
        val provider = f.provider()
        val thread = Thread {
            try { provider.authorize() }
            catch (_: InterruptedException) { preserved.set(Thread.currentThread().isInterrupted) }
            finally { finished.countDown() }
        }
        thread.start()
        try {
            assertTrue(f.headers.await(1, TimeUnit.SECONDS)); thread.interrupt()
            assertTrue(finished.await(1, TimeUnit.SECONDS)); assertTrue(preserved.get()); assertEquals(1, f.requests.size)
        } finally { thread.interrupt(); thread.join(3000) }
    }

    private data class Request(val method: String, val path: String, val contentType: String?, val body: String, val authorization: String?)
    private class Fixture : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        @Volatile var status = 200; @Volatile var body = success
        @Volatile var retryAfter: String? = null; @Volatile var location: String? = null
        @Volatile var sendStatus = 200
        @Volatile var stall = false; @Volatile var nano = 0L; @Volatile var nanoAfterRequest: Long? = null
        val clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC)
        val headers = CountDownLatch(1); private val release = CountDownLatch(1)
        private val executor = Executors.newFixedThreadPool(2)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Fixture.executor
            createContext("/") { exchange -> exchange.use {
                requests += Request(it.requestMethod, it.requestURI.path, it.requestHeaders.getFirst("Content-Type"), it.requestBody.readAllBytes().toString(Charsets.UTF_8), it.requestHeaders.getFirst("Authorization"))
                nanoAfterRequest?.let { nano = it }
                if (it.requestURI.path == "/send") {
                    val bytes = """{"name":"projects/test-project/messages/accepted"}""".toByteArray()
                    it.sendResponseHeaders(sendStatus, bytes.size.toLong()); it.responseBody.write(bytes)
                    return@use
                }
                retryAfter?.let { value -> it.responseHeaders.set("Retry-After", value) }
                location?.let { value -> it.responseHeaders.set("Location", value) }
                if (stall) {
                    it.sendResponseHeaders(200, 0); it.responseBody.write('{'.code); it.responseBody.flush(); headers.countDown(); release.await(3, TimeUnit.SECONDS)
                } else {
                    val bytes = body.toByteArray(); it.sendResponseHeaders(status, bytes.size.toLong()); it.responseBody.write(bytes)
                }
            } }
            start()
        }
        private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        val port get() = server.address.port
        fun provider(timeout: Duration = Duration.ofSeconds(2)) = FcmOAuthProvider.forTests(account,
            URI("http://127.0.0.1:$port/token"), http, clock, { nano }, timeout)
        fun sender() = HttpFcmSender.forTests("test-project", URI("http://127.0.0.1:$port/send"), http, clock)
        override fun close() { release.countDown(); http.shutdownNow(); server.stop(0); executor.shutdownNow(); check(executor.awaitTermination(3, TimeUnit.SECONDS)) }
        companion object { const val success = """{"access_token":"test-access-token","token_type":"Bearer","expires_in":120}""" }
    }
    companion object {
        private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val account = FcmServiceAccount("test-project", ServiceAccountCredentials.newBuilder()
            .setClientEmail("sender@test-project.iam.gserviceaccount.com").setPrivateKey(keys.private)
            .setPrivateKeyId("a".repeat(40)).setProjectId("test-project").build())
    }
}
