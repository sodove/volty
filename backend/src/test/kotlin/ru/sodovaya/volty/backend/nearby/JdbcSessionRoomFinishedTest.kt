package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Tag("postgres")
class JdbcSessionRoomFinishedTest {
    private val source = schema()
    private val store = JdbcMediaWebhookInbox(source)
    private val repository = JdbcSessionRepository(source)
    private val epoch = SfuEpoch("room", "a".repeat(32))
    private val event get() = VerifiedMediaWebhook("EV_room_" + id(), "a".repeat(64), "room", null, null, MediaWebhookKind.ROOM_FINISHED)

    @BeforeEach fun migrate() { SchemaMigrator(source).migrate() }

    @Test fun roomReceiptCapturesExactClaimsWithoutChangingRoster() {
        val first = join(); val second = join(); val foreign = join(room = "other")
        val before = JdbcRosterJournal(source).revision()
        val receipt = event
        assertEquals(MediaWebhookDisposition.ACCEPTED, store.accept(receipt))
        val pending = store.pending(10)
        assertEquals(setOf(first, second), pending.map { it.targetSessionId }.toSet())
        assertTrue(pending.none { it.targetSessionId == foreign })
        assertTrue(pending.all { it.event == receipt })
        assertEquals(before, JdbcRosterJournal(source).revision())
    }

    @Test fun replayNeverAddsNewJoinsAndAckConsumesOnlyExactTarget() {
        val first = join(); val second = join(); val receipt = event
        store.accept(receipt)
        val later = join()
        assertEquals(MediaWebhookDisposition.ACCEPTED, store.accept(receipt))
        assertFalse(store.complete(receipt.eventId, "b".repeat(64), first))
        assertFalse(store.complete(receipt.eventId, receipt.bodySha256, later))
        assertTrue(store.complete(receipt.eventId, receipt.bodySha256, first))
        assertEquals(listOf(second), store.pending(10).map { it.targetSessionId })
        assertFalse(processed(receipt.eventId))
        assertTrue(store.complete(receipt.eventId, receipt.bodySha256, second))
        assertTrue(processed(receipt.eventId))
        assertTrue(store.complete(receipt.eventId, receipt.bodySha256, first))
        store.accept(receipt)
        assertTrue(store.pending(10).isEmpty())
    }

    @Test fun emptyRoomReceiptIsDurableAndCannotAcquireLaterTargets() {
        val receipt = event
        store.accept(receipt)
        assertTrue(processed(receipt.eventId))
        join()
        store.accept(receipt)
        assertTrue(store.pending(10).isEmpty())
    }

    @Test fun targetInsertFailureRollsBackParentReceipt() {
        val session = join(); val receipt = event
        source.connection.use { c -> c.createStatement().use {
            it.execute("ALTER TABLE nearby_media_webhook_targets ADD CONSTRAINT test_fail_target CHECK(session_id <> '$session'::uuid)")
        } }
        assertFailsWith<SQLException> { store.accept(receipt) }
        source.connection.use { c -> c.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM nearby_media_webhook_inbox").use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
            statement.execute("ALTER TABLE nearby_media_webhook_targets DROP CONSTRAINT test_fail_target")
        } }
        store.accept(receipt)
        assertEquals(listOf(session), store.pending(10).map { it.targetSessionId })
    }

    @Test fun concurrentDifferentTargetAcksCannotStrandParent() {
        val first = join(); val second = join(); val receipt = event
        store.accept(receipt)
        val barrier = ActorLockBarrierSource(source)
        val inbox = JdbcMediaWebhookInbox(barrier)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val a = executor.submit<Boolean> { inbox.complete(receipt.eventId, receipt.bodySha256, first) }
            assertTrue(barrier.held.await(5, TimeUnit.SECONDS), "First real parent lock acquired")
            val b = executor.submit<Boolean> { inbox.complete(receipt.eventId, receipt.bodySha256, second) }
            assertTrue(barrier.competingQueryIssued.await(5, TimeUnit.SECONDS), "Competing real query issued")
            assertFalse(b.isDone)
            barrier.release.countDown()
            assertTrue(a.get(10, TimeUnit.SECONDS)); assertTrue(b.get(10, TimeUnit.SECONDS))
            assertTrue(processed(receipt.eventId))
            assertTrue(store.pending(10).isEmpty())
        } finally { barrier.release.countDown(); executor.shutdownNow() }
    }

    @Test fun incoherentClaimEpochIsExcludedAndParentPairConstraintRejectsNullSid() {
        val session = join()
        source.connection.use { c -> c.createStatement().use { it.executeUpdate("UPDATE nearby_actor_claims SET node_epoch='stale' WHERE session_id='$session'::uuid") } }
        val receipt = event
        store.accept(receipt)
        assertTrue(processed(receipt.eventId))
        assertTrue(store.pending(10).isEmpty())
        assertFailsWith<SQLException> {
            source.connection.use { c -> c.prepareStatement("INSERT INTO nearby_media_webhook_inbox(event_id,body_sha256,room_epoch,session_id,participant_sid,kind) VALUES (?,?,?,?::uuid,NULL,'JOINED')").use {
                it.setString(1, "EV_bad"); it.setString(2, "b".repeat(64)); it.setString(3, "room"); it.setString(4, session); it.executeUpdate()
            } }
        }
    }

    @Test fun versionFiveUpgradeBackfillsOnlyUnprocessedParticipantTargets() {
        val legacy = schema()
        SchemaMigrator(legacy, listOf(1 to "/db/migration/V1__initial.sql",
            2 to "/db/migration/V2__nearby_session_journal.sql", 3 to "/db/migration/V3__nearby_roster_journal.sql",
            4 to "/db/migration/V4__nearby_media_webhook_inbox.sql", 5 to "/db/migration/V5__nearby_media_observations.sql")).migrate()
        val session = id()
        legacy.connection.use { c -> c.prepareStatement("INSERT INTO nearby_media_webhook_inbox(event_id,body_sha256,room_epoch,session_id,participant_sid,kind,processed) VALUES (?,?,?,?::uuid,?,'LEFT',?)").use {
            for (done in listOf(false, true)) {
                it.setString(1, if (done) "EV_done" else "EV_pending"); it.setString(2, "a".repeat(64))
                it.setString(3, "room"); it.setString(4, session); it.setString(5, "PA_old"); it.setBoolean(6, done); it.executeUpdate()
            }
        } }
        SchemaMigrator(legacy).migrate(); SchemaMigrator(legacy).migrate()
        val pending = JdbcMediaWebhookInbox(legacy).pending(10).single()
        assertEquals("EV_pending", pending.event.eventId)
        assertEquals(session, pending.targetSessionId)
        assertTrue(JdbcMediaWebhookInbox(legacy).complete("EV_pending", "a".repeat(64), session))
        assertTrue(JdbcMediaWebhookInbox(legacy).pending(10).isEmpty())
        legacy.connection.use { c -> c.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM nearby_media_webhook_targets").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
        } }
    }

    private fun join(room: String = "room"): String {
        val actor = id(); val operation = id(); val session = id()
        source.connection.use { c -> c.prepareStatement("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES (?::uuid,?,'test','test',0)").use {
            it.setString(1, actor); it.setString(2, "$actor@example.invalid"); it.executeUpdate()
        } }
        repository.begin(actor, operation, id(), session, epoch.copy(roomEpoch = room), { 100 })
        return session
    }
    private fun processed(eventId: String) = source.connection.use { c -> c.prepareStatement("SELECT processed FROM nearby_media_webhook_inbox WHERE event_id=?").use {
        it.setString(1, eventId); it.executeQuery().use { rows -> check(rows.next()); rows.getBoolean(1) }
    } }
    private fun schema(): javax.sql.DataSource {
        val name = "room_finished_test_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}
