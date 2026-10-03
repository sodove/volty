package ru.sodovaya.volty.backend.nearby

sealed interface CommunityDeletionResult {
    /** SQL deletion committed. Exact durable targets still need SFU cleanup. */
    data class Deleted(val communityId: String, val revision: Long, val cleanupSessions: List<SessionJournalEntry>) : CommunityDeletionResult
    data object Denied : CommunityDeletionResult
    data object Conflict : CommunityDeletionResult
}
