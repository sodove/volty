package ru.sodovaya.volty.backend

import io.ktor.client.request.get
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiContractTest {
    @Test
    fun healthIsPublicAndErrorEnvelopeIsTyped() = testApplication {
        application {
            module(AppDependencies.forTests())
        }

        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertTrue(health.bodyAsText().contains("\"status\":\"ok\""))

        val missing = client.get("/v1/profile")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("application/json; charset=UTF-8", missing.headers[HttpHeaders.ContentType])
        assertTrue(missing.bodyAsText().contains("\"code\":\"unauthorized\""))
        assertTrue(missing.bodyAsText().contains("requestId"))
    }

    @Test
    fun registrationLoginProfileAndRefreshUseClientShapedFields() = testApplication {
        val store = FakeStore()
        application { module(AppDependencies(AppConfig.forTests(), store)) }
        val json = Json { ignoreUnknownKeys = true }
        val jsonClient = createClient {
            install(ContentNegotiation) { json(json) }
        }

        val registered = jsonClient.post("/v1/auth/register") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"email":"rider@example.com","password":"correct horse battery staple","displayName":"Rider"}""")
        }
        assertEquals(HttpStatusCode.Created, registered.status)
        val session = json.decodeFromString<SessionResponse>(registered.bodyAsText())
        assertTrue(session.expiresAtEpochMillis in (System.currentTimeMillis()..(System.currentTimeMillis() + 20 * 60_000L)))

        val profile = jsonClient.get("/v1/profile") { bearerAuth(session.accessToken) }
        assertEquals(HttpStatusCode.OK, profile.status)
        assertTrue(profile.bodyAsText().contains("\"displayName\":\"Rider\""))

        val refreshed = jsonClient.post("/v1/auth/refresh") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(RefreshRequest(session.refreshToken)))
        }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        assertTrue(refreshed.bodyAsText().contains("accessToken"))

        // Retraction: this test used to require an immediate replay to revoke everything.
        // That replay is exactly what a phone sends when the rotated response was lost, and it
        // logged a rider out in the field. A retry while the successor is unused is accepted.
        val retried = jsonClient.post("/v1/auth/refresh") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(RefreshRequest(session.refreshToken)))
        }
        assertEquals(HttpStatusCode.OK, retried.status)
        val retry = json.decodeFromString<SessionResponse>(retried.bodyAsText())
        val lost = json.decodeFromString<SessionResponse>(refreshed.bodyAsText())
        assertTrue(retry.refreshToken != lost.refreshToken)

        // The token the retry handed out is the live one and keeps rotating normally.
        val next = jsonClient.post("/v1/auth/refresh") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(RefreshRequest(retry.refreshToken)))
        }
        assertEquals(HttpStatusCode.OK, next.status)
        // The original token's successor was already used by the retry: a second replay of it
        // is reuse even inside the window.
        val reused = jsonClient.post("/v1/auth/refresh") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(RefreshRequest(session.refreshToken)))
        }
        assertEquals(HttpStatusCode.Unauthorized, reused.status)
        assertTrue(reused.bodyAsText().contains("refresh_reuse"))
        // Reuse revokes the whole account, including the live token.
        val live = json.decodeFromString<SessionResponse>(next.bodyAsText())
        val afterTheft = jsonClient.post("/v1/auth/refresh") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(RefreshRequest(live.refreshToken)))
        }
        assertEquals(HttpStatusCode.Unauthorized, afterTheft.status)
    }

    @Test
    fun replayOfARotatedTokenAfterTheGraceWindowRevokesTheAccount() = testApplication {
        val store = FakeStore()
        application { module(AppDependencies(AppConfig.forTests(), store)) }
        val json = Json { ignoreUnknownKeys = true }
        val jsonClient = createClient { install(ContentNegotiation) { json(json) } }
        val registered = jsonClient.post("/v1/auth/register") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"email":"rider@example.com","password":"correct horse battery staple","displayName":"Rider"}""")
        }
        val session = json.decodeFromString<SessionResponse>(registered.bodyAsText())
        val refreshed = jsonClient.post("/v1/auth/refresh") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(RefreshRequest(session.refreshToken)))
        }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        store.ageRevocations(REFRESH_RETRY_GRACE_SECONDS + 1)
        val replay = jsonClient.post("/v1/auth/refresh") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(json.encodeToString(RefreshRequest(session.refreshToken)))
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertTrue(replay.bodyAsText().contains("refresh_reuse"))
    }

    private class FakeStore : BackendStore {
        private val users = mutableMapOf<String, UserRecord>()
        private val refresh = mutableMapOf<String, RefreshRow>()
        private val revokedAt = mutableMapOf<String, Long>()

        override fun createUser(email: String, passwordHash: String, displayName: String, now: Long): UserRecord {
            val user = UserRecord("user-1", email, passwordHash, displayName, false)
            users[user.id] = user
            return user
        }

        override fun findUserById(id: String): UserRecord? = users[id]
        override fun findUserByEmail(email: String): UserRecord? = users.values.singleOrNull { it.email == email }
        override fun createOneTimeToken(userId: String, purpose: String, hash: String, expiresAt: Long): Int = 1

        override fun insertRefreshToken(userId: String, tokenHash: String, expiresAt: Long, now: Long): String {
            val id = "refresh-${refresh.size}"
            refresh[tokenHash] = RefreshRow(id, userId, tokenHash, expiresAt, null)
            return id
        }

        override fun findRefreshToken(hash: String): RefreshRow? = refresh[hash]
        override fun rotateRefreshToken(id: String, replacementHash: String, now: Long): Boolean {
            val current = refresh.values.singleOrNull { it.id == id } ?: return false
            if (current.revokedAtEpochSeconds != null) return false
            refresh[current.tokenHash] = current.copy(revokedAtEpochSeconds = now, replacedByHash = replacementHash)
            return true
        }

        fun ageRevocations(seconds: Long) {
            refresh.entries.forEach { (hash, row) ->
                row.revokedAtEpochSeconds?.let { refresh[hash] = row.copy(revokedAtEpochSeconds = it - seconds) }
            }
        }

        override fun revokeAllUserTokens(userId: String, nowEpochSeconds: Long) {
            revokedAt[userId] = nowEpochSeconds
            refresh.entries.filter { it.value.userId == userId }.forEach { (hash, row) -> refresh[hash] = row.copy(revokedAtEpochSeconds = nowEpochSeconds) }
        }

        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long): Boolean = users.containsKey(userId) && issuedAtEpochSeconds >= (revokedAt[userId] ?: 0)
    }
}
