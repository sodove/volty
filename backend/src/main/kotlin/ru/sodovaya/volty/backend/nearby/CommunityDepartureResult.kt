package ru.sodovaya.volty.backend.nearby

sealed interface CommunityDepartureResult {
    /** Membership committed; media stop still requires exact SFU cleanup acknowledgement. */
    data class Departed(val communityId: String, val revision: Long, val cleanupSessions: List<SessionJournalEntry>) : CommunityDepartureResult
    data object SuccessorRequired : CommunityDepartureResult
    data object DeleteRequired : CommunityDepartureResult
    data object InvalidSuccessor : CommunityDepartureResult
    data object Denied : CommunityDepartureResult
    data object Conflict : CommunityDepartureResult
}
