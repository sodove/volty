package ru.sodovaya.volty.backend.nearby

sealed interface ChannelDeletionResult {
    data class Deleted(val communityId: String, val channelId: String, val revision: Long,
                       val cleanupSessions: List<SessionJournalEntry>) : ChannelDeletionResult
    data object Denied : ChannelDeletionResult
    data object Conflict : ChannelDeletionResult
}
