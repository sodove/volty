package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Tag("postgres")
class JdbcSessionRosterDeliveryTest {
    private val source = schema()
    private val actor = id(); private val operation = id(); private val channel = id(); private val session = id()
    private val epoch = SfuEpoch("room", "node")
    private val repository = JdbcSessionRepository(source)
    private val journal = JdbcRosterJournal(source)
    private var allowed = true

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use { c -> c.prepareStatement("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES (?::uuid,?,'test','test',0)").use {
            it.setString(1, actor); it.setString(2, "$actor@example.invalid"); it.executeUpdate()
        } }
        repository.begin(actor, operation, channel, session, epoch, { 100 })
    }

    @Test fun concurrentCommitDuringInitialSnapshotRemainsInDurableBufferUntilNextOffer() {
        val barrier = RevisionReadBarrierSource(source)
        val cursor = RosterDeliveryCursor(actor, channel, journal, port(JdbcRosterRepository(barrier)))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val initial = executor.submit<RosterDeliveryOffer> { cursor.offer() }
            assertTrue(barrier.readIssued.await(5, TimeUnit.SECONDS), "Actual snapshot revision SELECT issued")
            repository.cancel(actor, operation, { 101 })
            assertEquals(2, journal.revision(), "Competing mutation committed while snapshot is pinned")
            barrier.release.countDown()
            val first = assertIs<RosterDeliveryOffer.Snapshot>(initial.get(10, TimeUnit.SECONDS)).snapshot
            assertEquals(1, first.revision)
            assertEquals(session, first.peers.single().sessionId)
            assertTrue(cursor.acknowledge(1))
            val next = assertIs<RosterDeliveryOffer.Snapshot>(cursor.offer()).snapshot
            assertEquals(2, next.revision)
            assertTrue(next.peers.isEmpty())
            assertTrue(cursor.acknowledge(2))
            assertEquals(listOf(2L), journal.eventsAfter(1, 10).map { it.revision }, "ACK does not consume another process's event")
        } finally { barrier.release.countDown(); executor.shutdownNow() }
    }

    @Test fun failedPublicationCanJumpToNewFullSnapshotButOldAckCannotConsumeIt() {
        val cursor = cursor()
        assertEquals(1, assertIs<RosterDeliveryOffer.Snapshot>(cursor.offer()).snapshot.revision)
        repository.admit(actor, operation, session, epoch, { 101 }, 30_101)
        val current = assertIs<RosterDeliveryOffer.Snapshot>(cursor.offer()).snapshot
        assertEquals(2, current.revision)
        assertEquals(RosterConnectionPhase.CONNECTING, current.peers.single().phase)
        assertFalse(cursor.acknowledge(1))
        assertNull(cursor.acknowledgedRevision)
        assertTrue(cursor.acknowledge(2))
        assertEquals(2, journal.eventsAfter(0, 10).size)
        assertIs<RosterDeliveryOffer.Idle>(cursor.offer())
    }

    @Test fun readerFailureRetainsPositionAndReconstructedConsumerGetsCommittedLeave() {
        var failing = false
        val reader = object : RosterJournalReader by journal {
            override fun eventsAfter(revision: Long, limit: Int): List<RosterJournalEvent> {
                if (failing) error("fixture private details")
                return journal.eventsAfter(revision, limit)
            }
        }
        val cursor = RosterDeliveryCursor(actor, channel, reader, port())
        cursor.offer(); assertTrue(cursor.acknowledge(1))
        repository.cancel(actor, operation, { 101 })
        failing = true
        assertIs<RosterDeliveryOffer.Unavailable>(cursor.offer())
        assertEquals(1, cursor.acknowledgedRevision)
        val restarted = cursor()
        val snapshot = assertIs<RosterDeliveryOffer.Snapshot>(restarted.offer()).snapshot
        assertEquals(2, snapshot.revision)
        assertTrue(snapshot.peers.isEmpty())
        assertTrue(restarted.acknowledge(2))
        assertEquals(listOf(2L), journal.eventsAfter(1, 10).map { it.revision })
    }

    @Test fun failedJournalReadStillRetiresOwnerWhenFixtureAuthorizationIsRevoked() {
        var failing = false
        var authorizations = 0
        val reader = object : RosterJournalReader by journal {
            override fun eventsAfter(revision: Long, limit: Int): List<RosterJournalEvent> {
                if (failing) error("fixture journal unavailable")
                return journal.eventsAfter(revision, limit)
            }
        }
        val authorized = port()
        val cursor = RosterDeliveryCursor(actor, channel, reader, RosterEndpointPort { readerActor, destination ->
            authorizations++
            authorized.snapshot(readerActor, destination)
        })
        cursor.offer(); assertTrue(cursor.acknowledge(1))
        failing = true; allowed = false
        assertIs<RosterDeliveryOffer.Denied>(cursor.offer())
        assertEquals(2, authorizations, "Fresh authorization issued even when the journal failed")
        assertEquals(1, cursor.acknowledgedRevision)
        assertFalse(cursor.acknowledge(1))
        assertEquals(session, repository.currentClaim(actor)!!.sessionId)
        assertEquals(listOf(1L), journal.eventsAfter(0, 10).map { it.revision })
        allowed = true; failing = false
        assertIs<RosterDeliveryOffer.Denied>(cursor.offer())
        assertEquals(2, authorizations, "Retired owner does not restart I/O")
    }

    @Test fun freshAuthorizationGateOnRetrySuppressesPreviouslyOfferedPrivateRows() {
        val cursor = cursor()
        assertEquals(session, assertIs<RosterDeliveryOffer.Snapshot>(cursor.offer()).snapshot.peers.single().sessionId)
        allowed = false
        assertIs<RosterDeliveryOffer.Denied>(cursor.offer())
        assertFalse(cursor.acknowledge(1))
        assertNull(cursor.acknowledgedRevision)
        assertEquals(session, repository.currentClaim(actor)!!.sessionId, "Read permission is not a voice stop command")
        allowed = true
        assertIs<RosterDeliveryOffer.Denied>(cursor.offer())
        assertIs<RosterDeliveryOffer.Snapshot>(cursor().offer())
    }

    // Fixture gate only: no real membership table/atomic community authorization claim.
    private fun port(rosters: JdbcRosterRepository = JdbcRosterRepository(source)) = RosterEndpointPort { reader, destination ->
        assertEquals(actor, reader); assertEquals(channel, destination)
        if (allowed) rosters.snapshot(destination) else null
    }
    private fun cursor() = RosterDeliveryCursor(actor, channel, journal, port())
    private fun schema(): javax.sql.DataSource {
        val name = "roster_delivery_test_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}
