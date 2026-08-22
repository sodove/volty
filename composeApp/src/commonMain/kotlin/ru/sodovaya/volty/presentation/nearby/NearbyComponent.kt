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
import ru.sodovaya.volty.domain.social.LocationProvider
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.LoginRequest
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.ShareSessionRequest
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialRepository
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialSessionPolicy
import ru.sodovaya.volty.domain.social.SocialShareSessionCoordinator
import ru.sodovaya.volty.domain.social.TelemetryShareProfile
import ru.sodovaya.volty.domain.social.VoiceRoomRepository
import ru.sodovaya.volty.domain.social.VoiceRoomFailureReason
import ru.sodovaya.volty.domain.social.VoiceRoomState
import ru.sodovaya.volty.domain.social.reduce

interface NearbyComponent {
    val state: StateFlow<State>
    val locationPermissions: List<String>
    val voicePermissions: List<String>

    fun onEmailChanged(value: String)
    fun onPasswordChanged(value: String)
    fun onDisplayNameChanged(value: String)
    fun onAuthModeChanged(mode: AuthMode)
    fun onSubmitAuth()
    fun onLogout()
    fun onRefresh()
    fun onSelectGroup(group: RideGroup)
    fun onCreateGroup()
    fun onJoinCodeChanged(value: String)
    fun onJoinGroup()
    fun onShareProfileChanged(profile: TelemetryShareProfile)
    fun onStartSharing(ttlMillis: Long)
    fun onLocationPermissionDenied()
    fun onStopSharing()
    fun onJoinVoice()
    fun onVoicePermissionResult(granted: Boolean)
    fun onLeaveVoice()
    fun onSetMuted(muted: Boolean)
    fun onBack()

    enum class AuthMode { LOGIN, REGISTER }

    data class State(
        val session: SocialSession = SocialSession.LoggedOut,
        val authMode: AuthMode = AuthMode.LOGIN,
        val email: String = "",
        val password: String = "",
        val displayName: String = "",
        val joinCode: String = "",
        val groups: List<RideGroup> = emptyList(),
        val friends: List<FriendSummary> = emptyList(),
        val selectedGroup: RideGroup? = null,
        val liveEvent: SocialLiveEvent? = null,
        val markers: List<ParticipantMarker> = emptyList(),
        val sharing: SharingSession? = null,
        val shareProfile: TelemetryShareProfile = TelemetryShareProfile.FULL,
        val voice: VoiceRoomState = VoiceRoomState.Unavailable,
        val pendingVoicePermissionRequest: Boolean = false,
        val error: String? = null,
        val isLoading: Boolean = false,
    )
}

class DefaultNearbyComponent(
    componentContext: ComponentContext,
    private val socialRepository: SocialRepository,
    private val voiceRepository: VoiceRoomRepository,
    private val locationProvider: LocationProvider,
    private val sharingCoordinator: SocialShareSessionCoordinator,
    private val liveSession: SocialLiveSession,
    private val onBackRequested: () -> Unit,
) : NearbyComponent, ComponentContext by componentContext {
    override val locationPermissions: List<String> = locationProvider.requiredPermissions
    override val voicePermissions: List<String> = voiceRepository.requiredPermissions
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val cleanupScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val _state = MutableStateFlow(
        NearbyComponent.State(voice = voiceRepository.state.value)
    )
    override val state: StateFlow<NearbyComponent.State> = _state.asStateFlow()
    private var sharingJob: Job? = null
    private var pendingVoiceJoinGroupId: RideGroupId? = null

    init {
        lifecycle.doOnDestroy {
            sharingJob?.cancel()
            pendingVoiceJoinGroupId = null
            scope.coroutineContext[Job]?.cancel()
            cleanupScope.launch {
                stopSharingSession()
                runCatching { voiceRepository.leave() }
                runCatching { locationProvider.stop() }
                cleanupScope.cancel()
            }
        }
        scope.launch {
            socialRepository.session.collectLatest { session ->
                _state.update { it.copy(session = session, error = null) }
                if (!SocialSessionPolicy.requiresAuthentication(session)) refreshSocial()
            }
        }
        scope.launch {
            voiceRepository.state.collect { voice -> _state.update { it.copy(voice = voice) } }
        }
        scope.launch {
            liveSession.state.collect { live ->
                _state.update { current ->
                    current.copy(
                        liveEvent = live.liveEvent,
                        markers = live.markers,
                        error = (live.liveEvent as? SocialLiveEvent.Failure)?.error?.message()
                            ?: current.error,
                    )
                }
            }
        }
    }

    override fun onEmailChanged(value: String) { _state.update { it.copy(email = value, error = null) } }
    override fun onPasswordChanged(value: String) { _state.update { it.copy(password = value, error = null) } }
    override fun onDisplayNameChanged(value: String) { _state.update { it.copy(displayName = value, error = null) } }
    override fun onJoinCodeChanged(value: String) { _state.update { it.copy(joinCode = value, error = null) } }
    override fun onAuthModeChanged(mode: NearbyComponent.AuthMode) {
        _state.update { it.copy(authMode = mode, error = null) }
    }

    override fun onSubmitAuth() {
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
            _state.update { it.copy(isLoading = true, error = null) }
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
            result.errorOrNull()?.let { error -> _state.update { it.copy(error = error.message()) } }
            _state.update { it.copy(isLoading = false) }
        }
    }

    override fun onLogout() {
        scope.launch {
            stopSharingSession()
            pendingVoiceJoinGroupId = null
            voiceRepository.leave()
            liveSession.clear()
            socialRepository.logout()
            _state.value = NearbyComponent.State(voice = voiceRepository.state.value)
        }
    }

    override fun onRefresh() { scope.launch { refreshSocial() } }

    override fun onSelectGroup(group: RideGroup) {
        val previousGroupId = state.value.selectedGroup?.id
        scope.launch {
            if (previousGroupId != null && previousGroupId != group.id) {
                stopSharingSession()
                pendingVoiceJoinGroupId = null
                runCatching { voiceRepository.leave() }
            }
            _state.update {
                it.copy(
                    selectedGroup = group,
                    liveEvent = null,
                    markers = emptyList(),
                    pendingVoicePermissionRequest = false,
                    error = null,
                )
            }
            liveSession.selectGroup(group.id)
        }
    }

    override fun onCreateGroup() {
        scope.launch {
            val result = socialRepository.createGroup("Покатушка")
            handleResult(result) { group ->
                _state.update { it.copy(groups = it.groups + group) }
                onSelectGroup(group)
            }
        }
    }

    override fun onJoinGroup() {
        scope.launch {
            val code = state.value.joinCode.trim()
            if (code.isBlank()) {
                _state.update { it.copy(error = "Введите код приглашения") }
                return@launch
            }
            val result = socialRepository.joinGroup(code)
            handleResult(result) { group ->
                _state.update { it.copy(groups = it.groups + group) }
                onSelectGroup(group)
            }
        }
    }

    override fun onShareProfileChanged(profile: TelemetryShareProfile) {
        _state.update { it.copy(shareProfile = profile) }
    }

    override fun onStartSharing(ttlMillis: Long) {
        val group = state.value.selectedGroup ?: return
        scope.launch {
            runCatching { locationProvider.start() }.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Не удалось включить геолокацию") }
                return@launch
            }
            val result = socialRepository.startSharing(
                ShareSessionRequest(
                    groupId = group.id,
                    profile = state.value.shareProfile,
                    ttlMillis = ttlMillis,
                    startedAtEpochMillis = epochMillis(),
                )
            )
            when (result) {
                is SocialResult.Success -> {
                    _state.update { it.copy(sharing = result.value, error = null) }
                    sharingJob?.cancel()
                    sharingJob = launch {
                        locationProvider.updates.collect { location ->
                            val published = sharingCoordinator.publish(
                                groupId = group.id,
                                profile = result.value.profile,
                                location = location,
                            )
                            if (published is SocialResult.Failure) {
                                _state.update { it.copy(error = published.error.message()) }
                            }
                        }
                    }
                }
                is SocialResult.Failure -> {
                    locationProvider.stop()
                    _state.update { it.copy(error = result.error.message()) }
                }
            }
        }
    }

    override fun onLocationPermissionDenied() {
        _state.update { it.copy(error = "Разрешение на геолокацию нужно для публикации места") }
    }

    override fun onStopSharing() {
        val sharing = state.value.sharing ?: return
        scope.launch {
            stopSharingRuntime()
            val result = socialRepository.stopSharing(sharing.groupId)
            handleResult(result) { _state.update { it.copy(sharing = null) } }
        }
    }

    override fun onJoinVoice() {
        val group = state.value.selectedGroup ?: return
        if (voicePermissions.isNotEmpty()) {
            pendingVoiceJoinGroupId = group.id
            _state.update { it.copy(pendingVoicePermissionRequest = true, error = null) }
            return
        }
        scope.launch {
            handleVoiceResult(voiceRepository.join(group.id))
        }
    }

    override fun onVoicePermissionResult(granted: Boolean) {
        val groupId = pendingVoiceJoinGroupId ?: return
        pendingVoiceJoinGroupId = null
        _state.update { it.copy(pendingVoicePermissionRequest = false) }
        if (!granted) {
            _state.update {
                it.copy(voice = VoiceRoomState.Failed(VoiceRoomFailureReason.MICROPHONE_PERMISSION_DENIED))
            }
            return
        }
        scope.launch { handleVoiceResult(voiceRepository.join(groupId)) }
    }

    override fun onLeaveVoice() { scope.launch { handleVoiceResult(voiceRepository.leave()) } }
    override fun onSetMuted(muted: Boolean) { scope.launch { handleVoiceResult(voiceRepository.setMuted(muted)) } }
    override fun onBack() {
        scope.launch {
            stopSharingSession()
            pendingVoiceJoinGroupId = null
            runCatching { voiceRepository.leave() }
            onBackRequested()
        }
    }

    private suspend fun stopSharingRuntime() {
        sharingJob?.cancel()
        sharingJob = null
        runCatching { locationProvider.stop() }
    }

    private suspend fun stopSharingSession() {
        val sharing = state.value.sharing ?: socialRepository.activeSharing.value
        stopSharingRuntime()
        if (sharing != null) {
            runCatching { socialRepository.stopSharing(sharing.groupId) }
            _state.update { it.copy(sharing = null) }
        }
    }

    private suspend fun refreshSocial() {
        if (SocialSessionPolicy.requiresAuthentication(state.value.session)) return
        _state.update { it.copy(isLoading = true, error = null) }
        val groups = socialRepository.listGroups()
        val friends = socialRepository.listFriends()
        groups.errorOrNull()?.let { error -> _state.update { it.copy(error = error.message()) } }
        friends.errorOrNull()?.let { error -> _state.update { it.copy(error = error.message()) } }
        _state.update {
            it.copy(
                groups = groups.valueOrNull().orEmpty(),
                friends = friends.valueOrNull().orEmpty(),
                isLoading = false,
            )
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

private fun <T> SocialResult<T>.valueOrNull(): T? = (this as? SocialResult.Success<T>)?.value
private fun <T> SocialResult<T>.errorOrNull(): SocialFailure? = (this as? SocialResult.Failure)?.error

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

@OptIn(kotlin.time.ExperimentalTime::class)
private fun epochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
