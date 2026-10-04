package ru.sodovaya.volty.data.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : SocialRepository {
    private val _session = MutableStateFlow<SocialSession>(SocialSession.LoggedOut)
    override val session: StateFlow<SocialSession> = _session.asStateFlow()
    private val _credentials = MutableStateFlow<SocialCredentials?>(null)
    private val _activeSharing = MutableStateFlow<SharingSession?>(null)
    private val _authenticatedAccount = MutableStateFlow<SocialAuthenticatedAccount?>(null)
    val authenticatedAccount: StateFlow<SocialAuthenticatedAccount?> = _authenticatedAccount.asStateFlow()
    private val refreshMutex = Mutex()
    private class AuthenticationGeneration(var pendingCaller: Job? = null)
    private val authenticationGeneration = MutableStateFlow(AuthenticationGeneration())
    private val sessionMutex = Mutex()
    private val credentialMutex = Mutex()
    private val accountParentJob = requireNotNull(scope.coroutineContext[Job]) {
        "Social repository requires a parent Job"
    }
    override val activeSharing: StateFlow<SharingSession?> = _activeSharing.asStateFlow()

    init {
        // Capture before dispatch: a queued startup task must not adopt a later login.
        val generation = authenticationGeneration.value
        scope.launch { restoreSession(generation) }
    }

    override suspend fun register(request: RegistrationRequest): SocialResult<Unit> =
        authenticate { transport.register(request) }

    override suspend fun login(request: LoginRequest): SocialResult<Unit> =
        authenticate { transport.login(request) }

    override suspend fun logout(): SocialResult<Unit> {
        // Retire local ownership before any storage or remote wait.
        val stored = clearLocalSession()
        return stored?.let { transport.logout(it.accessToken) }
            ?: SocialResult.Success(Unit)
    }

    override suspend fun verifyEmail(token: String): SocialResult<Unit> = transport.verifyEmail(token)
    override suspend fun requestPasswordReset(email: String): SocialResult<Unit> = transport.requestPasswordReset(email)
    override suspend fun resetPassword(token: String, newPassword: String): SocialResult<Unit> =
        transport.resetPassword(token, newPassword)

    override suspend fun deleteAccount(): SocialResult<Unit> {
        val generation = authenticationGeneration.value
        val result = authorized(generation) { accessToken -> transport.deleteAccount(accessToken) }
        // The successful current deletion retires its own generation after result validation.
        if (result is SocialResult.Success) clearLocalSession(generation)
        return result
    }

    override suspend fun getProfile(): SocialResult<SocialSession.Authenticated> = authorized(
        publish = ::publishProfile,
        validate = ::acceptsProfile,
    ) { accessToken ->
        transport.getProfile(accessToken)
    }

    override suspend fun updateProfile(request: ProfileUpdate): SocialResult<SocialSession.Authenticated> =
        authorized(publish = ::publishProfile, validate = ::acceptsProfile) { accessToken ->
            transport.updateProfile(accessToken, request)
        }

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
        authorized(publish = {
            if (_activeSharing.value?.groupId == groupId) _activeSharing.value = null
        }) { accessToken ->
            transport.leaveGroup(accessToken, groupId)
        }

    override suspend fun deleteGroup(groupId: RideGroupId): SocialResult<Unit> =
        authorized(publish = {
            if (_activeSharing.value?.groupId == groupId) _activeSharing.value = null
        }) { accessToken ->
            transport.deleteGroup(accessToken, groupId)
        }

    override fun observeGroup(groupId: RideGroupId): Flow<SocialLiveEvent> {
        val generation = authenticationGeneration.value
        if (SocialSessionPolicy.requiresAuthentication(_session.value)) {
            return flowOf(SocialLiveEvent.Failure(SocialFailure.Unauthorized))
        }
        return kotlinx.coroutines.flow.flow {
            var authRefreshAttempted = false
            while (currentCoroutineContext().isActive) {
                if (!ownsAuthentication(generation)) {
                    emit(SocialLiveEvent.Failure(SocialFailure.Unauthorized, terminal = true))
                    return@flow
                }
                var refresh = false
                var retired = false
                transport.observeGroup(groupId) { currentAccessToken(generation) }
                    .transformWhile { event ->
                        val accepted = sessionMutex.withLock {
                            if (!ownsAuthenticationLocked(generation)) false
                            else {
                                val userId = (_session.value as? SocialSession.Authenticated)?.userId
                                when (event) {
                                    is SocialLiveEvent.ShareExpired ->
                                        if (event.userId == userId) _activeSharing.value = null
                                    is SocialLiveEvent.ShareRevoked ->
                                        if (event.userId == userId) _activeSharing.value = null
                                    else -> Unit
                                }
                                true
                            }
                        }
                        if (!accepted) {
                            retired = true
                            emit(SocialLiveEvent.Failure(SocialFailure.Unauthorized, terminal = true))
                            return@transformWhile false
                        }
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
                                emit(event)
                                true
                            }
                        }
                        keepCollecting
                    }.collect { event -> emit(event) }
                if (retired) return@flow
                if (!ownsAuthentication(generation)) {
                    emit(SocialLiveEvent.Failure(SocialFailure.Unauthorized, terminal = true))
                    return@flow
                }
                if (!refresh) return@flow
                if (refresh) {
                    val saved = sessionMutex.withLock {
                        if (ownsAuthenticationLocked(generation)) _credentials.value else null
                    }
                    if (saved == null) {
                        emit(SocialLiveEvent.Failure(SocialFailure.Unauthorized, terminal = true))
                        return@flow
                    }
                    when (val refreshed = refreshAccessToken(saved, generation)) {
                        is SocialResult.Success -> Unit
                        is SocialResult.Failure -> {
                            if (ownsAuthentication(generation)) emit(SocialLiveEvent.Failure(refreshed.error))
                            else emit(SocialLiveEvent.Failure(SocialFailure.Unauthorized, terminal = true))
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
        return authorized(publish = { _activeSharing.value = it }) { accessToken ->
            transport.startSharing(accessToken, request)
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
        return authorized(publish = { _activeSharing.value = it }) { accessToken ->
            transport.renewSharing(accessToken, request)
        }
    }

    override suspend fun stopSharing(groupId: RideGroupId): SocialResult<Unit> = authorized(publish = {
        if (_activeSharing.value?.groupId == groupId) _activeSharing.value = null
    }) { accessToken ->
        transport.stopSharing(accessToken, groupId)
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
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        val callerJob = callerContext[Job]
        val generation = sessionMutex.withLock {
            if (!accountParentJob.isActive) return@withLock null
            AuthenticationGeneration(callerJob).also {
                authenticationGeneration.value = it
                retireAccount()
                _credentials.value = null
                _activeSharing.value = null
                _session.value = SocialSession.Authenticating
            }
        } ?: return unauthorized()
        // This child observes cancellation even when the transport ignores it.
        // Normal completion cancels the guardian while its caller remains active.
        val guardian = if (callerJob == null) null else CoroutineScope(callerContext).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally {
                if (callerJob?.isCancelled == true) {
                    withContext(NonCancellable) { clearLocalSession(generation) }
                }
            }
        }
        try {
            val result = request()
            callerContext.ensureActive()
            return when (result) {
                is SocialResult.Failure -> {
                    if (!ownsAuthentication(generation)) return unauthorized()
                    clearLocalSession(generation)
                    result
                }
                is SocialResult.Success -> {
                    if (!ownsAuthentication(generation)) return unauthorized()
                    finishAuthentication(result.value, generation)
                }
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                try { clearLocalSession(generation) }
                catch (cleanupFailure: Exception) { failure.addSuppressed(cleanupFailure) }
            }
            throw failure
        } finally {
            guardian?.cancel()
        }
    }

    private suspend fun finishAuthentication(
        session: SessionCredentials,
        generation: AuthenticationGeneration,
    ): SocialResult<Unit> {
        val stored = session.toStored()
        return when (val profile = transport.getProfile(stored.accessToken)) {
            is SocialResult.Success -> commitCredentials(generation, stored, profile.value)
            is SocialResult.Failure -> {
                if (!ownsAuthentication(generation)) return unauthorized()
                clearLocalSession(generation)
                profile
            }
        }
    }

    private suspend fun ownsAuthentication(generation: AuthenticationGeneration): Boolean =
        sessionMutex.withLock { ownsAuthenticationLocked(generation) }

    private fun ownsAuthenticationLocked(generation: AuthenticationGeneration): Boolean =
        authenticationGeneration.value === generation && accountParentJob.isActive &&
            generation.pendingCaller?.isActive != false &&
            _authenticatedAccount.value?.job?.isActive != false

    /** Called under the state lock; never joins children or waits for remote cleanup. */
    private fun retireAccount() {
        val previous = _authenticatedAccount.value
        _authenticatedAccount.value = null
        previous?.job?.cancel()
    }

    private suspend fun commitCredentials(
        generation: AuthenticationGeneration,
        stored: SocialCredentials,
        profile: SocialSession.Authenticated? = null,
    ): SocialResult<Unit> = credentialMutex.withLock {
        if (!ownsAuthentication(generation)) return@withLock unauthorized()
        currentCoroutineContext().ensureActive()
        try {
            credentials.write(stored)
            currentCoroutineContext().ensureActive()
        } catch (failure: Exception) {
            // A write can finish before delivering cancellation/another failure.
            // Keep rollback serialized and able to suspend in a cancelled caller.
            withContext(NonCancellable) {
                try { credentials.clear() }
                catch (cleanupFailure: Exception) { failure.addSuppressed(cleanupFailure) }
            }
            throw failure
        }
        val published = sessionMutex.withLock {
            if (!ownsAuthenticationLocked(generation)) false
            else {
                _credentials.value = stored
                if (profile != null) {
                    generation.pendingCaller = null
                    _session.value = profile
                    if (_authenticatedAccount.value == null) {
                        _authenticatedAccount.value = SocialAuthenticatedAccount(
                            profile.userId,
                            SupervisorJob(accountParentJob),
                        ) { currentAccessToken(generation) }
                    }
                }
                true
            }
        }
        if (published) SocialResult.Success(Unit)
        else {
            // All credential writers use this lock; a newer login cannot write until cleanup ends.
            // Earlier code denied the profile but left its already-written token behind.
            withContext(NonCancellable) { credentials.clear() }
            unauthorized()
        }
    }

    private suspend fun restoreSession(generation: AuthenticationGeneration) {
        val saved = credentialMutex.withLock {
            if (!ownsAuthentication(generation)) return@withLock null
            credentials.read()
        } ?: return
        if (!ownsAuthentication(generation)) return
        val session = if (saved.expiresAtEpochMillis <= epochMillis()) {
            transport.refreshSession(saved.refreshToken)
        } else {
            SocialResult.Success(saved.toSessionCredentials())
        }
        if (!ownsAuthentication(generation)) return
        when (session) {
            is SocialResult.Success -> {
                val refreshed = session.value.toStored()
                when (val profile = transport.getProfile(refreshed.accessToken)) {
                    is SocialResult.Success -> commitCredentials(generation, refreshed, profile.value)
                    is SocialResult.Failure -> if (profile.error is SocialFailure.Unauthorized) {
                        if (!ownsAuthentication(generation)) return
                        when (val retry = transport.refreshSession(refreshed.refreshToken)) {
                            is SocialResult.Success -> {
                                if (!ownsAuthentication(generation)) return
                                val retryCredentials = retry.value.toStored()
                                when (val retryProfile = transport.getProfile(retryCredentials.accessToken)) {
                                    is SocialResult.Success -> commitCredentials(generation, retryCredentials, retryProfile.value)
                                    is SocialResult.Failure -> if (retryProfile.error is SocialFailure.Unauthorized)
                                        clearLocalSession(generation)
                                }
                            }
                            is SocialResult.Failure -> if (retry.error is SocialFailure.Unauthorized)
                                clearLocalSession(generation)
                        }
                    }
                }
            }
            is SocialResult.Failure -> if (session.error is SocialFailure.Unauthorized) clearLocalSession(generation)
        }
    }

    private suspend fun <T> authorized(
        generation: AuthenticationGeneration = authenticationGeneration.value,
        publish: ((T) -> Unit)? = null,
        validate: ((T) -> Boolean)? = null,
        request: suspend (accessToken: String) -> SocialResult<T>,
    ): SocialResult<T> {
        currentCoroutineContext().ensureActive()
        if (SocialSessionPolicy.requiresAuthentication(_session.value)) return unauthorized()
        val saved = _credentials.value ?: return unauthorized()
        val accessToken = when (val fresh = ensureFreshAccessToken(saved, generation)) {
            is SocialResult.Failure -> return fresh
            is SocialResult.Success -> fresh.value
        }
        if (!ownsAuthentication(generation)) return unauthorized()
        currentCoroutineContext().ensureActive()
        val result = publishAuthorized(generation, request(accessToken), publish, validate)
        if (result !is SocialResult.Failure || result.error != SocialFailure.Unauthorized) return result
        val refreshed = when (val retryToken = refreshAccessToken(saved, generation)) {
            is SocialResult.Failure -> return retryToken
            is SocialResult.Success -> retryToken.value
        }
        if (!ownsAuthentication(generation)) return unauthorized()
        currentCoroutineContext().ensureActive()
        return publishAuthorized(generation, request(refreshed), publish, validate)
    }

    private suspend fun <T> publishAuthorized(
        generation: AuthenticationGeneration,
        result: SocialResult<T>,
        publish: ((T) -> Unit)?,
        validate: ((T) -> Boolean)?,
    ): SocialResult<T> = sessionMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!ownsAuthenticationLocked(generation)) unauthorized()
        else {
            if (result is SocialResult.Success) {
                if (validate?.invoke(result.value) == false) {
                    return@withLock SocialResult.Failure(
                        SocialFailure.InvalidRequest("Authenticated profile actor mismatch"),
                    )
                }
                publish?.invoke(result.value)
            }
            result
        }
    }

    private suspend fun currentAccessToken(generation: AuthenticationGeneration): String? = when (val result = authorized(generation) { accessToken -> SocialResult.Success(accessToken) }) {
        is SocialResult.Success -> result.value
        is SocialResult.Failure -> null
    }

    private suspend fun ensureFreshAccessToken(
        saved: SocialCredentials,
        generation: AuthenticationGeneration,
    ): SocialResult<String> {
        if (!ownsAuthentication(generation)) return unauthorized()
        return if (saved.expiresAtEpochMillis > epochMillis() + TOKEN_REFRESH_SKEW_MILLIS)
            SocialResult.Success(saved.accessToken)
        else refreshAccessToken(saved, generation)
    }

    private suspend fun refreshAccessToken(
        previous: SocialCredentials,
        generation: AuthenticationGeneration,
    ): SocialResult<String> {
        val account = sessionMutex.withLock {
            if (ownsAuthenticationLocked(generation)) _authenticatedAccount.value else null
        } ?: return unauthorized()
        return try {
            // Rotation belongs to the account: cancelling one screen/request must not
            // discard a refresh token that the server has already rotated.
            withContext(account.job) { refreshOwnedAccessToken(previous, generation) }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!ownsAuthentication(generation)) unauthorized() else throw cancelled
        }
    }

    private suspend fun refreshOwnedAccessToken(
        previous: SocialCredentials,
        generation: AuthenticationGeneration,
    ): SocialResult<String> = refreshMutex.withLock {
        if (!ownsAuthentication(generation)) return@withLock unauthorized()
        val current = _credentials.value
        if (current != null && current.accessToken != previous.accessToken &&
            current.expiresAtEpochMillis > epochMillis() + TOKEN_REFRESH_SKEW_MILLIS
        ) {
            return@withLock SocialResult.Success(current.accessToken)
        }
        val result = transport.refreshSession(previous.refreshToken)
        if (!ownsAuthentication(generation)) return@withLock unauthorized()
        when (result) {
            is SocialResult.Failure -> {
                if (result.error == SocialFailure.Unauthorized) {
                    withContext(NonCancellable) { clearLocalSession(generation) }
                }
                result
            }
            is SocialResult.Success -> {
                val refreshed = result.value.toStored()
                when (val committed = commitCredentials(generation, refreshed)) {
                    is SocialResult.Failure -> committed
                    is SocialResult.Success -> SocialResult.Success(refreshed.accessToken)
                }
            }
        }
    }

    private fun <T> unauthorized(): SocialResult<T> = SocialResult.Failure(SocialFailure.Unauthorized)

    /** Called only inside the generation-checked publication lock. */
    private fun publishProfile(profile: SocialSession.Authenticated) { _session.value = profile }

    /** A refresh or profile update cannot change the established principal. */
    private fun acceptsProfile(profile: SocialSession.Authenticated): Boolean =
        _authenticatedAccount.value?.userId == profile.userId

    private suspend fun clearLocalSession(expected: AuthenticationGeneration? = null): SocialCredentials? {
        val retired = sessionMutex.withLock {
            if (expected != null && authenticationGeneration.value !== expected) return@withLock null
            val previous = _credentials.value
            val next = AuthenticationGeneration()
            authenticationGeneration.value = next
            retireAccount()
            _credentials.value = null
            _activeSharing.value = null
            _session.value = SocialSession.LoggedOut
            next to previous
        } ?: return null
        return credentialMutex.withLock {
            if (!ownsAuthentication(retired.first)) return@withLock retired.second
            val stored = retired.second ?: credentials.read()
            if (ownsAuthentication(retired.first)) credentials.clear()
            stored
        }
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
