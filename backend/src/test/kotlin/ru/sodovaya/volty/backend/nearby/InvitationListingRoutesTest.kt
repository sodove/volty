package ru.sodovaya.volty.backend.nearby

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*

class InvitationListingRoutesTest {
    private val actor = id(); private val community = id(); private val invite = id(); private val after = "abcdef00-0000-0000-0000-000000000001"
    private val base = "/v2/nearby/communities/$community/invitations"
    private fun dependencies(port: InvitationEndpointPort? = null) = AppDependencies(AppConfig.forTests(), object : BackendStore {
        override fun findUserById(id: String) = UserRecord(actor, "test@example.invalid", "ignored", "Test", true).takeIf { id == actor }
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long) = userId == actor
    }, testMode = true, nearbyInvitations = port)
    private inner class Recording : InvitationEndpointPort {
        val calls = mutableListOf<String>()
        var result: InvitationListingResult = InvitationListingResult.Listed(listOf(InvitationSummary(invite, community, 100, 900100, 10, 2, 101, 7, 8)))
        override fun list(actor: String, community: String, after: String?, limit: Int): InvitationListingResult {
            calls += "$actor:$community:$after:$limit"; return result
        }
        override fun create(actor: String, community: String, invitation: String, token: String, ttlMillis: Long, maxUses: Int) = InvitationCreationResult.Denied
        override fun revoke(actor: String, community: String, invitation: String) = InvitationRevocationResult.Denied
        override fun accept(actor: String, operation: String, token: String) = InvitationAcceptanceResult.Denied
    }
    @Test fun listRequiresAuthenticationAndOptionalDependency() = testApplication {
        val deps = dependencies(); application { module(deps) }
        assertEquals(HttpStatusCode.Unauthorized, client.get(base).status)
        val response = client.get(base) { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status); assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }
    @Test fun listUsesAuthenticatedOwnerCursorAndMetadataOnlyWhileDenialIsGeneric() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        val response = client.get("$base?after=$after&limit=2") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.OK, response.status); assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("items"), root.keys)
        val metadata = root.getValue("items").jsonArray.single().jsonObject
        assertFalse(metadata.containsKey("token")); assertFalse(metadata.containsKey("tokenHash"))
        assertEquals(invite, metadata.getValue("invitationId").jsonPrimitive.content)
        assertEquals(101, metadata.getValue("revokedAtMillis").jsonPrimitive.long)
        assertEquals(listOf("$actor:$community:$after:2"), port.calls)
        port.result = InvitationListingResult.Denied
        for (resource in listOf(community, id())) {
            val denied = client.get("/v2/nearby/communities/$resource/invitations") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
            assertEquals(HttpStatusCode.NotFound, denied.status)
            assertEquals("invitation_unavailable", Json.parseToJsonElement(denied.bodyAsText()).jsonObject.getValue("code").jsonPrimitive.content)
        }
        assertEquals(3, port.calls.size)
    }
    @Test fun malformedAndDuplicatePageArgumentsNeverInvokeOwnerRead() = testApplication {
        val port = Recording(); val deps = dependencies(port); application { module(deps) }
        for (query in listOf("after=bad", "after=${after.uppercase()}", "after=$after&after=$after", "limit=0", "limit=51", "limit=no", "limit=1&limit=2"))
            assertEquals(HttpStatusCode.BadRequest, client.get("$base?$query") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }.status)
        assertTrue(port.calls.isEmpty())
    }
    private fun id() = UUID.randomUUID().toString()
}
