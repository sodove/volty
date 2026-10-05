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
class JdbcChannelSharingTest {
    private fun id() = UUID.randomUUID().toString()
    private val actor = id(); private val other = id(); private val community = id(); private val channel = id()
    private val operation = id(); private val session = id(); private val generation = id()
    private val schema = "share_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $schema")}}
        NearbyPostgresFixture.source(schema)
    }
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
    private val shares = JdbcChannelSharingRepository(sessions)
    private val epoch = SfuEpoch("volty-channel-$channel", "node")
    private var now = 101L

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c ->
            c.autoCommit = false
            c.createStatement().use {s ->
                for (user in listOf(actor, other)) s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$user','$user@example.invalid','test','test',0)")
                s.execute("INSERT INTO nearby_communities(id,name,owner_id,created_by,created_at) VALUES ('$community','Riders','$actor','$actor',0)")
                for (user in listOf(actor, other)) s.execute("INSERT INTO nearby_community_members VALUES ('$community','$user',0)")
                s.execute("INSERT INTO nearby_voice_channels(id,community_id,name,created_at) VALUES ('$channel','$community','Ride',0)")
            }
            c.commit()
        }
    }
    private fun join(op: String = operation, sid: String = session, replace: Boolean = false) {
        assertNull(sessions.begin(actor, op, channel, sid, epoch, {now}, replace).rejection)
        assertNull(sessions.admit(actor, op, sid, epoch, {now}, now + 30_000).rejection)
    }
    private fun start(gen: String = generation) = shares.start(actor, session, gen, ChannelSharingMode.LOCATION, ChannelSharingTtl.HALF_HOUR, {now})

    @Test fun actualSessionEarnsImmutableConsentAndReplayCannotExtendOrBroadenIt() {
        join(); val first = assertNotNull(start())
        assertEquals(ChannelSharingPhase.ACTIVE, first.phase); assertEquals(ChannelSharingMode.LOCATION, first.mode)
        assertEquals(now + 1_800_000, first.deadlineEpochMillis)
        now += 1000
        assertEquals(first, shares.start(actor, session, generation, ChannelSharingMode.FULL, ChannelSharingTtl.EIGHT_HOURS, {now}))
        assertEquals(first, shares.active(actor, session, generation, {now}))
    }

    @Test fun membershipPendingSessionAndWrongActorSessionNeverGrantConsent() {
        assertNull(start())
        sessions.begin(actor, operation, channel, session, epoch, {now})
        assertNull(start())
        sessions.admit(actor, operation, session, epoch, {now}, now + 30_000)
        assertNull(shares.start(other, session, generation, ChannelSharingMode.FULL, ChannelSharingTtl.HALF_HOUR, {now}))
        assertNull(shares.start(actor, id(), generation, ChannelSharingMode.FULL, ChannelSharingTtl.HALF_HOUR, {now}))
        assertNotNull(start())
    }

    @Test fun revokeBeforeStartSurvivesRestartWithoutStoppingVoiceOrMembership() {
        val tombstone = assertNotNull(shares.revoke(actor, session, generation, {now}))
        assertEquals(ChannelSharingPhase.REVOKED, tombstone.phase); assertNull(tombstone.mode); assertNull(tombstone.deadlineEpochMillis)
        join()
        val restarted = JdbcChannelSharingRepository(sessions)
        assertNull(restarted.start(actor, session, generation, ChannelSharingMode.FULL, ChannelSharingTtl.EIGHT_HOURS, {now}))
        assertEquals(tombstone, restarted.revoke(actor, session, generation, {now + 100}))
        assertEquals(session, sessions.currentClaim(actor)?.sessionId)
    }

    @Test fun hideAndOldRevokeCannotWithdrawExplicitNewGeneration() {
        join(); assertNotNull(start())
        assertNull(start(id()), "No second live generation without explicit hide")
        assertEquals(ChannelSharingPhase.REVOKED, shares.revoke(actor, session, generation, {now})?.phase)
        assertEquals(OperationPhase.ACTIVE, sessions.get(actor, operation)?.operation?.phase)
        val newGeneration = id(); val fresh = assertNotNull(start(newGeneration))
        shares.revoke(actor, session, generation, {now + 1})
        assertEquals(fresh, shares.active(actor, session, newGeneration, {now + 1}))
        assertNull(start())
    }

    @Test fun consentExpiryRequiresNewExplicitGenerationAndCannotBeReplayed() {
        join(); assertNotNull(start())
        now += 1_800_000
        // Deliberately keep an independently valid voice lease; sharing TTL is its own fence.
        source.connection.use {c -> c.createStatement().use {it.execute("UPDATE nearby_session_operations SET deadline_epoch_millis=${now + 30_000} WHERE actor_id='$actor'")}}
        assertNull(shares.active(actor, session, generation, {now})); assertNull(start())
        assertNotNull(start(id()))
    }

    @Test fun disconnectRevokesExactOriginalSharingAndLateRevokePreservesReplacement() {
        join(); assertNotNull(start()); sessions.cancel(actor, operation, {now})
        assertEquals("REVOKED", rawPhase(), "Journal cancellation itself must revoke before any sharing request")
        assertEquals(ChannelSharingPhase.REVOKED, shares.revoke(actor, session, generation, {now})?.phase)
        assertNull(shares.active(actor, session, generation, {now}))
        val newOperation = id(); val newSession = id(); val freshGeneration = id()
        join(newOperation, newSession)
        val fresh = assertNotNull(shares.start(actor, newSession, freshGeneration, ChannelSharingMode.RIDE, ChannelSharingTtl.TWO_HOURS, {now}))
        shares.revoke(actor, session, generation, {now})
        assertEquals(fresh, shares.active(actor, newSession, freshGeneration, {now}))
        assertEquals(newSession, sessions.currentClaim(actor)?.sessionId)
    }

    @Test fun channelAclLossWithdrawsConsentAndOriginalVoiceClaim() {
        join(); assertNotNull(start())
        source.connection.use {c -> c.createStatement().use {it.execute("INSERT INTO nearby_community_bans VALUES ('$community','$actor',0)")}}
        assertNull(shares.active(actor, session, generation, {now}))
        assertNull(sessions.currentClaim(actor)); assertTrue(assertNotNull(sessions.get(actor, operation)).operation.terminal)
    }

    @Test fun typedAcceptedCallNeverGetsChannelSharingOrLosesItsOriginalClaimOnRejection() {
        val call = id(); val callerOperation = id(); val calleeOperation = id()
        val callEpoch = SfuEpoch("volty-call-$call", "node")
        source.connection.use {c -> c.createStatement().use {it.execute("INSERT INTO friendships VALUES ('${id()}','$actor','$other','ACCEPTED',0)")}}
        val rings = JdbcDirectCallRingingRepository(source)
        assertEquals(DirectCallPhase.RINGING, rings.create(actor, other, call, id(), callerOperation, {now}).call?.phase)
        assertEquals(DirectCallPhase.ACCEPTED, JdbcDirectCallMediaRepository(source).accept(other, call, calleeOperation, 0, callEpoch, {now}).call?.phase)
        val callSessions = JdbcSessionRepository(source, JdbcVoiceAccess {now})
        val row = assertNotNull(callSessions.get(actor, callerOperation)); val sid = assertNotNull(row.operation.sessionId)
        assertNull(callSessions.admit(actor, callerOperation, sid, callEpoch, {now}, now + 30_000).rejection)
        val claim = callSessions.currentClaim(actor)
        assertNull(JdbcChannelSharingRepository(callSessions).start(actor, sid, generation, ChannelSharingMode.FULL, ChannelSharingTtl.HALF_HOUR, {now}))
        assertEquals(claim, callSessions.currentClaim(actor)); assertEquals(OperationPhase.ACTIVE, callSessions.get(actor, callerOperation)?.operation?.phase)
    }

    @Test fun voiceLeaseExpiryRevokesConsentWithoutWaitingForExplicitDisconnect() {
        join(); assertNotNull(start()); now += 30_000
        assertNull(shares.active(actor, session, generation, {now}))
        assertEquals("REVOKED", rawPhase()); assertNull(sessions.currentClaim(actor))
        assertTrue(assertNotNull(sessions.get(actor, operation)).cleanupPending)
    }

    @Test fun concurrentStartAndRevokeAlwaysEndWithDurableOriginalTombstone() {
        join(); val ready = CountDownLatch(2); val launch = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val positive = pool.submit<ChannelSharingConsent?> {ready.countDown(); check(launch.await(5, TimeUnit.SECONDS)); start()}
            val negative = pool.submit<ChannelSharingConsent?> {ready.countDown(); check(launch.await(5, TimeUnit.SECONDS)); shares.revoke(actor, session, generation, {now})}
            assertTrue(ready.await(5, TimeUnit.SECONDS)); launch.countDown()
            positive.get(15, TimeUnit.SECONDS); assertEquals(ChannelSharingPhase.REVOKED, negative.get(15, TimeUnit.SECONDS)?.phase)
            assertEquals("REVOKED", rawPhase()); assertNull(start()); assertEquals(session, sessions.currentClaim(actor)?.sessionId)
        } finally {launch.countDown(); pool.shutdownNow()}
    }

    @Test fun actualEndpointCreditUsesOriginalDeadlineAndNegativeWorksAfterVoiceDisconnect() {
        join(); val endpoint = JdbcChannelSharingEndpoint(shares) {now}
        val first = assertNotNull(endpoint.start(actor, session, generation, ChannelSharingMode.LOCATION, ChannelSharingTtl.HALF_HOUR))
        assertEquals(1_800_000, first.remainingMillis)
        now += 1000
        val replay = assertNotNull(endpoint.start(actor, session, generation, ChannelSharingMode.FULL, ChannelSharingTtl.EIGHT_HOURS))
        assertEquals(first.consent, replay.consent); assertEquals(1_799_000, replay.remainingMillis)
        assertEquals(replay, endpoint.status(actor, session, generation))
        sessions.cancel(actor, operation, {now})
        val negative = assertNotNull(endpoint.revoke(actor, session, generation))
        assertEquals(0, negative.remainingMillis); assertEquals(ChannelSharingPhase.REVOKED, negative.consent.phase)
        assertNull(endpoint.status(actor, session, generation)); assertNull(sessions.currentClaim(actor))
    }

    @Test fun responsePreparationPastOriginalConsentDeadlineCannotOfferPositiveCredit() {
        join(); var reads = 0
        val endpoint = JdbcChannelSharingEndpoint(shares) {if (++reads == 1) now else now + 1_800_000}
        assertNull(endpoint.start(actor, session, generation, ChannelSharingMode.LOCATION, ChannelSharingTtl.HALF_HOUR))
        assertEquals(2, reads); assertEquals(session, sessions.currentClaim(actor)?.sessionId)
    }

    private fun rawPhase(): String? = source.connection.use {c -> c.prepareStatement("SELECT phase FROM nearby_channel_sharing_consent WHERE actor_id=?::uuid AND session_id=?::uuid AND generation_id=?::uuid").use {
        it.setString(1, actor); it.setString(2, session); it.setString(3, generation)
        it.executeQuery().use {rows -> if (rows.next()) rows.getString(1) else null}
    }}
}
