package ru.sodovaya.volty.backend

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SocialInvariantsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun rejectingARequestRemovesItAndAllowsTheSameRequestAgain() = testApplication {
        val store = FriendStore()
        val dependencies = AppDependencies(AppConfig.forTests(), store, testMode = true)
        application { module(dependencies) }
        val addressee = dependencies.tokenService.issueAccessToken("user-2")
        val requester = dependencies.tokenService.issueAccessToken("user-1")

        val rejected = client.post("/v1/friends/requests/friendship-1/respond") {
            bearerAuth(addressee)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"accept\":false}")
        }
        val retried = client.post("/v1/friends/requests") {
            bearerAuth(requester)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"userId\":\"user-2\"}")
        }

        assertEquals(HttpStatusCode.OK, rejected.status)
        assertEquals("DECLINED", json.parseToJsonElement(rejected.bodyAsText()).jsonObject["state"]!!.toString().trim('"'))
        assertEquals(HttpStatusCode.Created, retried.status)
        assertEquals("REQUEST_SENT", json.parseToJsonElement(retried.bodyAsText()).jsonObject["state"]!!.toString().trim('"'))
        assertEquals(1, store.createCalls)
    }

    @Test
    fun respondingToTheSameRequestTwiceReturnsTheSameTypedResult() = testApplication {
        val store = FriendStore()
        val dependencies = AppDependencies(AppConfig.forTests(), store, testMode = true)
        application { module(dependencies) }
        val token = dependencies.tokenService.issueAccessToken("user-2")

        val first = client.post("/v1/friends/requests/friendship-1/respond") {
            bearerAuth(token)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"accept\":true}")
        }
        val second = client.post("/v1/friends/requests/friendship-1/respond") {
            bearerAuth(token)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{\"accept\":true}")
        }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(first.bodyAsText(), second.bodyAsText())
        assertEquals("ACCEPTED", json.parseToJsonElement(second.bodyAsText()).jsonObject["state"]!!.toString().trim('"'))
        assertEquals("friendship-1", json.parseToJsonElement(second.bodyAsText()).jsonObject["friendshipId"]!!.toString().trim('"'))
    }

    @Test
    fun liveSnapshotKeepsRosterMembersWithoutAStoredLocation() {
        val snapshot = LiveSnapshotDto(
            groupId = "group-1",
            capturedAtEpochMillis = 1000L,
            participants = listOf(
                ParticipantDto("user-1", "Rider", "ONLINE", LocationDto(56.8, 60.6, 5.0, 1000L, 16000L), null, 1000L),
                ParticipantDto("user-2", "Passenger", "OFFLINE", null, null, 0L),
            ),
        )

        assertEquals(2, snapshot.participants.size)
        assertNull(snapshot.participants.single { it.userId == "user-2" }.location)
    }

    private class FriendStore : BackendStore {
        private val users = setOf("user-1", "user-2")
        private var state = "PENDING"
        var createCalls = 0

        override fun findUserById(id: String): UserRecord? = id.takeIf(users::contains)?.let {
            UserRecord(it, "$it@example.com", "ignored", it, true)
        }

        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long): Boolean = users.contains(userId)

        override fun createFriendRequest(requesterId: String, addresseeId: String, now: Long): FriendRequestResultDto {
            createCalls += 1
            state = "PENDING"
            return FriendRequestResultDto("friendship-1", "REQUEST_SENT")
        }

        override fun respondToFriendRequest(userId: String, friendshipId: String, accept: Boolean): FriendRequestResultDto {
            if (accept) state = "ACCEPTED" else state = "DECLINED"
            return FriendRequestResultDto(friendshipId, state)
        }
    }
}
