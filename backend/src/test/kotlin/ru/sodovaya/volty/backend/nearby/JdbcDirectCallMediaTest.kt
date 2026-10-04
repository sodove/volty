package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcDirectCallMediaTest {
    private val caller = id(); private val callee = id(); private val other = id()
    private val call = id(); private val callerOperation = id(); private val calleeOperation = id()
    private val epoch = SfuEpoch("volty-call-$call", "node")
    private val name = "call_media_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $name")}}
        NearbyPostgresFixture.source(name)
    }
    private val rings = JdbcDirectCallRingingRepository(source)
    private val media = JdbcDirectCallMediaRepository(source)
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(caller, callee, other))
                s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$callee','$other','ACCEPTED',0)")
        }}
        assertEquals(DirectCallPhase.RINGING, rings.create(caller, callee, call, id(), callerOperation, {100}).call?.phase)
    }

    @Test fun acceptReplacesCalleeChannelAndCommitsTwoPendingOriginalClaims() {
        val oldOperation = channel()
        assertEquals(DirectCallPhase.ACCEPTED, accept().call?.phase)
        val callerRow = assertNotNull(sessions.get(caller, callerOperation))
        val calleeRow = assertNotNull(sessions.get(callee, calleeOperation))
        for (row in listOf(callerRow, calleeRow)) {
            assertEquals(VoiceDestinationKind.DIRECT_CALL, row.operation.destinationKind)
            assertEquals(call, row.operation.destinationId); assertEquals(OperationPhase.PENDING, row.operation.phase)
            assertEquals(15_101, row.operation.deadlineEpochMillis); assertEquals(epoch, row.sfuEpoch)
            assertTrue(row.admissionPending); assertFalse(row.cleanupPending)
            assertEquals(SessionClaim(row.operation.operationId, row.operation.sessionId!!, epoch), sessions.currentClaim(row.operation.actorId))
        }
        assertNotEquals(callerRow.operation.sessionId, calleeRow.operation.sessionId)
        val old = assertNotNull(sessions.get(callee, oldOperation))
        assertEquals(OperationPhase.CANCELLED, old.operation.phase); assertTrue(old.cleanupPending)
        assertNull(reserved(caller)); assertNull(reserved(callee))
    }

    @Test fun callerStopAfterAcceptClosesBothOriginalSessions() {
        accept()
        val stopped = media.stop(caller, call, {102})
        assertEquals(DirectCallPhase.ENDED, stopped.call?.phase)
        assertEquals(DirectCallOutcome.CALLER_ENDED, stopped.call?.outcome)
        assertNull(sessions.currentClaim(caller)); assertNull(sessions.currentClaim(callee))
        for ((actor, operation) in listOf(caller to callerOperation, callee to calleeOperation)) {
            val row = assertNotNull(sessions.get(actor, operation))
            assertTrue(row.operation.terminal); assertTrue(row.cleanupPending); assertFalse(row.admissionPending)
        }
        assertEquals(stopped.call, media.stop(callee, call, {103}).call)
        assertEquals(DirectCallRejection.TERMINAL, accept().rejection)
    }

    @Test fun stopBeforeAcceptCannotCreateMediaOrResurrectRing() {
        assertEquals(DirectCallPhase.CANCELLED, media.stop(caller, call, {101}).call?.phase)
        assertEquals(DirectCallRejection.TERMINAL, accept().rejection)
        assertNull(sessions.get(caller, callerOperation)); assertNull(sessions.get(callee, calleeOperation))
        assertNull(reserved(caller)); assertNull(sessions.currentClaim(caller)); assertNull(sessions.currentClaim(callee))
    }

    @Test fun calleeOutgoingReservationDeniesAcceptWithoutChangingEitherRing() {
        val outgoing = id()
        assertEquals(DirectCallPhase.RINGING, rings.create(callee, other, outgoing, id(), id(), {100}).call?.phase)
        assertEquals(DirectCallRejection.CLAIM_BUSY, accept().rejection)
        assertEquals(call, reserved(caller)); assertEquals(outgoing, reserved(callee))
        assertEquals(DirectCallPhase.RINGING, rings.status(caller, call, {102}).call?.phase)
        assertNull(sessions.get(caller, callerOperation)); assertNull(sessions.get(callee, calleeOperation))
    }

    @Test fun failureWritingSecondClaimRollsBackChannelAndFirstClaim() {
        val oldOperation = channel(); val old = assertNotNull(sessions.get(callee, oldOperation))
        val oldClaim = assertNotNull(sessions.currentClaim(callee)); val revision = JdbcRosterJournal(source).revision()
        source.connection.use {c -> c.createStatement().use {
            it.execute("ALTER TABLE nearby_actor_claims ADD CONSTRAINT fixture_reject_callee CHECK (actor_id<>'$callee'::uuid OR session_id IS NULL) NOT VALID")
        }}
        assertFailsWith<SQLException> {accept()}
        assertEquals(old, sessions.get(callee, oldOperation)); assertEquals(oldClaim, sessions.currentClaim(callee))
        assertEquals(call, reserved(caller)); assertEquals(revision, JdbcRosterJournal(source).revision())
        assertNull(sessions.get(caller, callerOperation)); assertNull(sessions.get(callee, calleeOperation))
        assertEquals(DirectCallPhase.RINGING, rings.status(caller, call, {102}).call?.phase)
    }

    @Test fun acceptedReplayKeepsOriginalSessionsAndRejectsCalleeBindingChange() {
        val accepted = accept()
        val callerRow = assertNotNull(sessions.get(caller, callerOperation))
        val calleeRow = assertNotNull(sessions.get(callee, calleeOperation))
        assertEquals(accepted.call, media.accept(callee, call, calleeOperation, 0, epoch, {102}).call)
        assertEquals(DirectCallRejection.IDENTITY_MISMATCH, media.accept(callee, call, id(), 0, epoch, {103}).rejection)
        assertEquals(callerRow, sessions.get(caller, callerOperation)); assertEquals(calleeRow, sessions.get(callee, calleeOperation))
    }

    @Test fun callerOperationTombstoneBeforeAcceptCancelsRingWithoutMedia() {
        sessions.cancel(caller, callerOperation, {101})
        val result = accept()
        assertEquals(DirectCallPhase.CANCELLED, result.call?.phase)
        assertEquals(DirectCallOutcome.CALLER_CANCELLED, result.call?.outcome)
        assertNull(reserved(caller)); assertNull(sessions.get(callee, calleeOperation))
        assertNull(sessions.get(caller, callerOperation)?.operation?.sessionId)
    }

    @Test fun expiredAcceptedSessionPairCannotBeReplayedAsLiveAcceptance() {
        accept()
        val result = media.accept(callee, call, calleeOperation, 0, epoch, {15_101})
        assertEquals(DirectCallPhase.ENDED, result.call?.phase)
        assertEquals(DirectCallOutcome.MEDIA_TIMEOUT, result.call?.outcome)
        assertNull(sessions.currentClaim(caller)); assertNull(sessions.currentClaim(callee))
        assertTrue(sessions.get(caller, callerOperation)!!.cleanupPending)
        assertTrue(sessions.get(callee, calleeOperation)!!.cleanupPending)
    }

    @Test fun stopAtExpiredAcceptedLeaseRecordsTimeoutWithoutInventingUserCause() {
        accept()
        val result = media.stop(caller, call, {15_101})
        assertEquals(DirectCallOutcome.MEDIA_TIMEOUT, result.call?.outcome)
        assertNull(sessions.currentClaim(caller)); assertNull(sessions.currentClaim(callee))
    }

    @Test fun outsiderCannotAcceptOrStopEvenAtElapsedRingDeadline() {
        val original = assertNotNull(rings.status(caller, call, {100}).call)
        assertNull(media.accept(other, call, id(), 0, epoch, {90_000}).call)
        assertEquals(DirectCallRejection.ACCESS_DENIED, media.stop(other, call, {90_000}).rejection)
        assertEquals(original, rings.status(caller, call, {100}).call)
        assertEquals(call, reserved(caller)); assertNull(sessions.get(caller, callerOperation))
    }

    @Test fun oldAcceptedStopPreservesIndependentlySupersedingCallerClaim() {
        accept()
        val next = SessionClaim(id(), id(), SfuEpoch("newer-channel", "node"))
        // Deliberately incoherent producer state distinguishes exact original ownership
        // from a bulk clear of whichever claim the actor happens to have now.
        source.connection.use {c -> c.prepareStatement("UPDATE nearby_actor_claims SET operation_id=?::uuid,session_id=?::uuid,room_epoch=?,node_epoch=? WHERE actor_id=?::uuid").use {
            it.setString(1, next.operationId); it.setString(2, next.sessionId); it.setString(3, next.sfuEpoch.roomEpoch)
            it.setString(4, next.sfuEpoch.nodeEpoch); it.setString(5, caller); assertEquals(1, it.executeUpdate())
        }}
        assertEquals(DirectCallPhase.ENDED, media.stop(callee, call, {102}).call?.phase)
        assertEquals(next, sessions.currentClaim(caller)); assertNull(sessions.currentClaim(callee))
        assertTrue(sessions.get(caller, callerOperation)!!.cleanupPending)
    }

    @Test fun acceptedCallRevokesBothOldSharingConsentsAndPositionsWithoutDeletingGroups() {
        val group = id()
        source.connection.use {c -> c.createStatement().use {s ->
            s.execute("INSERT INTO groups VALUES ('$group','Legacy ride','$callee','KEEPME',1000,100)")
            s.execute("INSERT INTO group_members VALUES ('$group','$callee','OWNER',100),('$group','$caller','MEMBER',100)")
            for (actor in listOf(caller, callee)) {
                s.execute("INSERT INTO sharing_sessions VALUES ('${id()}','$actor','$group','LOCATION',100,1000,NULL)")
                s.execute("INSERT INTO live_updates VALUES ('$actor','$group','{}',NULL,100,100)")
            }
        }}
        accept()
        source.connection.use {c -> c.createStatement().use {s ->
            s.executeQuery("SELECT count(*) FROM sharing_sessions WHERE revoked_at IS NULL").use {r -> assertTrue(r.next()); assertEquals(0, r.getInt(1))}
            s.executeQuery("SELECT count(*) FROM live_updates").use {r -> assertTrue(r.next()); assertEquals(0, r.getInt(1))}
            s.executeQuery("SELECT count(*) FROM groups").use {r -> assertTrue(r.next()); assertEquals(1, r.getInt(1))}
        }}
    }

    private fun accept() = media.accept(callee, call, calleeOperation, 0, epoch, {101})
    private fun channel(): String {
        val detail = assertIs<CommunityWriteResult.Accepted>(JdbcCommunityRepository(source).create(callee, id(), "Riders", now = {100})).snapshot
        val channel = detail.channels.single().id; val operation = id(); val session = id()
        val channelEpoch = SfuEpoch("volty-channel-$channel", "node")
        assertNull(sessions.begin(callee, operation, channel, session, channelEpoch, {100}).rejection)
        assertNull(sessions.admit(callee, operation, session, channelEpoch, {100}, 30_100).rejection)
        return operation
    }
    private fun reserved(actor: String): String? = source.connection.use {c -> c.prepareStatement("SELECT reserved_call_id FROM nearby_actor_claims WHERE actor_id=?::uuid").use {
        it.setString(1, actor); it.executeQuery().use {r -> if (r.next()) r.getString(1) else null}
    }}
    private fun id() = UUID.randomUUID().toString()
}
