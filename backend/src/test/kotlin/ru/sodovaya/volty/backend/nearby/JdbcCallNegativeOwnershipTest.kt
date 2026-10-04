package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcCallNegativeOwnershipTest {
    private val caller = id(); private val callee = id(); private val call = id()
    private val callerOperation = id(); private val calleeOperation = id()
    private val name = "call_negative_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $name")}}
        NearbyPostgresFixture.source(name)
    }
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
    private val media = JdbcDirectCallMediaRepository(source)
    private lateinit var channel: String

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(caller, callee))
                s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
        }}
        val communities = JdbcCommunityRepository(source)
        val community = id()
        channel = assertIs<CommunityWriteResult.Accepted>(communities.create(callee, community, "Riders", visibility = CommunityVisibility.PUBLIC, now = {100})).snapshot.channels.single().id
        assertIs<CommunityWriteResult.Accepted>(communities.joinPublic(caller, community, {100}))
        assertEquals(DirectCallPhase.RINGING, JdbcDirectCallRingingRepository(source).create(caller, callee, call, id(), callerOperation, {100}).call?.phase)
        assertEquals(DirectCallPhase.ACCEPTED, media.accept(callee, call, calleeOperation, 0, SfuEpoch("volty-call-$call", "node"), {101}).call?.phase)
    }

    @Test fun genericCalleeCancellationEndsBothOriginalSessions() {
        val stopped = assertNotNull(sessions.cancel(callee, calleeOperation, {102}).state)
        assertTrue(stopped.operation.terminal)
        assertEquals("ENDED", phase()); assertEquals("CALLEE_ENDED", outcome())
        assertPairClosed()
    }

    @Test fun freshAcceptedPendingExpiryDoesNotApplyChannelDenial() {
        val original = assertNotNull(sessions.get(caller, callerOperation))
        val result = sessions.expire(caller, callerOperation, {102})
        assertNull(result.rejection); assertEquals(original, result.state)
        assertEquals("ACCEPTED", phase())
        assertNotNull(sessions.currentClaim(caller)); assertNotNull(sessions.currentClaim(callee))
    }

    @Test fun elapsedGenericExpiryClosesWholeCallWithTimeoutReason() {
        sessions.expire(callee, calleeOperation, {15_101})
        assertEquals("ENDED", phase()); assertEquals("MEDIA_TIMEOUT", outcome())
        assertPairClosed()
    }

    @Test fun accountDeletionRetiresPeerMediaAlongsideOwnAccount() {
        assertEquals(1, JdbcAccountDeletionRepository(source).deleteAccount(callee, {102}))
        assertEquals("ENDED", phase()); assertEquals("FRIENDSHIP_REVOKED", outcome())
        assertPairClosed()
    }

    @Test fun channelReplacementCannotStealAcceptedCallClaim() {
        val original = assertNotNull(sessions.currentClaim(caller))
        val operation = id()
        val result = sessions.begin(caller, operation, channel, id(), SfuEpoch("volty-channel-$channel", "node"), {102}, true)
        assertEquals(OperationRejection.CLAIM_BUSY, result.rejection)
        assertEquals(original, sessions.currentClaim(caller)); assertNull(sessions.get(caller, operation))
        assertEquals("ACCEPTED", phase()); assertNotNull(sessions.currentClaim(callee))
    }

    @Test fun acceptedFriendRevocationEndsBothOnGenericReconciliation() {
        source.connection.use {c -> c.createStatement().use {it.execute("DELETE FROM friendships")}}
        sessions.expire(caller, callerOperation, {102})
        assertEquals("ENDED", phase()); assertEquals("FRIENDSHIP_REVOKED", outcome())
        assertPairClosed()
    }

    @Test fun oldGenericStopAfterNewChannelCannotReleaseNewerClaim() {
        media.stop(caller, call, {102})
        val nextOperation = id()
        assertNull(sessions.begin(caller, nextOperation, channel, id(), SfuEpoch("volty-channel-$channel", "node"), {103}).rejection)
        val newer = assertNotNull(sessions.currentClaim(caller))
        sessions.cancel(callee, calleeOperation, {104})
        assertEquals(newer, sessions.currentClaim(caller)); assertEquals("CALLER_ENDED", outcome())
    }

    @Test fun accountFailureRollsBackBothOriginalMediaAndAccountState() {
        val callerClaim = assertNotNull(sessions.currentClaim(caller)); val calleeClaim = assertNotNull(sessions.currentClaim(callee))
        source.connection.use {c -> c.createStatement().use {it.execute("ALTER TABLE users ADD CONSTRAINT fixture_reject_delete CHECK (deleted_at IS NULL) NOT VALID")}}
        assertFailsWith<SQLException> {JdbcAccountDeletionRepository(source).deleteAccount(callee, {102})}
        assertEquals("ACCEPTED", phase()); assertNull(outcome())
        assertEquals(callerClaim, sessions.currentClaim(caller)); assertEquals(calleeClaim, sessions.currentClaim(callee))
        assertFalse(sessions.get(caller, callerOperation)!!.cleanupPending)
        assertFalse(sessions.get(callee, calleeOperation)!!.cleanupPending)
    }

    @Test fun lostOriginalClaimEndsOldPairAndPreservesNewerClaim() {
        val next = SessionClaim(id(), id(), SfuEpoch("newer", "node"))
        source.connection.use {c -> c.prepareStatement("UPDATE nearby_actor_claims SET operation_id=?::uuid,session_id=?::uuid,room_epoch=?,node_epoch=? WHERE actor_id=?::uuid").use {
            it.setString(1, next.operationId); it.setString(2, next.sessionId); it.setString(3, next.sfuEpoch.roomEpoch)
            it.setString(4, next.sfuEpoch.nodeEpoch); it.setString(5, callee); assertEquals(1, it.executeUpdate())
        }}
        sessions.expire(caller, callerOperation, {102})
        assertEquals("ENDED", phase()); assertEquals(DirectCallOutcome.MEDIA_LOST.name, outcome())
        assertNull(sessions.currentClaim(caller)); assertEquals(next, sessions.currentClaim(callee))
        assertTrue(sessions.get(caller, callerOperation)!!.cleanupPending)
        assertTrue(sessions.get(callee, calleeOperation)!!.cleanupPending)
    }

    @Test fun accountDeletionAtElapsedMediaLeaseRetainsTimeoutOutcome() {
        assertEquals(1, JdbcAccountDeletionRepository(source).deleteAccount(callee, {15_101}))
        assertEquals("ENDED", phase()); assertEquals("MEDIA_TIMEOUT", outcome())
        assertPairClosed()
    }

    @Test fun terminalOldChannelOperationCanBeReplayedWithoutTouchingAcceptedCall() {
        val oldOperation = id()
        sessions.cancel(caller, oldOperation, {102})
        val callClaim = assertNotNull(sessions.currentClaim(caller))
        val replay = sessions.begin(caller, oldOperation, channel, id(), SfuEpoch("volty-channel-$channel", "node"), {103}, true)
        assertEquals(OperationRejection.TERMINAL, replay.rejection)
        assertNull(replay.state!!.operation.sessionId)
        assertEquals(callClaim, sessions.currentClaim(caller))
        assertEquals("ACCEPTED", phase()); assertNotNull(sessions.currentClaim(callee))
    }

    private fun assertPairClosed() {
        assertNull(sessions.currentClaim(caller)); assertNull(sessions.currentClaim(callee))
        for ((actor, operation) in listOf(caller to callerOperation, callee to calleeOperation)) {
            val row = assertNotNull(sessions.get(actor, operation))
            assertTrue(row.operation.terminal); assertTrue(row.cleanupPending); assertFalse(row.admissionPending)
        }
    }
    private fun phase() = field("phase")
    private fun outcome() = field("outcome")
    private fun field(column: String): String? = source.connection.use {c -> c.prepareStatement("SELECT $column FROM nearby_direct_calls WHERE call_id=?::uuid").use {
        it.setString(1, call); it.executeQuery().use {r -> assertTrue(r.next()); r.getString(1)}
    }}
    private fun id() = UUID.randomUUID().toString()
}
