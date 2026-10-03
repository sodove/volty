package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Tag("postgres")
class JdbcSessionMediaWebhookTest {
    private val source = schema()
    private val store = JdbcMediaWebhookInbox(source)
    private val event = VerifiedMediaWebhook("EV_" + UUID.randomUUID(), "a".repeat(64), "room", UUID.randomUUID().toString(), "PA_old", MediaWebhookKind.JOINED)

    @Test fun durableReplayHasOnePendingEventAfterBackendRestart() {
        SchemaMigrator(source).migrate()
        assertEquals(MediaWebhookDisposition.ACCEPTED, store.accept(event))
        assertEquals(MediaWebhookDisposition.ACCEPTED, store.accept(event))
        val pending = JdbcMediaWebhookInbox(source).pending(10)
        assertEquals(listOf(event), pending.map { it.event })
        assertTrue(pending.single().sequence > 0)
        assertTrue(store.complete(event.eventId, event.bodySha256))
        assertTrue(JdbcMediaWebhookInbox(source).pending(10).isEmpty())
        assertEquals(MediaWebhookDisposition.ACCEPTED, store.accept(event))
        assertTrue(store.pending(10).isEmpty(), "Duplicate cannot requeue completed event")
    }

    @Test fun sameEventIdDifferentSignedBytesCannotReplaceStoredIntentOrAcknowledgeIt() {
        SchemaMigrator(source).migrate()
        store.accept(event)
        val collision = event.copy(bodySha256 = "b".repeat(64), participantSid = "PA_new", kind = MediaWebhookKind.LEFT)
        assertEquals(MediaWebhookDisposition.CONFLICT, store.accept(collision))
        assertFalse(store.complete(collision.eventId, collision.bodySha256))
        assertEquals(event, store.pending(1).single().event)
    }

    @Test fun concurrentDeliveryDeduplicatesAndConflictingBodiesHaveOneWinner() {
        SchemaMigrator(source).migrate()
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        try {
            val collision = event.copy(bodySha256 = "b".repeat(64))
            val calls = listOf(event, collision).map { value -> executor.submit<MediaWebhookDisposition> {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); store.accept(value)
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); release.countDown()
            assertEquals(setOf(MediaWebhookDisposition.ACCEPTED, MediaWebhookDisposition.CONFLICT), calls.map { it.get(10, TimeUnit.SECONDS) }.toSet())
            assertEquals(1, store.pending(10).size)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun completionIsExactAndDoesNotConsumeAnotherEvent() {
        SchemaMigrator(source).migrate()
        val next = event.copy(eventId = "EV_" + UUID.randomUUID(), participantSid = "PA_new", kind = MediaWebhookKind.LEFT)
        store.accept(event); store.accept(next)
        assertFalse(store.complete("EV_missing", event.bodySha256))
        assertTrue(store.complete(event.eventId, event.bodySha256))
        assertEquals(listOf(next), store.pending(10).map { it.event })
    }

    @Test fun upgradingVersionThreePreservesRosterJournalAndAddsEmptyInbox() {
        SchemaMigrator(source, listOf(1 to "/db/migration/V1__initial.sql", 2 to "/db/migration/V2__nearby_session_journal.sql", 3 to "/db/migration/V3__nearby_roster_journal.sql")).migrate()
        source.connection.use { c -> c.createStatement().use { it.executeUpdate("UPDATE nearby_roster_clock SET revision=12 WHERE singleton=1") } }
        SchemaMigrator(source).migrate(); SchemaMigrator(source).migrate()
        assertEquals(12, JdbcRosterJournal(source).revision())
        assertTrue(store.pending(1).isEmpty())
        assertEquals(MediaWebhookDisposition.ACCEPTED, store.accept(event))
        assertEquals(event, store.pending(1).single().event)
    }

    private fun schema(): javax.sql.DataSource {
        val name = "media_hook_test_" + UUID.randomUUID().toString().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
}
