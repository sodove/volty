package ru.sodovaya.volty.backend.nearby

import kotlinx.serialization.Serializable

@Serializable data class ChannelCreateRequest(val name: String)
@Serializable data class ChannelDeleteRequest(val operationId: String)
@Serializable data class ChannelCreationResponse(val communityId: String, val channel: CommunityChannelDto, val revision: Long)
@Serializable data class ChannelDeletionResponse(val operationId: String, val communityId: String, val channelId: String,
                                               val revision: Long, val cleanupPending: Boolean)
