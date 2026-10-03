package ru.sodovaya.volty.backend.nearby

sealed interface ChannelCreationResult {
    data class Created(val channel: CommunityChannel, val revision: Long) : ChannelCreationResult
    data object Denied : ChannelCreationResult
    data object Conflict : ChannelCreationResult
    data object LimitReached : ChannelCreationResult
}
