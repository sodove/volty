package ru.sodovaya.volty.backend.nearby

data class SfuEpoch(val roomEpoch: String, val nodeEpoch: String) {
    init { require(roomEpoch.isNotBlank() && roomEpoch.length <= 128 && nodeEpoch.isNotBlank() && nodeEpoch.length <= 128) }
}
data class SessionJournalEntry(
    val operation: SessionOperation,
    val sfuEpoch: SfuEpoch?,
    val admissionPending: Boolean,
    val cleanupPending: Boolean,
)
data class SessionClaim(val operationId: String, val sessionId: String, val sfuEpoch: SfuEpoch)
data class SessionJournalResult(val state: SessionJournalEntry?, val rejection: OperationRejection? = null)

/** Server timestamps only. No network effects may execute inside these transactions. */
interface SessionRepository {
    fun begin(actor: String, operation: String, destination: String, session: String, sfuEpoch: SfuEpoch, now: () -> Long): SessionJournalResult
    fun cancel(actor: String, operation: String, now: () -> Long): SessionJournalResult
    fun admit(actor: String, operation: String, session: String, sfuEpoch: SfuEpoch, now: () -> Long, confirmedDeadline: Long): SessionJournalResult
    fun heartbeat(actor: String, operation: String, session: String, sfuEpoch: SfuEpoch, now: () -> Long, confirmedDeadline: Long): SessionJournalResult
    fun expire(actor: String, operation: String, now: () -> Long): SessionJournalResult
    fun get(actor: String, operation: String): SessionJournalEntry?
    fun currentClaim(actor: String): SessionClaim?
    fun pendingWork(limit: Int): List<SessionJournalEntry>
    fun pendingWork(actor: String, limit: Int): List<SessionJournalEntry>
    fun pendingCleanup(actor: String, limit: Int): List<SessionJournalEntry>
    fun dueOperations(now: Long, limit: Int): List<SessionJournalEntry>
    fun dueOperations(actor: String, now: Long, limit: Int): List<SessionJournalEntry>
    fun completeCleanup(actor: String, operation: String, session: String, sfuEpoch: SfuEpoch): Boolean
    /** Producer must be local CPU work only. It executes under the actor lock. */
    fun <T : Any> withActiveSession(actor: String, operation: String, now: () -> Long, producer: (SessionJournalEntry) -> T): T?
}
