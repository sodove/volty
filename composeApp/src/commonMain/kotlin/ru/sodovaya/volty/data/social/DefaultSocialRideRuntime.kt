package ru.sodovaya.volty.data.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.sodovaya.volty.domain.social.LocationProvider
import ru.sodovaya.volty.domain.social.LocationSharePolicy
import ru.sodovaya.volty.domain.social.LocationSnapshotStatus
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.ShareSessionRequest
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialParticipantMarker
import ru.sodovaya.volty.domain.social.SocialRepository
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialRideRuntime
import ru.sodovaya.volty.domain.social.SocialRuntimeState
import ru.sodovaya.volty.domain.social.SocialShareSessionCoordinator
import ru.sodovaya.volty.domain.social.TelemetryShareProfile
import ru.sodovaya.volty.domain.social.VoiceRoomRepository
import ru.sodovaya.volty.domain.social.VoiceRoomState

class DefaultSocialRideRuntime(
    private val socialRepository: SocialRepository,
    private val voiceRepository: VoiceRoomRepository,
    private val locationProvider: LocationProvider,
    private val sharingCoordinator: SocialShareSessionCoordinator,
    val store: SocialRuntimeStore = SocialRuntimeStore(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()),
    private val nowEpochMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    private val freshnessTickerIntervalMillis: Long? = 1_000L,
) : SocialRideRuntime {
    override val state: StateFlow<SocialRuntimeState> = store.state
    override val locationPermissions: List<String> = locationProvider.requiredPermissions
    override val voicePermissions: List<String> = voiceRepository.requiredPermissions
    private var liveJob: Job? = null
    private var sharingJob: Job? = null
    private var freshnessJob: Job? = null
    private var pendingVoiceGroupId: RideGroupId? = null
    private var cachedSnapshot: ru.sodovaya.volty.domain.social.LiveGroupSnapshot? = null

    init {
        scope.launch {
            voiceRepository.state.collect { store.setVoice(it) }
        }
        scope.launch {
            socialRepository.activeSharing.collect { active ->
                if (state.value.sharing == null || active == null) store.setSharing(active)
            }
        }
    }

    override fun selectGroup(group: RideGroup) {
        val previous = state.value.selectedGroup?.id
        if (previous == group.id) {
            store.selectGroup(group)
            return
        }
        val previousSharing = state.value.sharing
        liveJob?.cancel()
        freshnessJob?.cancel()
        store.selectGroup(group)
        cachedSnapshot = null
        freshnessJob = freshnessTickerIntervalMillis?.let { intervalMillis ->
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(intervalMillis)
                    refreshLiveProjection()
                }
            }
        }
        scope.launch {
            stopSharingFor(previousSharing)
            pendingVoiceGroupId = null
            if (previous != null) runCatching { voiceRepository.leave() }
            liveJob = scope.launch {
                socialRepository.observeGroup(group.id).collect { event ->
                    if (state.value.selectedGroup?.id != group.id) return@collect
                    applyLiveEvent(event)
                }
            }
        }
    }

    override fun clearGroup() {
        val previousSharing = state.value.sharing
        val previousVoice = state.value.voice
        liveJob?.cancel()
        liveJob = null
        freshnessJob?.cancel()
        freshnessJob = null
        cachedSnapshot = null
        store.clear()
        scope.launch {
            stopSharingFor(previousSharing)
            if (previousVoice is VoiceRoomState.Joined || previousVoice is VoiceRoomState.Joining) {
                runCatching { voiceRepository.leave() }
            }
            pendingVoiceGroupId = null
        }
    }

    override fun setShareProfile(profile: TelemetryShareProfile) = store.setShareProfile(profile)

    override fun requestVoicePermission(groupId: RideGroupId) {
        pendingVoiceGroupId = groupId
        store.setPendingVoicePermission(true)
    }

    override fun onBack() = Unit
    override fun onNavigationChanged() = Unit

    override suspend fun onVoicePermissionResult(granted: Boolean): SocialResult<Unit> {
        val groupId = pendingVoiceGroupId ?: return SocialResult.Success(Unit)
        pendingVoiceGroupId = null
        store.setPendingVoicePermission(false)
        if (!granted) {
            store.setVoice(VoiceRoomState.Failed(ru.sodovaya.volty.domain.social.VoiceRoomFailureReason.MICROPHONE_PERMISSION_DENIED))
            return SocialResult.Failure(SocialFailure.InvalidRequest("Microphone permission denied"))
        }
        return voiceRepository.join(groupId)
    }

    override suspend fun startSharing(ttlMillis: Long): SocialResult<SharingSession> =
        startSharingInternal(ttlMillis, renew = false)

    override suspend fun renewSharing(ttlMillis: Long): SocialResult<SharingSession> =
        startSharingInternal(ttlMillis, renew = true)

    private suspend fun startSharingInternal(ttlMillis: Long, renew: Boolean): SocialResult<SharingSession> {
        val group = state.value.selectedGroup ?: return failure("No group is selected")
        val request = ShareSessionRequest(
            groupId = group.id,
            profile = state.value.shareProfile,
            ttlMillis = ttlMillis,
            startedAtEpochMillis = epochMillis(),
        )
        val result = if (renew) socialRepository.renewSharing(request) else socialRepository.startSharing(request)
        if (result is SocialResult.Success) {
            store.setSharing(result.value)
            sharingJob?.cancel()
            sharingJob = scope.launch {
                locationProvider.updates.collect { location ->
                    sharingCoordinator.publish(group.id, result.value.profile, location)
                }
            }
            try {
                locationProvider.start()
            } catch (error: Throwable) {
                sharingJob?.cancel()
                sharingJob = null
                store.setSharing(null)
                runCatching { socialRepository.stopSharing(group.id) }
                return SocialResult.Failure(
                    SocialFailure.Network(error.message ?: "Location sharing could not start"),
                )
            }
        } else {
            runCatching { locationProvider.stop() }
        }
        return result
    }

    override suspend fun stopSharing(): SocialResult<Unit> {
        return stopSharingFor(state.value.sharing)
    }

    private suspend fun stopSharingFor(knownSharing: SharingSession?): SocialResult<Unit> {
        val sharing = knownSharing ?: socialRepository.activeSharing.value
        stopSharingInternal()
        return if (sharing == null) SocialResult.Success(Unit)
        else socialRepository.stopSharing(sharing.groupId)
    }

    private suspend fun stopSharingInternal() {
        sharingJob?.cancel()
        sharingJob = null
        runCatching { locationProvider.stop() }
        store.setSharing(null)
    }

    override suspend fun joinVoice(): SocialResult<Unit> {
        val group = state.value.selectedGroup ?: return failure("No group is selected")
        return voiceRepository.join(group.id)
    }

    override suspend fun leaveVoice(): SocialResult<Unit> = voiceRepository.leave()

    override suspend fun setMuted(muted: Boolean): SocialResult<Unit> = voiceRepository.setMuted(muted)

    override suspend fun logoutCleanup() {
        stopSharing()
        pendingVoiceGroupId = null
        runCatching { voiceRepository.leave() }
        liveJob?.cancel()
        liveJob = null
        freshnessJob?.cancel()
        freshnessJob = null
        cachedSnapshot = null
        store.clear()
    }

    override fun close() {
        liveJob?.cancel()
        sharingJob?.cancel()
        freshnessJob?.cancel()
        scope.cancel()
    }

    /** Re-evaluates the cached snapshot without requiring a WebSocket event. */
    internal fun refreshLiveProjection() {
        val snapshot = cachedSnapshot ?: return
        if (state.value.selectedGroup?.id != snapshot.groupId) return
        store.setLive(
            event = SocialLiveEvent.Snapshot(snapshot),
            markers = markersForSnapshot(snapshot, nowEpochMillis()),
        )
    }

    private fun applyLiveEvent(event: SocialLiveEvent) {
        when (event) {
            is SocialLiveEvent.Snapshot -> {
                cachedSnapshot = event.value
                store.setLive(event, markersForSnapshot(event.value, nowEpochMillis()))
            }
            is SocialLiveEvent.Failure -> {
                if (event.error is SocialFailure.Network && cachedSnapshot != null) {
                    refreshLiveProjection()
                } else if (event.error is SocialFailure.NotFound || event.error is SocialFailure.Forbidden) {
                    clearGroup()
                } else {
                    store.setLive(event, state.value.markers)
                }
            }
            is SocialLiveEvent.ShareRevoked,
            is SocialLiveEvent.ShareExpired -> {
                val snapshot = cachedSnapshot
                if (snapshot == null) {
                    store.setLive(event, markersFor(event))
                } else {
                    val updated = snapshot.copy(
                        participants = snapshot.participants.filterNot {
                            it.userId == when (event) {
                                is SocialLiveEvent.ShareRevoked -> event.userId
                                is SocialLiveEvent.ShareExpired -> event.userId
                                else -> error("unreachable")
                            }
                        },
                    )
                    cachedSnapshot = updated
                    store.setLive(
                        SocialLiveEvent.Snapshot(updated),
                        markersForSnapshot(updated, nowEpochMillis()),
                    )
                }
            }
        }
    }

    private fun markersFor(event: SocialLiveEvent): List<SocialParticipantMarker> = when (event) {
        is SocialLiveEvent.Snapshot -> markersForSnapshot(event.value, nowEpochMillis())
        is SocialLiveEvent.ShareRevoked -> state.value.markers.filterNot { it.userId == event.userId.value }
        is SocialLiveEvent.ShareExpired -> state.value.markers.filterNot { it.userId == event.userId.value }
        is SocialLiveEvent.Failure -> state.value.markers
    }

    private fun markersForSnapshot(
        snapshot: ru.sodovaya.volty.domain.social.LiveGroupSnapshot,
        now: Long,
    ): List<SocialParticipantMarker> = snapshot.participants.mapNotNull { participant ->
            val location = participant.location ?: return@mapNotNull null
            if (location.capturedAtEpochMillis > now) return@mapNotNull null
            val snapshotIsStale = LocationSharePolicy.snapshotStatus(location, now) == LocationSnapshotStatus.STALE
            SocialParticipantMarker(
                userId = participant.userId.value,
                label = participant.displayName,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracyMeters,
                presence = if (snapshotIsStale && participant.presence == PresenceStatus.ONLINE) {
                    PresenceStatus.STALE
                } else {
                    participant.presence
                },
                stale = participant.presence == PresenceStatus.STALE || snapshotIsStale,
            )
        }

    private fun <T> failure(message: String): SocialResult<T> =
        SocialResult.Failure(SocialFailure.InvalidRequest(message))

    private fun epochMillis(): Long = nowEpochMillis()
}
