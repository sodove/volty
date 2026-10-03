package ru.sodovaya.volty.domain.nearby.community

data class ChannelCreationReceipt(val communityId: String, val channelId: String, val name: String, val revision: Long)
data class ChannelDeletionReceipt(val operationId: String, val communityId: String, val channelId: String, val revision: Long, val cleanupPending: Boolean)
enum class ChannelCommandFailure { UNAUTHORIZED, ACCESS_DENIED, CONFLICT, LIMIT_REACHED, UNAVAILABLE, INVALID_RESPONSE }
sealed interface ChannelCreationCommandResult {
    data class Created(val receipt: ChannelCreationReceipt) : ChannelCreationCommandResult
    data class Failed(val reason: ChannelCommandFailure) : ChannelCreationCommandResult
}
sealed interface ChannelDeletionCommandResult {
    data class Deleted(val receipt: ChannelDeletionReceipt) : ChannelDeletionCommandResult
    data class Failed(val reason: ChannelCommandFailure) : ChannelDeletionCommandResult
}
/** Caller retains stable IDs for retries. No membership, capture or sharing side effects. */
interface ChannelCommandsPort {
    suspend fun create(community: String, channel: String, name: String): ChannelCreationCommandResult
    suspend fun delete(community: String, channel: String, operation: String): ChannelDeletionCommandResult
}
