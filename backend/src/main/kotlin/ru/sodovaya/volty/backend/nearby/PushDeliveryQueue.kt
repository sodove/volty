package ru.sodovaya.volty.backend.nearby

import javax.sql.DataSource
import java.sql.Connection
import java.util.concurrent.ThreadLocalRandom
import kotlinx.coroutines.CancellationException

internal class PushDeliveryClaim(val id: Long, val ticket: String, val attemptNumber: Int, val target: PushDeviceTarget,
                               val hint: CallPushHint, val expiresMillis: Long) {
    override fun toString() = "PushDeliveryClaim(<redacted>)"
}

internal class JdbcPushDeliveryQueue(private val source: DataSource,
                                    private val jitterMillis: () -> Long = { ThreadLocalRandom.current().nextLong(251) }) {
    fun claim(nowMillis: Long, ticket: String): PushDeliveryClaim? {
        require(nowMillis >= 0 && pushDeviceCanonicalId(ticket))
        return pushStorageTransaction(source, true) { c ->
            materializePushEvent(c, nowMillis)
            val row = c.prepareStatement("""
                SELECT q.* FROM nearby_push_deliveries q WHERE NOT q.done
                    AND (q.expires_at_millis<=? OR (q.next_attempt_at_millis<=? AND
                        (q.lease_until_millis IS NULL OR q.lease_until_millis<=?) AND
                        (SELECT not_before_millis FROM nearby_push_delivery_clock WHERE singleton=1)<=?))
                ORDER BY q.delivery_id LIMIT 1 FOR UPDATE OF q SKIP LOCKED
            """.trimIndent()).use {
                for (n in 1..4) it.setLong(n, nowMillis)
                it.executeQuery().use { r -> if (r.next()) r.pushQueueRow() else null }
            } ?: return@pushStorageTransaction null
            val event = pushQueueEvent(c, row.event)
            if (row.expires <= nowMillis || event == null || !pushEventAvailable(c, event, nowMillis)) {
                pushDone(c, row.id); return@pushStorageTransaction null
            }
            if (row.ticket == ticket) return@pushStorageTransaction null
            val target = pushCurrentTarget(c, row.installation, event.actor)
                ?: run { pushDone(c, row.id); return@pushStorageTransaction null }
            val changed = row.generation != target.generation || row.binding != target.bindingId || row.tokenHash != target.tokenHash
            val attempts = if (changed) 0 else row.attempts
            if (attempts >= MAXIMUM_PUSH_ATTEMPTS) { pushDone(c, row.id); return@pushStorageTransaction null }
            c.prepareStatement("""
                UPDATE nearby_push_deliveries SET claim_ticket=?::uuid,lease_until_millis=?,
                    claimed_generation=?,claimed_binding_id=?::uuid,claimed_token_hash=?,attempts=? WHERE delivery_id=?
            """.trimIndent()).use {
                it.setString(1, ticket); it.setLong(2, pushSaturatingAdd(nowMillis, PUSH_ATTEMPT_LEASE_MILLIS))
                it.setLong(3, target.generation); it.setString(4, target.bindingId); it.setString(5, target.tokenHash)
                it.setInt(6, attempts + 1); it.setLong(7, row.id); check(it.executeUpdate() == 1)
            }
            PushDeliveryClaim(row.id, ticket, attempts + 1, target, CallPushHint(event.call, event.revision), row.expires)
        }
    }

    fun ready(claim: PushDeliveryClaim, nowMillis: Long): Boolean {
        require(nowMillis >= 0)
        return pushStorageTransaction(source, true) { c ->
            val row = pushQueueRow(c, claim.id) ?: return@pushStorageTransaction false
            if (!owned(row, claim, nowMillis)) return@pushStorageTransaction false
            val event = pushQueueEvent(c, row.event)
            if (row.expires <= nowMillis || event == null || !pushEventAvailable(c, event, nowMillis)) {
                pushDone(c, row.id); return@pushStorageTransaction false
            }
            val target = pushCurrentTarget(c, row.installation, event.actor)
            if (target == null) { pushDone(c, row.id); return@pushStorageTransaction false }
            if (!pushSameTarget(target, claim.target)) {
                pushRelease(c, row.id, nowMillis, true); return@pushStorageTransaction false
            }
            val providerDue = c.createStatement().use {
                it.executeQuery("SELECT not_before_millis FROM nearby_push_delivery_clock WHERE singleton=1").use { r ->
                    check(r.next()); r.getLong(1)
                }
            }
            if (providerDue > nowMillis) {
                pushRelease(c, row.id, providerDue, false); return@pushStorageTransaction false
            }
            c.prepareStatement("UPDATE nearby_push_deliveries SET lease_until_millis=? WHERE delivery_id=?").use {
                it.setLong(1, pushSaturatingAdd(nowMillis, PUSH_ATTEMPT_LEASE_MILLIS)); it.setLong(2, row.id); it.executeUpdate()
            }
            true
        }
    }

    /** Receipt ownership, not proof of handset delivery. Old tickets cannot mutate new attempts. */
    fun complete(claim: PushDeliveryClaim, result: FcmSendResult, nowMillis: Long): Boolean {
        require(nowMillis >= 0 && (result !is FcmSendResult.Retry || result.afterMillis >= 0))
        return pushStorageTransaction(source, true) { c ->
            val row = pushQueueRow(c, claim.id) ?: return@pushStorageTransaction false
            if (!owned(row, claim, nowMillis)) return@pushStorageTransaction false
            if (result is FcmSendResult.Retry && result.reason in setOf(FcmRetryReason.THROTTLED, FcmRetryReason.UNAVAILABLE, FcmRetryReason.CONFIGURATION)) {
                c.prepareStatement("UPDATE nearby_push_delivery_clock SET not_before_millis=GREATEST(not_before_millis,?) WHERE singleton=1").use {
                    it.setLong(1, pushSaturatingAdd(nowMillis, result.minimumDelayMillis())); it.executeUpdate()
                }
            }
            val event = pushQueueEvent(c, row.event)
            if (row.expires <= nowMillis || event == null || !pushEventAvailable(c, event, nowMillis)) {
                pushDone(c, row.id); return@pushStorageTransaction true
            }
            val current = pushCurrentTarget(c, row.installation, event.actor)
            if (current == null) { pushDone(c, row.id); return@pushStorageTransaction true }
            if (!pushSameTarget(current, claim.target)) {
                pushRelease(c, row.id, nowMillis, true); return@pushStorageTransaction true
            }
            when (result) {
                FcmSendResult.Accepted, FcmSendResult.InvalidRequest -> pushDone(c, row.id)
                FcmSendResult.Unregistered -> { retirePushDevice(c, claim.target, nowMillis); pushDone(c, row.id) }
                is FcmSendResult.Retry -> retry(c, row, result, nowMillis)
            }
            true
        }
    }

    private fun retry(c: Connection, row: PushQueueRow, result: FcmSendResult.Retry, at: Long) {
        val jitter = jitterMillis().also { require(it in 0..250) }
        val backoff = (1000L shl (row.attempts - 1).coerceIn(0, 5)) + jitter
        val delay = maxOf(result.minimumDelayMillis(), backoff)
        if (row.attempts >= MAXIMUM_PUSH_ATTEMPTS || delay >= row.expires - at) pushDone(c, row.id)
        else pushRelease(c, row.id, at + delay, false)
    }
    private fun owned(row: PushQueueRow, claim: PushDeliveryClaim, at: Long) = !row.done && row.ticket == claim.ticket && row.attempts == claim.attemptNumber &&
        row.leaseUntil != null && row.leaseUntil > at && row.installation == claim.target.installationId &&
        row.generation == claim.target.generation && row.binding == claim.target.bindingId && row.tokenHash == claim.target.tokenHash
}

/** The queue owns provider minima even when a different sender supplies an incoherent delay. */
private fun FcmSendResult.Retry.minimumDelayMillis(): Long = maxOf(afterMillis, when (reason) {
    FcmRetryReason.THROTTLED, FcmRetryReason.CONFIGURATION -> 60000L
    FcmRetryReason.UNAVAILABLE, FcmRetryReason.TRANSPORT, FcmRetryReason.INVALID_RESPONSE -> 1000L
})

internal fun interface CallPushAttempt { fun send(claim: PushDeliveryClaim, ttlSeconds: Int): FcmSendResult }
internal class PushDeliveryWorker(private val queue: JdbcPushDeliveryQueue, private val attempt: CallPushAttempt,
                                  private val now: () -> Long, private val ticket: () -> String) {
    fun tick(): Boolean {
        val claim = queue.claim(now(), ticket()) ?: return false
        val dispatchAt = now()
        if (!queue.ready(claim, dispatchAt)) return true
        val ttl = minOf(30L, (claim.expiresMillis - dispatchAt) / 1000).toInt()
        if (ttl < 1) { queue.complete(claim, FcmSendResult.InvalidRequest, now()); return true }
        val result = try { attempt.send(claim, ttl) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (_: Exception) { FcmSendResult.Retry(FcmRetryReason.TRANSPORT) }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Push worker interrupted")
        queue.complete(claim, result, now())
        return true
    }
}
