package ru.sodovaya.volty.data.social

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.LoginRequest
import ru.sodovaya.volty.domain.social.RegistrationRequest
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SessionCredentials
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.SessionTokenState
import ru.sodovaya.volty.domain.social.VoiceRoomCredentials

class HttpSocialTransportTest {
    @Test
    fun profileResponseWithoutTransportTokenStateBecomesAnActiveSession() = runTest {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = """
                            {"userId":"user-7","displayName":"Rider","emailVerified":false}
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val result = HttpSocialTransport(client, "https://example.test/v1").getProfile("access-token")

        assertEquals(
            SocialResult.Success(
                SocialSession.Authenticated(
                    userId = SocialUserId("user-7"),
                    displayName = "Rider",
                    tokenState = SessionTokenState.ACTIVE,
                    emailVerified = false,
                ),
            ),
            result,
        )
        client.close()
    }

    @Test
    fun registerUsesVersionedHttpsPathAndJsonBody() = runTest {
        var requestData: HttpRequestData? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestData = request
                    respond(
                        content = """
                            {"accessToken":"access","refreshToken":"refresh","expiresAtEpochMillis":1234}
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val result = HttpSocialTransport(client, "https://example.test/v1").register(
            RegistrationRequest("rider@example.com", "password", "Rider"),
        )

        assertEquals(
            SocialResult.Success(SessionCredentials("access", "refresh", 1234)),
            result,
        )
        assertEquals("https://example.test/v1/auth/register", requestData?.url?.toString())
        assertEquals("POST", requestData?.method?.value)
        assertTrue(requestData?.headers?.get(HttpHeaders.Authorization) == null)
        assertTrue((requestData?.body as TextContent).text.contains("\"displayName\":\"Rider\""))
        client.close()
    }

    @Test
    fun authenticatedRequestsUseOnlyTheExplicitBearerToken() = runTest {
        var requestData: HttpRequestData? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestData = request
                    respond(
                        content = "[]",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val result = HttpSocialTransport(client, "https://example.test/v1").listFriends("access-token")

        assertIs<SocialResult.Success<List<FriendSummary>>>(result)
        assertEquals("https://example.test/v1/friends", requestData?.url?.toString())
        assertEquals("Bearer access-token", requestData?.headers?.get(HttpHeaders.Authorization))
        client.close()
    }

    @Test
    fun httpErrorsMapToTypedFailuresAndRetryAfter() = runTest {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = "{\"code\":\"rate_limited\",\"message\":\"slow down\"}",
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.RetryAfter, "17"),
                    )
                }
            }
        }

        val result = HttpSocialTransport(client, "https://example.test/v1").login(
            LoginRequest("rider@example.com", "password"),
        )

        assertEquals(
            SocialResult.Failure(SocialFailure.RateLimited(17)),
            result,
        )
        client.close()
    }

    @Test
    fun joinVoiceUsesAuthenticatedGroupPathAndDecodesCredentials() = runTest {
        var requestData: HttpRequestData? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestData = request
                    respond(
                        content = """
                            {
                              "provider":"livekit",
                              "serverUrl":"wss://voice.example.test",
                              "roomId":"room-7",
                              "participantToken":"secret-token",
                              "expiresAtEpochMillis":4321
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val result = HttpSocialTransport(client, "https://example.test/v1").joinVoice(
            accessToken = "access-token",
            groupId = RideGroupId("group-7"),
        )

        assertEquals(
            SocialResult.Success(
                VoiceRoomCredentials(
                    provider = "livekit",
                    serverUrl = "wss://voice.example.test",
                    roomId = "room-7",
                    participantToken = "secret-token",
                    expiresAtEpochMillis = 4321L,
                ),
            ),
            result,
        )
        assertEquals("https://example.test/v1/groups/group-7/voice/join", requestData?.url?.toString())
        assertEquals("POST", requestData?.method?.value)
        assertEquals("Bearer access-token", requestData?.headers?.get(HttpHeaders.Authorization))
        client.close()
    }
}
