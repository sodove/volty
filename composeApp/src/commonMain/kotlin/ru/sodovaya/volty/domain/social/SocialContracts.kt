package ru.sodovaya.volty.domain.social

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

sealed interface SocialFailure {
    data object Unauthorized : SocialFailure
    data object Forbidden : SocialFailure
    data object NotFound : SocialFailure
    data object Conflict : SocialFailure
    data class RateLimited(val retryAfterSeconds: Long?) : SocialFailure
    data class InvalidRequest(val message: String) : SocialFailure
    data class Network(val message: String? = null) : SocialFailure
    data class Server(val message: String? = null) : SocialFailure
}

sealed interface SocialResult<out T> {
    data class Success<T>(val value: T) : SocialResult<T>

    data class Failure(val error: SocialFailure) : SocialResult<Nothing>
}

data class RegistrationRequest(
    val email: String,
    val password: String,
    val displayName: String,
)

data class LoginRequest(
    val email: String,
    val password: String,
)

/** Credentials are transient transport output; Task 2 owns their secure persistence boundary. */
data class SessionCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
)

data class ProfileUpdate(val displayName: String)

data class FriendRequest(val userId: SocialUserId)

data class ShareSessionRequest(
    val groupId: RideGroupId,
    val profile: TelemetryShareProfile,
    val ttlMillis: Long,
    val startedAtEpochMillis: Long,
)

sealed interface SocialLiveEvent {
    data class Snapshot(val value: LiveGroupSnapshot) : SocialLiveEvent
    data class ShareRevoked(val userId: SocialUserId) : SocialLiveEvent
    data class ShareExpired(val userId: SocialUserId) : SocialLiveEvent
    /** terminal=true means the server ended the subscription; it must not be retried. */
    open class Failure(val error: SocialFailure, val terminal: Boolean = false) : SocialLiveEvent {
        override fun equals(other: Any?): Boolean =
            other is Failure && error == other.error && terminal == other.terminal

        override fun hashCode(): Int = 31 * error.hashCode() + terminal.hashCode()

        override fun toString(): String = "Failure(error=$error, terminal=$terminal)"
    }

    data class SubscriptionTerminated(val reason: String? = null) :
        Failure(SocialFailure.Forbidden, terminal = true)
}

/** HTTPS/REST plus authenticated WebSocket; no BLE identifiers belong in these methods. */
interface SocialTransport {
    suspend fun register(request: RegistrationRequest): SocialResult<SessionCredentials>

    suspend fun login(request: LoginRequest): SocialResult<SessionCredentials>

    suspend fun refreshSession(refreshToken: String): SocialResult<SessionCredentials>

    suspend fun logout(accessToken: String): SocialResult<Unit>

    suspend fun getProfile(accessToken: String): SocialResult<SocialSession.Authenticated>

    suspend fun verifyEmail(token: String): SocialResult<Unit>

    suspend fun requestPasswordReset(email: String): SocialResult<Unit>

    suspend fun resetPassword(token: String, newPassword: String): SocialResult<Unit>

    suspend fun deleteAccount(accessToken: String): SocialResult<Unit>

    suspend fun updateProfile(accessToken: String, request: ProfileUpdate): SocialResult<SocialSession.Authenticated>

    suspend fun listFriends(accessToken: String): SocialResult<List<FriendSummary>>

    suspend fun sendFriendRequest(accessToken: String, request: FriendRequest): SocialResult<Unit>

    suspend fun respondToFriendRequest(accessToken: String, friendshipId: String, accept: Boolean): SocialResult<Unit>

    suspend fun searchUsers(accessToken: String, query: String): SocialResult<List<UserSearchResult>> =
        SocialResult.Failure(SocialFailure.InvalidRequest("User search is not supported by this transport"))

    suspend fun listGroups(accessToken: String): SocialResult<List<RideGroup>>

    suspend fun createGroup(accessToken: String, name: String): SocialResult<RideGroup>

    suspend fun joinGroup(accessToken: String, inviteCode: String): SocialResult<RideGroup>

    suspend fun leaveGroup(accessToken: String, groupId: RideGroupId): SocialResult<Unit>

    suspend fun deleteGroup(accessToken: String, groupId: RideGroupId): SocialResult<Unit>

    fun observeGroup(accessToken: String, groupId: RideGroupId): Flow<SocialLiveEvent>

    fun observeGroup(
        groupId: RideGroupId,
        accessTokenProvider: suspend () -> String?,
    ): Flow<SocialLiveEvent> = flow {
        val accessToken = accessTokenProvider()
        if (accessToken == null) {
            emit(SocialLiveEvent.Failure(SocialFailure.Unauthorized))
        } else {
            emitAll(observeGroup(accessToken, groupId))
        }
    }

    suspend fun startSharing(accessToken: String, request: ShareSessionRequest): SocialResult<SharingSession>

    suspend fun renewSharing(accessToken: String, request: ShareSessionRequest): SocialResult<SharingSession> =
        startSharing(accessToken, request)

    suspend fun publishSharingUpdate(
        accessToken: String,
        groupId: RideGroupId,
        update: ParticipantShareUpdate,
    ): SocialResult<Unit>

    suspend fun stopSharing(accessToken: String, groupId: RideGroupId): SocialResult<Unit>

    suspend fun joinVoice(accessToken: String, groupId: RideGroupId): SocialResult<VoiceRoomCredentials>

    suspend fun getVoiceProvider(accessToken: String): SocialResult<VoiceProviderAvailability> =
        SocialResult.Failure(SocialFailure.InvalidRequest("Voice provider availability is not supported by this transport"))

    suspend fun leaveVoice(accessToken: String, groupId: RideGroupId): SocialResult<Unit>
}

/** Repository owns local session/cache orchestration; transport owns protocol framing. */
interface SocialRepository {
    val session: StateFlow<SocialSession>
    val activeSharing: StateFlow<SharingSession?>

    suspend fun register(request: RegistrationRequest): SocialResult<Unit>

    suspend fun login(request: LoginRequest): SocialResult<Unit>

    suspend fun logout(): SocialResult<Unit>

    suspend fun verifyEmail(token: String): SocialResult<Unit>

    suspend fun requestPasswordReset(email: String): SocialResult<Unit>

    suspend fun resetPassword(token: String, newPassword: String): SocialResult<Unit>

    suspend fun deleteAccount(): SocialResult<Unit>

    suspend fun getProfile(): SocialResult<SocialSession.Authenticated>

    suspend fun updateProfile(request: ProfileUpdate): SocialResult<SocialSession.Authenticated>

    suspend fun listFriends(): SocialResult<List<FriendSummary>>

    suspend fun sendFriendRequest(request: FriendRequest): SocialResult<Unit>

    suspend fun respondToFriendRequest(friendshipId: String, accept: Boolean): SocialResult<Unit>

    suspend fun searchUsers(query: String): SocialResult<List<UserSearchResult>> =
        SocialResult.Failure(SocialFailure.InvalidRequest("User search is not supported by this repository"))

    suspend fun listGroups(): SocialResult<List<RideGroup>>

    suspend fun createGroup(name: String): SocialResult<RideGroup>

    suspend fun joinGroup(inviteCode: String): SocialResult<RideGroup>

    suspend fun leaveGroup(groupId: RideGroupId): SocialResult<Unit>

    suspend fun deleteGroup(groupId: RideGroupId): SocialResult<Unit>

    fun observeGroup(groupId: RideGroupId): Flow<SocialLiveEvent>

    suspend fun startSharing(request: ShareSessionRequest): SocialResult<SharingSession>

    suspend fun renewSharing(request: ShareSessionRequest): SocialResult<SharingSession> =
        startSharing(request)

    suspend fun publishSharingUpdate(
        groupId: RideGroupId,
        update: ParticipantShareUpdate,
    ): SocialResult<Unit>

    suspend fun stopSharing(groupId: RideGroupId): SocialResult<Unit>

    suspend fun joinVoice(groupId: RideGroupId): SocialResult<VoiceRoomCredentials>

    suspend fun getVoiceProvider(): SocialResult<VoiceProviderAvailability> =
        SocialResult.Failure(SocialFailure.InvalidRequest("Voice provider availability is not supported by this repository"))

    suspend fun leaveVoice(groupId: RideGroupId): SocialResult<Unit>
}

data class VoiceRoomCredentials(
    val provider: String,
    val serverUrl: String,
    val roomId: String,
    val participantToken: String,
    val expiresAtEpochMillis: Long,
)

interface VoiceRoomRepository {
    val requiredPermissions: List<String>
        get() = emptyList()

    val state: StateFlow<VoiceRoomState>

    suspend fun join(groupId: RideGroupId): SocialResult<Unit>

    suspend fun leave(): SocialResult<Unit>

    suspend fun setMuted(muted: Boolean): SocialResult<Unit>
}
