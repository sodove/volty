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
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.SessionTokenState
import ru.sodovaya.volty.domain.social.VoiceRoomCredentials
import ru.sodovaya.volty.domain.social.VoiceProviderAvailability

class HttpSocialTransportTest {
    @Test
    fun subscriptionTerminatedEventIsDecodedAsTerminalEvent() {
        val event = HttpSocialTransport().decodeLiveEvent(
            jsonForTest("""{"type":"subscription_terminated","reason":"not_member"}"""),
        )

        assertEquals(
            SocialLiveEvent.SubscriptionTerminated("not_member"),
            event,
        )
    }

    @Test
    fun forbiddenHandshakeIsMembershipFailureOnlyWhenErrorCodeSaysSo() {
        assertEquals(
            SocialFailure.Forbidden,
            websocketHandshakeFailure(HttpStatusCode.Forbidden, """{"code":"not_member"}"""),
        )
        assertEquals(
            SocialFailure.Unauthorized,
            websocketHandshakeFailure(HttpStatusCode.Forbidden, """{"code":"invalid_token"}"""),
        )
        assertEquals(
            SocialFailure.Forbidden,
            websocketHandshakeFailure(HttpStatusCode.Forbidden),
        )
        assertEquals(
            SocialFailure.Forbidden,
            websocketHandshakeFailure(HttpStatusCode.Forbidden, """{"code":"unknown"}"""),
        )
    }

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
    fun groupTransportKeepsOwnerInviteCodeAndDoesNotInventOneForMembers() = runTest {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val body = if (request.headers[HttpHeaders.Authorization] == "Bearer owner-token") {
                        """
                        {"id":"group-1","name":"Ride","ownerId":"user-1","members":[],"inviteOnly":true,"inviteExpiresAtEpochMillis":2000,"inviteCode":"ABC123"}
                        """
                    } else {
                        """
                        [{"id":"group-1","name":"Ride","ownerId":"user-1","members":[],"inviteOnly":true,"inviteExpiresAtEpochMillis":2000}]
                        """
                    }
                    respond(
                        content = body.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val transport = HttpSocialTransport(client, "https://example.test/v1")
        val owner = transport.createGroup("owner-token", "Ride")
        val member = transport.listGroups("member-token")

        assertEquals("ABC123", (owner as SocialResult.Success).value.inviteCode)
        assertEquals(null, (member as SocialResult.Success).value.single().inviteCode)
        client.close()
    }

    @Test
    fun groupDeleteAndMemberLeaveUseDifferentHttpContracts() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                        headers = headersOf(),
                    )
                }
            }
        }
        val transport = HttpSocialTransport(client, "https://example.test/v1")

        transport.leaveGroup("access-token", RideGroupId("group-1"))
        transport.deleteGroup("access-token", RideGroupId("group-1"))

        assertEquals("POST", requests[0].method.value)
        assertEquals("https://example.test/v1/groups/group-1/leave", requests[0].url.toString())
        assertEquals("DELETE", requests[1].method.value)
        assertEquals("https://example.test/v1/groups/group-1", requests[1].url.toString())
        client.close()
    }

    @Test
    fun userSearchUsesAuthenticatedQueryAndReturnsOpaqueProfileOnly() = runTest {
        var requestData: HttpRequestData? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestData = request
                    respond(
                        content = """[{"userId":"user-2","displayName":"Passenger","friendshipId":null,"state":null}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val result = HttpSocialTransport(client, "https://example.test/v1").searchUsers("access-token", "passenger")

        assertEquals("https://example.test/v1/users/search?q=passenger", requestData?.url?.toString())
        assertEquals("Bearer access-token", requestData?.headers?.get(HttpHeaders.Authorization))
        assertEquals("user-2", (result as SocialResult.Success).value.single().userId.value)
        assertEquals(null, result.value.single().friendshipId)
        assertEquals(null, result.value.single().state)
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
    fun forbiddenHttpErrorUsesCodeToSeparateAuthRejectionFromMembership() = runTest {
        val responses = ArrayDeque(
            listOf(
                """{"code":"invalid_token"}""" to HttpStatusCode.Forbidden,
                """{"code":"not_member"}""" to HttpStatusCode.Forbidden,
            ),
        )
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    val (body, status) = responses.removeFirst()
                    respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
            }
        }

        assertEquals(SocialResult.Failure(SocialFailure.Unauthorized), client.let {
            HttpSocialTransport(it, "https://example.test/v1").listFriends("access")
        })
        assertEquals(SocialResult.Failure(SocialFailure.Forbidden), client.let {
            HttpSocialTransport(it, "https://example.test/v1").listFriends("access")
        })
        client.close()
    }

    @Test
    fun websocketHandshakeFailuresAreClassifiedAsTerminalLiveEvents() {
        assertEquals(SocialFailure.Unauthorized, websocketHandshakeFailure(HttpStatusCode.Unauthorized))
        assertEquals(SocialFailure.Forbidden, websocketHandshakeFailure(HttpStatusCode.Forbidden))
        assertEquals(SocialFailure.NotFound, websocketHandshakeFailure(HttpStatusCode.NotFound))
        assertEquals(null, websocketHandshakeFailure(HttpStatusCode.ServiceUnavailable))
        assertEquals(SocialFailure.Unauthorized, websocketExceptionFailure("invalid token"))
        assertEquals(SocialFailure.Forbidden, websocketExceptionFailure("group membership required"))
    }

    private fun jsonForTest(value: String) = kotlinx.serialization.json.Json.parseToJsonElement(value)

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

    @Test
    fun voiceProviderAvailabilityIsAuthenticatedAndCanAdvertiseUnavailable() = runTest {
        var requestData: HttpRequestData? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestData = request
                    respond(
                        content = """{"available":false,"provider":"unconfigured","message":"SFU is not configured"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val result = HttpSocialTransport(client, "https://example.test/v1").getVoiceProvider("access-token")

        assertEquals(
            SocialResult.Success(
                VoiceProviderAvailability(false, "unconfigured", message = "SFU is not configured"),
            ),
            result,
        )
        assertEquals("https://example.test/v1/voice/provider", requestData?.url?.toString())
        assertEquals("Bearer access-token", requestData?.headers?.get(HttpHeaders.Authorization))
        client.close()
    }

    @Test
    fun renewSharingUsesDedicatedPathAndDoesNotResendTelemetryProfilePayload() = runTest {
        var requestData: HttpRequestData? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestData = request
                    respond(
                        content = """{"groupId":"group-7","profile":"LOCATION","expiresAtEpochMillis":9999}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val result = HttpSocialTransport(client, "https://example.test/v1").renewSharing(
            accessToken = "access-token",
            request = ru.sodovaya.volty.domain.social.ShareSessionRequest(
                groupId = RideGroupId("group-7"),
                profile = ru.sodovaya.volty.domain.social.TelemetryShareProfile.LOCATION,
                ttlMillis = 60_000L,
                startedAtEpochMillis = 1_000L,
            ),
        )

        assertEquals("https://example.test/v1/groups/group-7/sharing/renew", requestData?.url?.toString())
        assertEquals("POST", requestData?.method?.value)
        assertTrue((requestData?.body as TextContent).text.contains("ttlMillis"))
        assertTrue(!(requestData?.body as TextContent).text.contains("profile"))
        assertEquals(9999L, (result as SocialResult.Success).value.expiresAtEpochMillis)
        client.close()
    }
}
