package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Tag("postgres")
class JdbcCallPositiveAdmissionTest {
    private val caller = id(); private val callee = id(); private val outsider = id()
    private val callerOperation = id(); private val calleeOperation = id()
    private val name = "call_positive_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $name")}}
        NearbyPostgresFixture.source(name)
    }
    private var at = 102L
    private val access = JdbcVoiceAccess {at}
    private val sessions = JdbcSessionRepository(source, access)
    private lateinit var channel: String
    private val call get() = channel // A public channel cannot confer private call authority.
    private val epoch get() = SfuEpoch("volty-call-$call", "node")

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(caller, callee, outsider))
                s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
        }}
        val communities = JdbcCommunityRepository(source); val community = id()
        channel = assertIs<CommunityWriteResult.Accepted>(communities.create(callee, community, "Riders", visibility = CommunityVisibility.PUBLIC, now = {100})).snapshot.channels.single().id
        for (actor in listOf(caller, outsider)) assertIs<CommunityWriteResult.Accepted>(communities.joinPublic(actor, community, {100}))
        assertEquals(DirectCallPhase.RINGING, JdbcDirectCallRingingRepository(source).create(caller, callee, call, id(), callerOperation, {100}).call?.phase)
        assertEquals(DirectCallPhase.ACCEPTED, JdbcDirectCallMediaRepository(source).accept(callee, call, calleeOperation, 0, epoch, {101}).call?.phase)
    }

    @Test fun callerAdmissionEarnsOnlyShortestLeaseUntilPendingPeerIsAdmitted() {
        val callerRow = assertNotNull(sessions.get(caller, callerOperation))
        assertNull(sessions.admit(caller, callerOperation, callerRow.operation.sessionId!!, epoch, {at}, 30_102).rejection)
        assertEquals(OperationPhase.ACTIVE, sessions.get(caller, callerOperation)!!.operation.phase)
        var produced = 0
        val creditDeadline = sessions.withActiveSession(caller, callerOperation, {at}) {row -> produced++; row.operation.deadlineEpochMillis!!}
        assertEquals(15_101, creditDeadline); assertEquals(1, produced)
        assertEquals(30_102, sessions.get(caller, callerOperation)!!.operation.deadlineEpochMillis) // Durable self lease is not rewritten.
        val calleeRow = assertNotNull(sessions.get(callee, calleeOperation))
        assertNull(sessions.admit(callee, calleeOperation, calleeRow.operation.sessionId!!, epoch, {at}, 30_102).rejection)
        assertEquals(30_102, sessions.withActiveSession(caller, callerOperation, {at}) {it.operation.deadlineEpochMillis!!})
        assertEquals(SessionMedia(), sessions.get(caller, callerOperation)!!.media) // Admission is not observed capture/render/transport.
    }

    @Test fun typedAccessRequiresPrivateCallPairInsteadOfCollidingPublicChannelMembership() {
        source.connection.use {c ->
            assertTrue(access.allows(c, caller, call, epoch.roomEpoch, VoiceDestinationKind.DIRECT_CALL))
            assertFalse(access.allows(c, outsider, call, epoch.roomEpoch, VoiceDestinationKind.DIRECT_CALL))
            assertFalse(access.allows(c, caller, call, "volty-channel-$channel", VoiceDestinationKind.DIRECT_CALL))
            assertTrue(access.allows(c, outsider, channel, "volty-channel-$channel", VoiceDestinationKind.CHANNEL))
        }
        assertNull(sessions.withActiveSession(outsider, id(), {at}) {error("outsider producer")})
    }

    @Test fun lateAdmissionAtOriginalPendingDeadlineEndsBothBeforeGrant() {
        val original = assertNotNull(sessions.get(caller, callerOperation)); at = 15_101
        val result = sessions.admit(caller, callerOperation, original.operation.sessionId!!, epoch, {at}, 30_102)
        assertTrue(result.state!!.operation.terminal)
        assertEquals("ENDED", field("phase")); assertEquals("MEDIA_TIMEOUT", field("outcome"))
        assertPairClosed()
        assertNull(sessions.withActiveSession(caller, callerOperation, {at}) {error("late producer")})
    }

    @Test fun expiredConnectedReceiptClosesWholePairWithoutTrustingRetainedFutureDeadline() {
        // Deliberate SQL observation fixture: active original media with future retained
        // deadlines, while the new conservative SFU receipt proves expired credit.
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(caller, callee)) s.execute("UPDATE nearby_session_operations SET phase='ACTIVE',deadline_epoch_millis=30102,admission_pending=FALSE,participant_sid='PA_$actor',media_state='ACTIVE',media_observation_version=1 WHERE actor_id='$actor'")
        }}
        val original = assertNotNull(sessions.get(caller, callerOperation))
        val result = sessions.heartbeatConnected(caller, callerOperation, original.operation.sessionId!!, epoch, 1, "PA_$caller", {at}, at)
        assertEquals(OperationPhase.EXPIRED, result.state!!.operation.phase)
        assertEquals("ENDED", field("phase")); assertEquals("MEDIA_TIMEOUT", field("outcome"))
        assertPairClosed()
    }

    private fun assertPairClosed() {
        assertNull(sessions.currentClaim(caller)); assertNull(sessions.currentClaim(callee))
        for ((actor, operation) in listOf(caller to callerOperation, callee to calleeOperation)) {
            val row = assertNotNull(sessions.get(actor, operation))
            assertTrue(row.operation.terminal); assertTrue(row.cleanupPending)
        }
    }

    @Test fun revokedFriendshipStopsBothBeforeCredentialProducerIsInvoked() {
        admitPair()
        source.connection.use {c -> c.createStatement().use {it.execute("DELETE FROM friendships")}}
        var issued = 0
        assertNull(sessions.withActiveSession(caller, callerOperation, {at}) {issued++; "grant"})
        assertEquals(0, issued); assertEquals("FRIENDSHIP_REVOKED", field("outcome")); assertPairClosed()
    }

    @Test fun staleObservationCannotCloseOrRenewOriginalPair() {
        admitPair()
        val row = assertNotNull(sessions.get(caller, callerOperation))
        val result = sessions.heartbeatConnected(caller, callerOperation, id(), epoch, 0, "PA_stale", {at}, at)
        assertEquals(OperationRejection.SESSION_MISMATCH, result.rejection)
        assertEquals(row, sessions.get(caller, callerOperation))
        assertEquals("ACCEPTED", field("phase")); assertNotNull(sessions.currentClaim(callee))
    }

    @Test fun producerFailureRollsBackWithoutInventingPairTermination() {
        admitPair()
        assertFailsWith<IllegalStateException> {
            sessions.withActiveSession(caller, callerOperation, {at}) {error("deliberate signing failure")}
        }
        assertEquals("ACCEPTED", field("phase"))
        for (actor in listOf(caller, callee)) assertNotNull(sessions.currentClaim(actor))
    }

    @Test fun pairStopWaitsForActualProducerThenPreventsEveryLaterGrant() {
        admitPair()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val issuedStop = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val grant = pool.submit<Long?> {
                sessions.withActiveSession(caller, callerOperation, {at}) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); it.operation.deadlineEpochMillis!!
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stop = pool.submit<DirectCallDecision> {
                issuedStop.countDown(); JdbcDirectCallMediaRepository(source).stop(callee, call, {at})
            }
            assertTrue(issuedStop.await(5, TimeUnit.SECONDS)); release.countDown()
            assertEquals(30_102, grant.get(5, TimeUnit.SECONDS))
            assertEquals(DirectCallPhase.ENDED, stop.get(5, TimeUnit.SECONDS).call?.phase)
            assertPairClosed()
            assertNull(sessions.withActiveSession(caller, callerOperation, {at}) {error("post-stop grant")})
        } finally {release.countDown(); pool.shutdownNow()}
    }

    private fun admitPair() {
        for ((actor, operation) in listOf(caller to callerOperation, callee to calleeOperation)) {
            val row = assertNotNull(sessions.get(actor, operation))
            assertNull(sessions.admit(actor, operation, row.operation.sessionId!!, epoch, {at}, 30_102).rejection)
        }
    }
    private fun field(column: String): String? = source.connection.use {c -> c.prepareStatement("SELECT $column FROM nearby_direct_calls WHERE call_id=?::uuid").use {
        it.setString(1, call); it.executeQuery().use {r -> assertTrue(r.next()); r.getString(1)}
    }}
    private fun id() = UUID.randomUUID().toString()
}
