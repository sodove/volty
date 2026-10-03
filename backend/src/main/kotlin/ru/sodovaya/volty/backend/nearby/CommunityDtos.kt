package ru.sodovaya.volty.backend.nearby

import kotlinx.serialization.Serializable

@Serializable data class CommunityCreateRequest(val name: String, val description: String = "", val visibility: String = "PRIVATE")
@Serializable data class CommunitySummaryDto(val id: String, val name: String, val description: String,
                                            val visibility: String, val createdAtEpochMillis: Long)
@Serializable data class CommunityListResponse(val items: List<CommunitySummaryDto>)
@Serializable data class CommunityChannelDto(val id: String, val name: String, val isDefault: Boolean)
@Serializable data class CommunityDetailResponse(val revision: Long, val community: CommunitySummaryDto,
                                                val ownerId: String, val channels: List<CommunityChannelDto>)

internal fun CommunitySummary.dto() = CommunitySummaryDto(id, name, description, visibility.name, createdAtEpochMillis)
internal fun CommunitySnapshot.dto() = CommunityDetailResponse(revision, community.dto(), community.ownerId,
    channels.map { CommunityChannelDto(it.id, it.name, it.isDefault) })
