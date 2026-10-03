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
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class InvitationRoutesTest {
    private val actor = id(); private val community = id(); private val invitation = "abcdef00-0000-0000-0000-000000000001"; private val operation = id()
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
    private val base = "/v2/nearby/communities/$community/invitations/$invitation"
    private val acceptPath = "/v2/nearby/invitations/accept"
    private val createBody get() = """{"token":"$token","ttlMillis":900000,"maxUses":10}"""
    private val acceptBody get() = """{"operationId":"$operation","token":"$token"}"""
    private fun dependencies(port: InvitationEndpointPort? = null) = AppDependencies(AppConfig.forTests(), object : BackendStore {
        override fun findUserById(id: String) = UserRecord(actor, "test@example.invalid", "ignored", "Test", true).takeIf { id == actor }
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long) = userId == actor
    }, testMode = true, nearbyInvitations = port)
    private inner class Recording : InvitationEndpointPort {
        val calls = mutableListOf<String>()
        var creation: InvitationCreationResult = InvitationCreationResult.Created(InvitationSummary(invitation, community, 100, 900100, 10, 0, null, 7, null))
        var revocation: InvitationRevocationResult = InvitationRevocationResult.Revoked(invitation, community, 8)
        var acceptance: InvitationAcceptanceResult = InvitationAcceptanceResult.Accepted(InvitationAcceptanceReceipt(operation, invitation, community, 9, true))
        override fun create(actor: String, community: String, invitation: String, token: String, ttlMillis: Long, maxUses: Int): InvitationCreationResult {
            assertEquals(this@InvitationRoutesTest.token, token); calls += "create:$actor:$community:$invitation:$ttlMillis:$maxUses"; return creation
        }
        override fun revoke(actor: String, community: String, invitation: String): InvitationRevocationResult {
            calls += "revoke:$actor:$community:$invitation"; return revocation
        }
        override fun accept(actor: String, operation: String, token: String): InvitationAcceptanceResult {
            assertEquals(this@InvitationRoutesTest.token, token); calls += "accept:$actor:$operation"; return acceptance
        }
    }
    @Test fun authenticationAndOptionalDependencyGateEveryAction() = testApplication {
        val deps = dependencies(); application { module(deps) }
        for ((method, url) in listOf(HttpMethod.Put to base, HttpMethod.Post to "$base/revoke", HttpMethod.Post to acceptPath)) {
            assertEquals(HttpStatusCode.Unauthorized, client.request(url) { this.method = method }.status)
            val response = client.request(url) { this.method = method; bearerAuth(deps.tokenService.issueAccessToken(actor)) }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status); assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }
    }
    @Test fun creationAndRevocationExposeMetadataWithoutTokenOrHash() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        repeat(2) {
            val response = client.put(base) { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody(createBody.dropLast(1) + ",\"actorId\":\"${id()}\"}") }
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText(); assertFalse(text.contains(token)); assertFalse(text.contains(InvitationTokens.hash(token)))
            val root = Json.parseToJsonElement(text).jsonObject
            assertEquals(setOf("invitationId", "communityId", "createdAtMillis", "expiresAtMillis", "maxUses", "uses", "revokedAtMillis", "creationRevision", "revocationRevision"), root.keys)
            assertEquals(invitation, root.getValue("invitationId").jsonPrimitive.content)
            assertEquals(community, root.getValue("communityId").jsonPrimitive.content)
            assertEquals(900100, root.getValue("expiresAtMillis").jsonPrimitive.long)
        }
        repeat(2) {
            val response = client.post("$base/revoke") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
            assertEquals(HttpStatusCode.OK, response.status)
            val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(setOf("invitationId", "communityId", "revision"), root.keys); assertEquals(8, root.getValue("revision").jsonPrimitive.long)
        }
        assertEquals(List(2) { "create:$actor:$community:$invitation:900000:10" } + List(2) { "revoke:$actor:$community:$invitation" }, port.calls)
    }
    @Test fun explicitAcceptanceReturnsHistoricalReceiptWithoutVoiceOrPositionGrant() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        for (changed in listOf(true, false)) {
            port.acceptance = InvitationAcceptanceResult.Accepted(InvitationAcceptanceReceipt(operation, invitation, community, 9, changed))
            val response = client.post(acceptPath) { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody(acceptBody) }
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText(); assertFalse(text.contains(token))
            val root = Json.parseToJsonElement(text).jsonObject
            assertEquals(setOf("operationId", "invitationId", "communityId", "revision", "membershipChanged"), root.keys)
            assertEquals(operation, root.getValue("operationId").jsonPrimitive.content)
            assertEquals(invitation, root.getValue("invitationId").jsonPrimitive.content)
            assertEquals(community, root.getValue("communityId").jsonPrimitive.content)
            assertEquals(changed, root.getValue("membershipChanged").jsonPrimitive.boolean)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }
        assertEquals(List(2) { "accept:$actor:$operation" }, port.calls)
    }
    @Test fun unavailableReasonsAndConflictsAreGenericWithoutSecrets() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        port.creation = InvitationCreationResult.Denied; port.revocation = InvitationRevocationResult.Denied; port.acceptance = InvitationAcceptanceResult.Denied
        for ((method, url, body) in listOf(Triple(HttpMethod.Put, base, createBody), Triple(HttpMethod.Post, "$base/revoke", ""), Triple(HttpMethod.Post, acceptPath, acceptBody))) {
            val response = client.request(url) { this.method = method; bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody(body) }
            assertEquals(HttpStatusCode.NotFound, response.status)
            val text = response.bodyAsText(); assertFalse(text.contains(token))
            assertEquals("invitation_unavailable", Json.parseToJsonElement(text).jsonObject.getValue("code").jsonPrimitive.content)
        }
        port.creation = InvitationCreationResult.Conflict; port.acceptance = InvitationAcceptanceResult.Conflict
        for ((method, url, body) in listOf(Triple(HttpMethod.Put, base, createBody), Triple(HttpMethod.Post, acceptPath, acceptBody))) {
            val response = client.request(url) { this.method = method; bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody(body) }
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("invitation_conflict", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
        }
        assertEquals(5, port.calls.size)
    }
    @Test fun invalidTokenBoundsAndPayloadAreRejectedBeforePortWithGenericErrors() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        val invalid = listOf("{}", "{", createBody.replace(token, token + "="), createBody.replace(token, token.dropLast(1) + "F"),
            createBody.replace("900000", "899999"), createBody.replace("900000", "604800001"), createBody.replace("\"maxUses\":10", "\"maxUses\":0"),
            createBody.replace("\"maxUses\":10", "\"maxUses\":1001"))
        for (body in invalid) {
            val response = client.put(base) { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody(body) }
            assertEquals(HttpStatusCode.BadRequest, response.status); assertFalse(response.bodyAsText().contains(token))
        }
        for (body in listOf("{}", acceptBody.replace(operation, "bad"), acceptBody.replace(token, "bad")))
            assertEquals(HttpStatusCode.BadRequest, client.post(acceptPath) { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody(body) }.status)
        for (url in listOf(base.replace(invitation, invitation.uppercase()), base.replace(community, "bad")))
            assertEquals(HttpStatusCode.BadRequest, client.put(url) { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody(createBody) }.status)
        assertTrue(port.calls.isEmpty())
    }
    @Test fun streamedOversizeAndInvalidUtf8NeverAcceptInvitation() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        val malformed = (acceptBody.dropLast(1) + ",\"ignored\":\"").toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()
        assertEquals(token, backendJson().decodeFromString<InvitationAcceptRequest>(malformed.toString(Charsets.UTF_8)).token)
        for ((bytes, status) in listOf(ByteArray(16 * 1024 + 1) { 120 } to HttpStatusCode.PayloadTooLarge, malformed to HttpStatusCode.BadRequest)) {
            val response = client.post(acceptPath) {
                bearerAuth(deps.tokenService.issueAccessToken(actor))
                setBody(object : OutgoingContent.WriteChannelContent() { override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(bytes) } })
            }
            assertEquals(status, response.status)
        }
        assertTrue(port.calls.isEmpty())
    }
    private fun id() = UUID.randomUUID().toString()
}
