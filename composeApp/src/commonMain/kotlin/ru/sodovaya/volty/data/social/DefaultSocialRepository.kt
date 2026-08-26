package ru.sodovaya.volty.data.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.social.FriendRequest
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.LocationSharePolicy
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
import ru.sodovaya.volty.domain.social.SocialRepository
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialSessionPolicy
import ru.sodovaya.volty.domain.social.SocialTransport
import ru.sodovaya.volty.domain.social.VoiceRoomCredentials
import ru.sodovaya.volty.domain.social.UserSearchResult
import ru.sodovaya.volty.domain.social.VoiceProviderAvailability

/**
 * Session/cache orchestration. Tokens are read from secure storage and passed
 * explicitly to the authenticated transport boundary; UI and domain code
 * never handle bearer credentials.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class DefaultSocialRepository(
    private val transport: SocialTransport,
    private val credentials: SocialCredentialStore,
) : SocialRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _session = MutableStateFlow<SocialSession>(SocialSession.LoggedOut)
    override val session: StateFlow<SocialSession> = _session.asStateFlow()
    private val _credentials = MutableStateFlow<SocialCredentials?>(null)
    private val _activeSharing = MutableStateFlow<SharingSession?>(null)
    private val refreshMutex = Mutex()
    override val activeSharing: StateFlow<SharingSession?> = _activeSharing.asStateFlow()

    init { scope.launch { restoreSession() } }

    override suspend fun register(request: RegistrationRequest): SocialResult<Unit> =
        authenticate { transport.register(request) }

    override suspend fun login(request: LoginRequest): SocialResult<Unit> =
        authenticate { transport.login(request) }

    override suspend fun logout(): SocialResult<Unit> {
        val stored = _credentials.value ?: credentials.read()
        val result = stored?.let { transport.logout(it.accessToken) }
            ?: SocialResult.Success(Unit)
        clearLocalSession()
        return result
    }

    override suspend fun verifyEmail(token: String): SocialResult<Unit> = transport.verifyEmail(token)
    override suspend fun requestPasswordReset(email: String): SocialResult<Unit> = transport.requestPasswordReset(email)
    override suspend fun resetPassword(token: String, newPassword: String): SocialResult<Unit> =
        transport.resetPassword(token, newPassword)

    override suspend fun deleteAccount(): SocialResult<Unit> = authorized { accessToken ->
        transport.deleteAccount(accessToken).also { result ->
            if (result is SocialResult.Success) clearLocalSession()
        }
    }

    override suspend fun getProfile(): SocialResult<SocialSession.Authenticated> = authorized { accessToken ->
        transport.getProfile(accessToken).also(::publishProfile)
    }

    override suspend fun updateProfile(request: ProfileUpdate): SocialResult<SocialSession.Authenticated> =
        authorized { accessToken -> transport.updateProfile(accessToken, request).also(::publishProfile) }

    override suspend fun listFriends(): SocialResult<List<FriendSummary>> =
        authorized { accessToken -> transport.listFriends(accessToken) }

    override suspend fun sendFriendRequest(request: FriendRequest): SocialResult<Unit> =
        authorized { accessToken -> transport.sendFriendRequest(accessToken, request) }

    override suspend fun respondToFriendRequest(friendshipId: String, accept: Boolean): SocialResult<Unit> =
        authorized { accessToken -> transport.respondToFriendRequest(accessToken, friendshipId, accept) }

    override suspend fun searchUsers(query: String): SocialResult<List<UserSearchResult>> =
        authorized { accessToken -> transport.searchUsers(accessToken, query) }

    override suspend fun listGroups(): SocialResult<List<RideGroup>> =
        authorized { accessToken -> transport.listGroups(accessToken) }

    override suspend fun createGroup(name: String): SocialResult<RideGroup> =
        authorized { accessToken -> transport.createGroup(accessToken, name) }

    override suspend fun joinGroup(inviteCode: String): SocialResult<RideGroup> =
        authorized { accessToken -> transport.joinGroup(accessToken, inviteCode) }

    override suspend fun leaveGroup(groupId: RideGroupId): SocialResult<Unit> =
        authorized { accessToken ->
            transport.leaveGroup(accessToken, groupId).also { result ->
                if (result is SocialResult.Success && _activeSharing.value?.groupId == groupId) {
                    _activeSharing.value = null
                }
            }
        }

    override suspend fun deleteGroup(groupId: RideGroupId): SocialResult<Unit> =
        authorized { accessToken ->
            transport.deleteGroup(accessToken, groupId).also { result ->
                if (result is SocialResult.Success && _activeSharing.value?.groupId == groupId) {
                    _activeSharing.value = null
                }
            }
        }

    override fun observeGroup(groupId: RideGroupId): Flow<SocialLiveEvent> {
        if (SocialSessionPolicy.requiresAuthentication(_session.value)) {
            return flowOf(SocialLiveEvent.Failure(SocialFailure.Unauthorized))
        }
        return kotlinx.coroutines.flow.flow {
            var authRefreshAttempted = false
            while (currentCoroutineContext().isActive) {
                var refresh = false
                transport.observeGroup(groupId) { currentAccessToken() }
                    .transformWhile { event ->
                        val keepCollecting = when (event) {
                            is SocialLiveEvent.Failure -> if (event.terminal) {
                                emit(event)
                                false
                            } else when (event.error) {
                                SocialFailure.Unauthorized -> {
                                    if (!authRefreshAttempted) {
                                        authRefreshAttempted = true
                                        refresh = true
                                        false
                                    } else {
                                        emit(event)
                                        false
                                    }
                                }
                                SocialFailure.Forbidden, SocialFailure.NotFound -> {
                                    emit(event)
                                    false
                                }
                                else -> {
                                    emit(event)
                                    true
                                }
                            }
                            else -> {
                                val currentUserId = (_session.value as? SocialSession.Authenticated)?.userId
                                when (event) {
                                    is SocialLiveEvent.ShareExpired ->
                                        if (event.userId == currentUserId) _activeSharing.value = null
                                    is SocialLiveEvent.ShareRevoked ->
                                        if (event.userId == currentUserId) _activeSharing.value = null
                                    else -> Unit
                                }
                                emit(event)
                                true
                            }
                        }
                        keepCollecting
                    }.collect { event -> emit(event) }
                if (!refresh) return@flow
                if (refresh) {
                    when (val refreshed = refreshAccessToken(_credentials.value ?: return@flow)) {
                        is SocialResult.Success -> Unit
                        is SocialResult.Failure -> {
                            emit(SocialLiveEvent.Failure(refreshed.error))
                            return@flow
                        }
                    }
                    // The flag is intentionally not reset: one live subscription
                    // gets at most one forced refresh after a rejected handshake.
                    continue
                }
            }
        }
    }

    override suspend fun startSharing(request: ShareSessionRequest): SocialResult<SharingSession> {
        if (SocialSessionPolicy.requiresAuthentication(_session.value)) return unauthorized()
        if (request.ttlMillis <= 0L || request.ttlMillis > LocationSharePolicy.maxTtlMillis) {
            return SocialResult.Failure(SocialFailure.InvalidRequest("Sharing TTL is outside the allowed range"))
        }
        return authorized { accessToken ->
            transport.startSharing(accessToken, request).also { result ->
                if (result is SocialResult.Success) _activeSharing.value = result.value
            }
        }
    }

    override suspend fun publishSharingUpdate(
        groupId: RideGroupId,
        update: ParticipantShareUpdate,
    ): SocialResult<Unit> {
        val active = _activeSharing.value
            ?: return SocialResult.Failure(SocialFailure.InvalidRequest("Sharing is not active"))
        if (active.groupId != groupId || epochMillis() >= active.expiresAtEpochMillis) {
            _activeSharing.value = null
            return SocialResult.Failure(SocialFailure.InvalidRequest("Sharing has expired"))
        }
        val telemetryValid = when (active.profile) {
            ru.sodovaya.volty.domain.social.TelemetryShareProfile.LOCATION -> update.telemetry == null
            else -> update.telemetry != null && update.telemetry.profile == active.profile
        }
        if (update.location == null || !telemetryValid) {
            return SocialResult.Failure(SocialFailure.InvalidRequest("Sharing update is incomplete"))
        }
        return authorized { accessToken -> transport.publishSharingUpdate(accessToken, groupId, update) }
    }

    override suspend fun renewSharing(request: ShareSessionRequest): SocialResult<SharingSession> {
        val active = _activeSharing.value
        if (active == null || active.groupId != request.groupId || active.profile != request.profile) {
            return SocialResult.Failure(SocialFailure.InvalidRequest("Sharing is not active for this group/profile"))
        }
        if (epochMillis() >= active.expiresAtEpochMillis) {
            _activeSharing.value = null
            return SocialResult.Failure(SocialFailure.InvalidRequest("Sharing has expired"))
        }
        if (request.ttlMillis <= 0L || request.ttlMillis > LocationSharePolicy.maxTtlMillis) {
            return SocialResult.Failure(SocialFailure.InvalidRequest("Sharing TTL is outside the allowed range"))
        }
        return authorized { accessToken ->
            transport.renewSharing(accessToken, request).also { result ->
                if (result is SocialResult.Success) _activeSharing.value = result.value
            }
        }
    }

    override suspend fun stopSharing(groupId: RideGroupId): SocialResult<Unit> = authorized { accessToken ->
        transport.stopSharing(accessToken, groupId).also { result ->
            if (result is SocialResult.Success && _activeSharing.value?.groupId == groupId) {
                _activeSharing.value = null
            }
        }
    }

    override suspend fun joinVoice(groupId: RideGroupId): SocialResult<VoiceRoomCredentials> =
        authorized { accessToken -> transport.joinVoice(accessToken, groupId) }

    override suspend fun getVoiceProvider(): SocialResult<VoiceProviderAvailability> =
        authorized { accessToken -> transport.getVoiceProvider(accessToken) }

    override suspend fun leaveVoice(groupId: RideGroupId): SocialResult<Unit> {
        if (SocialSessionPolicy.requiresAuthentication(_session.value) || _credentials.value == null) {
            return SocialResult.Success(Unit)
        }
        return when (val result = authorized { accessToken -> transport.leaveVoice(accessToken, groupId) }) {
            is SocialResult.Failure ->
                if (result.error == SocialFailure.Unauthorized) SocialResult.Success(Unit) else result
            is SocialResult.Success -> result
        }
    }

    private suspend fun authenticate(
        request: suspend () -> SocialResult<SessionCredentials>,
    ): SocialResult<Unit> {
        _session.value = SocialSession.Authenticating
        return when (val result = request()) {
            is SocialResult.Failure -> {
                clearLocalSession()
                result
            }
            is SocialResult.Success -> finishAuthentication(result.value)
        }
    }

    private suspend fun finishAuthentication(session: SessionCredentials): SocialResult<Unit> {
        val stored = session.toStored()
        return when (val profile = transport.getProfile(stored.accessToken)) {
            is SocialResult.Success -> {
                credentials.write(stored)
                _credentials.value = stored
                _session.value = profile.value
                SocialResult.Success(Unit)
            }
            is SocialResult.Failure -> {
                clearLocalSession()
                profile
            }
        }
    }

    private suspend fun restoreSession() {
        val saved = credentials.read() ?: return
        val session = if (saved.expiresAtEpochMillis <= epochMillis()) {
            transport.refreshSession(saved.refreshToken)
        } else {
            SocialResult.Success(saved.toSessionCredentials())
        }
        when (session) {
            is SocialResult.Success -> {
                val refreshed = session.value.toStored()
                if (refreshed != saved) credentials.write(refreshed)
                _credentials.value = refreshed
                when (val profile = transport.getProfile(refreshed.accessToken)) {
                    is SocialResult.Success -> _session.value = profile.value
                    is SocialResult.Failure -> if (profile.error is SocialFailure.Unauthorized) {
                        when (val retryToken = refreshAccessToken(refreshed)) {
                            is SocialResult.Success -> when (val retryProfile = transport.getProfile(retryToken.value)) {
                                is SocialResult.Success -> _session.value = retryProfile.value
                                is SocialResult.Failure -> if (retryProfile.error is SocialFailure.Unauthorized) clearLocalSession()
                            }
                            is SocialResult.Failure -> Unit
                        }
                    }
                }
            }
            is SocialResult.Failure -> if (session.error is SocialFailure.Unauthorized) clearLocalSession()
        }
    }

    private suspend fun <T> authorized(
        request: suspend (accessToken: String) -> SocialResult<T>,
    ): SocialResult<T> {
        if (SocialSessionPolicy.requiresAuthentication(_session.value)) return unauthorized()
        val saved = _credentials.value ?: return unauthorized()
        val accessToken = when (val fresh = ensureFreshAccessToken(saved)) {
            is SocialResult.Failure -> return fresh
            is SocialResult.Success -> fresh.value
        }
        val result = request(accessToken)
        if (result !is SocialResult.Failure || result.error != SocialFailure.Unauthorized) return result
        val refreshed = when (val retryToken = refreshAccessToken(saved)) {
            is SocialResult.Failure -> return retryToken
            is SocialResult.Success -> retryToken.value
        }
        return request(refreshed)
    }

    private suspend fun currentAccessToken(): String? = when (val result = authorized { accessToken -> SocialResult.Success(accessToken) }) {
        is SocialResult.Success -> result.value
        is SocialResult.Failure -> null
    }

    private suspend fun ensureFreshAccessToken(saved: SocialCredentials): SocialResult<String> =
        if (saved.expiresAtEpochMillis > epochMillis() + TOKEN_REFRESH_SKEW_MILLIS) {
            SocialResult.Success(saved.accessToken)
        } else {
            refreshAccessToken(saved)
        }

    private suspend fun refreshAccessToken(previous: SocialCredentials): SocialResult<String> = refreshMutex.withLock {
        val current = _credentials.value
        if (current != null && current.accessToken != previous.accessToken &&
            current.expiresAtEpochMillis > epochMillis() + TOKEN_REFRESH_SKEW_MILLIS
        ) {
            return@withLock SocialResult.Success(current.accessToken)
        }
        when (val result = transport.refreshSession(previous.refreshToken)) {
            is SocialResult.Failure -> {
                if (result.error == SocialFailure.Unauthorized) clearLocalSession()
                result
            }
            is SocialResult.Success -> {
                val refreshed = result.value.toStored()
                credentials.write(refreshed)
                _credentials.value = refreshed
                SocialResult.Success(refreshed.accessToken)
            }
        }
    }

    private fun <T> unauthorized(): SocialResult<T> = SocialResult.Failure(SocialFailure.Unauthorized)

    private fun publishProfile(result: SocialResult<SocialSession.Authenticated>) {
        if (result is SocialResult.Success) _session.value = result.value
    }

    private suspend fun clearLocalSession() {
        credentials.clear()
        _credentials.value = null
        _activeSharing.value = null
        _session.value = SocialSession.LoggedOut
    }

    private fun SessionCredentials.toStored() = SocialCredentials(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    private fun SocialCredentials.toSessionCredentials() = SessionCredentials(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochMillis = expiresAtEpochMillis,
    )

    private fun epochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val TOKEN_REFRESH_SKEW_MILLIS = 60_000L
    }
}

/** Honest fallback for tests or builds that intentionally disable networking. */
class UnavailableSocialTransport : SocialTransport {
    private fun <T> unavailable(): SocialResult<T> = SocialResult.Failure(
        SocialFailure.Network("Social server adapter is not configured"),
    )

    override suspend fun register(request: RegistrationRequest) = unavailable<SessionCredentials>()
    override suspend fun login(request: LoginRequest) = unavailable<SessionCredentials>()
    override suspend fun refreshSession(refreshToken: String) = unavailable<SessionCredentials>()
    override suspend fun logout(accessToken: String) = unavailable<Unit>()
    override suspend fun getProfile(accessToken: String) = unavailable<SocialSession.Authenticated>()
    override suspend fun verifyEmail(token: String) = unavailable<Unit>()
    override suspend fun requestPasswordReset(email: String) = unavailable<Unit>()
    override suspend fun resetPassword(token: String, newPassword: String) = unavailable<Unit>()
    override suspend fun deleteAccount(accessToken: String) = unavailable<Unit>()
    override suspend fun updateProfile(accessToken: String, request: ProfileUpdate) = unavailable<SocialSession.Authenticated>()
    override suspend fun listFriends(accessToken: String) = unavailable<List<FriendSummary>>()
    override suspend fun sendFriendRequest(accessToken: String, request: FriendRequest) = unavailable<Unit>()
    override suspend fun respondToFriendRequest(accessToken: String, friendshipId: String, accept: Boolean) = unavailable<Unit>()
    override suspend fun listGroups(accessToken: String) = unavailable<List<RideGroup>>()
    override suspend fun createGroup(accessToken: String, name: String) = unavailable<RideGroup>()
    override suspend fun joinGroup(accessToken: String, inviteCode: String) = unavailable<RideGroup>()
    override suspend fun leaveGroup(accessToken: String, groupId: RideGroupId) = unavailable<Unit>()
    override suspend fun deleteGroup(accessToken: String, groupId: RideGroupId) = unavailable<Unit>()
    override fun observeGroup(accessToken: String, groupId: RideGroupId): Flow<SocialLiveEvent> =
        flowOf(SocialLiveEvent.Failure(SocialFailure.Network("Social server adapter is not configured")))
    override suspend fun startSharing(accessToken: String, request: ShareSessionRequest) = unavailable<SharingSession>()
    override suspend fun publishSharingUpdate(
        accessToken: String,
        groupId: RideGroupId,
        update: ParticipantShareUpdate,
    ) = unavailable<Unit>()
    override suspend fun stopSharing(accessToken: String, groupId: RideGroupId) = unavailable<Unit>()
    override suspend fun joinVoice(accessToken: String, groupId: RideGroupId) = unavailable<VoiceRoomCredentials>()
    override suspend fun leaveVoice(accessToken: String, groupId: RideGroupId) = SocialResult.Success(Unit)
}
