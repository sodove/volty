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

class CommunityRemovalRoutesTest {
    private val actor = id(); private val community = id(); private val operation = "abcdef00-0000-0000-0000-000000000001"; private val successor = id()
    private val base = "/v2/nearby/communities/$community"
    private fun dependencies(port: CommunityRemovalEndpointPort? = null) = AppDependencies(AppConfig.forTests(), object : BackendStore {
        override fun findUserById(id: String) = UserRecord(actor, "test@example.invalid", "ignored", "Test", true).takeIf { id == actor }
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long) = userId == actor
    }, testMode = true, nearbyCommunityRemoval = port)
    private inner class Recording : CommunityRemovalEndpointPort {
        val calls = mutableListOf<String>()
        var leaveResult: CommunityDepartureResult = CommunityDepartureResult.Departed(community, 9, emptyList())
        var deleteResult: CommunityDeletionResult = CommunityDeletionResult.Deleted(community, 10, emptyList())
        override fun leave(actor: String, operation: String, community: String, successor: String?): CommunityDepartureResult {
            calls += "leave:$actor:$operation:$community:$successor"; return leaveResult
        }
        override fun delete(actor: String, operation: String, community: String): CommunityDeletionResult {
            calls += "delete:$actor:$operation:$community"; return deleteResult
        }
    }
    @Test fun removalRequiresAuthenticationAndOptionalDependency() = testApplication {
        val deps = dependencies(); application { module(deps) }
        for (action in listOf("leave", "delete")) {
            assertEquals(HttpStatusCode.Unauthorized, client.post("$base/$action").status)
            val response = client.post("$base/$action") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }
    }
    @Test fun leaveUsesAuthenticatedActorAndStableExplicitSuccessorPayload() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        repeat(2) {
            val response = client.post("$base/leave") {
                bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
                setBody("""{"operationId":"$operation","successorId":"$successor","actorId":"${id()}"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(setOf("operationId", "communityId", "revision", "cleanupPending"), body.keys)
            assertEquals(operation, body.getValue("operationId").jsonPrimitive.content)
            assertEquals(community, body.getValue("communityId").jsonPrimitive.content)
            assertEquals(9, body.getValue("revision").jsonPrimitive.long)
        }
        assertEquals(List(2) { "leave:$actor:$operation:$community:$successor" }, port.calls)
    }
    @Test fun deleteIsSeparateExplicitActionAndDoesNotExposeCleanupTargets() = testApplication {
        val port = Recording(); port.deleteResult = CommunityDeletionResult.Deleted(community, 10, listOf(target(true), target(false)))
        val deps = dependencies(port); application { module(deps) }
        val response = client.post("$base/delete") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
            setBody("""{"operationId":"$operation"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body.getValue("cleanupPending").jsonPrimitive.boolean)
        assertEquals(setOf("operationId", "communityId", "revision", "cleanupPending"), body.keys)
        assertEquals(listOf("delete:$actor:$operation:$community"), port.calls)
    }
    @Test fun cleanupProjectionUsesDurableFlagDespiteTerminalSessionShape() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        for (pending in listOf(true, false)) {
            // Deliberately incoherent: terminal operation without a session still has a pending flag.
            port.leaveResult = CommunityDepartureResult.Departed(community, 9, listOf(target(pending)))
            val response = client.post("$base/leave") { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody("""{"operationId":"$operation"}""") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(pending, Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("cleanupPending").jsonPrimitive.boolean)
        }
        assertEquals(2, port.calls.size)
    }
    @Test fun ownerActionsAndOperationConflictAreExplicitWithoutAutomaticDeletion() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        val outcomes = listOf(
            CommunityDepartureResult.SuccessorRequired to "successor_required",
            CommunityDepartureResult.DeleteRequired to "delete_required",
            CommunityDepartureResult.InvalidSuccessor to "invalid_successor",
            CommunityDepartureResult.Conflict to "community_conflict",
        )
        for ((outcome, code) in outcomes) {
            port.leaveResult = outcome
            val response = client.post("$base/leave") { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody("""{"operationId":"$operation"}""") }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(code, Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
        }
        assertEquals(4, port.calls.size); assertTrue(port.calls.all { it.startsWith("leave:") })
        port.deleteResult = CommunityDeletionResult.Conflict
        val response = client.post("$base/delete") { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody("""{"operationId":"$operation"}""") }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("community_conflict", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals("delete:$actor:$operation:$community", port.calls.last())
    }
    @Test fun deniedAndUnknownRemovalHaveUniformPrivateResponse() = testApplication {
        val port = Recording().also { it.leaveResult = CommunityDepartureResult.Denied; it.deleteResult = CommunityDeletionResult.Denied }
        val deps = dependencies(port); application { module(deps) }
        for (resource in listOf(community, id())) for (action in listOf("leave", "delete")) {
            val response = client.post("/v2/nearby/communities/$resource/$action") { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody("""{"operationId":"$operation"}""") }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("community_unavailable", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
        }
        assertEquals(4, port.calls.size)
    }
    @Test fun malformedPayloadAndNoncanonicalIdsAreRejectedBeforeMutation() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        for (body in listOf("{}", "{", """{"operationId":"bad"}""", """{"operationId":"$operation","successorId":"bad"}""", """{"operationId":"${operation.uppercase()}"}""")) {
            val response = client.post("$base/leave") { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody(body) }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        assertTrue(port.calls.isEmpty())
    }
    @Test fun streamedOversizeAndMalformedUtf8AreRejectedWithoutContentLength() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        // Replacement decoding would make this valid JSON with an ignored field
        // and invoke deletion. Strict decoding must reject before the port.
        val malformed = """{"operationId":"$operation","ignored":"""".toByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        assertEquals(operation, backendJson().decodeFromString<CommunityDeleteRequest>(malformed.toString(Charsets.UTF_8)).operationId)
        for ((bytes, status) in listOf(ByteArray(16 * 1024 + 1) { 120 } to HttpStatusCode.PayloadTooLarge,
            malformed to HttpStatusCode.BadRequest)) {
            val response = client.post("$base/delete") {
                bearerAuth(deps.tokenService.issueAccessToken(actor))
                setBody(object : OutgoingContent.WriteChannelContent() {
                    override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) }
                })
            }
            assertEquals(status, response.status)
        }
        assertTrue(port.calls.isEmpty())
    }
    private fun target(pending: Boolean) = SessionJournalEntry(SessionOperation(id(), id(), null, null, OperationPhase.CANCELLED, null), null, false, pending)
    private fun id() = UUID.randomUUID().toString()
}
