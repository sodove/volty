package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Tag("postgres")
class JdbcSessionMediaObservationTest {
    private val source = schema()
    private val actor = id(); private val operation = id(); private val session = id(); private val destination = id()
    private val epoch = SfuEpoch("room", "node")
    private val sessions = JdbcSessionRepository(source)
    private val roster = JdbcRosterRepository(source) { 100 }
    private val journal = JdbcRosterJournal(source)
    private val active = SfuParticipantObservation("PA_current", SfuParticipantState.ACTIVE)

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use { c -> c.prepareStatement("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES (?::uuid,?,'test','test',0)").use {
            it.setString(1, actor); it.setString(2, "$actor@example.invalid"); it.executeUpdate()
        } }
        sessions.begin(actor, operation, destination, session, epoch, { 100 })
        sessions.admit(actor, operation, session, epoch, { 101 }, 30_100)
    }

    @Test fun confirmedMediaAndOutboxCommitTogetherButIdenticalObservationDoesNotInventRevision() {
        assertEquals(MediaPresence.UNKNOWN, roster.snapshot(destination).peers.single().mediaPresence)
        val confirmed = observe(0, active)
        assertNull(confirmed.rejection)
        assertEquals(1, confirmed.state!!.media.observationVersion)
        val snapshot = roster.snapshot(destination)
        assertEquals(3, snapshot.revision)
        assertEquals(MediaPresence.CONNECTED, snapshot.peers.single().mediaPresence)
        assertEquals(RosterConnectionPhase.CONNECTED, snapshot.peers.single().phase)
        assertEquals("PA_current", snapshot.peers.single().participantSid)
        assertEquals("PA_current", journal.eventsAfter(2, 1).single().participantSid)
        assertEquals(SfuParticipantState.ACTIVE, journal.eventsAfter(2, 1).single().mediaState)
        val repeated = observe(1, active)
        assertEquals(2, repeated.state!!.media.observationVersion)
        assertEquals(3, journal.revision())
        assertTrue(journal.eventsAfter(3, 1).isEmpty())
    }

    @Test fun oldNetworkReplyCannotOverwriteNewerSIDObservation() {
        observe(0, active.copy(participantSid = "PA_new"))
        val old = observe(0, active.copy(participantSid = "PA_old"))
        assertEquals(OperationRejection.OBSERVATION_CHANGED, old.rejection)
        assertEquals("PA_new", sessions.get(actor, operation)!!.media.participantSid)
        assertEquals(3, journal.revision())
    }

    @Test fun unknownOrJoinedMediaDoesNotEarnConnectedPresenceOrExtendLease() {
        observe(0, active)
        observe(1, SfuParticipantObservation(null, null))
        val row = sessions.get(actor, operation)!!
        assertEquals(30_100, row.operation.deadlineEpochMillis)
        assertEquals(MediaPresence.UNKNOWN, roster.snapshot(destination).peers.single().mediaPresence)
        val refused = sessions.heartbeatConnected(actor, operation, session, epoch, row.media.observationVersion, "PA_current", { 200 }, 30_200)
        assertEquals(OperationRejection.NOT_ACTIVE, refused.rejection)
        assertEquals(30_100, refused.state!!.operation.deadlineEpochMillis)
        observe(2, SfuParticipantObservation("PA_joined", SfuParticipantState.JOINED))
        assertEquals(MediaPresence.UNKNOWN, roster.snapshot(destination).peers.single().mediaPresence)
        assertEquals(RosterConnectionPhase.CONNECTING, roster.snapshot(destination).peers.single().phase)
    }

    @Test fun renewalReceiptMustMatchCurrentObservedSIDAndVersion() {
        observe(0, active)
        val old = sessions.heartbeatConnected(actor, operation, session, epoch, 0, "PA_current", { 200 }, 30_200)
        assertEquals(OperationRejection.OBSERVATION_CHANGED, old.rejection)
        val wrong = sessions.heartbeatConnected(actor, operation, session, epoch, 1, "PA_old", { 200 }, 30_200)
        assertEquals(OperationRejection.OBSERVATION_CHANGED, wrong.rejection)
        val accepted = sessions.heartbeatConnected(actor, operation, session, epoch, 1, "PA_current", { 200 }, 30_200)
        assertNull(accepted.rejection)
        assertEquals(30_200, accepted.state!!.operation.deadlineEpochMillis)
        assertEquals("PA_current", accepted.state!!.media.participantSid)
        assertEquals(4, journal.revision())
    }

    @Test fun delayedConfirmationAndRenewalCannotResurrectExpiredOrReplacedOperation() {
        val expired = observe(0, active, 30_100)
        assertEquals(OperationPhase.EXPIRED, expired.state!!.operation.phase)
        assertNull(sessions.currentClaim(actor))
        assertEquals(0, expired.state!!.media.observationVersion)
        assertTrue(roster.snapshot(destination).peers.isEmpty())
        val nextOperation = id(); val nextSession = id()
        sessions.begin(actor, nextOperation, destination, nextSession, epoch, { 30_101 })
        observe(0, active, 30_102)
        assertEquals(nextSession, sessions.currentClaim(actor)!!.sessionId)
        assertEquals(listOf(nextSession), roster.snapshot(destination).peers.map { it.sessionId })
    }

    @Test fun incoherentClaimEpochDoesNotEarnMediaFromOtherwiseActiveOperation() {
        source.connection.use { c -> c.prepareStatement("UPDATE nearby_actor_claims SET node_epoch='older' WHERE actor_id=?::uuid").use {
            it.setString(1, actor); assertEquals(1, it.executeUpdate())
        } }
        assertEquals(OperationRejection.CLAIM_MISMATCH, observe(0, active).rejection)
        assertNull(sessions.get(actor, operation)!!.media.participantSid)
        assertEquals(2, journal.revision())
    }

    @Test fun snapshotNeverPairsOldRevisionWithConcurrentlyConfirmedMedia() {
        val barrier = RevisionReadBarrierSource(source)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<ChannelRosterSnapshot> { JdbcRosterRepository(barrier) { 100 }.snapshot(destination) }
            assertTrue(barrier.readIssued.await(5, TimeUnit.SECONDS))
            assertNull(observe(0, active).rejection)
            assertFalse(read.isDone)
            barrier.release.countDown()
            val old = read.get(10, TimeUnit.SECONDS)
            assertEquals(2, old.revision)
            assertEquals(MediaPresence.UNKNOWN, old.peers.single().mediaPresence)
            assertEquals(3, roster.snapshot(destination).revision)
            assertEquals(MediaPresence.CONNECTED, roster.snapshot(destination).peers.single().mediaPresence)
        } finally { barrier.release.countDown(); executor.shutdownNow() }
    }

    @Test fun databaseRejectsSidWithoutStateInsteadOfInventingUnknownProvenance() {
        source.connection.use { c ->
            val failure = assertFailsWith<java.sql.SQLException> {
                c.prepareStatement("UPDATE nearby_session_operations SET participant_sid='PA_incoherent',media_state=NULL WHERE actor_id=?::uuid").use {
                    it.setString(1, actor); it.executeUpdate()
                }
            }
            assertEquals("23514", failure.sqlState)
        }
        assertNull(sessions.get(actor, operation)!!.media.participantSid)
    }

    @Test fun failedMediaOutboxInsertRollsBackObservationVersionAndRosterClock() {
        source.connection.use { c -> c.createStatement().use {
            it.execute("ALTER TABLE nearby_roster_outbox ADD CONSTRAINT refuse_active_observation CHECK (media_state IS DISTINCT FROM 'ACTIVE')")
        } }
        assertFailsWith<java.sql.SQLException> { observe(0, active) }
        assertNull(sessions.get(actor, operation)!!.media.participantSid)
        assertEquals(0, sessions.get(actor, operation)!!.media.observationVersion)
        assertEquals(2, journal.revision())
        assertTrue(journal.eventsAfter(2, 1).isEmpty())
    }

    @Test fun migrationFromVersionFourPreservesLeaseClaimAndUndeliveredHookAsUnknownMedia() {
        val old = schema()
        SchemaMigrator(old, listOf(1 to "/db/migration/V1__initial.sql", 2 to "/db/migration/V2__nearby_session_journal.sql",
            3 to "/db/migration/V3__nearby_roster_journal.sql", 4 to "/db/migration/V4__nearby_media_webhook_inbox.sql")).migrate()
        old.connection.use { c ->
            c.prepareStatement("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES (?::uuid,?,'test','test',0)").use {
                it.setString(1, actor); it.setString(2, "$actor@example.invalid"); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO nearby_session_operations(actor_id,operation_id,destination_id,session_id,room_epoch,node_epoch,phase,deadline_epoch_millis,admission_pending) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,'room','node','PENDING',15100,TRUE)").use {
                it.setString(1, actor); it.setString(2, operation); it.setString(3, destination); it.setString(4, session); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO nearby_actor_claims(actor_id,operation_id,session_id,room_epoch,node_epoch) VALUES (?::uuid,?::uuid,?::uuid,'room','node')").use {
                it.setString(1, actor); it.setString(2, operation); it.setString(3, session); it.executeUpdate()
            }
        }
        val hook = VerifiedMediaWebhook("EV_retained", "a".repeat(64), "room", session, "PA_old", MediaWebhookKind.LEFT)
        JdbcMediaWebhookInbox(old).accept(hook)
        SchemaMigrator(old).migrate(); SchemaMigrator(old).migrate()
        val migrated = JdbcSessionRepository(old)
        assertEquals(SessionMedia(), migrated.get(actor, operation)!!.media)
        assertEquals(session, migrated.currentClaim(actor)!!.sessionId)
        assertEquals(15_100, migrated.get(actor, operation)!!.operation.deadlineEpochMillis)
        assertEquals(listOf(hook), JdbcMediaWebhookInbox(old).pending(10).map { it.event })
    }

    private fun observe(version: Long, value: SfuParticipantObservation, now: Long = 102) =
        sessions.observeMedia(actor, operation, session, epoch, version, value, { now })
    private fun schema(): javax.sql.DataSource {
        val name = "media_observe_test_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}
