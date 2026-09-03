package ru.sodovaya.volty.domain.social

import kotlinx.coroutines.flow.StateFlow

/** App-scoped social ride state shared by Nearby and live ride surfaces. */
data class SocialRuntimeState(
    val selectedGroup: RideGroup? = null,
    val liveEvent: SocialLiveEvent? = null,
    val markers: List<SocialParticipantMarker> = emptyList(),
    val sharing: SharingSession? = null,
    val shareProfile: TelemetryShareProfile = TelemetryShareProfile.FULL,
    val voice: VoiceRoomState = VoiceRoomState.Unavailable,
    val pendingVoicePermissionRequest: Boolean = false,
)

data class SocialParticipantMarker(
    val userId: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val presence: PresenceStatus,
    val stale: Boolean,
)

interface SocialRideRuntime {
    val state: StateFlow<SocialRuntimeState>
    val locationPermissions: List<String>
    val voicePermissions: List<String>

    fun selectGroup(group: RideGroup)
    fun clearGroup()
    fun setShareProfile(profile: TelemetryShareProfile)
    fun requestVoicePermission(groupId: RideGroupId)
    fun onBack()
    fun onNavigationChanged()

    suspend fun onVoicePermissionResult(granted: Boolean): SocialResult<Unit>
    suspend fun startSharing(ttlMillis: Long): SocialResult<SharingSession>
    suspend fun renewSharing(ttlMillis: Long): SocialResult<SharingSession>
    suspend fun stopSharing(): SocialResult<Unit>
    suspend fun joinVoice(): SocialResult<Unit>
    suspend fun leaveVoice(): SocialResult<Unit>
    suspend fun setMuted(muted: Boolean): SocialResult<Unit>
    suspend fun logoutCleanup()

    fun close()
}
