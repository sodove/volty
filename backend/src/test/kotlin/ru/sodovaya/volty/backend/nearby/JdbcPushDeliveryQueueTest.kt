package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcPushDeliveryQueueTest {
    private val caller = id(); private val callee = id(); private val call = id()
    private val installation = id(); private val key = "a".repeat(64); private val binding = id()
    private val schema = "push_delivery_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let { base ->
        base.connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        NearbyPostgresFixture.source(schema)
    }
    private val devices = JdbcPushDeviceRegistry(source)
    private val rings = JdbcDirectCallRingingRepository(source)
    private val queue = JdbcPushDeliveryQueue(source) { 0 }
    private var base = 0L

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use { c -> c.createStatement().use { s ->
            for (actor in listOf(caller, callee)) s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
        } }
        base = source.connection.use { c -> c.createStatement().use {
            it.executeQuery("SELECT (extract(epoch from clock_timestamp())*1000)::bigint").use { row -> check(row.next()); row.getLong(1) }
        } }
        assertEquals(PushDeviceDecision.APPLIED, devices.apply(registration(), base))
        assertEquals(DirectCallPhase.RINGING, rings.create(caller, callee, call, id(), id(), { base }).call?.phase)
    }

    @Test fun committedEventHasOneDeliveryPerBoundDeviceAndCompletionSurvivesRestart() {
        assertEquals(PushDeviceDecision.APPLIED, devices.apply(registration(device = id(), token = "second-device"), base))
        val first = assertNotNull(queue.claim(base + 10000, id()))
        assertEquals(call, first.hint.callId); assertEquals(callee, first.target.actorId)
        assertTrue(queue.ready(first, base + 10001))
        assertTrue(queue.complete(first, FcmSendResult.Accepted, base + 10002))
        val restarted = JdbcPushDeliveryQueue(source)
        val second = assertNotNull(restarted.claim(base + 10003, id()))
        assertNotEquals(first.target.installationId, second.target.installationId)
        assertTrue(restarted.complete(second, FcmSendResult.Accepted, base + 10004))
        assertNull(restarted.claim(base + 10005, id()))
    }

    @Test fun crashLeaseCanBeReclaimedButOldReceiptCannotFinishNewAttempt() {
        val first = assertNotNull(queue.claim(base + 10000, id()))
        val restarted = JdbcPushDeliveryQueue(source)
        assertNull(restarted.claim(base + 11000, id()))
        assertNull(restarted.claim(base + 21000, first.ticket))
        val second = assertNotNull(restarted.claim(base + 21000, id()))
        assertEquals(first.id, second.id); assertNotEquals(first.ticket, second.ticket)
        assertFalse(queue.complete(first, FcmSendResult.Unregistered, base + 21001))
        assertEquals("device-token", devices.targets(callee).single().token)
        assertTrue(restarted.complete(second, FcmSendResult.Accepted, base + 21002))
    }

    @Test fun newerCallRevisionPreventsDispatchOfPreparedOldRing() {
        val first = assertNotNull(queue.claim(base + 10000, id()))
        assertEquals(DirectCallPhase.DECLINED, rings.decline(callee, call, { base + 11000 }).call?.phase)
        assertFalse(queue.ready(first, base + 11001))
        val next = assertNotNull(queue.claim(base + 11002, id()))
        assertEquals(1L, next.hint.revision)
        assertTrue(queue.ready(next, base + 11003))
    }

    @Test fun lateUnregisteredForOldTokenRequeuesNewBindingWithoutRetiringIt() {
        val first = assertNotNull(queue.claim(base + 10000, id()))
        val nextBinding = id()
        assertEquals(PushDeviceDecision.APPLIED, devices.apply(registration(2, nextBinding, "new-token"), base + 10001))
        assertTrue(queue.complete(first, FcmSendResult.Unregistered, base + 10002))
        assertEquals("new-token", devices.targets(callee).single().token)
        val next = assertNotNull(queue.claim(base + 10003, id()))
        assertEquals(nextBinding, next.target.bindingId)
        assertTrue(queue.complete(next, FcmSendResult.Unregistered, base + 10004))
        assertTrue(devices.targets(callee).isEmpty())
    }

    @Test fun switchedAccountNeverReceivesOldAccountsHint() {
        val prepared = assertNotNull(queue.claim(base + 10000, id()))
        assertEquals(PushDeviceDecision.APPLIED, devices.apply(registration(2, id(), "new-token", actor = caller), base + 10001))
        assertFalse(queue.ready(prepared, base + 10002))
        assertNull(queue.claim(base + 10003, id()))
    }

    @Test fun revocationAfterClaimPreventsDispatch() {
        val prepared = assertNotNull(queue.claim(base + 10000, id()))
        updateUser(callee, "tokens_revoked_at=20")
        assertFalse(queue.ready(prepared, base + 10001))
        assertNull(queue.claim(base + 10002, id()))
    }

    @Test fun deletedCallerWithOtherwiseEligibleCalleePreventsRingDispatch() {
        val prepared = assertNotNull(queue.claim(base + 10000, id()))
        updateUser(caller, "deleted_at=100")
        assertEquals(1, devices.targets(callee).size)
        assertFalse(queue.ready(prepared, base + 10001))
    }

    @Test fun deletedCalleePreventsDispatchEvenWhenBindingStillExists() {
        val prepared = assertNotNull(queue.claim(base + 10000, id()))
        updateUser(callee, "deleted_at=100")
        assertFalse(queue.ready(prepared, base + 10001))
    }

    @Test fun expiredHintCannotBeDispatchedOrReclaimed() {
        val prepared = assertNotNull(queue.claim(base + 10000, id()))
        assertFalse(queue.ready(prepared, base + 60000))
        assertNull(JdbcPushDeliveryQueue(source).claim(base + 60001, id()))
    }

    @Test fun retryDueTimeAndProjectCooldownSurviveAnotherQueueInstance() {
        val prepared = assertNotNull(queue.claim(base + 10000, id()))
        assertTrue(queue.complete(prepared, FcmSendResult.Retry(FcmRetryReason.TRANSPORT), base + 10001))
        val restarted = JdbcPushDeliveryQueue(source)
        assertNull(restarted.claim(base + 10500, id()))
        val second = assertNotNull(restarted.claim(base + 11002, id()))
        assertTrue(restarted.complete(second, FcmSendResult.Retry(FcmRetryReason.UNAVAILABLE, 3000), base + 11003))
        assertNull(JdbcPushDeliveryQueue(source).claim(base + 13000, id()))
        assertNotNull(JdbcPushDeliveryQueue(source).claim(base + 14004, id()))
    }

    @Test fun enormousRemoteDelayExpiresHintAndCannotOverflowCooldownIntoImmediateRetry() {
        val prepared = assertNotNull(queue.claim(base + 10000, id()))
        assertTrue(queue.complete(prepared, FcmSendResult.Retry(FcmRetryReason.THROTTLED, Long.MAX_VALUE), base + 10001))
        assertNull(JdbcPushDeliveryQueue(source).claim(base + 10002, id()))
        assertEquals("device-token", devices.targets(callee).single().token)
        // A new current event also obeys the durable provider delay, despite old hint expiry.
        assertNotNull(rings.decline(callee, call, { base + 11000 }).call)
        assertNull(JdbcPushDeliveryQueue(source).claim(base + 11001, id()))
    }

    @Test fun workerIssuesOnlyOneAttemptAndSuppliesBoundedTtl() {
        var sends = 0; var clock = base + 10000
        val worker = PushDeliveryWorker(queue, CallPushAttempt { prepared, ttl ->
            sends++; assertEquals(binding, prepared.target.bindingId); assertTrue(ttl in 1..30)
            FcmSendResult.Accepted
        }, { clock }, ::id)
        assertTrue(worker.tick()); assertEquals(1, sends)
        clock++
        assertFalse(worker.tick()); assertEquals(1, sends)
    }

    @Test fun bindingRotationBetweenClaimAndDispatchRequeuesInsteadOfSendingOldTarget() {
        var sends = 0; var clocks = 0
        val newer = id()
        val worker = PushDeliveryWorker(queue, CallPushAttempt { prepared, _ ->
            sends++; assertEquals(newer, prepared.target.bindingId); FcmSendResult.Accepted
        }, {
            clocks++
            if (clocks == 2) assertEquals(PushDeviceDecision.APPLIED, devices.apply(registration(2, newer, "new-token"), base + 10001))
            base + 10000 + clocks
        }, ::id)
        assertTrue(worker.tick()); assertEquals(0, sends)
        assertTrue(worker.tick()); assertEquals(1, sends)
    }

    @Test fun providerPauseAfterClaimStopsPreparedParallelDispatch() {
        assertEquals(PushDeviceDecision.APPLIED, devices.apply(registration(device = id(), token = "second-device"), base))
        val first = assertNotNull(queue.claim(base + 10000, id()))
        val second = assertNotNull(queue.claim(base + 10001, id()))
        assertNotEquals(first.id, second.id)
        assertTrue(queue.complete(first, FcmSendResult.Retry(FcmRetryReason.UNAVAILABLE, 3000), base + 10002))
        assertFalse(queue.ready(second, base + 10003))
        assertNull(JdbcPushDeliveryQueue(source).claim(base + 12000, id()))
        assertNotNull(JdbcPushDeliveryQueue(source).claim(base + 13003, id()))
    }

    @Test fun quotaReasonEnforcesMinimumEvenWhenTypedDelayIsZero() {
        val prepared = assertNotNull(queue.claim(base + 10000, id()))
        // Deliberately incoherent: the HTTP producer supplies >=60s, but the queue owns its contract.
        assertTrue(queue.complete(prepared, FcmSendResult.Retry(FcmRetryReason.THROTTLED, 0), base + 10001))
        assertNotNull(rings.decline(callee, call, { base + 11000 }).call)
        // The first lookup may clean an obsolete revision; the second must still not issue the new one.
        val restarted = JdbcPushDeliveryQueue(source)
        assertNull(restarted.claim(base + 11002, id()))
        assertNull(restarted.claim(base + 11003, id()))
        source.connection.use { c -> c.createStatement().use {
            it.executeQuery("SELECT not_before_millis FROM nearby_push_delivery_clock").use { row ->
                assertTrue(row.next()); assertTrue(row.getLong(1) >= base + 70001)
            }
        } }
        assertEquals("device-token", devices.targets(callee).single().token)
    }

    @Test fun historicalUntimestampedEventIsSkippedButNewRevisionStillDispatches() {
        source.connection.use { c -> c.createStatement().use {
            assertEquals(1, it.executeUpdate("UPDATE nearby_direct_call_push_outbox SET created_at_millis=NULL"))
        } }
        assertNull(queue.claim(base + 10000, id()))
        assertNotNull(rings.decline(callee, call, { base + 11000 }).call)
        val prepared = assertNotNull(queue.claim(base + 11001, id()))
        assertEquals(1L, prepared.hint.revision)
        assertTrue(queue.ready(prepared, base + 11002))
    }

    @Test fun cancellationPropagatesAndLeavesReclaimableLease() {
        var sends = 0
        val worker = PushDeliveryWorker(queue, CallPushAttempt { _, _ ->
            sends++; throw kotlinx.coroutines.CancellationException("test cancellation")
        }, { base + 10000 }, ::id)
        assertFailsWith<kotlinx.coroutines.CancellationException> { worker.tick() }
        assertEquals(1, sends)
        assertNull(JdbcPushDeliveryQueue(source).claim(base + 11000, id()))
        val reclaimed = assertNotNull(JdbcPushDeliveryQueue(source).claim(base + 21000, id()))
        assertTrue(queue.complete(reclaimed, FcmSendResult.Accepted, base + 21001))
    }

    @Test fun ticketReuseAfterInterveningAttemptCannotAcceptAncientReceipt() {
        val ticket = id()
        val first = assertNotNull(queue.claim(base + 10000, ticket))
        assertTrue(queue.complete(first, FcmSendResult.Retry(FcmRetryReason.TRANSPORT), base + 10001))
        val second = assertNotNull(queue.claim(base + 11002, id()))
        assertTrue(queue.complete(second, FcmSendResult.Retry(FcmRetryReason.TRANSPORT), base + 11003))
        val third = assertNotNull(queue.claim(base + 13004, ticket))
        assertFalse(queue.complete(first, FcmSendResult.Unregistered, base + 13005))
        assertEquals("device-token", devices.targets(callee).single().token)
        assertTrue(queue.complete(third, FcmSendResult.Accepted, base + 13006))
    }

    @Test fun materializationResumesAfterHundredWithoutDuplicateInstallations() {
        repeat(100) { n ->
            assertEquals(PushDeviceDecision.APPLIED, devices.apply(registration(device = id(), token = "extra-device-$n"), base))
        }
        fun deliveryCount() = source.connection.use { c -> c.createStatement().use {
            it.executeQuery("SELECT count(*) FROM nearby_push_deliveries").use { row -> check(row.next()); row.getInt(1) }
        } }
        val first = assertNotNull(queue.claim(base + 10000, id()))
        assertEquals(100, deliveryCount())
        val restarted = JdbcPushDeliveryQueue(source)
        val second = assertNotNull(restarted.claim(base + 10001, id()))
        assertEquals(101, deliveryCount()); assertNotEquals(first.target.installationId, second.target.installationId)
        assertTrue(restarted.complete(second, FcmSendResult.Accepted, base + 10002))
        assertNotNull(restarted.claim(base + 10003, id()))
        assertEquals(101, deliveryCount())
    }

    @Test fun rotationDuringCredentialPreparationIsRecheckedBeforeAnyHttpAttempt() {
        var sends = 0; var preparations = 0
        val newer = id()
        val attempt = CallPushAttempt { prepared, _ -> sends++; assertEquals(newer, prepared.target.bindingId); FcmSendResult.Accepted }
        val worker = PushDeliveryWorker(queue, CallPushPreparation {
            if (++preparations == 1) assertEquals(PushDeviceDecision.APPLIED, devices.apply(registration(2, newer, "new-token"), base + 10000))
            PreparedCallPush.Ready(attempt)
        }, { base + 10000 }, ::id)
        assertTrue(worker.tick()); assertEquals(0, sends); assertEquals(1, preparations)
        assertTrue(worker.tick()); assertEquals(1, sends); assertEquals(2, preparations)
    }

    @Test fun callEndingDuringCredentialPreparationSuppressesOldRingSend() {
        var sends = 0; var preparations = 0
        val attempt = CallPushAttempt { prepared, _ -> sends++; assertEquals(1L, prepared.hint.revision); FcmSendResult.Accepted }
        val worker = PushDeliveryWorker(queue, CallPushPreparation {
            if (++preparations == 1) assertNotNull(rings.decline(callee, call, { base + 10000 }).call)
            PreparedCallPush.Ready(attempt)
        }, { base + 10000 }, ::id)
        assertTrue(worker.tick()); assertEquals(0, sends)
        assertTrue(worker.tick()); assertEquals(1, sends)
    }

    @Test fun failedCredentialPreparationPausesProviderWithoutIssuingHttpSend() {
        var sends = 0; var preparations = 0
        val unexpected = CallPushAttempt { _, _ -> sends++; FcmSendResult.Accepted }
        val worker = PushDeliveryWorker(queue, CallPushPreparation {
            preparations++
            if (preparations == 1) PreparedCallPush.Retry(FcmSendResult.Retry(FcmRetryReason.THROTTLED, 0)) else PreparedCallPush.Ready(unexpected)
        }, { base + 10000 }, ::id)
        assertTrue(worker.tick()); assertEquals(1, preparations); assertEquals(0, sends)
        assertEquals("device-token", devices.targets(callee).single().token)
        assertNotNull(rings.decline(callee, call, { base + 11000 }).call)
        assertNull(queue.claim(base + 11001, id()))
    }

    private fun registration(generation: Long = 1, bindingId: String = binding, token: String = "device-token",
                             device: String = installation, actor: String = callee) =
        PushDeviceRegistration(actor, 10, device, key, generation, bindingId, token)
    private fun id() = UUID.randomUUID().toString()
    private fun updateUser(actor: String, expression: String) = source.connection.use { c -> c.createStatement().use { it.execute("UPDATE users SET $expression WHERE id='$actor'") } }
}
