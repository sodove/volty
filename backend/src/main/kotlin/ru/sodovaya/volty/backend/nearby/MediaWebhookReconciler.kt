package ru.sodovaya.volty.backend.nearby

/** One bounded intent. Owner schedules/backoffs; no network happens within an inbox/actor transaction. */
class MediaWebhookReconciler(
    private val inbox: MediaWebhookInbox,
    private val repository: SessionRepository,
    private val sessions: SessionService,
) {
    /** False means no pending item or retryable work; it never acknowledges failed observation. */
    fun reconcileNext(): Boolean {
        val item = inbox.pending(1).firstOrNull() ?: return false
        val event = item.event
        val row = repository.findSessionById(item.targetSessionId)
        if (row == null || row.sfuEpoch?.roomEpoch != event.roomEpoch || row.operation.terminal) {
            return inbox.complete(event.eventId, event.bodySha256, item.targetSessionId)
        }
        // Notification SID/timestamp cannot command stop: a newer transport may now own this session.
        return when (sessions.refreshMedia(row.operation.actorId, row.operation.operationId)) {
            is SessionAction.Confirmed, is SessionAction.Ended, is SessionAction.Denied -> inbox.complete(event.eventId, event.bodySha256, item.targetSessionId)
            is SessionAction.Unavailable, is SessionAction.Pending, is SessionAction.Ready -> false
        }
    }
}
