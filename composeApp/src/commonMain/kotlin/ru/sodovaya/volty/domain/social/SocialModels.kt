package ru.sodovaya.volty.domain.social

import kotlinx.serialization.Serializable

/** Opaque identifiers issued by the social server; never BLE addresses or local vehicle ids. */
@Serializable
@JvmInline
value class SocialUserId(val value: String) {
    init {
        require(value.isNotBlank()) { "Social user id must not be blank" }
    }
}

@Serializable
@JvmInline
value class RideGroupId(val value: String) {
    init {
        require(value.isNotBlank()) { "Ride group id must not be blank" }
    }
}

@Serializable
@JvmInline
value class FriendshipId(val value: String) {
    init {
        require(value.isNotBlank()) { "Friendship id must not be blank" }
    }
}

@Serializable
enum class SessionTokenState {
    ACTIVE,
    EXPIRED,
    REVOKED,
}

@Serializable
sealed interface SocialSession {
    @Serializable
    data object LoggedOut : SocialSession

    @Serializable
    data object Authenticating : SocialSession

    @Serializable
    data class Authenticated(
        val userId: SocialUserId,
        val displayName: String,
        val tokenState: SessionTokenState,
        val emailVerified: Boolean = false,
    ) : SocialSession {
        init {
            require(displayName.isNotBlank()) { "Display name must not be blank" }
        }
    }
}

@Serializable
enum class FriendshipState {
    ACCEPTED,
    REQUEST_SENT,
    REQUEST_RECEIVED,
    BLOCKED,
}

@Serializable
data class FriendSummary(
    val friendshipId: FriendshipId,
    val userId: SocialUserId,
    val displayName: String,
    val state: FriendshipState,
)

@Serializable
enum class GroupMemberRole {
    OWNER,
    MEMBER,
}

@Serializable
data class GroupMemberSummary(
    val userId: SocialUserId,
    val displayName: String,
    val role: GroupMemberRole,
)

@Serializable
data class RideGroup(
    val id: RideGroupId,
    val name: String,
    val ownerId: SocialUserId,
    val members: List<GroupMemberSummary> = emptyList(),
    val inviteOnly: Boolean = true,
    val inviteExpiresAtEpochMillis: Long? = null,
) {
    init {
        require(name.isNotBlank()) { "Ride group name must not be blank" }
        require(!inviteOnly || inviteExpiresAtEpochMillis == null || inviteExpiresAtEpochMillis > 0L) {
            "An invite expiry must be a positive epoch timestamp"
        }
    }
}

@Serializable
enum class PresenceStatus {
    ONLINE,
    STALE,
    OFFLINE,
}

@Serializable
data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAtEpochMillis: Long,
    val staleAfterEpochMillis: Long,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Latitude is outside [-90, 90]" }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude is outside [-180, 180]"
        }
        require(accuracyMeters.isFinite() && accuracyMeters >= 0.0) {
            "Location accuracy must be finite and non-negative"
        }
        require(staleAfterEpochMillis >= capturedAtEpochMillis) {
            "Location cannot become stale before it was captured"
        }
    }
}

@Serializable
data class LocationShareWindow(
    val audienceGroupId: RideGroupId,
    val startedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(expiresAtEpochMillis > startedAtEpochMillis) {
            "Location sharing must have a positive duration"
        }
    }
}

@Serializable
enum class TelemetryShareProfile {
    LOCATION,
    RIDE,
    FULL,
}

/** A metric distinguishes unsupported hardware from a value not earned yet. */
@Serializable
data class TelemetryNumber(
    val supported: Boolean,
    val known: Boolean,
    val value: Double? = null,
) {
    init {
        require(!known || supported) { "A known metric must be supported" }
        require(!known || value != null) { "A known metric must carry a value" }
        require(value == null || value.isFinite()) { "Telemetry values must be finite" }
        require(supported || (!known && value == null)) {
            "Unsupported metrics must not carry a value"
        }
    }

    companion object {
        fun unsupported() = TelemetryNumber(supported = false, known = false)

        fun unknown(supported: Boolean = true) = TelemetryNumber(
            supported = supported,
            known = false,
        )

        fun known(value: Double) = TelemetryNumber(
            supported = true,
            known = true,
            value = value,
        )
    }
}

@Serializable
data class TelemetryFaults(
    val supported: Boolean,
    val known: Boolean,
    val values: List<String>? = null,
) {
    init {
        require(!known || supported) { "Known faults must be supported" }
        require(!known || values != null) { "Known faults must carry a list" }
        require(supported || (!known && values == null)) {
            "Unsupported faults must not carry values"
        }
    }

    companion object {
        fun unsupported() = TelemetryFaults(supported = false, known = false)

        fun unknown(supported: Boolean = true) = TelemetryFaults(
            supported = supported,
            known = false,
        )

        fun known(values: List<String>) = TelemetryFaults(
            supported = true,
            known = true,
            values = values,
        )
    }
}

/** Only metrics already earned by local producers may cross the social boundary. */
@Serializable
data class EarnedTelemetry(
    val speedKmh: TelemetryNumber = TelemetryNumber.unsupported(),
    val batterySocFraction: TelemetryNumber = TelemetryNumber.unsupported(),
    val packVoltageV: TelemetryNumber = TelemetryNumber.unsupported(),
    val batteryCurrentA: TelemetryNumber = TelemetryNumber.unsupported(),
    val powerW: TelemetryNumber = TelemetryNumber.unsupported(),
    val escTempC: TelemetryNumber = TelemetryNumber.unsupported(),
    val motorTempC: TelemetryNumber = TelemetryNumber.unsupported(),
    val cellMinV: TelemetryNumber = TelemetryNumber.unsupported(),
    val cellMaxV: TelemetryNumber = TelemetryNumber.unsupported(),
    val cellDeltaV: TelemetryNumber = TelemetryNumber.unsupported(),
    val faults: TelemetryFaults = TelemetryFaults.unsupported(),
)

@Serializable
data class SharedTelemetry(
    val schemaVersion: Int = 1,
    val profile: TelemetryShareProfile,
    val speedKmh: TelemetryNumber = TelemetryNumber.unsupported(),
    val batterySocFraction: TelemetryNumber = TelemetryNumber.unsupported(),
    val packVoltageV: TelemetryNumber = TelemetryNumber.unsupported(),
    val batteryCurrentA: TelemetryNumber = TelemetryNumber.unsupported(),
    val powerW: TelemetryNumber = TelemetryNumber.unsupported(),
    val escTempC: TelemetryNumber = TelemetryNumber.unsupported(),
    val motorTempC: TelemetryNumber = TelemetryNumber.unsupported(),
    val cellMinV: TelemetryNumber = TelemetryNumber.unsupported(),
    val cellMaxV: TelemetryNumber = TelemetryNumber.unsupported(),
    val cellDeltaV: TelemetryNumber = TelemetryNumber.unsupported(),
    val faults: TelemetryFaults = TelemetryFaults.unsupported(),
)

@Serializable
data class ParticipantSnapshot(
    val userId: SocialUserId,
    val displayName: String,
    val presence: PresenceStatus,
    val location: LocationSnapshot?,
    val telemetry: SharedTelemetry?,
    val lastSeenAtEpochMillis: Long,
)

@Serializable
data class LiveGroupSnapshot(
    val groupId: RideGroupId,
    val capturedAtEpochMillis: Long,
    val participants: List<ParticipantSnapshot>,
)

@Serializable
data class SharingSession(
    val groupId: RideGroupId,
    val profile: TelemetryShareProfile,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class ParticipantShareUpdate(
    val capturedAtEpochMillis: Long,
    val location: LocationSnapshot?,
    val telemetry: SharedTelemetry?,
)

@Serializable
data class VoiceParticipant(
    val userId: SocialUserId,
    val displayName: String,
    val isSpeaking: Boolean,
)
