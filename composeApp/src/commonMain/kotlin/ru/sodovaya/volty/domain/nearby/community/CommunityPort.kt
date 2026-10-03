package ru.sodovaya.volty.domain.nearby.community

enum class CommunityVisibility { PRIVATE, PUBLIC }
data class CommunitySummary(val id: String, val name: String, val description: String,
                            val visibility: CommunityVisibility, val createdAtEpochMillis: Long)
data class CommunityChannel(val id: String, val name: String, val isDefault: Boolean)
data class CommunityDetails(val revision: Long, val community: CommunitySummary,
                            val ownerId: String, val channels: List<CommunityChannel>)
enum class CommunityFailure { UNAUTHORIZED, ACCESS_DENIED, CONFLICT, UNAVAILABLE, INVALID_RESPONSE }
sealed interface CommunityResult<out T> {
    data class Ready<T>(val value: T) : CommunityResult<T>
    data class Failed(val reason: CommunityFailure) : CommunityResult<Nothing>
}

/** Permanent membership only. None of these operations starts voice or sharing. */
interface CommunityPort {
    suspend fun memberships(after: String? = null, limit: Int = 20): CommunityResult<List<CommunitySummary>>
    suspend fun catalog(query: String = "", after: String? = null, limit: Int = 20): CommunityResult<List<CommunitySummary>>
    suspend fun detail(community: String): CommunityResult<CommunityDetails>
    suspend fun create(community: String, name: String, description: String = "",
                       visibility: CommunityVisibility = CommunityVisibility.PRIVATE): CommunityResult<CommunityDetails>
    suspend fun joinPublic(community: String): CommunityResult<CommunityDetails>
}
