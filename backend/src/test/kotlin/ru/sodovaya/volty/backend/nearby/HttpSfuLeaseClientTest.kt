package ru.sodovaya.volty.backend.nearby

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HttpSfuLeaseClientTest {
    private val epoch = "a".repeat(32)
    private val requests = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(2)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var respond: (HttpExchange) -> Unit = { reply(it, 404, "missing") }
    private val origin: URI
    init {
        server.executor = executor
        server.createContext("/volty/leases/") { exchange -> requests.incrementAndGet(); exchange.use { respond(it) } }
        server.start()
        origin = URI("http://127.0.0.1:${server.address.port}")
    }
    private fun client(clock: Clock = Clock.systemUTC(), timeout: Duration = Duration.ofSeconds(6)) =
        HttpSfuLeaseClient(origin, "test-key", "test-secret", clock, timeout)

    @AfterEach fun close() { server.stop(0); executor.shutdownNow() }

    @Test fun stockNodeIsUnavailable() {
        assertEquals(SfuFailure.UNSUPPORTED, (client().inspect("room") as SfuReply.Rejected).reason)
    }

    @Test fun observationRequiresExactNodeAndImmutableIdentityWithoutInterpretingJoinedAsActive() {
        for (state in listOf("JOINING", "JOINED", "ACTIVE", "DISCONNECTED")) {
            respond = { exchange ->
                assertEquals("/volty/leases/participant", exchange.requestURI.path)
                val body = Json.parseToJsonElement(exchange.requestBody.reader().readText()).jsonObject
                assertEquals("room", body["room"]!!.jsonPrimitive.content)
                assertEquals("session", body["identity"]!!.jsonPrimitive.content)
                assertEquals(epoch, body["nodeEpoch"]!!.jsonPrimitive.content)
                val bearer = exchange.requestHeaders.getFirst("Authorization").removePrefix("Bearer ")
                val video = JWT.require(Algorithm.HMAC256("test-secret")).build().verify(bearer).getClaim("video").asMap()
                assertEquals("room", video["room"])
                assertEquals(true, video["roomAdmin"])
                reply(exchange, 200, """{"nodeEpoch":"$epoch","sid":"PA_current","state":"$state"}""")
            }
            val value = (client().observeParticipant("room", "session", epoch) as SfuReply.Accepted).value
            assertEquals("PA_current", value.participantSid)
            assertEquals(state, value.state!!.name)
        }
    }

    @Test fun absentParticipantIsExplicitButMissingEndpointIsUnsupported() {
        respond = { reply(it, 200, """{"nodeEpoch":"$epoch","sid":"","state":""}""") }
        val value = (client().observeParticipant("room", "session", epoch) as SfuReply.Accepted).value
        assertNull(value.participantSid)
        assertNull(value.state)
        respond = { reply(it, 404, "missing") }
        assertEquals(SfuFailure.UNSUPPORTED, (client().observeParticipant("room", "session", epoch) as SfuReply.Rejected).reason)
    }

    @Test fun observationRejectsOldNodeAndIncoherentWireState() {
        for (body in listOf(
            """{"nodeEpoch":"${"b".repeat(32)}","sid":"PA_current","state":"ACTIVE"}""",
            """{"nodeEpoch":"$epoch","sid":"PA_current","state":"future-state"}""",
            """{"nodeEpoch":"$epoch","sid":"","state":"ACTIVE"}""",
            """{"nodeEpoch":"$epoch","sid":"PA_current","state":""}""",
            """{"nodeEpoch":"$epoch","sid":"${"a".repeat(257)}","state":"ACTIVE"}""",
            """{"nodeEpoch":"$epoch","sid":"PA_current"}""",
        )) {
            respond = { reply(it, 200, body) }
            assertEquals(SfuFailure.INVALID_RESPONSE, (client().observeParticipant("room", "session", epoch) as SfuReply.Rejected).reason)
        }
    }

    @Test fun observationNeverConvertsRejectionIntoAbsentParticipant() {
        for ((code, expected) in listOf(409 to SfuFailure.REJECTED, 403 to SfuFailure.FORBIDDEN, 503 to SfuFailure.UNAVAILABLE)) {
            respond = { reply(it, code, "private-server-details") }
            assertEquals(expected, (client().observeParticipant("room", "session", epoch) as SfuReply.Rejected).reason)
        }
    }

    @Test fun connectedRenewalCarriesExactObservedSIDAndPreservesRemainingReceipt() {
        respond = { exchange ->
            assertEquals("/volty/leases/renew-connected", exchange.requestURI.path)
            val body = Json.parseToJsonElement(exchange.requestBody.reader().readText()).jsonObject
            assertEquals("PA_current", body["participantSid"]!!.jsonPrimitive.content)
            assertEquals("session", body["identity"]!!.jsonPrimitive.content)
            assertEquals(epoch, body["nodeEpoch"]!!.jsonPrimitive.content)
            reply(exchange, 204, "", "12000")
        }
        val clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneId.of("UTC"))
        assertEquals(13000, (client(clock).renewConnected("room", "session", epoch, "PA_current") as SfuReply.Accepted).value.conservativeDeadlineEpochMillis)
        respond = { reply(it, 409, "unconfirmed_or_replaced") }
        assertEquals(SfuFailure.REJECTED, (client().renewConnected("room", "session", epoch, "PA_old") as SfuReply.Rejected).reason)
    }

    @Test fun readinessRequiresExactProtocolAndValidFields() {
        for (body in listOf(readyBody(version = 1), readyBody(version = 2), readyBody(version = 3), readyBody(node = ""), readyBody(used = 4097), readyBody(ttl = 60), readyBody(age = -1), readyBody(singleNode = false))) {
            respond = { reply(it, 200, body) }
            assertEquals(SfuFailure.INVALID_RESPONSE, (client().inspect("room") as SfuReply.Rejected).reason)
        }
    }

    @Test fun readinessPreservesCapacityAndUnhealthyState() {
        respond = { reply(it, 200, readyBody(used = 4096, ready = false, age = -1)) }
        val result = (client().inspect("room") as SfuReply.Accepted).value
        assertEquals(epoch, result.nodeEpoch)
        assertFalse(result.ready)
        assertFalse(result.canRegisterNew)
    }

    @Test fun adminCredentialsAreScopedToRequestedRoom() {
        respond = { exchange ->
            val bearer = exchange.requestHeaders.getFirst("Authorization")
            assertTrue(bearer.startsWith("Bearer "))
            val verified = JWT.require(Algorithm.HMAC256("test-secret")).build().verify(bearer.removePrefix("Bearer "))
            val video = verified.getClaim("video").asMap()
            assertEquals("room", video["room"])
            assertEquals(true, video["roomAdmin"])
            val request = Json.parseToJsonElement(exchange.requestBody.reader().readText()).jsonObject
            assertEquals("room", request["room"]!!.jsonPrimitive.content)
            reply(exchange, 200, readyBody())
        }
        assertIs<SfuReply.Accepted<SfuNodeStatus>>(client().inspect("room"))
    }

    @Test fun commandCarriesImmutableSessionAndExpectedIncarnation() {
        respond = { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.reader().readText()).jsonObject
            assertEquals("session", body["identity"]!!.jsonPrimitive.content)
            assertEquals(epoch, body["nodeEpoch"]!!.jsonPrimitive.content)
            assertEquals(30, body["ttlSeconds"]!!.jsonPrimitive.int)
            reply(exchange, 204, "")
        }
        assertIs<SfuReply.Accepted<LeaseReceipt>>(client().register("room", "session", epoch))
    }

    @Test fun delayedAckDoesNotExtendDeadlineFromArrivalTime() {
        var now = 1000L
        val clock = object : Clock() {
            override fun getZone() = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId) = this
            override fun instant() = Instant.ofEpochMilli(now)
        }
        respond = { now = 9000; reply(it, 204, "") }
        val receipt = (client(clock).renew("room", "session", epoch) as SfuReply.Accepted).value
        assertEquals(31_000, receipt.conservativeDeadlineEpochMillis)
    }

    @Test fun idempotentRegistrationReplayUsesRemainingLeaseInsteadOfNewThirtySeconds() {
        var now = 1000L
        val clock = object : Clock() {
            override fun getZone() = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId) = this
            override fun instant() = Instant.ofEpochMilli(now)
        }
        respond = { it.responseHeaders.set("X-Volty-Lease-Remaining-Millis", "30000"); reply(it, 204, "") }
        val first = (client(clock).register("room", "session", epoch) as SfuReply.Accepted).value
        now = 9000
        respond = { it.responseHeaders.set("X-Volty-Lease-Remaining-Millis", "22000"); reply(it, 204, "") }
        val replay = (client(clock).register("room", "session", epoch) as SfuReply.Accepted).value
        assertEquals(first.conservativeDeadlineEpochMillis, replay.conservativeDeadlineEpochMillis)
    }

    @Test fun missingOrImpossibleLeaseReceiptIsNotAcknowledged() {
        for (remaining in listOf(null, "0", "-1", "30001", "not-a-number")) {
            respond = { reply(it, 204, "", remaining) }
            assertEquals(SfuFailure.INVALID_RESPONSE, (client().register("room", "session", epoch) as SfuReply.Rejected).reason)
        }
    }

    @Test fun epochConflictAndUnavailableAreExplicitFailures() {
        respond = { reply(it, 409, "node_epoch_mismatch") }
        assertEquals(SfuFailure.REJECTED, (client().revoke("room", "session", epoch) as SfuReply.Rejected).reason)
        respond = { reply(it, 503, "admission_unavailable") }
        assertEquals(SfuFailure.UNAVAILABLE, (client().register("room", "session", epoch) as SfuReply.Rejected).reason)
    }

    @Test fun errorBodyDoesNotEscapeAsFailureDetails() {
        respond = { reply(it, 500, "Bearer do-not-expose-this-response") }
        val rejected = client().revoke("room", "session", epoch) as SfuReply.Rejected
        assertEquals("Rejected(reason=UNAVAILABLE)", rejected.toString())
    }

    @Test fun oversizedReadinessIsRejected() {
        respond = { reply(it, 200, " ".repeat(4097)) }
        assertEquals(SfuFailure.INVALID_RESPONSE, (client().inspect("room") as SfuReply.Rejected).reason)
    }

    @Test fun requestTimeoutRequiresIssuedRequest() {
        val issued = CountDownLatch(1); val release = CountDownLatch(1)
        respond = { issued.countDown(); release.await(2, TimeUnit.SECONDS); reply(it, 204, "") }
        try {
            val result = client(timeout = Duration.ofMillis(150)).revoke("room", "session", epoch)
            assertTrue(issued.await(1, TimeUnit.SECONDS))
            assertEquals(SfuFailure.TIMEOUT, (result as SfuReply.Rejected).reason)
            assertEquals(1, requests.get())
        } finally { release.countDown() }
    }

    @Test fun responseBodyStallIsAlsoBoundedAfterHeadersArrive() {
        val headersSent = CountDownLatch(1); val release = CountDownLatch(1)
        val caller = Executors.newSingleThreadExecutor()
        respond = { exchange ->
            exchange.sendResponseHeaders(200, 100)
            exchange.responseBody.write('{'.code)
            exchange.responseBody.flush()
            headersSent.countDown()
            release.await(2, TimeUnit.SECONDS)
        }
        try {
            val request = caller.submit<SfuReply<SfuNodeStatus>> { client(timeout = Duration.ofMillis(150)).inspect("room") }
            assertTrue(headersSent.await(1, TimeUnit.SECONDS), "Response headers and first body byte issued")
            val result = request.get(700, TimeUnit.MILLISECONDS)
            assertEquals(SfuFailure.TIMEOUT, (result as SfuReply.Rejected).reason)
        } finally { release.countDown(); caller.shutdownNow() }
    }

    private fun readyBody(version: Int = 4, node: String = epoch, used: Int = 0, ttl: Int = 30, ready: Boolean = true, age: Long = 0, singleNode: Boolean = true) =
        """{"protocolVersion":$version,"nodeEpoch":"$node","ready":$ready,"slotsUsed":$used,"capacity":4096,"maximumLeaseSeconds":$ttl,"lastSweepAgeMillis":$age,"singleNode":$singleNode}"""

    private fun reply(exchange: HttpExchange, code: Int, body: String, remaining: String? = "30000") {
        if (code == 204 && remaining != null && exchange.responseHeaders.getFirst("X-Volty-Lease-Remaining-Millis") == null) {
            exchange.responseHeaders.set("X-Volty-Lease-Remaining-Millis", remaining)
        }
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, if (code == 204) -1 else bytes.size.toLong())
        if (code != 204) exchange.responseBody.write(bytes)
    }
}
