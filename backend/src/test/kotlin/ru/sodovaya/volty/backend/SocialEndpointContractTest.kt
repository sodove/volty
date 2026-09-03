package ru.sodovaya.volty.backend

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocialEndpointContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun groupInviteCodeIsOwnerOnlyAndSearchReturnsNoEmail() = testApplication {
        val store = ContractStore()
        val dependencies = AppDependencies(AppConfig.forTests(), store, testMode = true)
        application { module(dependencies) }

        val owner = client.get("/v1/groups") {
            bearerAuth(dependencies.tokenService.issueAccessToken("user-1"))
        }
        val member = client.get("/v1/groups") {
            bearerAuth(dependencies.tokenService.issueAccessToken("user-2"))
        }
        val search = client.get("/v1/users/search?q=Rider") {
            bearerAuth(dependencies.tokenService.issueAccessToken("user-2"))
        }

        assertEquals(HttpStatusCode.OK, owner.status)
        assertEquals("INVITE-SECRET", json.parseToJsonElement(owner.bodyAsText()).jsonArray[0].jsonObject["inviteCode"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, member.status)
        assertEquals(null, json.parseToJsonElement(member.bodyAsText()).jsonArray[0].jsonObject["inviteCode"])
        assertEquals(HttpStatusCode.OK, search.status)
        assertTrue(!search.bodyAsText().contains("email"))
        assertEquals("user-1", json.parseToJsonElement(search.bodyAsText()).jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content)
    }

    private class ContractStore : BackendStore {
        private val users = mapOf(
            "user-1" to UserRecord("user-1", "rider@example.com", "ignored", "Rider", true),
            "user-2" to UserRecord("user-2", "passenger@example.com", "ignored", "Passenger", true),
        )

        override fun findUserById(id: String): UserRecord? = users[id]
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long): Boolean = users.containsKey(userId)
        override fun listGroups(userId: String): List<GroupDto> = listOf(
            GroupDto(
                id = "group-1",
                name = "Ride",
                ownerId = "user-1",
                members = listOf(GroupMemberDto("user-1", "Rider", "OWNER"), GroupMemberDto("user-2", "Passenger", "MEMBER")),
                inviteExpiresAtEpochMillis = 2_000L,
                inviteCode = "INVITE-SECRET".takeIf { userId == "user-1" },
            ),
        )

        override fun searchUsers(userId: String, query: String, limit: Int): List<UserSearchResultDto> = listOf(
            UserSearchResultDto(userId = "user-1", displayName = "Rider"),
        )
    }
}
