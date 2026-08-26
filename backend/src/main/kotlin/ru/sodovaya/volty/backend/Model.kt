package ru.sodovaya.volty.backend

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class HealthResponse(val status: String, val service: String, val version: String)

@Serializable
data class RegisterRequest(val email: String, val password: String, val displayName: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class SessionResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class ProfileResponse(
    val userId: String,
    val displayName: String,
    val tokenState: String = "ACTIVE",
    val emailVerified: Boolean,
)

@Serializable
data class ProfileUpdateRequest(val displayName: String)

@Serializable
data class VerifyResponse(val verified: Boolean = true)

@Serializable
data class PasswordResetRequest(val email: String)

@Serializable
data class PasswordResetConfirmRequest(val token: String, val newPassword: String)

@Serializable
data class TokenRequest(val token: String)

@Serializable
data class FriendRequestDto(val userId: String)

@Serializable
data class FriendRespondRequest(val accept: Boolean)

@Serializable
data class FriendRequestResultDto(
    val friendshipId: String,
    val state: String,
)

@Serializable
data class FriendSummaryDto(
    val friendshipId: String,
    val userId: String,
    val displayName: String,
    val state: String,
)

@Serializable
data class CreateGroupRequest(val name: String)

@Serializable
data class JoinGroupRequest(val inviteCode: String)

@Serializable
data class UserSearchResultDto(
    val userId: String,
    val displayName: String,
    val friendshipId: String? = null,
    val state: String? = null,
)

@Serializable
data class RenewSharingRequest(
    val ttlMillis: Long,
    val startedAtEpochMillis: Long,
)

@Serializable
data class GroupMemberDto(val userId: String, val displayName: String, val role: String)

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    val ownerId: String,
    val members: List<GroupMemberDto>,
    val inviteOnly: Boolean = true,
    val inviteExpiresAtEpochMillis: Long? = null,
    val inviteCode: String? = null,
)

@Serializable
data class StartSharingRequest(
    val groupId: String,
    val profile: String,
    val ttlMillis: Long,
    val startedAtEpochMillis: Long,
)

@Serializable
data class SharingResponse(
    val groupId: String,
    val profile: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class TelemetryNumberDto(
    val supported: Boolean,
    val known: Boolean,
    val value: Double? = null,
)

@Serializable
data class TelemetryFaultsDto(
    val supported: Boolean,
    val known: Boolean,
    val values: List<String>? = null,
)

@Serializable
data class SharedTelemetryDto(
    val schemaVersion: Int = 1,
    val profile: String,
    val speedKmh: TelemetryNumberDto = TelemetryNumberDto(false, false),
    val batterySocFraction: TelemetryNumberDto = TelemetryNumberDto(false, false),
    val packVoltageV: TelemetryNumberDto = TelemetryNumberDto(false, false),
    val batteryCurrentA: TelemetryNumberDto = TelemetryNumberDto(false, false),
    val powerW: TelemetryNumberDto = TelemetryNumberDto(false, false),
    val escTempC: TelemetryNumberDto = TelemetryNumberDto(false, false),
    val motorTempC: TelemetryNumberDto = TelemetryNumberDto(false, false),
    val cellMinV: TelemetryNumberDto = TelemetryNumberDto(false, false),
    val cellMaxV: TelemetryNumberDto = TelemetryNumberDto(false, false),
    val cellDeltaV: TelemetryNumberDto = TelemetryNumberDto(false, false),
    val faults: TelemetryFaultsDto = TelemetryFaultsDto(false, false),
)

@Serializable
data class LocationDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAtEpochMillis: Long,
    val staleAfterEpochMillis: Long,
)

@Serializable
data class PublishSharingRequest(
    val capturedAtEpochMillis: Long,
    val location: LocationDto?,
    val telemetry: SharedTelemetryDto?,
)

@Serializable
data class ParticipantDto(
    val userId: String,
    val displayName: String,
    val presence: String,
    val location: LocationDto?,
    val telemetry: SharedTelemetryDto?,
    val lastSeenAtEpochMillis: Long,
)

@Serializable
data class LiveSnapshotDto(
    val groupId: String,
    val capturedAtEpochMillis: Long,
    val participants: List<ParticipantDto>,
)

@Serializable
data class LiveEventDto(
    val type: String,
    val snapshot: LiveSnapshotDto? = null,
    val userId: String? = null,
)

@Serializable
data class VoiceProviderResponse(
    val available: Boolean,
    val provider: String,
    val serverUrl: String? = null,
    val message: String? = null,
)

@Serializable
data class VoiceJoinResponse(
    val provider: String,
    val serverUrl: String,
    val roomId: String,
    val participantToken: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val requestId: String,
    val details: Map<String, String>? = null,
)

class ApiException(
    val statusCode: io.ktor.http.HttpStatusCode,
    val code: String,
    override val message: String,
    val details: Map<String, String>? = null,
) : RuntimeException(message)

class GroupOwnerRequiredException : RuntimeException("Only the group owner can delete it")

object Validation {
    private val emailRegex = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun email(value: String): Result<String> {
        val normalized = value.trim().lowercase()
        return if (normalized.length <= 254 && emailRegex.matches(normalized)) {
            Result.success(normalized)
        } else {
            Result.failure(IllegalArgumentException("email must be valid"))
        }
    }

    fun password(value: String): Result<String> = if (
        value.length in 12..128 && value.any { !it.isWhitespace() }
    ) {
        Result.success(value)
    } else {
        Result.failure(IllegalArgumentException("password must be 12-128 characters and contain a non-whitespace character"))
    }

    fun displayName(value: String): Result<String> {
        val normalized = value.trim()
        return if (normalized.length in 2..80) Result.success(normalized)
        else Result.failure(IllegalArgumentException("displayName must be 2-80 characters"))
    }

    fun groupName(value: String): Result<String> {
        val normalized = value.trim()
        return if (normalized.length in 2..80) Result.success(normalized)
        else Result.failure(IllegalArgumentException("group name must be 2-80 characters"))
    }

    fun searchQuery(value: String): Result<String> {
        val normalized = value.trim()
        return if (normalized.length in 2..254) Result.success(normalized)
        else Result.failure(IllegalArgumentException("search query must be 2-254 characters"))
    }

    fun profile(value: String): Result<String> = when (value.uppercase()) {
        "LOCATION", "RIDE", "FULL" -> Result.success(value.uppercase())
        else -> Result.failure(IllegalArgumentException("profile must be LOCATION, RIDE, or FULL"))
    }
}

data class RefreshTokenRecord(
    val userId: String,
    val raw: String,
    val hash: String,
    val expiresAtEpochSeconds: Long,
    var revoked: Boolean = false,
)

class RefreshTokenService(
    private val secret: ByteArray,
    private val accessTtlSeconds: Long,
    private val refreshTtlSeconds: Long,
) {
    private val random = SecureRandom()
    private val issued = ConcurrentHashMap<String, RefreshTokenRecord>()

    fun issueRefreshToken(userId: String, nowEpochSeconds: Long): RefreshTokenRecord {
        val raw = randomToken()
        val record = RefreshTokenRecord(userId, raw, hash(raw), nowEpochSeconds + refreshTtlSeconds)
        issued[record.hash] = record
        return record
    }

    fun rotate(previous: RefreshTokenRecord, nowEpochSeconds: Long): Result<RefreshTokenRecord> {
        val current = issued[previous.hash]
            ?: return Result.failure(IllegalArgumentException("refresh token is invalid"))
        if (current.revoked || current.expiresAtEpochSeconds <= nowEpochSeconds) {
            return Result.failure(IllegalArgumentException("refresh token is revoked or expired"))
        }
        current.revoked = true
        return Result.success(issueRefreshToken(current.userId, nowEpochSeconds))
    }

    fun verifyHash(raw: String, expectedHash: String): Boolean = MessageDigest.isEqual(
        hash(raw).toByteArray(),
        expectedHash.toByteArray(),
    )

    fun hash(raw: String): String = sha256(raw + Base64.getEncoder().encodeToString(secret))

    fun accessTtlSeconds(): Long = accessTtlSeconds

    private fun randomToken(): String {
        val bytes = ByteArray(48)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

class RateLimiter(private val maxRequests: Int, private val windowSeconds: Long) {
    private data class Bucket(var start: Long, var count: Int)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun allow(key: String, nowEpochSeconds: Long): Boolean {
        val bucket = buckets.compute(key) { _, previous ->
            if (previous == null || nowEpochSeconds - previous.start >= windowSeconds) Bucket(nowEpochSeconds, 1)
            else previous.copy(count = previous.count + 1)
        } ?: return false
        return bucket.count <= maxRequests
    }

    fun retryAfterSeconds(key: String, nowEpochSeconds: Long): Long {
        val bucket = buckets[key] ?: return 0
        return (windowSeconds - (nowEpochSeconds - bucket.start)).coerceAtLeast(1)
    }
}

object SharingRules {
    fun allowsLocation(profile: String): Boolean = profile.uppercase() in setOf("LOCATION", "RIDE", "FULL")
    fun allowsTelemetry(profile: String): Boolean = profile.uppercase() in setOf("RIDE", "FULL")
    fun allowsFullMetrics(profile: String): Boolean = profile.uppercase() == "FULL"

    fun acceptsTelemetry(profile: String, hasTelemetry: Boolean): Boolean =
        if (profile.uppercase() == "LOCATION") !hasTelemetry else allowsTelemetry(profile) && hasTelemetry

    fun isLocationFresh(staleAfter: Long, now: Long): Boolean = staleAfter > now

    fun isTtlValid(ttlMillis: Long, maxTtlMillis: Long): Boolean =
        ttlMillis > 0L && ttlMillis <= maxTtlMillis

    fun isPublishable(startedAt: Long, expiresAt: Long, capturedAt: Long, now: Long): Boolean =
        now >= startedAt && now < expiresAt && capturedAt in startedAt..now
}

enum class LiveEventKind(val wireName: String) {
    SNAPSHOT("snapshot"),
    REVOKED("share_revoked"),
    EXPIRED("share_expired"),
    TERMINATED("subscription_terminated"),
}

fun nowMillis(): Long = Instant.now().toEpochMilli()
fun uuid(): String = UUID.randomUUID().toString()
fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
