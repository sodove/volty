package ru.sodovaya.volty.domain.social

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Android supplies real GPS updates only while an explicit share is active. */
interface LocationProvider {
    /** Runtime permissions requested only after the rider confirms sharing. */
    val requiredPermissions: List<String>
        get() = emptyList()

    val updates: Flow<LocationSnapshot>
    suspend fun start()
    suspend fun stop()
}

/** Bridges earned local telemetry into social publishing without exposing BLE ids. */
interface SocialTelemetrySource {
    val latest: StateFlow<EarnedTelemetry?>
}

class SocialShareSessionCoordinator(
    private val repository: SocialRepository,
    private val telemetry: SocialTelemetrySource,
) {
    suspend fun publish(
        groupId: RideGroupId,
        profile: TelemetryShareProfile,
        location: LocationSnapshot,
    ): SocialResult<Unit> {
        val sharedTelemetry = if (profile == TelemetryShareProfile.LOCATION) {
            null
        } else {
            val earned = telemetry.latest.value ?: EarnedTelemetry()
            TelemetryShareMapper.map(profile, earned)
        }
        return repository.publishSharingUpdate(
            groupId = groupId,
            update = ParticipantShareUpdate(
                capturedAtEpochMillis = location.capturedAtEpochMillis,
                location = location,
                telemetry = sharedTelemetry,
            ),
        )
    }
}
