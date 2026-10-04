package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcDirectCallRingingTest {
    private val caller = id(); private val callee = id(); private val other = id()
    private val source = schema()
    private val repository = JdbcDirectCallRingingRepository(source)
    private val call = id(); private val key = id(); private val operation = id()

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(caller, callee, other))
                s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
        }}
    }

    @Test fun ringReservesOnlyCallerAndBlocksChannelReplacementWithoutTouchingCallee() {
        val (channel, sessions) = channel()
        val calleeOperation = id()
        assertNull(sessions.begin(callee, calleeOperation, channel, id(), SfuEpoch("volty-channel-$channel", "node"), {100}, false).rejection)
        val calleeClaim = assertNotNull(sessions.currentClaim(callee))
        val revision = JdbcRosterJournal(source).revision()
        val ringing = create()
        assertEquals(DirectCallPhase.RINGING, ringing.phase)
        assertEquals(call, reserved(caller)); assertNull(reserved(callee))
        val blocked = sessions.begin(caller, id(), channel, id(), SfuEpoch("volty-channel-$channel", "node"), {101}, true)
        assertEquals(OperationRejection.CLAIM_BUSY, blocked.rejection)
        assertEquals(calleeClaim, sessions.currentClaim(callee)); assertEquals(call, reserved(caller))
        assertEquals(revision, JdbcRosterJournal(source).revision())
        assertEquals(1, count("nearby_session_operations")); assertEquals(0, count("sharing_sessions"))
        assertEquals(1, count("nearby_direct_calls"))
    }

    @Test fun friendshipAndExistingChannelClaimDenyWithoutCreatingCall() {
        assertEquals(DirectCallRejection.NOT_FRIENDS, repository.create(caller, other, id(), id(), id(), {100}).rejection)
        assertEquals(0, count("nearby_direct_calls")); assertNull(reserved(caller))
        val (channel, sessions) = channel()
        assertNull(sessions.begin(caller, id(), channel, id(), SfuEpoch("volty-channel-$channel", "node"), {100}, false).rejection)
        val old = assertNotNull(sessions.currentClaim(caller))
        assertEquals(DirectCallRejection.CLAIM_BUSY, repository.create(caller, callee, call, key, operation, {101}).rejection)
        assertEquals(old, sessions.currentClaim(caller)); assertEquals(0, count("nearby_direct_calls")); assertNull(reserved(caller))
    }

    @Test fun replayAndExpiredOldCallCannotReplaceOrReleaseNewReservation() {
        val original = create()
        assertEquals(original, repository.create(caller, callee, id(), key, operation, {200}).call)
        assertEquals(DirectCallRejection.IDENTITY_MISMATCH, repository.create(caller, callee, id(), key, id(), {200}).rejection)
        assertEquals(call, reserved(caller)); assertEquals(1, count("nearby_direct_calls"))
        val newer = id()
        assertEquals(newer, repository.create(caller, callee, newer, id(), id(), {45_100}).call?.callId)
        assertEquals(newer, reserved(caller))
        assertEquals(DirectCallPhase.MISSED, repository.status(callee, call, {45_101}).call?.phase)
        assertEquals(DirectCallOutcome.RING_TIMEOUT, repository.cancelRinging(caller, call, {45_102}).call?.outcome)
        assertEquals(newer, reserved(caller)); assertEquals(2, count("nearby_direct_calls"))
        assertEquals(DirectCallPhase.DECLINED, repository.decline(callee, newer, {45_103}).call?.phase)
        assertNull(reserved(caller)); assertNull(reserved(callee))
    }

    @Test fun outsiderCannotReadExpireOrCancelCall() {
        val original = create()
        val read = repository.status(other, call, {90_000})
        assertNull(read.call); assertEquals(DirectCallRejection.ACCESS_DENIED, read.rejection)
        val stop = repository.cancelRinging(other, call, {90_000})
        assertNull(stop.call); assertEquals(DirectCallRejection.ACCESS_DENIED, stop.rejection)
        assertEquals(call, reserved(caller))
        assertEquals(original, repository.status(caller, call, {101}).call)
    }

    @Test fun failureBetweenCallInsertAndReservationRollsBackBoth() {
        source.connection.use {c -> c.createStatement().use {
            it.execute("ALTER TABLE nearby_actor_claims ADD CONSTRAINT fixture_reject_call CHECK (reserved_call_id IS NULL) NOT VALID")
        }}
        assertFailsWith<SQLException> {repository.create(caller, callee, call, key, operation, {100})}
        assertEquals(0, count("nearby_direct_calls")); assertEquals(0, count("nearby_actor_claims"))
    }

    @Test fun concurrentOutgoingCallsCommitOneCallerReservation() {
        source.connection.use {c -> c.createStatement().use {
            it.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$other','ACCEPTED',0)")
            it.execute("INSERT INTO nearby_actor_claims(actor_id) VALUES ('$caller')")
        }}
        val pool = Executors.newFixedThreadPool(2); val issued = CountDownLatch(2)
        val blocker = source.connection; blocker.autoCommit = false
        try {
            blocker.createStatement().use {it.executeQuery("SELECT actor_id FROM nearby_actor_claims WHERE actor_id='$caller' FOR UPDATE").close()}
            val attempts = listOf(callee, other).map {target -> pool.submit<DirectCallDecision> {
                issued.countDown(); repository.create(caller, target, id(), id(), id(), {100})
            }}
            assertTrue(issued.await(5, TimeUnit.SECONDS)); blocker.commit()
            val results = attempts.map {it.get(10, TimeUnit.SECONDS)}
            assertEquals(1, results.count {it.rejection == null && it.call != null})
            val winner = assertNotNull(results.single {it.rejection == null}.call)
            assertEquals(1, results.count {it.rejection == DirectCallRejection.CLAIM_BUSY})
            assertEquals(winner.callId, reserved(caller)); assertNull(reserved(callee)); assertNull(reserved(other))
            assertEquals(1, count("nearby_direct_calls")); assertEquals(0, count("nearby_session_operations"))
        } finally {try {blocker.rollback()} finally {blocker.close(); pool.shutdownNow()}}
    }

    @Test fun outgoingCancelBeforeCreateTombstonesIdentityWithoutIncomingCallOrReservation() {
        val cancelled = assertNotNull(repository.cancelOutgoingRinging(caller, callee, call, key, operation, {100}).call)
        assertEquals(DirectCallPhase.CANCELLED, cancelled.phase)
        assertEquals(DirectCallOutcome.CALLER_CANCELLED, cancelled.outcome)
        assertNull(reserved(caller)); assertNull(reserved(callee))
        assertEquals(cancelled, repository.create(caller, callee, id(), key, operation, {101}).call)
        assertEquals(DirectCallRejection.ACCESS_DENIED, repository.status(callee, call, {102}).rejection)
        assertEquals(cancelled, repository.status(caller, call, {102}).call)
        source.connection.use {c -> c.createStatement().use {
            it.executeQuery("SELECT ever_rang FROM nearby_direct_calls WHERE call_id='$call'").use {r -> assertTrue(r.next()); assertFalse(r.getBoolean(1))}
        }}
        assertEquals(1, count("nearby_direct_calls")); assertEquals(0, count("nearby_session_operations"))
        assertEquals(0, count("sharing_sessions")); assertEquals(0L, JdbcRosterJournal(source).revision())
    }

    @Test fun replayWithSupersededCallerSlotCannotKeepOldRingAlive() {
        create()
        val newer = supersedeCallerSlot()
        val ended = repository.create(caller, callee, id(), key, operation, {101}).call
        assertEquals(DirectCallPhase.CANCELLED, ended?.phase)
        assertEquals(newer, JdbcSessionRepository(source, JdbcChannelAccess()).currentClaim(caller))
        assertNull(reserved(caller))
    }

    @Test fun calleeStatusWithSupersededCallerSlotCannotKeepOldRingAlive() {
        create()
        val newer = supersedeCallerSlot()
        val ended = repository.status(callee, call, {101}).call
        assertEquals(DirectCallPhase.CANCELLED, ended?.phase)
        assertEquals(newer, JdbcSessionRepository(source, JdbcChannelAccess()).currentClaim(caller))
        assertNull(reserved(caller))
    }

    @Test fun conflictingOutgoingStopCannotCancelOriginalRingOrReuseItsOperation() {
        val original = create()
        val conflict = repository.cancelOutgoingRinging(caller, other, id(), key, operation, {101})
        assertEquals(DirectCallRejection.IDENTITY_MISMATCH, conflict.rejection)
        assertEquals(original, repository.status(callee, call, {102}).call)
        assertEquals(call, reserved(caller))
        repository.cancelRinging(caller, call, {103})
        val reused = repository.create(caller, callee, id(), id(), operation, {104})
        assertEquals(DirectCallRejection.IDENTITY_MISMATCH, reused.rejection)
        assertNull(reserved(caller)); assertEquals(1, count("nearby_direct_calls"))
    }

    @Test fun friendshipRevocationClosesOnlyOriginalRingAndPreservesCalleeChannel() {
        val (channel, sessions) = channel()
        assertNull(sessions.begin(callee, id(), channel, id(), SfuEpoch("volty-channel-$channel", "node"), {100}, false).rejection)
        val calleeClaim = assertNotNull(sessions.currentClaim(callee))
        create()
        source.connection.use {c -> c.createStatement().use {it.executeUpdate("DELETE FROM friendships")}}
        val stopped = assertNotNull(repository.status(callee, call, {101}).call)
        assertEquals(DirectCallPhase.CANCELLED, stopped.phase)
        assertEquals(DirectCallOutcome.FRIENDSHIP_REVOKED, stopped.outcome)
        assertNull(reserved(caller)); assertEquals(calleeClaim, sessions.currentClaim(callee))
        assertEquals(stopped, repository.cancelRinging(caller, call, {102}).call)
        assertEquals(DirectCallRejection.NOT_FRIENDS, repository.create(caller, callee, id(), id(), id(), {103}).rejection)
        assertEquals(1, count("nearby_direct_calls")); assertEquals(1, count("nearby_session_operations"))
    }

    private fun supersedeCallerSlot(): SessionClaim {
        val next = SessionClaim(id(), id(), SfuEpoch("newer-channel", "node"))
        source.connection.use {c -> c.prepareStatement("UPDATE nearby_actor_claims SET reserved_call_id=NULL,operation_id=?::uuid,session_id=?::uuid,room_epoch=?,node_epoch=? WHERE actor_id=?::uuid").use {
            it.setString(1, next.operationId); it.setString(2, next.sessionId); it.setString(3, next.sfuEpoch.roomEpoch)
            it.setString(4, next.sfuEpoch.nodeEpoch); it.setString(5, caller); assertEquals(1, it.executeUpdate())
        }}
        return next
    }

    @Test fun databaseRejectsTerminalRowsWithoutOutcome() {
        source.connection.use {c -> c.prepareStatement("""
            INSERT INTO nearby_direct_calls(call_id,caller_id,callee_id,idempotency_key,caller_operation_id,phase,ring_deadline_millis,revision,outcome)
            VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,45100,1,NULL)
        """.trimIndent()).use {s ->
            for (phase in listOf("DECLINED", "CANCELLED", "MISSED", "ENDED")) {
                s.setString(1, id()); s.setString(2, caller); s.setString(3, callee)
                s.setString(4, id()); s.setString(5, id()); s.setString(6, phase)
                assertFailsWith<SQLException> {s.executeUpdate()}
            }
        }}
        assertEquals(0, count("nearby_direct_calls"))
    }

    private fun create() = assertNotNull(repository.create(caller, callee, call, key, operation, {100}).call)
    private fun channel(): Pair<String, JdbcSessionRepository> {
        val communities = JdbcCommunityRepository(source)
        val community = id()
        val detail = assertIs<CommunityWriteResult.Accepted>(communities.create(callee, community, "Riders", visibility = CommunityVisibility.PUBLIC, now = {100})).snapshot
        assertIs<CommunityWriteResult.Accepted>(communities.joinPublic(caller, community, {100}))
        return detail.channels.single().id to JdbcSessionRepository(source, JdbcChannelAccess())
    }
    private fun reserved(actor: String): String? = source.connection.use {c ->
        c.prepareStatement("SELECT reserved_call_id FROM nearby_actor_claims WHERE actor_id=?::uuid").use {
            it.setString(1, actor); it.executeQuery().use {r -> if (r.next()) r.getString(1) else null}
        }
    }
    private fun count(table: String) = source.connection.use {c -> c.createStatement().use {
        it.executeQuery("SELECT count(*) FROM $table").use {r -> assertTrue(r.next()); r.getInt(1)}
    }}
    private fun schema(): DataSource {
        val name = "call_ringing_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $name")}}
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}
