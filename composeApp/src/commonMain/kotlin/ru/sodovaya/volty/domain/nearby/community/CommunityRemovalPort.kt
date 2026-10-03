package ru.sodovaya.volty.domain.nearby.community

data class CommunityRemovalReceipt(val operationId: String, val communityId: String, val revision: Long, val cleanupPending: Boolean)
enum class CommunityRemovalFailure {
    UNAUTHORIZED, ACCESS_DENIED, SUCCESSOR_REQUIRED, DELETE_REQUIRED, INVALID_SUCCESSOR,
    CONFLICT, UNAVAILABLE, INVALID_RESPONSE,
}
sealed interface CommunityRemovalResult {
    data class Removed(val receipt: CommunityRemovalReceipt) : CommunityRemovalResult
    data class Failed(val reason: CommunityRemovalFailure) : CommunityRemovalResult
}
/** Permanent membership/removal only. A voice/trip disconnect uses its own controller. */
interface CommunityRemovalPort {
    suspend fun leave(community: String, operation: String, successor: String? = null): CommunityRemovalResult
    suspend fun delete(community: String, operation: String): CommunityRemovalResult
}
