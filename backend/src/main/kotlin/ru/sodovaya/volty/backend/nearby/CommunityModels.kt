package ru.sodovaya.volty.backend.nearby

enum class CommunityVisibility { PRIVATE, PUBLIC }

data class CommunitySummary(
    val id: String, val name: String, val description: String,
    val visibility: CommunityVisibility, val ownerId: String, val createdAtEpochMillis: Long,
)
data class CommunityChannel(val id: String, val name: String, val isDefault: Boolean)
data class CommunitySnapshot(val revision: Long, val community: CommunitySummary, val channels: List<CommunityChannel>)

sealed interface CommunityWriteResult {
    data class Accepted(val snapshot: CommunitySnapshot) : CommunityWriteResult
    data object Denied : CommunityWriteResult
    data object Conflict : CommunityWriteResult
}
