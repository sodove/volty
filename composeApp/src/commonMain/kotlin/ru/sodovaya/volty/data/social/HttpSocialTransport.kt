package ru.sodovaya.volty.data.social

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.encodeToString
import ru.sodovaya.volty.domain.social.FriendRequest
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.LoginRequest
import ru.sodovaya.volty.domain.social.ParticipantShareUpdate
import ru.sodovaya.volty.domain.social.ProfileUpdate
import ru.sodovaya.volty.domain.social.RegistrationRequest
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SessionCredentials
import ru.sodovaya.volty.domain.social.ShareSessionRequest
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.VoiceRoomCredentials
import ru.sodovaya.volty.domain.social.VoiceProviderAvailability
import ru.sodovaya.volty.domain.social.UserSearchResult

/**
 * The concrete REST/WebSocket boundary for the social contracts.
 *
 * The repository supplies access tokens for every authenticated operation. This
 * class does not retain credentials, add them to URLs, or log request data.
 */
class HttpSocialTransport(
    private val client: HttpClient = HttpClient { install(WebSockets) },
    baseUrl: String = DEFAULT_BASE_URL,
) : ru.sodovaya.volty.domain.social.SocialTransport {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun register(request: RegistrationRequest): SocialResult<SessionCredentials> =
        decode(requestJson(HttpMethod.Post, "auth/register", body = json.encodeToString(RegisterDto.from(request)))) {
            decodeCredentials(it)
        }

    override suspend fun login(request: LoginRequest): SocialResult<SessionCredentials> =
        decode(requestJson(HttpMethod.Post, "auth/login", body = json.encodeToString(LoginDto.from(request)))) {
            decodeCredentials(it)
        }

    override suspend fun refreshSession(refreshToken: String): SocialResult<SessionCredentials> =
        decode(requestJson(HttpMethod.Post, "auth/refresh", body = json.encodeToString(RefreshDto(refreshToken)))) {
            decodeCredentials(it)
        }

    override suspend fun logout(accessToken: String): SocialResult<Unit> =
        unit(requestJson(HttpMethod.Post, "auth/logout", accessToken))

    override suspend fun getProfile(accessToken: String): SocialResult<SocialSession.Authenticated> =
        decode(requestJson(HttpMethod.Get, "profile", accessToken), ::decodeProfile)

    override suspend fun verifyEmail(token: String): SocialResult<Unit> =
        unit(requestJson(HttpMethod.Post, "auth/verify", body = json.encodeToString(TokenDto(token))))

    override suspend fun requestPasswordReset(email: String): SocialResult<Unit> =
        unit(requestJson(HttpMethod.Post, "auth/password-reset/request", body = json.encodeToString(EmailDto(email))))

    override suspend fun resetPassword(token: String, newPassword: String): SocialResult<Unit> =
        unit(
            requestJson(
                HttpMethod.Post,
                "auth/password-reset",
                body = json.encodeToString(ResetPasswordDto(token, newPassword)),
            ),
        )

    override suspend fun deleteAccount(accessToken: String): SocialResult<Unit> =
        unit(requestJson(HttpMethod.Delete, "account", accessToken))

    override suspend fun updateProfile(
        accessToken: String,
        request: ProfileUpdate,
    ): SocialResult<SocialSession.Authenticated> =
        decode(
            requestJson(
                HttpMethod.Patch,
                "profile",
                accessToken,
                json.encodeToString(ProfileUpdateDto(request.displayName)),
            ),
            ::decodeProfile,
        )

    override suspend fun listFriends(accessToken: String): SocialResult<List<FriendSummary>> =
        decode(requestJson(HttpMethod.Get, "friends", accessToken))

    override suspend fun sendFriendRequest(
        accessToken: String,
        request: FriendRequest,
    ): SocialResult<Unit> =
        unit(
            requestJson(
                HttpMethod.Post,
                "friends/requests",
                accessToken,
                json.encodeToString(FriendRequestDto(request.userId)),
            ),
        )

    override suspend fun respondToFriendRequest(
        accessToken: String,
        friendshipId: String,
        accept: Boolean,
    ): SocialResult<Unit> =
        unit(
            requestJson(
                HttpMethod.Post,
                "friends/requests/${friendshipId.pathSegment()}/respond",
                accessToken,
                json.encodeToString(FriendResponseDto(accept)),
            ),
        )

    override suspend fun searchUsers(
        accessToken: String,
        query: String,
    ): SocialResult<List<UserSearchResult>> =
        decode(requestJson(HttpMethod.Get, "users/search?q=${query.encodeUrlComponent()}", accessToken))

    override suspend fun listGroups(accessToken: String): SocialResult<List<RideGroup>> =
        decode(requestJson(HttpMethod.Get, "groups", accessToken))

    override suspend fun createGroup(accessToken: String, name: String): SocialResult<RideGroup> =
        decode(
            requestJson(
                HttpMethod.Post,
                "groups",
                accessToken,
                json.encodeToString(CreateGroupDto(name)),
            ),
        )

    override suspend fun joinGroup(accessToken: String, inviteCode: String): SocialResult<RideGroup> =
        decode(
            requestJson(
                HttpMethod.Post,
                "groups/join",
                accessToken,
                json.encodeToString(JoinGroupDto(inviteCode)),
            ),
        )

    override suspend fun leaveGroup(accessToken: String, groupId: RideGroupId): SocialResult<Unit> =
        unit(requestJson(HttpMethod.Post, "groups/${groupId.value.pathSegment()}/leave", accessToken))

    override suspend fun deleteGroup(accessToken: String, groupId: RideGroupId): SocialResult<Unit> =
        unit(requestJson(HttpMethod.Delete, "groups/${groupId.value.pathSegment()}", accessToken))

    override fun observeGroup(accessToken: String, groupId: RideGroupId): Flow<SocialLiveEvent> =
        observeGroup(groupId) { accessToken }

    override fun observeGroup(
        groupId: RideGroupId,
        accessTokenProvider: suspend () -> String?,
    ): Flow<SocialLiveEvent> = flow {
        var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
        while (currentCoroutineContext().isActive) {
            try {
                val accessToken = accessTokenProvider()
                if (accessToken == null) {
                    emit(SocialLiveEvent.Failure(SocialFailure.Unauthorized))
                    return@flow
                }
                val session = client.webSocketSession {
                    url(webSocketUrl(groupId))
                    header(HttpHeaders.Authorization, bearer(accessToken))
                }
                retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
                try {
                    for (frame in session.incoming) {
                        if (frame is Frame.Text) {
                            emit(decodeLiveEvent(json.parseToJsonElement(frame.readText())))
                        }
                    }
                } finally {
                    session.close()
                }
                if (currentCoroutineContext().isActive) {
                    emit(SocialLiveEvent.Failure(SocialFailure.Network("Live group connection closed")))
                    delay(retryDelayMillis)
                    retryDelayMillis = nextRetryDelay(retryDelayMillis)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ResponseException) {
                val failure = websocketHandshakeFailure(error.response.status, error.response.bodyAsText())
                if (failure != null) {
                    emit(SocialLiveEvent.Failure(failure))
                    return@flow
                }
                emit(SocialLiveEvent.Failure(SocialFailure.Network("Live group connection unavailable")))
                delay(retryDelayMillis)
                retryDelayMillis = nextRetryDelay(retryDelayMillis)
            } catch (error: Exception) {
                val failure = websocketExceptionFailure(error.message)
                if (failure != null) {
                    emit(SocialLiveEvent.Failure(failure))
                    return@flow
                }
                emit(SocialLiveEvent.Failure(SocialFailure.Network("Live group connection unavailable")))
                delay(retryDelayMillis)
                retryDelayMillis = nextRetryDelay(retryDelayMillis)
            }
        }
    }

    override suspend fun startSharing(
        accessToken: String,
        request: ShareSessionRequest,
    ): SocialResult<SharingSession> =
        decode(
            requestJson(
                HttpMethod.Post,
                "groups/${request.groupId.value.pathSegment()}/sharing",
                accessToken,
                json.encodeToString(ShareSessionDto.from(request)),
            ),
        )

    override suspend fun renewSharing(
        accessToken: String,
        request: ShareSessionRequest,
    ): SocialResult<SharingSession> =
        decode(
            requestJson(
                HttpMethod.Post,
                "groups/${request.groupId.value.pathSegment()}/sharing/renew",
                accessToken,
                json.encodeToString(RenewShareSessionDto.from(request)),
            ),
        )

    override suspend fun publishSharingUpdate(
        accessToken: String,
        groupId: RideGroupId,
        update: ParticipantShareUpdate,
    ): SocialResult<Unit> =
        unit(
            requestJson(
                HttpMethod.Post,
                "groups/${groupId.value.pathSegment()}/sharing/update",
                accessToken,
                json.encodeToString(update),
            ),
        )

    override suspend fun stopSharing(accessToken: String, groupId: RideGroupId): SocialResult<Unit> =
        unit(requestJson(HttpMethod.Delete, "groups/${groupId.value.pathSegment()}/sharing", accessToken))

    override suspend fun joinVoice(accessToken: String, groupId: RideGroupId): SocialResult<VoiceRoomCredentials> =
        decode(
            requestJson(
                HttpMethod.Post,
                "groups/${groupId.value.pathSegment()}/voice/join",
                accessToken,
            ),
        ) { decodeVoiceRoomCredentials(it) }

    override suspend fun getVoiceProvider(accessToken: String): SocialResult<VoiceProviderAvailability> =
        decode(requestJson(HttpMethod.Get, "voice/provider", accessToken), ::decodeVoiceProviderAvailability)

    override suspend fun leaveVoice(accessToken: String, groupId: RideGroupId): SocialResult<Unit> =
        unit(requestJson(HttpMethod.Post, "groups/${groupId.value.pathSegment()}/voice/leave", accessToken))

    private suspend fun requestJson(
        method: HttpMethod,
        path: String,
        accessToken: String? = null,
        body: String? = null,
    ): SocialResult<JsonElement?> {
        return try {
            val response = client.request(url(path)) {
                this.method = method
                accessToken?.let { header(HttpHeaders.Authorization, bearer(it)) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            if (!response.status.isSuccess()) {
                failure(response)
            } else {
                val text = response.bodyAsText()
                if (text.isBlank()) {
                    SocialResult.Success(null)
                } else {
                    SocialResult.Success(unwrap(json.parseToJsonElement(text)))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SocialResult.Failure(SocialFailure.Network("Social network request failed"))
        }
    }

    private suspend fun failure(response: HttpResponse): SocialResult<JsonElement?> {
        val errorBody = response.bodyAsText()
        val errorJson = runCatching { json.parseToJsonElement(errorBody) }.getOrNull()
        val message = errorJson?.let(::errorMessage)
        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
            ?: errorJson?.let(::retryAfterSeconds)
        val error = when (response.status) {
            HttpStatusCode.Unauthorized -> SocialFailure.Unauthorized
            HttpStatusCode.Forbidden -> {
                val code = (errorJson as? JsonObject)?.string("code", "reason", "error")?.lowercase()
                if (code.isAuthRejection()) SocialFailure.Unauthorized else SocialFailure.Forbidden
            }
            HttpStatusCode.NotFound -> SocialFailure.NotFound
            HttpStatusCode.Conflict -> SocialFailure.Conflict
            HttpStatusCode.TooManyRequests -> SocialFailure.RateLimited(retryAfter)
            in CLIENT_ERROR_STATUSES -> SocialFailure.InvalidRequest(message ?: "Invalid social request")
            else -> SocialFailure.Server(message ?: "Social server request failed")
        }
        return SocialResult.Failure(error)
    }

    private inline fun <reified T> decode(
        result: SocialResult<JsonElement?>,
        crossinline mapper: (JsonElement) -> T = { json.decodeFromJsonElement(it) },
    ): SocialResult<T> = when (result) {
        is SocialResult.Failure -> result
        is SocialResult.Success -> result.value?.let { element ->
            runCatching { SocialResult.Success(mapper(element)) }
                .getOrElse { SocialResult.Failure(SocialFailure.Server("Malformed social server response")) }
        } ?: SocialResult.Failure(SocialFailure.Server("Empty social server response"))
    }

    private fun unit(result: SocialResult<JsonElement?>): SocialResult<Unit> = when (result) {
        is SocialResult.Failure -> result
        is SocialResult.Success -> SocialResult.Success(Unit)
    }

    private fun decodeCredentials(element: JsonElement): SessionCredentials {
        val source = element.jsonObject.let { it["tokens"]?.jsonObject ?: it }
        return SessionCredentials(
            accessToken = source.requiredString("accessToken", "access_token", "token"),
            refreshToken = source.requiredString("refreshToken", "refresh_token"),
            expiresAtEpochMillis = source.requiredLong("expiresAtEpochMillis", "expires_at_epoch_millis"),
        )
    }

    private fun decodeProfile(element: JsonElement): SocialSession.Authenticated {
        val source = element.jsonObject
        return SocialSession.Authenticated(
            userId = ru.sodovaya.volty.domain.social.SocialUserId(source.requiredString("userId", "user_id")),
            displayName = source.requiredString("displayName", "display_name"),
            tokenState = ru.sodovaya.volty.domain.social.SessionTokenState.ACTIVE,
            emailVerified = source["emailVerified"]?.jsonPrimitive?.booleanOrNull
                ?: source["email_verified"]?.jsonPrimitive?.booleanOrNull
                ?: false,
        )
    }

    private fun decodeVoiceRoomCredentials(element: JsonElement): VoiceRoomCredentials {
        val source = element.jsonObject
        return VoiceRoomCredentials(
            provider = source.requiredString("provider"),
            serverUrl = source.requiredString("serverUrl", "server_url"),
            roomId = source.requiredString("roomId", "room_id"),
            participantToken = source.requiredString("participantToken", "participant_token"),
            expiresAtEpochMillis = source.requiredLong("expiresAtEpochMillis", "expires_at_epoch_millis"),
        )
    }

    private fun decodeVoiceProviderAvailability(element: JsonElement): VoiceProviderAvailability {
        val source = element.jsonObject
        return VoiceProviderAvailability(
            available = source["available"]?.jsonPrimitive?.booleanOrNull ?: false,
            provider = source.requiredString("provider"),
            serverUrl = source.string("serverUrl", "server_url"),
            message = source.string("message"),
        )
    }

    internal fun decodeLiveEvent(element: JsonElement): SocialLiveEvent {
        val objectValue = element as? JsonObject
        val type = objectValue?.string("type", "kind", "event")?.lowercase()
        return when (type) {
            "share_revoked", "revoked" ->
                SocialLiveEvent.ShareRevoked(objectValue.requiredSocialUserId())
            "share_expired", "expired" ->
                SocialLiveEvent.ShareExpired(objectValue.requiredSocialUserId())
            "subscription_terminated", "terminated" ->
                SocialLiveEvent.SubscriptionTerminated(objectValue.string("reason", "message"))
            "error", "failure" ->
                SocialLiveEvent.Failure(objectValue.liveFailure())
            else -> {
                val snapshotElement = objectValue?.get("snapshot")
                    ?: objectValue?.get("data")
                    ?: element
                runCatching {
                    SocialLiveEvent.Snapshot(json.decodeFromJsonElement<ru.sodovaya.volty.domain.social.LiveGroupSnapshot>(snapshotElement))
                }.getOrElse {
                    SocialLiveEvent.Failure(SocialFailure.Server("Malformed live group event"))
                }
            }
        }
    }

    private fun JsonObject.liveFailure(): SocialFailure {
        val code = string("code", "reason", "error")?.lowercase()
        return when {
            code.isAuthRejection() -> SocialFailure.Unauthorized
            code.isMembershipRejection() -> SocialFailure.Forbidden
            else -> SocialFailure.Server(string("message", "error"))
        }
    }

    private fun url(path: String): String = "$baseUrl/${path.trimStart('/')}"

    private fun webSocketUrl(groupId: RideGroupId): String =
        url("ws/groups/${groupId.value.pathSegment()}")
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")

    private fun bearer(token: String): String = "Bearer $token"

    private fun String.pathSegment(): String =
        encodeUrlComponent()

    private fun String.encodeUrlComponent(): String = buildString {
        for (byte in encodeToByteArray()) {
            val value = byte.toInt() and 0xff
            if (value in 0x30..0x39 || value in 0x41..0x5a || value in 0x61..0x7a || value in "-._~".map(Char::code)) {
                append(value.toChar())
            } else {
                append('%')
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private fun nextRetryDelay(current: Long): Long =
        (current * 2L).coerceAtMost(MAX_RETRY_DELAY_MILLIS)

    private fun unwrap(element: JsonElement): JsonElement {
        val objectValue = element as? JsonObject ?: return element
        return objectValue["data"] ?: objectValue["result"] ?: objectValue["payload"] ?: element
    }

    private fun JsonObject.string(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }

    private fun JsonObject.requiredString(vararg names: String): String =
        string(*names)?.takeIf { it.isNotBlank() }
            ?: error("Missing social response field")

    private fun JsonObject.requiredLong(vararg names: String): Long =
        names.firstNotNullOfOrNull { name -> this[name]?.jsonPrimitive?.longOrNull }
            ?: error("Missing social response field")

    private fun JsonObject.requiredSocialUserId(): ru.sodovaya.volty.domain.social.SocialUserId =
        ru.sodovaya.volty.domain.social.SocialUserId(requiredString("userId", "user_id"))

    private fun errorMessage(element: JsonElement): String? {
        val objectValue = element as? JsonObject ?: return null
        return objectValue.string("message", "detail", "error")
            ?: (objectValue["error"] as? JsonObject)?.string("message", "detail")
    }

    private fun retryAfterSeconds(element: JsonElement): Long? {
        val objectValue = element as? JsonObject ?: return null
        return objectValue["retryAfterSeconds"]?.jsonPrimitive?.longOrNull
            ?: objectValue["retry_after_seconds"]?.jsonPrimitive?.longOrNull
    }

    @Serializable
    private data class RegisterDto(
        val email: String,
        val password: String,
        val displayName: String,
    ) {
        companion object {
            fun from(request: RegistrationRequest) = RegisterDto(request.email, request.password, request.displayName)
        }
    }

    @Serializable
    private data class LoginDto(val email: String, val password: String) {
        companion object {
            fun from(request: LoginRequest) = LoginDto(request.email, request.password)
        }
    }

    @Serializable
    private data class RefreshDto(@SerialName("refreshToken") val refreshToken: String)

    @Serializable
    private data class TokenDto(val token: String)

    @Serializable
    private data class EmailDto(val email: String)

    @Serializable
    private data class ResetPasswordDto(val token: String, val newPassword: String)

    @Serializable
    private data class FriendRequestDto(val userId: ru.sodovaya.volty.domain.social.SocialUserId)

    @Serializable
    private data class FriendResponseDto(val accept: Boolean)

    @Serializable
    private data class CreateGroupDto(val name: String)

    @Serializable
    private data class JoinGroupDto(val inviteCode: String)

    @Serializable
    private data class ProfileUpdateDto(val displayName: String)

    @Serializable
    private data class ShareSessionDto(
        val groupId: RideGroupId,
        val profile: ru.sodovaya.volty.domain.social.TelemetryShareProfile,
        val ttlMillis: Long,
        val startedAtEpochMillis: Long,
    ) {
        companion object {
            fun from(request: ShareSessionRequest) = ShareSessionDto(
                groupId = request.groupId,
                profile = request.profile,
                ttlMillis = request.ttlMillis,
                startedAtEpochMillis = request.startedAtEpochMillis,
            )
        }
    }

    @Serializable
    private data class RenewShareSessionDto(
        val ttlMillis: Long,
        val startedAtEpochMillis: Long,
    ) {
        companion object {
            fun from(request: ShareSessionRequest) = RenewShareSessionDto(
                ttlMillis = request.ttlMillis,
                startedAtEpochMillis = request.startedAtEpochMillis,
            )
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://volty.sodove.ru/v1"
        const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 30_000L
        val CLIENT_ERROR_STATUSES = setOf(
            HttpStatusCode.BadRequest,
            HttpStatusCode.UnprocessableEntity,
        )
        val HEX = "0123456789ABCDEF"
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
        }
    }
}

internal fun websocketHandshakeFailure(status: HttpStatusCode, body: String? = null): SocialFailure? = when (status) {
    HttpStatusCode.Unauthorized -> SocialFailure.Unauthorized
    HttpStatusCode.Forbidden -> {
        val code = body?.let { runCatching { websocketJson.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.string("code", "reason", "error")?.lowercase()
        if (code.isAuthRejection()) SocialFailure.Unauthorized else SocialFailure.Forbidden
    }
    HttpStatusCode.NotFound -> SocialFailure.NotFound
    else -> null
}

internal fun websocketExceptionFailure(message: String?): SocialFailure? {
    val value = message?.lowercase() ?: return null
    return when {
        "invalid token" in value || "unauthorized" in value || "authentication" in value ->
            SocialFailure.Unauthorized
        "not a member" in value || "not_member" in value || "membership" in value ->
            SocialFailure.Forbidden
        else -> null
    }
}

private fun String?.isAuthRejection(): Boolean = this in setOf(
    "invalid_token", "expired_token", "unauthorized", "authentication_required", "auth_rejected",
)

private fun String?.isMembershipRejection(): Boolean = this in setOf(
    "not_member", "membership", "membership_required", "group_membership", "forbidden",
)

private fun JsonObject.string(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()
}

private val websocketJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}
