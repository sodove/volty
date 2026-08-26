package ru.sodovaya.volty.backend

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupEndpointContractTest {
    @Test
    fun deleteIsOwnerOnlyAndAllMembersCanLeaveWithoutDeleting() = testApplication {
        val store = GroupStore()
        val dependencies = AppDependencies(AppConfig.forTests(), store, testMode = true)
        application { module(dependencies) }

        val ownerDelete = client.delete("/v1/groups/group-1") {
            bearerAuth(dependencies.tokenService.issueAccessToken("owner"))
        }
        val memberDelete = client.delete("/v1/groups/group-1") {
            bearerAuth(dependencies.tokenService.issueAccessToken("member"))
        }
        val memberLeave = client.post("/v1/groups/group-1/leave") {
            bearerAuth(dependencies.tokenService.issueAccessToken("member"))
        }
        val ownerLeave = client.post("/v1/groups/group-1/leave") {
            bearerAuth(dependencies.tokenService.issueAccessToken("owner"))
        }

        assertEquals(HttpStatusCode.NoContent, ownerDelete.status)
        assertEquals("owner", store.deletedBy)
        assertEquals(HttpStatusCode.Forbidden, memberDelete.status)
        assertEquals(2, store.deleteCalls)
        assertEquals(HttpStatusCode.OK, memberLeave.status)
        assertEquals(HttpStatusCode.Forbidden, ownerLeave.status)
        assertEquals("member", store.leftBy)
        assertEquals(1, store.leaveCalls)
    }

    @Test
    fun soleOwnerCannotLeaveAndMakeTheGroupInaccessible() = testApplication {
        val store = GroupStore().also { it.memberIds.clear() }
        val dependencies = AppDependencies(AppConfig.forTests(), store, testMode = true)
        application { module(dependencies) }

        val response = client.post("/v1/groups/group-1/leave") {
            bearerAuth(dependencies.tokenService.issueAccessToken("owner"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("group_owner_cannot_leave"))
        assertEquals(0, store.leaveCalls)
    }

    @Test
    fun repeatedJoinReturnsTheSameGroupWithOneMembership() = testApplication {
        val store = GroupStore()
        val dependencies = AppDependencies(AppConfig.forTests(), store, testMode = true)
        application { module(dependencies) }
        val token = dependencies.tokenService.issueAccessToken("member")

        val first = client.post("/v1/groups/join") {
            bearerAuth(token)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"inviteCode":"INVITE-1"}""")
        }
        val second = client.post("/v1/groups/join") {
            bearerAuth(token)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"inviteCode":"INVITE-1"}""")
        }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(1, store.memberIds.count { it == "member" })
        val firstPayload = Json.parseToJsonElement(first.bodyAsText()).jsonObject
        val secondPayload = Json.parseToJsonElement(second.bodyAsText()).jsonObject
        assertEquals(firstPayload["id"], secondPayload["id"])
        assertEquals(firstPayload["members"], secondPayload["members"])
        assertEquals(2, secondPayload["members"]!!.jsonArray.size)
    }

    private class GroupStore : BackendStore {
        private val users = setOf("owner", "member")
        val memberIds = mutableListOf<String>()
        var deletedBy: String? = null
        var leftBy: String? = null
        var deleteCalls: Int = 0
        var leaveCalls: Int = 0
        var soleOwner: Boolean = false

        override fun findUserById(id: String): UserRecord? = id.takeIf(users::contains)?.let {
            UserRecord(it, "$it@example.com", "ignored", it, true)
        }

        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long): Boolean = users.contains(userId)

        override fun deleteGroup(userId: String, groupId: String) {
            deleteCalls += 1
            if (userId != "owner") {
                throw GroupOwnerRequiredException()
            }
            deletedBy = userId
        }

        override fun leaveGroup(userId: String, groupId: String) {
            if (userId == "owner") {
                throw GroupOwnerCannotLeaveException()
            }
            leaveCalls += 1
            leftBy = userId
        }

        override fun joinGroup(userId: String, inviteCode: String, now: Long): GroupDto {
            if (inviteCode != "INVITE-1") throw NoSuchElementException("invite not found or expired")
            if (userId !in memberIds) memberIds += userId
            return GroupDto(
                id = "group-1",
                name = "Night Ride",
                ownerId = "owner",
                members = listOf(
                    GroupMemberDto("owner", "owner", "OWNER"),
                    GroupMemberDto("member", "member", "MEMBER"),
                ).filter { it.userId == "owner" || it.userId in memberIds },
                inviteExpiresAtEpochMillis = now + 60_000,
            )
        }
    }
}
