package ru.sodovaya.volty.backend.nearby

data class SfuEpoch(val roomEpoch: String, val nodeEpoch: String) {
    init { require(roomEpoch.isNotBlank() && roomEpoch.length <= 128 && nodeEpoch.isNotBlank() && nodeEpoch.length <= 128) }
}
data class SessionJournalEntry(
    val operation: SessionOperation,
    val sfuEpoch: SfuEpoch?,
    val admissionPending: Boolean,
    val cleanupPending: Boolean,
    val media: SessionMedia = SessionMedia(),
)

/** Version fences concurrent observation replies; it is separate from visible roster revision. */
data class SessionMedia(val participantSid: String? = null, val state: SfuParticipantState? = null, val observationVersion: Long = 0) {
    init {
        require(observationVersion >= 0 && (participantSid == null) == (state == null))
        require(participantSid == null || (participantSid.isNotBlank() && participantSid.length <= 256))
    }
}
data class SessionClaim(val operationId: String, val sessionId: String, val sfuEpoch: SfuEpoch)
data class SessionJournalResult(val state: SessionJournalEntry?, val rejection: OperationRejection? = null)

/** Server timestamps only. No network effects may execute inside these transactions. */
interface SessionRepository {
    fun begin(actor: String, operation: String, destination: String, session: String, sfuEpoch: SfuEpoch, now: () -> Long, replace: Boolean = false): SessionJournalResult
    fun cancel(actor: String, operation: String, now: () -> Long): SessionJournalResult
    /** Trusted internal loss evidence, never a client-provided assertion or user disconnect. */
    fun invalidateMedia(actor: String, operation: String, now: () -> Long): SessionJournalResult = cancel(actor, operation, now)
    fun admit(actor: String, operation: String, session: String, sfuEpoch: SfuEpoch, now: () -> Long, confirmedDeadline: Long): SessionJournalResult
    fun heartbeat(actor: String, operation: String, session: String, sfuEpoch: SfuEpoch, now: () -> Long, confirmedDeadline: Long): SessionJournalResult
    fun observeMedia(actor: String, operation: String, session: String, sfuEpoch: SfuEpoch, expectedVersion: Long, observation: SfuParticipantObservation, now: () -> Long): SessionJournalResult
    fun heartbeatConnected(actor: String, operation: String, session: String, sfuEpoch: SfuEpoch, expectedVersion: Long, participantSid: String, now: () -> Long, confirmedDeadline: Long): SessionJournalResult
    fun expire(actor: String, operation: String, now: () -> Long): SessionJournalResult
    fun get(actor: String, operation: String): SessionJournalEntry?
    fun findSession(actor: String, session: String): SessionJournalEntry?
    /** Trusted internal reconciliation only; public endpoints still require exact actor ownership. */
    fun findSessionById(session: String): SessionJournalEntry?
    fun currentClaim(actor: String): SessionClaim?
    fun pendingWork(limit: Int): List<SessionJournalEntry>
    fun pendingWork(actor: String, limit: Int): List<SessionJournalEntry>
    fun pendingCleanup(actor: String, limit: Int): List<SessionJournalEntry>
    fun dueOperations(now: Long, limit: Int): List<SessionJournalEntry>
    fun dueOperations(actor: String, now: Long, limit: Int): List<SessionJournalEntry>
    fun completeCleanup(actor: String, operation: String, session: String, sfuEpoch: SfuEpoch): Boolean
    /** Local CPU work only, under original actor ownership (both original actors for calls). */
    fun <T : Any> withActiveSession(actor: String, operation: String, now: () -> Long, producer: (SessionJournalEntry) -> T): T?
}
