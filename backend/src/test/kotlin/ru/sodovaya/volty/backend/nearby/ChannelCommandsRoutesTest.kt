package ru.sodovaya.volty.backend.nearby

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.*
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*

class ChannelCommandsRoutesTest {
    private val actor = id(); private val community = id(); private val channel = "abcdef00-0000-0000-0000-000000000001"; private val operation = id()
    private val base = "/v2/nearby/communities/$community/channels/$channel"
    private fun dependencies(port: ChannelCommandsEndpointPort? = null) = AppDependencies(AppConfig.forTests(), object : BackendStore {
        override fun findUserById(id: String) = UserRecord(actor, "test@example.invalid", "ignored", "Test", true).takeIf { id == actor }
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long) = userId == actor
    }, testMode = true, nearbyChannelCommands = port)
    private inner class Recording : ChannelCommandsEndpointPort {
        val calls = mutableListOf<String>()
        var created: ChannelCreationResult = ChannelCreationResult.Created(CommunityChannel(channel, "Поездка", false), 7)
        var deleted: ChannelDeletionResult = ChannelDeletionResult.Deleted(community, channel, 8, emptyList())
        override fun create(actor: String, community: String, channel: String, name: String): ChannelCreationResult {
            calls += "create:$actor:$community:$channel:$name"; return created
        }
        override fun delete(actor: String, operation: String, community: String, channel: String): ChannelDeletionResult {
            calls += "delete:$actor:$operation:$community:$channel"; return deleted
        }
    }
    @Test fun commandsRequireAuthenticationAndOptionalDependency() = testApplication {
        val deps = dependencies(); application { module(deps) }
        for ((method, url) in listOf(HttpMethod.Put to base, HttpMethod.Post to "$base/delete")) {
            assertEquals(HttpStatusCode.Unauthorized, client.request(url) { this.method = method }.status)
            val response = client.request(url) { this.method = method; bearerAuth(deps.tokenService.issueAccessToken(actor)) }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }
    }
    @Test fun creationPassesAuthenticatedActorNormalizedNameAndStableChannel() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        repeat(2) {
            val response = client.put(base) {
                bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody("""{"name":"  Поездка  ","actorId":"${id()}"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status); assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(setOf("communityId", "channel", "revision"), body.keys)
            assertEquals(community, body.getValue("communityId").jsonPrimitive.content)
            val item = body.getValue("channel").jsonObject
            assertEquals(channel, item.getValue("id").jsonPrimitive.content); assertEquals("Поездка", item.getValue("name").jsonPrimitive.content)
            assertFalse(item.getValue("isDefault").jsonPrimitive.boolean); assertEquals(7, body.getValue("revision").jsonPrimitive.long)
        }
        assertEquals(List(2) { "create:$actor:$community:$channel:Поездка" }, port.calls)
    }
    @Test fun deletionUsesExplicitOperationAndProjectsActualCleanupFlagOnly() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        for (pending in listOf(true, false)) {
            // Incoherent terminal operation without session still carries authoritative durable flag.
            port.deleted = ChannelDeletionResult.Deleted(community, channel, 8, listOf(SessionJournalEntry(
                SessionOperation(id(), id(), null, null, OperationPhase.CANCELLED, null), null, false, pending)))
            val response = client.post("$base/delete") {
                bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody("""{"operationId":"$operation"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(setOf("operationId", "communityId", "channelId", "revision", "cleanupPending"), body.keys)
            assertEquals(operation, body.getValue("operationId").jsonPrimitive.content)
            assertEquals(community, body.getValue("communityId").jsonPrimitive.content)
            assertEquals(channel, body.getValue("channelId").jsonPrimitive.content)
            assertEquals(8, body.getValue("revision").jsonPrimitive.long)
            assertEquals(pending, body.getValue("cleanupPending").jsonPrimitive.boolean)
        }
        assertEquals(List(2) { "delete:$actor:$operation:$community:$channel" }, port.calls)
    }
    @Test fun conflictsAndCapAreExplicitWithoutAutomaticMutationFallback() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        for ((result, code) in listOf(ChannelCreationResult.Conflict to "channel_conflict", ChannelCreationResult.LimitReached to "channel_limit_reached")) {
            port.created = result
            val response = client.put(base) { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody("""{"name":"Поездка"}""") }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(code, Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
        }
        port.deleted = ChannelDeletionResult.Conflict
        val response = client.post("$base/delete") { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody("""{"operationId":"$operation"}""") }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("channel_conflict", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(3, port.calls.size)
    }
    @Test fun deniedAndUnknownChannelHaveUniformPrivateResponse() = testApplication {
        val port = Recording().also { it.created = ChannelCreationResult.Denied; it.deleted = ChannelDeletionResult.Denied }
        val deps = dependencies(port); application { module(deps) }
        val texts = mutableListOf<String>()
        for (resource in listOf(channel, id())) {
            val url = "/v2/nearby/communities/$community/channels/$resource"
            for (method in listOf(HttpMethod.Put, HttpMethod.Post)) {
                val response = client.request(if (method == HttpMethod.Put) url else "$url/delete") {
                    this.method = method; bearerAuth(deps.tokenService.issueAccessToken(actor))
                    setBody(if (method == HttpMethod.Put) """{"name":"Voice"}""" else """{"operationId":"$operation"}""")
                }
                assertEquals(HttpStatusCode.NotFound, response.status)
                texts += Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content
            }
        }
        assertEquals(List(4) { "channel_unavailable" }, texts); assertEquals(4, port.calls.size)
    }
    @Test fun invalidNamesBodiesAndIdsNeverReachPort() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        for (body in listOf("{}", "{", """{"name":" "}""", """{"name":"${"x".repeat(81)}"}""", """{"name":"bad\nname"}""")) {
            assertEquals(HttpStatusCode.BadRequest, client.put(base) { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody(body) }.status)
        }
        for (url in listOf(base.replace(channel, "bad"), base.replace(channel, channel.uppercase()), base.replace(community, "bad")))
            assertEquals(HttpStatusCode.BadRequest, client.put(url) { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody("""{"name":"Voice"}""") }.status)
        for (body in listOf("{}", "{", """{"operationId":"bad"}"""))
            assertEquals(HttpStatusCode.BadRequest, client.post("$base/delete") { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody(body) }.status)
        assertTrue(port.calls.isEmpty())
    }
    @Test fun streamedOversizeAndMalformedUtf8AreRejectedBeforeCommands() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        val malformed = """{"name":"Voice","ignored":"""".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        assertEquals("Voice", backendJson().decodeFromString<ChannelCreateRequest>(malformed.toString(Charsets.UTF_8)).name)
        for ((bytes, status) in listOf(ByteArray(16 * 1024 + 1) { 120 } to HttpStatusCode.PayloadTooLarge, malformed to HttpStatusCode.BadRequest)) {
            val response = client.put(base) {
                bearerAuth(deps.tokenService.issueAccessToken(actor))
                setBody(object : OutgoingContent.WriteChannelContent() { override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) } })
            }
            assertEquals(status, response.status)
        }
        assertTrue(port.calls.isEmpty())
    }
    private fun id() = UUID.randomUUID().toString()
}
