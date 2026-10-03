package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcSessionMediaWorkerTest {
    private val source = schema()
    private val actor = id(); private val operation = id(); private val destination = id()
    private val repository = JdbcSessionRepository(source)
    private val inbox = JdbcMediaWebhookInbox(source)
    private val sfu = MediaSfu()
    private var issued = 0
    private val service = SessionService(repository, sfu, { _, _ -> "room" }, SessionCredentialIssuer { row ->
        issued++
        SessionCredentials("ws://test", "opaque-test-only", row.operation.sessionId!!, "room", row.operation.deadlineEpochMillis!!)
    }, { 100 })

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use { c -> c.prepareStatement("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES (?::uuid,?,'test','test',0)").use {
            it.setString(1, actor); it.setString(2, "$actor@example.invalid"); it.executeUpdate()
        } }
        assertIs<SessionAction.Ready>(service.join(actor, operation, destination))
    }

    @Test fun joinedNotificationDoesNotInventActiveTransportOrExtendAdmission() {
        sfu.media = SfuParticipantObservation("PA_joined", SfuParticipantState.JOINED)
        inbox.accept(event(MediaWebhookKind.JOINED, "PA_joined"))
        assertTrue(worker().reconcileNext())
        assertTrue(inbox.pending(1).isEmpty())
        assertEquals(MediaPresence.UNKNOWN, JdbcRosterRepository(source).snapshot(destination).peers.single().mediaPresence)
        assertEquals(30_100, repository.get(actor, operation)!!.operation.deadlineEpochMillis)
        assertEquals(0, sfu.renewals)
        assertEquals(1, issued)
    }

    @Test fun delayedLeftForOldSidPreservesSameSessionWithNewLiveSid() {
        sfu.media = SfuParticipantObservation("PA_new", SfuParticipantState.ACTIVE)
        inbox.accept(event(MediaWebhookKind.LEFT, "PA_old"))
        assertTrue(worker().reconcileNext())
        assertEquals("PA_new", repository.get(actor, operation)!!.media.participantSid)
        assertEquals(OperationPhase.ACTIVE, repository.get(actor, operation)!!.operation.phase)
        assertEquals(repository.get(actor, operation)!!.operation.sessionId, repository.currentClaim(actor)!!.sessionId)
        assertEquals(MediaPresence.CONNECTED, JdbcRosterRepository(source).snapshot(destination).peers.single().mediaPresence)
        assertEquals(0, sfu.revocations)
        assertEquals(0, sfu.renewals)
    }

    @Test fun oldTerminalSessionHookCannotObserveOrStopReplacementClaim() {
        val old = event(MediaWebhookKind.LEFT, "PA_old")
        val next = id()
        val fresh = assertIs<SessionAction.Ready>(service.join(actor, next, destination, replace = true)).credentials.sessionId
        inbox.accept(old)
        val queries = sfu.observations; val revokes = sfu.revocations
        assertTrue(worker().reconcileNext())
        assertEquals(queries, sfu.observations)
        assertEquals(revokes, sfu.revocations)
        assertEquals(fresh, repository.currentClaim(actor)!!.sessionId)
    }

    @Test fun lostInboxAckReplaysDurableMediaWithoutInventingRosterRevisionOrCredentials() {
        sfu.media = SfuParticipantObservation("PA_live", SfuParticipantState.ACTIVE)
        inbox.accept(event(MediaWebhookKind.JOINED, "PA_live"))
        val lostAck = object : MediaWebhookInbox by inbox {
            override fun complete(eventId: String, bodySha256: String, targetSessionId: String) = false
        }
        assertFalse(MediaWebhookReconciler(lostAck, repository, service).reconcileNext())
        assertEquals(3, JdbcRosterJournal(source).revision())
        assertEquals(1, inbox.pending(1).size)
        assertTrue(worker().reconcileNext())
        assertEquals(3, JdbcRosterJournal(source).revision())
        assertEquals(2, repository.get(actor, operation)!!.media.observationVersion)
        assertTrue(inbox.pending(1).isEmpty())
        assertEquals(1, issued)
    }

    @Test fun failedCurrentObservationRetainsDurableIntentAndWrongRoomCannotQuerySession() {
        val pending = event(MediaWebhookKind.JOINED, "PA_live")
        inbox.accept(pending)
        sfu.failure = SfuFailure.TIMEOUT
        assertFalse(worker().reconcileNext())
        assertEquals(listOf(pending), inbox.pending(1).map { it.event })
        assertNull(repository.get(actor, operation)!!.media.participantSid)
        sfu.failure = null
        assertTrue(worker().reconcileNext())
        val queries = sfu.observations
        inbox.accept(event(MediaWebhookKind.JOINED, "PA_live").copy(roomEpoch = "other-room"))
        assertTrue(worker().reconcileNext())
        assertEquals(queries, sfu.observations)
    }

    private fun worker() = MediaWebhookReconciler(JdbcMediaWebhookInbox(source), JdbcSessionRepository(source), service)

    @Test fun delayedRoomFinishedPreservesNewCurrentSessionAndTransport() {
        val next = id()
        val fresh = assertIs<SessionAction.Ready>(service.join(actor, next, destination, replace = true)).credentials.sessionId
        sfu.media = SfuParticipantObservation("PA_new", SfuParticipantState.ACTIVE)
        inbox.accept(VerifiedMediaWebhook("EV_room_" + id(), "a".repeat(64), "room", null, null, MediaWebhookKind.ROOM_FINISHED))
        assertEquals(fresh, inbox.pending(1).single().targetSessionId)
        val revokes = sfu.revocations
        assertTrue(worker().reconcileNext())
        assertEquals(fresh, repository.currentClaim(actor)!!.sessionId)
        assertEquals("PA_new", repository.get(actor, next)!!.media.participantSid)
        assertEquals(revokes, sfu.revocations)
        assertEquals(0, sfu.renewals)
        assertEquals(2, issued)
    }

    @Test fun roomTargetTimeoutAndLostAckRemainDurableWithoutDuplicateVisibleRevision() {
        inbox.accept(VerifiedMediaWebhook("EV_room_" + id(), "a".repeat(64), "room", null, null, MediaWebhookKind.ROOM_FINISHED))
        sfu.failure = SfuFailure.TIMEOUT
        assertFalse(worker().reconcileNext())
        assertEquals(1, inbox.pending(1).size)
        sfu.failure = null
        sfu.media = SfuParticipantObservation("PA_new", SfuParticipantState.ACTIVE)
        val lostAck = object : MediaWebhookInbox by inbox {
            override fun complete(eventId: String, bodySha256: String, targetSessionId: String) = false
        }
        assertFalse(MediaWebhookReconciler(lostAck, repository, service).reconcileNext())
        val revision = JdbcRosterJournal(source).revision()
        assertEquals("PA_new", repository.get(actor, operation)!!.media.participantSid)
        assertEquals(1, repository.get(actor, operation)!!.media.observationVersion)
        assertTrue(worker().reconcileNext())
        assertEquals(revision, JdbcRosterJournal(source).revision())
        assertEquals(2, repository.get(actor, operation)!!.media.observationVersion)
        assertTrue(inbox.pending(1).isEmpty())
        assertEquals(1, issued)
        assertEquals(0, sfu.renewals)
    }
    private fun event(kind: MediaWebhookKind, sid: String) = VerifiedMediaWebhook("EV_" + id(), "a".repeat(64), "room",
        repository.get(actor, operation)!!.operation.sessionId!!, sid, kind)
    private fun schema(): javax.sql.DataSource {
        val name = "media_worker_test_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()

    private class MediaSfu : SfuLeaseClient, SfuConnectedLeaseClient {
        var media = SfuParticipantObservation(null, null)
        var failure: SfuFailure? = null
        var observations = 0; var renewals = 0; var revocations = 0
        override fun inspect(room: String) = SfuReply.Accepted(SfuNodeStatus("a".repeat(32), true, true, true))
        override fun register(room: String, session: String, expectedNodeEpoch: String) = SfuReply.Accepted(LeaseReceipt(30_100))
        override fun renew(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt> = error("Generic renewal must never be used")
        override fun revoke(room: String, session: String, expectedNodeEpoch: String): SfuReply<Unit> { revocations++; return SfuReply.Accepted(Unit) }
        override fun observeParticipant(room: String, session: String, expectedNodeEpoch: String): SfuReply<SfuParticipantObservation> {
            observations++; failure?.let { return SfuReply.Rejected(it) }; return SfuReply.Accepted(media)
        }
        override fun renewConnected(room: String, session: String, expectedNodeEpoch: String, expectedParticipantSid: String): SfuReply<LeaseReceipt> {
            renewals++; return SfuReply.Accepted(LeaseReceipt(30_100))
        }
    }
}
