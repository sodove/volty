package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityHttpPortTest {
    private val community = "20000000-0000-0000-0000-000000000002"
    private val second = "40000000-0000-0000-0000-000000000004"
    private val after = "10000000-0000-0000-0000-000000000001"
    private val owner = "50000000-0000-0000-0000-000000000005"
    private val channel = "30000000-0000-0000-0000-000000000003"
    private fun summary(id: String = community, visibility: String = "PUBLIC", created: String = "100") =
        """{"id":"$id","name":"Riders","description":"Ride","visibility":"$visibility","createdAtEpochMillis":$created}"""
    private fun detail(id: String = community, flag: String = "true") =
        """{"revision":7,"community":${summary(id)},"ownerId":"$owner","channels":[{"id":"$channel","name":"Общий","isDefault":$flag}]}"""
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine) {
        engine { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) }
    }
    private fun port(client: HttpClient) = CommunityHttpPort(client, "https://example.test/v2/nearby/communities", { "bearer-secret" })

    @Test fun createUsesStablePutIdPrivateDefaultAndSecureActorProvenance() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client { requests += it; respond(detail()) }; val port = port(client)
        try {
            repeat(2) {
                val result = assertIs<CommunityResult.Ready<CommunityDetails>>(port.create(community, "Riders", "Ride"))
                assertEquals(community, result.value.community.id); assertEquals(owner, result.value.ownerId)
            }
            assertEquals(2, requests.size)
            for (request in requests) {
                assertEquals(HttpMethod.Put, request.method)
                assertEquals("/v2/nearby/communities/$community", request.url.encodedPath)
                assertEquals("Bearer bearer-secret", request.headers[HttpHeaders.Authorization])
                val body = (request.body as TextContent).text
                assertTrue(body.contains("PRIVATE")); assertFalse(body.contains("ownerId")); assertFalse(body.contains(owner))
            }
        } finally { port.close(); client.close() }
    }

    @Test fun catalogKeepsLiteralQueryCursorAndOnlySummaryData() = runTest {
        var request: HttpRequestData? = null
        val client = client { request = it; respond("""{"items":[${summary()}],"ownerId":"$owner","channels":["$channel"]}""") }
        val port = port(client)
        try {
            val result = assertIs<CommunityResult.Ready<List<CommunitySummary>>>(port.catalog("%_", after, 2))
            assertEquals(listOf(community), result.value.map { it.id })
            assertEquals("%_", request!!.url.parameters["q"]); assertEquals(after, request!!.url.parameters["after"])
            assertEquals("2", request!!.url.parameters["limit"])
            assertFalse(result.toString().contains(owner)); assertFalse(result.toString().contains(channel))
        } finally { port.close(); client.close() }
    }

    @Test fun foreignDetailAndQuotedDefaultFlagCannotUpdateChosenServer() = runTest {
        var response = detail(second); var issued = 0
        val client = client { issued++; respond(response) }; val port = port(client)
        try {
            assertEquals(CommunityResult.Failed(CommunityFailure.INVALID_RESPONSE), port.detail(community))
            response = detail(flag = "\"true\"")
            assertEquals(CommunityResult.Failed(CommunityFailure.INVALID_RESPONSE), port.detail(community))
            assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }

    @Test fun privateCatalogMissingFieldsAndIncoherentPagingFailClosed() = runTest {
        var items = summary(visibility = "PRIVATE"); var issued = 0
        val client = client { issued++; respond("""{"items":[$items]}""") }; val port = port(client)
        try {
            for (invalid in listOf(items, summary().replace("\"createdAtEpochMillis\":100", "\"ignored\":100"),
                "${summary()},${summary()}", "${summary(second)},${summary()}", summary(after), summary(created = "-1"))) {
                items = invalid
                assertEquals(CommunityResult.Failed(CommunityFailure.INVALID_RESPONSE), port.catalog("", after, 2))
            }
            assertEquals(6, issued)
        } finally { port.close(); client.close() }
    }

    @Test fun privateMembershipIsValidAndPublicJoinNeverReturnsVoiceCredentials() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client { request -> requests += request; respond(if (request.method == HttpMethod.Get)
            """{"items":[${summary(visibility = "PRIVATE")}]}""" else detail()) }
        val port = port(client)
        try {
            val memberships = assertIs<CommunityResult.Ready<List<CommunitySummary>>>(port.memberships())
            assertEquals(CommunityVisibility.PRIVATE, memberships.value.single().visibility)
            assertIs<CommunityResult.Ready<CommunityDetails>>(port.joinPublic(community))
            assertEquals(listOf("/v2/nearby/communities", "/v2/nearby/communities/$community/join"), requests.map { it.url.encodedPath })
            assertTrue(requests.none { it.url.encodedPath.contains("sessions") || it.url.encodedPath.contains("sharing") })
        } finally { port.close(); client.close() }
    }

    @Test fun statusFailuresOversizedBodyAndRedirectRemainSafe() = runTest {
        var status = HttpStatusCode.Unauthorized; var body = "bearer-secret"; var issued = 0
        val client = client { issued++; respond(body, status, headersOf(HttpHeaders.Location, "https://elsewhere.test/")) }; val port = port(client)
        try {
            for ((code, failure) in listOf(HttpStatusCode.Unauthorized to CommunityFailure.UNAUTHORIZED,
                HttpStatusCode.NotFound to CommunityFailure.ACCESS_DENIED, HttpStatusCode.Conflict to CommunityFailure.CONFLICT,
                HttpStatusCode.Found to CommunityFailure.UNAVAILABLE)) {
                status = code; val result = port.detail(community)
                assertEquals(CommunityResult.Failed(failure), result); assertFalse(result.toString().contains(body))
            }
            status = HttpStatusCode.OK; body = "x".repeat(512 * 1024 + 1)
            assertEquals(CommunityResult.Failed(CommunityFailure.INVALID_RESPONSE), port.detail(community))
            assertEquals(5, issued)
        } finally { port.close(); client.close() }
    }

    @Test fun fullLegalPageWithEscapedDescriptionsFitsTheBoundedResponse() = runTest {
        val items = (1..50).joinToString(",") { index ->
            val id = "60000000-0000-0000-0000-${index.toString(16).padStart(12, '0')}"
            summary(id).replace("\"description\":\"Ride\"", "\"description\":\"${"\\u0001".repeat(1000)}\"")
        }
        var issued = 0
        val client = client { issued++; respond("""{"items":[$items]}""") }; val port = port(client)
        try {
            val result = assertIs<CommunityResult.Ready<List<CommunitySummary>>>(port.catalog(limit = 50))
            assertEquals(50, result.value.size); assertTrue(result.value.all { it.description.length == 1000 })
            assertEquals(1, issued)
        } finally { port.close(); client.close() }
    }

    @Test fun invalidUtf8IsAnInvalidResponseWithoutReplacementMetadata() = runTest {
        val start = "{\"items\":[{\"id\":\"$community\",\"name\":\"".encodeToByteArray()
        val end = "\",\"description\":\"Ride\",\"visibility\":\"PUBLIC\",\"createdAtEpochMillis\":100}]}".encodeToByteArray()
        var issued = 0
        val client = client { issued++; respond(start + byteArrayOf(0xc3.toByte(), 0x28) + end) }; val port = port(client)
        try {
            assertEquals(CommunityResult.Failed(CommunityFailure.INVALID_RESPONSE), port.catalog())
            assertEquals(1, issued)
        } finally { port.close(); client.close() }
    }

    @Test fun issuedCancellationDoesNotTurnIntoAnAutomaticJoinRetry() = runTest {
        val entered = CompletableDeferred<Unit>(); var issued = 0
        val client = client { issued++; entered.complete(Unit); awaitCancellation() }; val port = port(client)
        try {
            val request = async { port.joinPublic(community) }; runCurrent()
            assertTrue(entered.isCompleted)
            request.cancelAndJoin(); runCurrent()
            assertTrue(request.isCancelled); assertEquals(1, issued)
        } finally { port.close(); client.close() }
    }

    @Test fun finiteTimeoutAndNetworkExceptionDoNotExposeBodiesOrRetry() = runTest {
        var delayed = false; var issued = 0
        val client = client { issued++; if (delayed) { delay(10001); respond(detail()) } else error("bearer-secret") }
        val port = port(client)
        try {
            assertEquals(CommunityResult.Failed(CommunityFailure.UNAVAILABLE), port.detail(community))
            delayed = true
            assertEquals(CommunityResult.Failed(CommunityFailure.UNAVAILABLE), port.detail(community))
            assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
}
