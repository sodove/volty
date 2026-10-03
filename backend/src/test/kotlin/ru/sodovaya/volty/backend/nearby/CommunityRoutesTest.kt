package ru.sodovaya.volty.backend.nearby

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*

class CommunityRoutesTest {
    private val actor = id(); private val community = id(); private val channel = id(); private val after = id()
    private val base = "/v2/nearby/communities"
    private fun dependencies(port: CommunityEndpointPort? = null) = AppDependencies(AppConfig.forTests(), object : BackendStore {
        override fun findUserById(id: String) = UserRecord(actor, "test@example.invalid", "ignored", "Test", true).takeIf { id == actor }
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long) = userId == actor
    }, testMode = true, nearbyCommunities = port)

    @Test fun everyCommunityPathRequiresAuthentication() = testApplication {
        val port = RecordingPort(); application { module(dependencies(port)) }
        for (path in listOf(base, "$base/catalog", "$base/$community")) assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("$base/$community").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("$base/$community/join").status)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun productionDefaultStaysDisabledAndResponseIsNotCached() = testApplication {
        val deps = dependencies(); application { module(deps) }
        val response = client.get(base) { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test fun createUsesJwtActorStableResourceAndPrivateDefault() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application { module(deps) }
        repeat(2) {
            val response = client.put("$base/$community") {
                bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
                setBody("""{"name":"  Riders  ","description":" Ride ","ownerId":"${id()}"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(actor, body.getValue("ownerId").jsonPrimitive.content)
            assertEquals(7, body.getValue("revision").jsonPrimitive.long)
            assertEquals(channel, body.getValue("channels").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        }
        assertEquals(listOf("create:$actor:$community:Riders:Ride:PRIVATE", "create:$actor:$community:Riders:Ride:PRIVATE"), port.calls)
    }

    @Test fun catalogKeepsLiteralQueryAndCursorWithoutPrivateDetailFields() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application { module(deps) }
        val response = client.get("$base/catalog") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); parameter("q", "%_"); parameter("after", after); parameter("limit", 5)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("items").jsonArray.single().jsonObject
        assertEquals(setOf("id", "name", "description", "visibility", "createdAtEpochMillis"), body.keys)
        assertEquals(community, body.getValue("id").jsonPrimitive.content)
        assertEquals(listOf("catalog:$actor:%_:$after:5"), port.calls)
    }

    @Test fun membershipListAndDetailUseAuthenticatedActorAndBoundedPage() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application { module(deps) }
        val list = client.get(base) { bearerAuth(deps.tokenService.issueAccessToken(actor)); parameter("after", after); parameter("limit", 2) }
        assertEquals(HttpStatusCode.OK, list.status)
        val detail = client.get("$base/$community") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.OK, detail.status)
        assertEquals(listOf("memberships:$actor:$after:2", "detail:$actor:$community"), port.calls)
    }

    @Test fun deniedDetailAndJoinDoNotRevealWhetherPrivateResourceExists() = testApplication {
        val port = RecordingPort().also { it.denied = true }; val deps = dependencies(port); application { module(deps) }
        for (resource in listOf(community, id())) {
            val detail = client.get("$base/$resource") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
            val join = client.post("$base/$resource/join") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
            assertEquals(HttpStatusCode.NotFound, detail.status); assertEquals(HttpStatusCode.NotFound, join.status)
            val detailBody = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
            val joinBody = Json.parseToJsonElement(join.bodyAsText()).jsonObject
            assertEquals(detailBody.getValue("code"), joinBody.getValue("code"))
            assertEquals(detailBody.getValue("message"), joinBody.getValue("message"))
        }
        assertEquals(4, port.calls.size)
    }

    @Test fun publicJoinIsExplicitAndDoesNotInvokeVoiceOrSharing() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application { module(deps) }
        val response = client.post("$base/$community/join") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("join:$actor:$community"), port.calls)
        assertFalse(response.bodyAsText().contains("credentials")); assertFalse(response.bodyAsText().contains("sessionId"))
    }

    @Test fun invalidPageAndCreateInputAreRejectedBeforeRepositoryInvocation() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application { module(deps) }
        for ((key, value) in listOf("limit" to "0", "limit" to "51", "limit" to "oops", "after" to "bad", "q" to "q".repeat(81))) {
            val response = client.get("$base/catalog") { bearerAuth(deps.tokenService.issueAccessToken(actor)); parameter(key, value) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        for (body in listOf("{}", """{"name":" "}""", """{"name":"${"n".repeat(81)}"}""", """{"name":"Riders","visibility":"ANY"}""", "{")) {
            val response = client.put("$base/$community") { bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        val oversized = client.put("$base/$community") { bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json); setBody("x".repeat(16 * 1024 + 1)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun conflictingCreateReturnsConflictWithoutSnapshot() = testApplication {
        val port = RecordingPort().also { it.conflict = true }; val deps = dependencies(port); application { module(deps) }
        val response = client.put("$base/$community") { bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json); setBody("""{"name":"Riders"}""") }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertFalse(response.bodyAsText().contains(channel))
        assertEquals(1, port.calls.size)
    }

    @Test fun streamingBodyWithoutContentLengthCannotBypassSizeLimit() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application { module(deps) }
        val response = client.put("$base/$community") {
            bearerAuth(deps.tokenService.issueAccessToken(actor))
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(ByteArray(16 * 1024 + 1) { 120 }) }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun invalidUtf8CannotSilentlyChangeCreationPayload() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application { module(deps) }
        // Structurally valid JSON if decoded with replacement characters: that
        // lenient path would invoke create with a changed, otherwise valid name.
        val bytes = "{\"name\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        val response = client.put("$base/$community") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json); setBody(bytes)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(port.calls.isEmpty())
    }

    private inner class RecordingPort : CommunityEndpointPort {
        val calls = mutableListOf<String>(); var denied = false; var conflict = false
        private val summary get() = CommunitySummary(community, "Riders", "Ride", CommunityVisibility.PUBLIC, actor, 100)
        private val snapshot get() = CommunitySnapshot(7, summary, listOf(CommunityChannel(channel, "Общий", true)))
        override fun catalog(actor: String, query: String, after: String?, limit: Int): List<CommunitySummary> { calls += "catalog:$actor:$query:$after:$limit"; return listOf(summary) }
        override fun memberships(actor: String, after: String?, limit: Int): List<CommunitySummary> { calls += "memberships:$actor:$after:$limit"; return listOf(summary) }
        override fun detail(actor: String, community: String): CommunitySnapshot? { calls += "detail:$actor:$community"; return if (denied) null else snapshot }
        override fun create(actor: String, community: String, name: String, description: String, visibility: CommunityVisibility): CommunityWriteResult {
            calls += "create:$actor:$community:$name:$description:$visibility"
            return if (conflict) CommunityWriteResult.Conflict else CommunityWriteResult.Accepted(snapshot)
        }
        override fun join(actor: String, community: String): CommunityWriteResult { calls += "join:$actor:$community"; return if (denied) CommunityWriteResult.Denied else CommunityWriteResult.Accepted(snapshot) }
    }
    private fun id() = UUID.randomUUID().toString()
}
