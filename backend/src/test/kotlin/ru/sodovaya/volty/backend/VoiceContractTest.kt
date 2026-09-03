package ru.sodovaya.volty.backend

import com.auth0.jwt.JWT
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VoiceContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun defaultLivekitTokenCoversTheMaximumSharingWindow() {
        val config = AppConfig.fromEnvironment(
            mapOf(
                "VOLTY_JWT_SECRET" to "test-secret-that-is-long-enough-for-hmac",
                "VOLTY_VOICE_PROVIDER" to "livekit",
                "LIVEKIT_URL" to "wss://voice.sodove.ru",
                "LIVEKIT_API_KEY" to "livekit-key",
                "LIVEKIT_API_SECRET" to "livekit-secret",
                "VOLTY_PUBLIC_IP" to "203.0.113.10",
            ),
        )

        assertEquals(AppConfig.MAX_VOICE_TOKEN_TTL_SECONDS, config.voiceTokenTtlSeconds)
    }

    @Test
    fun livekitProviderRequiresFullCredentialSet() {
        val error = assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(
                mapOf(
                    "VOLTY_JWT_SECRET" to "test-secret-that-is-long-enough-for-hmac",
                    "VOLTY_VOICE_PROVIDER" to "livekit",
                    "LIVEKIT_URL" to "wss://voice.sodove.ru",
                    "LIVEKIT_API_KEY" to "livekit-key",
                    "VOLTY_PUBLIC_IP" to "203.0.113.10",
                ),
            )
        }

        assertContains(error.message ?: "", "LIVEKIT_API_SECRET")
    }

    @Test
    fun configuredProviderEndpointAdvertisesLivekitSignalingUrl() = testApplication {
        val store = VoiceStore(groupMemberships = mapOf("group-1" to setOf("user-1")))
        val dependencies = AppDependencies(livekitConfig(), store)
        application { module(dependencies) }

        val response = client.get("/v1/voice/provider") {
            bearerAuth(dependencies.tokenService.issueAccessToken("user-1"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(true, payload["available"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("livekit", payload["provider"]!!.jsonPrimitive.content)
        assertEquals("wss://voice.sodove.ru", payload["serverUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun disabledProviderEndpointStaysUnavailable() = testApplication {
        val store = VoiceStore(groupMemberships = emptyMap())
        val dependencies = AppDependencies(AppConfig.forTests(), store)
        application { module(dependencies) }

        val response = client.get("/v1/voice/provider") {
            bearerAuth(dependencies.tokenService.issueAccessToken("user-1"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, payload["available"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("unconfigured", payload["provider"]!!.jsonPrimitive.content)
        assertEquals("SFU is not configured", payload["message"]!!.jsonPrimitive.content)
        assertEquals(null, payload["serverUrl"])
    }

    @Test
    fun nonMembersCannotJoinConfiguredLivekitRoom() = testApplication {
        val store = VoiceStore(groupMemberships = emptyMap())
        val dependencies = AppDependencies(livekitConfig(), store)
        application { module(dependencies) }

        val response = client.post("/v1/groups/group-1/voice/join") {
            bearerAuth(dependencies.tokenService.issueAccessToken("user-1"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertContains(response.bodyAsText(), "\"code\":\"forbidden\"")
    }

    @Test
    fun groupMembersReceiveBoundedLivekitTokenAndRoomCredentials() = testApplication {
        val ttlSeconds = 120L
        val store = VoiceStore(groupMemberships = mapOf("group-1" to setOf("user-1")))
        val dependencies = AppDependencies(livekitConfig(ttlSeconds), store)
        application { module(dependencies) }

        val beforeJoinMillis = nowMillis()
        val response = client.post("/v1/groups/group-1/voice/join") {
            bearerAuth(dependencies.tokenService.issueAccessToken("user-1"))
        }
        val afterJoinMillis = nowMillis()

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("livekit", payload["provider"]!!.jsonPrimitive.content)
        assertEquals("wss://voice.sodove.ru", payload["serverUrl"]!!.jsonPrimitive.content)

        val roomId = payload["roomId"]!!.jsonPrimitive.content
        assertTrue(roomId.isNotBlank())
        assertNotEquals("voice-provider-unconfigured", roomId)

        val expiresAtEpochMillis = payload["expiresAtEpochMillis"]!!.jsonPrimitive.content.toLong()
        assertTrue(expiresAtEpochMillis >= beforeJoinMillis + ttlSeconds * 1_000)
        assertTrue(expiresAtEpochMillis <= afterJoinMillis + ttlSeconds * 1_000 + 1_000)

        val participantToken = payload["participantToken"]!!.jsonPrimitive.content
        val decoded = JWT.decode(participantToken)
        assertEquals("user-1", decoded.subject)
        assertEquals("Rider One", decoded.getClaim("name").asString())
        val grants = decoded.getClaim("video").asMap()
        assertEquals(true, grants["roomJoin"])
        assertEquals(roomId, grants["room"])
        assertEquals(true, grants["canPublish"])
        assertEquals(true, grants["canSubscribe"])
        assertEquals(listOf("microphone"), grants["canPublishSources"])
        assertTrue(decoded.expiresAt.time >= beforeJoinMillis + ttlSeconds * 1_000 - 1_000)
        assertTrue(decoded.expiresAt.time <= afterJoinMillis + ttlSeconds * 1_000 + 1_000)
    }

    private fun livekitConfig(ttlSeconds: Long = 180): AppConfig = AppConfig.fromEnvironment(
        mapOf(
            "VOLTY_DATABASE_URL" to "jdbc:postgresql://localhost:5432/volty-test",
            "VOLTY_DATABASE_USER" to "volty",
            "VOLTY_DATABASE_PASSWORD" to "test",
            "VOLTY_JWT_SECRET" to "test-secret-that-is-long-enough-for-hmac",
            "VOLTY_VOICE_PROVIDER" to "livekit",
            "LIVEKIT_URL" to "wss://voice.sodove.ru",
            "LIVEKIT_API_KEY" to "livekit-key",
            "LIVEKIT_API_SECRET" to "livekit-secret",
            "VOLTY_VOICE_TOKEN_TTL_SECONDS" to ttlSeconds.toString(),
            "VOLTY_PUBLIC_IP" to "203.0.113.10",
        ),
    )

    private class VoiceStore(
        private val groupMemberships: Map<String, Set<String>>,
    ) : BackendStore {
        private val users = mapOf(
            "user-1" to UserRecord(
                id = "user-1",
                email = "rider@example.com",
                passwordHash = "ignored",
                displayName = "Rider One",
                emailVerified = true,
            )
        )

        override fun findUserById(id: String): UserRecord? = users[id]

        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long): Boolean = users.containsKey(userId)

        override fun isGroupMember(userId: String, groupId: String): Boolean =
            groupMemberships[groupId]?.contains(userId) == true
    }
}
