package ru.sodovaya.volty.presentation.nearby

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.FriendRequest
import ru.sodovaya.volty.domain.social.FriendshipState
import ru.sodovaya.volty.domain.social.LoginRequest
import ru.sodovaya.volty.domain.social.ProfileUpdate
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialRepository
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialRideRuntime
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialSessionPolicy
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.TelemetryShareProfile
import ru.sodovaya.volty.domain.social.UserSearchResult
import ru.sodovaya.volty.domain.social.VoiceRoomState
import ru.sodovaya.volty.domain.social.reduce

interface NearbyComponent {
    val state: StateFlow<State>
    val locationPermissions: List<String>
    val voicePermissions: List<String>

    fun onEmailChanged(value: String)
    fun onPasswordChanged(value: String)
    fun onDisplayNameChanged(value: String)
    fun onProfileNameChanged(value: String)
    fun onAuthModeChanged(mode: AuthMode)
    fun onSubmitAuth()
    fun onRequestRecovery()
    fun onLogout()
    fun onRefresh()
    fun onSelectGroup(group: RideGroup)
    fun onGroupNameChanged(value: String)
    fun onCreateGroup()
    fun onJoinCodeChanged(value: String)
    fun onJoinGroup()
    fun onLeaveGroup(group: RideGroup)
    fun onDeleteGroup(group: RideGroup)
    fun onFriendQueryChanged(value: String)
    fun onSendFriendRequest(userId: String)
    fun onRespondToFriendRequest(friendshipId: String, accept: Boolean)
    fun onUpdateProfile()
    fun onShareProfileChanged(profile: TelemetryShareProfile)
    fun onStartSharing(ttlMillis: Long)
    fun onRenewSharing(ttlMillis: Long)
    fun onLocationPermissionDenied()
    fun onStopSharing()
    fun onJoinVoice()
    fun onVoicePermissionResult(granted: Boolean)
    fun onLeaveVoice()
    fun onSetMuted(muted: Boolean)
    /** Root integration should consume this callback to show the full-screen group map. */
    fun onOpenGroupMap()
    fun onBack()

    enum class AuthMode { LOGIN, REGISTER }

    enum class Operation {
        AUTH,
        RECOVERY,
        LOGOUT,
        REFRESH,
        CREATE_GROUP,
        JOIN_GROUP,
        LEAVE_GROUP,
        DELETE_GROUP,
        FRIEND_ACTION,
        UPDATE_PROFILE,
        START_SHARING,
        RENEW_SHARING,
        STOP_SHARING,
        VOICE_JOIN,
        VOICE_LEAVE,
        VOICE_MUTE,
    }

    data class State(
        val session: SocialSession = SocialSession.LoggedOut,
        val authMode: AuthMode = AuthMode.LOGIN,
        val email: String = "",
        val password: String = "",
        val displayName: String = "",
        val profileName: String = "",
        val groupName: String = "",
        val joinCode: String = "",
        val groups: List<RideGroup> = emptyList(),
        val friends: List<FriendSummary> = emptyList(),
        val friendQuery: String = "",
        val friendSearchResults: List<UserSearchResult> = emptyList(),
        val friendSearchLoading: Boolean = false,
        val friendActionIds: Set<String> = emptySet(),
        val selectedGroup: RideGroup? = null,
        val liveEvent: SocialLiveEvent? = null,
        val markers: List<ParticipantMarker> = emptyList(),
        val sharing: SharingSession? = null,
        val shareProfile: TelemetryShareProfile = TelemetryShareProfile.FULL,
        val voice: VoiceRoomState = VoiceRoomState.Unavailable,
        val pendingVoicePermissionRequest: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
        val operation: Operation? = null,
        val isLoading: Boolean = false,
    )
}

class DefaultNearbyComponent(
    componentContext: ComponentContext,
    private val socialRepository: SocialRepository,
    private val socialRuntime: SocialRideRuntime,
    private val onBackRequested: () -> Unit,
    private val onOpenGroupMapRequested: () -> Unit = {},
) : NearbyComponent, ComponentContext by componentContext {
    override val locationPermissions: List<String> = socialRuntime.locationPermissions
    override val voicePermissions: List<String> = socialRuntime.voicePermissions
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableStateFlow(
        NearbyComponent.State(voice = socialRuntime.state.value.voice)
    )
    override val state: StateFlow<NearbyComponent.State> = _state.asStateFlow()
    private var friendSearchJob: Job? = null
    private var friendSearchGeneration: Long = 0L

    init {
        lifecycle.doOnDestroy {
            friendSearchJob?.cancel()
            scope.cancel()
        }
        scope.launch {
            socialRepository.session.collectLatest { session ->
                _state.update {
                    it.copy(
                        session = session,
                        profileName = (session as? SocialSession.Authenticated)?.displayName
                            ?: it.profileName,
                    )
                }
                if (!SocialSessionPolicy.requiresAuthentication(session)) refreshSocial()
            }
        }
        scope.launch {
            socialRuntime.state.collect { runtime ->
                _state.update { current ->
                    current.copy(
                        selectedGroup = runtime.selectedGroup,
                        liveEvent = runtime.liveEvent,
                        markers = runtime.markers.map { marker -> marker.toParticipantMarker() },
                        sharing = runtime.sharing,
                        shareProfile = runtime.shareProfile,
                        voice = runtime.voice,
                        pendingVoicePermissionRequest = runtime.pendingVoicePermissionRequest,
                        error = (runtime.liveEvent as? SocialLiveEvent.Failure)?.error?.message()
                            ?: current.error,
                    )
                }
            }
        }
    }

    override fun onEmailChanged(value: String) { _state.update { it.copy(email = value, error = null, notice = null) } }
    override fun onPasswordChanged(value: String) { _state.update { it.copy(password = value, error = null, notice = null) } }
    override fun onDisplayNameChanged(value: String) { _state.update { it.copy(displayName = value, error = null) } }
    override fun onProfileNameChanged(value: String) { _state.update { it.copy(profileName = value, error = null, notice = null) } }
    override fun onGroupNameChanged(value: String) { _state.update { it.copy(groupName = value, error = null) } }
    override fun onJoinCodeChanged(value: String) { _state.update { it.copy(joinCode = value, error = null) } }
    override fun onAuthModeChanged(mode: NearbyComponent.AuthMode) {
        _state.update { it.copy(authMode = mode, error = null) }
    }

    override fun onSubmitAuth() {
        if (!canStartOperation(state.value)) return
        val current = state.value
        val validationError = validateAuthForm(
            mode = current.authMode,
            email = current.email,
            password = current.password,
            displayName = current.displayName,
        )
        if (validationError != null) {
            _state.update { it.copy(error = validationError) }
            return
        }
        scope.launch {
            beginOperation(NearbyComponent.Operation.AUTH)
            try {
                val result = when (current.authMode) {
                    NearbyComponent.AuthMode.LOGIN -> socialRepository.login(
                        LoginRequest(current.email.trim(), current.password)
                    )
                    NearbyComponent.AuthMode.REGISTER -> socialRepository.register(
                        ru.sodovaya.volty.domain.social.RegistrationRequest(
                            email = current.email.trim(),
                            password = current.password,
                            displayName = current.displayName.trim(),
                        )
                    )
                }
                result.errorOrNull()?.let { error ->
                    val message = if (
                        current.authMode == NearbyComponent.AuthMode.LOGIN &&
                        error == SocialFailure.Unauthorized
                    ) {
                        "Неверный email или пароль"
                    } else {
                        error.message()
                    }
                    _state.update { it.copy(error = message) }
                }
            } finally {
                finishOperation(NearbyComponent.Operation.AUTH)
            }
        }
    }

    override fun onRequestRecovery() {
        val email = state.value.email.trim()
        if (email.isBlank()) {
            _state.update { it.copy(error = "Введите email для восстановления") }
            return
        }
        if (!canStartOperation(state.value)) return
        scope.launch {
            beginOperation(NearbyComponent.Operation.RECOVERY)
            try {
                when (val result = socialRepository.requestPasswordReset(email)) {
                    is SocialResult.Success -> _state.update {
                        it.copy(notice = "Если аккаунт существует, инструкции отправлены")
                    }
                    is SocialResult.Failure -> _state.update { it.copy(error = result.error.message()) }
                }
            } finally {
                finishOperation(NearbyComponent.Operation.RECOVERY)
            }
        }
    }

    override fun onLogout() {
        if (!canStartOperation(state.value)) return
        scope.launch {
            beginOperation(NearbyComponent.Operation.LOGOUT)
            try {
                socialRuntime.logoutCleanup()
                socialRepository.logout()
                _state.value = NearbyComponent.State(voice = socialRuntime.state.value.voice)
            } finally {
                finishOperation(NearbyComponent.Operation.LOGOUT)
            }
        }
    }

    override fun onRefresh() {
        if (!canStartOperation(state.value)) return
        scope.launch { refreshSocial() }
    }

    override fun onSelectGroup(group: RideGroup) {
        socialRuntime.selectGroup(group)
        _state.update { it.copy(selectedGroup = group, error = null) }
    }

    override fun onCreateGroup() {
        val name = state.value.groupName.trim()
        if (name.isBlank()) {
            _state.update { it.copy(error = "Введите название группы") }
            return
        }
        if (!canStartOperation(state.value)) return
        scope.launch {
            beginOperation(NearbyComponent.Operation.CREATE_GROUP)
            try {
                val result = socialRepository.createGroup(name)
                handleResult(result) { group ->
                    _state.update { it.copy(groups = upsertGroupEntry(it.groups, group), groupName = "") }
                    onSelectGroup(group)
                }
            } finally {
                finishOperation(NearbyComponent.Operation.CREATE_GROUP)
            }
        }
    }

    override fun onJoinGroup() {
        if (!canStartOperation(state.value)) return
        scope.launch {
            val code = state.value.joinCode.trim()
            if (code.isBlank()) {
                _state.update { it.copy(error = "Введите код приглашения") }
                return@launch
            }
            beginOperation(NearbyComponent.Operation.JOIN_GROUP)
            try {
                val result = socialRepository.joinGroup(code)
                handleResult(result) { group ->
                    _state.update { it.copy(groups = upsertGroupEntry(it.groups, group), joinCode = "") }
                    onSelectGroup(group)
                }
            } finally {
                finishOperation(NearbyComponent.Operation.JOIN_GROUP)
            }
        }
    }

    override fun onLeaveGroup(group: RideGroup) {
        if (!canLeaveGroup(state.value.session, group)) {
            _state.update { it.copy(error = "Владелец может только удалить группу") }
            return
        }
        if (!canStartOperation(state.value)) return
        scope.launch {
            beginOperation(NearbyComponent.Operation.LEAVE_GROUP)
            try {
                when (val result = socialRepository.leaveGroup(group.id)) {
                    is SocialResult.Success -> {
                        clearGroupRuntimeAfterRemoval(group, "Вы вышли из группы «${group.name}»")
                    }
                    is SocialResult.Failure -> _state.update { it.copy(error = result.error.message()) }
                }
            } finally {
                finishOperation(NearbyComponent.Operation.LEAVE_GROUP)
            }
        }
    }

    override fun onDeleteGroup(group: RideGroup) {
        if (!isGroupOwner(state.value.session, group)) {
            _state.update { it.copy(error = "Только владелец может удалить группу") }
            return
        }
        if (!canStartOperation(state.value)) return
        scope.launch {
            beginOperation(NearbyComponent.Operation.DELETE_GROUP)
            try {
                when (val result = socialRepository.deleteGroup(group.id)) {
                    is SocialResult.Success -> clearGroupRuntimeAfterRemoval(group, "Группа «${group.name}» удалена")
                    is SocialResult.Failure -> _state.update { it.copy(error = result.error.message()) }
                }
            } finally {
                finishOperation(NearbyComponent.Operation.DELETE_GROUP)
            }
        }
    }

    override fun onFriendQueryChanged(value: String) {
        friendSearchGeneration += 1L
        val generation = friendSearchGeneration
        friendSearchJob?.cancel()
        _state.update { it.copy(friendQuery = value, error = null) }
        val query = value.trim()
        if (query.isBlank()) {
            _state.update { it.copy(friendSearchResults = emptyList(), friendSearchLoading = false) }
            return
        }
        friendSearchJob = scope.launch {
            kotlinx.coroutines.delay(FRIEND_SEARCH_DEBOUNCE_MILLIS)
            if (!isCurrentFriendSearch(value, generation)) return@launch
            _state.update { it.copy(friendSearchLoading = true, error = null) }
            when (val result = socialRepository.searchUsers(query)) {
                is SocialResult.Success -> if (isCurrentFriendSearch(value, generation)) {
                    _state.update {
                        it.copy(
                            friendSearchResults = result.value,
                            friendSearchLoading = false,
                            error = null,
                        )
                    }
                }
                is SocialResult.Failure -> if (isCurrentFriendSearch(value, generation)) {
                    _state.update {
                        it.copy(friendSearchLoading = false, error = result.error.message())
                    }
                }
            }
        }
    }

    override fun onSendFriendRequest(userId: String) {
        if (userId in state.value.friendActionIds) return
        scope.launch {
            beginFriendAction(userId)
            try {
                when (val result = socialRepository.sendFriendRequest(FriendRequest(SocialUserId(userId)))) {
                    is SocialResult.Success -> {
                        refreshFriendData()
                        _state.update { it.copy(notice = "Запрос отправлен") }
                    }
                    is SocialResult.Failure -> _state.update { it.copy(error = result.error.message()) }
                }
            } finally {
                finishFriendAction(userId)
            }
        }
    }

    override fun onRespondToFriendRequest(friendshipId: String, accept: Boolean) {
        if (friendshipId in state.value.friendActionIds) return
        scope.launch {
            beginFriendAction(friendshipId)
            try {
                when (val result = socialRepository.respondToFriendRequest(friendshipId, accept)) {
                    is SocialResult.Success -> {
                        refreshFriendData()
                        _state.update { it.copy(notice = if (accept) "Запрос принят" else "Запрос отклонён") }
                    }
                    is SocialResult.Failure -> _state.update { it.copy(error = result.error.message()) }
                }
            } finally {
                finishFriendAction(friendshipId)
            }
        }
    }

    override fun onUpdateProfile() {
        val name = state.value.profileName.trim()
        if (name.isBlank()) {
            _state.update { it.copy(error = "Введите имя") }
            return
        }
        if (!canStartOperation(state.value)) return
        scope.launch {
            beginOperation(NearbyComponent.Operation.UPDATE_PROFILE)
            try {
                when (val result = socialRepository.updateProfile(ProfileUpdate(name))) {
                    is SocialResult.Success -> _state.update { it.copy(profileName = result.value.displayName, notice = "Профиль сохранён") }
                    is SocialResult.Failure -> _state.update { it.copy(error = result.error.message()) }
                }
            } finally {
                finishOperation(NearbyComponent.Operation.UPDATE_PROFILE)
            }
        }
    }

    override fun onShareProfileChanged(profile: TelemetryShareProfile) {
        socialRuntime.setShareProfile(profile)
        _state.update { it.copy(shareProfile = profile) }
    }

    override fun onStartSharing(ttlMillis: Long) {
        startSharing(ttlMillis, NearbyComponent.Operation.START_SHARING)
    }

    override fun onRenewSharing(ttlMillis: Long) {
        startSharing(ttlMillis, NearbyComponent.Operation.RENEW_SHARING)
    }

    private fun startSharing(ttlMillis: Long, operation: NearbyComponent.Operation) {
        if (state.value.selectedGroup == null) return
        if (!canStartOperation(state.value)) return
        scope.launch {
            beginOperation(operation)
            try {
                val result = if (operation == NearbyComponent.Operation.RENEW_SHARING) {
                    socialRuntime.renewSharing(ttlMillis)
                } else {
                    socialRuntime.startSharing(ttlMillis)
                }
                when (result) {
                    is SocialResult.Success -> _state.update { it.copy(sharing = result.value, error = null, notice = null) }
                    is SocialResult.Failure -> _state.update { it.copy(error = result.error.message()) }
                }
            } finally {
                finishOperation(operation)
            }
        }
    }

    override fun onLocationPermissionDenied() {
        _state.update { it.copy(error = "Разрешение на геолокацию нужно для публикации места") }
    }

    override fun onStopSharing() {
        if (state.value.sharing == null) return
        if (!canStartOperation(state.value)) return
        scope.launch {
            beginOperation(NearbyComponent.Operation.STOP_SHARING)
            try {
                val result = socialRuntime.stopSharing()
                when (result) {
                    is SocialResult.Success -> _state.update { it.copy(sharing = null, notice = "Расшаривание остановлено") }
                    is SocialResult.Failure -> _state.update { it.copy(error = result.error.message()) }
                }
            } finally {
                finishOperation(NearbyComponent.Operation.STOP_SHARING)
            }
        }
    }

    override fun onJoinVoice() {
        val group = state.value.selectedGroup ?: return
        if (!canStartOperation(state.value)) return
        if (voicePermissions.isNotEmpty()) {
            socialRuntime.requestVoicePermission(group.id)
            _state.update { it.copy(pendingVoicePermissionRequest = true, error = null) }
            return
        }
        scope.launch {
            beginOperation(NearbyComponent.Operation.VOICE_JOIN)
            try { handleVoiceResult(socialRuntime.joinVoice()) }
            finally { finishOperation(NearbyComponent.Operation.VOICE_JOIN) }
        }
    }

    override fun onVoicePermissionResult(granted: Boolean) {
        scope.launch {
            beginOperation(NearbyComponent.Operation.VOICE_JOIN)
            try { handleVoiceResult(socialRuntime.onVoicePermissionResult(granted)) }
            finally { finishOperation(NearbyComponent.Operation.VOICE_JOIN) }
        }
    }

    override fun onLeaveVoice() {
        if (!canStartOperation(state.value)) return
        scope.launch {
            beginOperation(NearbyComponent.Operation.VOICE_LEAVE)
            try { handleVoiceResult(socialRuntime.leaveVoice()) }
            finally { finishOperation(NearbyComponent.Operation.VOICE_LEAVE) }
        }
    }
    override fun onSetMuted(muted: Boolean) {
        if (!canStartOperation(state.value)) return
        scope.launch {
            beginOperation(NearbyComponent.Operation.VOICE_MUTE)
            try { handleVoiceResult(socialRuntime.setMuted(muted)) }
            finally { finishOperation(NearbyComponent.Operation.VOICE_MUTE) }
        }
    }
    override fun onBack() {
        socialRuntime.onBack()
        onBackRequested()
    }

    override fun onOpenGroupMap() = onOpenGroupMapRequested()

    private suspend fun clearGroupRuntimeAfterRemoval(group: RideGroup, notice: String) {
        val current = state.value
        val affectsRuntime = current.selectedGroup?.id == group.id ||
            current.sharing?.groupId == group.id ||
            socialRepository.activeSharing.value?.groupId == group.id
        if (affectsRuntime) {
            socialRuntime.clearGroup()
        }
        _state.update {
            clearRemovedGroupState(it, group.id, socialRuntime.state.value.voice).copy(notice = notice)
        }
    }

    private fun isCurrentFriendSearch(value: String, generation: Long): Boolean =
        state.value.friendQuery == value && friendSearchGeneration == generation

    private fun beginFriendAction(actionId: String) {
        _state.update {
            it.copy(
                friendActionIds = it.friendActionIds + actionId,
                error = null,
                notice = null,
            )
        }
    }

    private fun finishFriendAction(actionId: String) {
        _state.update { it.copy(friendActionIds = it.friendActionIds - actionId) }
    }

    private suspend fun refreshFriendData() {
        val query = state.value.friendQuery
        val friends = socialRepository.listFriends()
        val search = query.trim().takeUnless { it.isBlank() }?.let { socialRepository.searchUsers(it) }
        _state.update { current ->
            if (current.friendQuery != query) return@update current
            val errors = listOfNotNull(
                friends.errorOrNull()?.message(),
                search?.errorOrNull()?.message(),
            ).joinToString("\n").ifBlank { null }
            current.copy(
                friends = friends.valueOrNull() ?: current.friends,
                friendSearchResults = search?.valueOrNull() ?: current.friendSearchResults,
                friendSearchLoading = false,
                error = errors,
            )
        }
    }

    private suspend fun refreshSocial() {
        if (SocialSessionPolicy.requiresAuthentication(state.value.session)) return
        if (!canStartOperation(state.value)) return
        beginOperation(NearbyComponent.Operation.REFRESH)
        try {
            val groups = socialRepository.listGroups()
            val friends = socialRepository.listFriends()
            _state.update { current -> mergeRefreshState(current, groups, friends) }
        } finally {
            finishOperation(NearbyComponent.Operation.REFRESH)
        }
    }

    private fun beginOperation(operation: NearbyComponent.Operation) {
        _state.update { it.copy(operation = operation, isLoading = true, error = null, notice = null) }
    }

    private fun finishOperation(operation: NearbyComponent.Operation) {
        _state.update {
            if (it.operation == operation) it.copy(operation = null, isLoading = false) else it
        }
    }

    private suspend fun <T> handleResult(result: SocialResult<T>, onSuccess: (T) -> Unit) {
        when (result) {
            is SocialResult.Success -> onSuccess(result.value)
            is SocialResult.Failure -> _state.update { it.copy(error = result.error.message()) }
        }
    }

    private suspend fun handleVoiceResult(result: SocialResult<Unit>) {
        if (result is SocialResult.Failure && _state.value.voice !is VoiceRoomState.Failed) {
            _state.update { it.copy(error = result.error.message()) }
        }
    }
}

private const val FRIEND_SEARCH_DEBOUNCE_MILLIS = 300L

private fun <T> SocialResult<T>.valueOrNull(): T? = (this as? SocialResult.Success<T>)?.value
private fun <T> SocialResult<T>.errorOrNull(): SocialFailure? = (this as? SocialResult.Failure)?.error

private fun ru.sodovaya.volty.domain.social.SocialParticipantMarker.toParticipantMarker() =
    ParticipantMarker(
        userId = userId,
        label = label,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        presence = presence,
        stale = stale,
    )

internal fun canStartOperation(state: NearbyComponent.State): Boolean = state.operation == null

internal fun isGroupOwner(session: SocialSession, group: RideGroup): Boolean =
    (session as? SocialSession.Authenticated)?.userId == group.ownerId

internal fun canLeaveGroup(session: SocialSession, group: RideGroup): Boolean =
    session is SocialSession.Authenticated

internal fun upsertGroupEntry(groups: List<RideGroup>, group: RideGroup): List<RideGroup> {
    val index = groups.indexOfFirst { it.id == group.id }
    if (index < 0) return groups + group
    return groups.toMutableList().apply { set(index, group) }
}

internal fun clearRemovedGroupState(
    current: NearbyComponent.State,
    groupId: RideGroupId,
    voiceState: VoiceRoomState,
): NearbyComponent.State {
    val selected = current.selectedGroup?.id == groupId
    return current.copy(
        groups = current.groups.filterNot { it.id == groupId },
        selectedGroup = current.selectedGroup?.takeUnless { it.id == groupId },
        liveEvent = current.liveEvent.takeUnless { selected },
        markers = if (selected) emptyList() else current.markers,
        sharing = current.sharing?.takeUnless { it.groupId == groupId },
        pendingVoicePermissionRequest = if (selected) false else current.pendingVoicePermissionRequest,
        voice = if (selected) voiceState else current.voice,
    )
}

internal fun remainingSharingMillis(expiresAt: Long, now: Long): Long =
    (expiresAt - now).coerceAtLeast(0L)

internal fun mergeRefreshState(
    current: NearbyComponent.State,
    groups: SocialResult<List<RideGroup>>,
    friends: SocialResult<List<FriendSummary>>,
): NearbyComponent.State {
    val groupError = groups.errorOrNull()?.message()
    val friendError = friends.errorOrNull()?.message()
    val error = listOfNotNull(groupError, friendError).joinToString("\n").ifBlank { null }
    val nextGroups = groups.valueOrNull() ?: current.groups
    val nextFriends = friends.valueOrNull() ?: current.friends
    val selectedGroup = current.selectedGroup?.let { selected ->
        nextGroups.firstOrNull { it.id == selected.id } ?: selected.takeIf { groups is SocialResult.Failure }
    }
    return current.copy(
        groups = nextGroups,
        friends = nextFriends,
        selectedGroup = selectedGroup,
        error = error,
    )
}

internal fun validateAuthForm(
    mode: NearbyComponent.AuthMode,
    email: String,
    password: String,
    displayName: String,
): String? = when {
    email.isBlank() || password.isBlank() -> "Заполните обязательные поля"
    mode == NearbyComponent.AuthMode.REGISTER && displayName.isBlank() -> "Введите имя"
    mode == NearbyComponent.AuthMode.REGISTER && password.length !in 12..128 ->
        "Пароль должен содержать от 12 до 128 символов"
    else -> null
}

private fun SocialFailure.message(): String = when (this) {
    SocialFailure.Unauthorized -> "Нужна авторизация"
    SocialFailure.Forbidden -> "Недостаточно прав"
    SocialFailure.NotFound -> "Не найдено"
    SocialFailure.Conflict -> "Конфликт данных"
    is SocialFailure.RateLimited -> "Слишком много запросов"
    is SocialFailure.InvalidRequest -> when {
        message.contains("password", ignoreCase = true) -> "Пароль должен содержать от 12 до 128 символов"
        message.contains("displayName", ignoreCase = true) -> "Имя должно содержать от 2 до 80 символов"
        message.contains("email", ignoreCase = true) -> "Проверьте адрес email"
        else -> message
    }
    is SocialFailure.Network -> message ?: "Сеть недоступна или сервер не настроен"
    is SocialFailure.Server -> message ?: "Ошибка сервера"
}
