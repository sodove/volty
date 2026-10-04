package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.sql.Connection
import java.sql.PreparedStatement
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcCallOperationBindingTest {
    private val caller = id(); private val callee = id(); private val operation = id()
    private val name = "call_binding_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $name")}}
        NearbyPostgresFixture.source(name)
    }
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
    private val rings = JdbcDirectCallRingingRepository(source)
    private lateinit var channel: String
    private val call get() = channel // Deliberate collision: namespaces must earn separation.

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(caller, callee))
                s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
        }}
        val communities = JdbcCommunityRepository(source); val community = id()
        channel = assertIs<CommunityWriteResult.Accepted>(communities.create(callee, community, "Riders", visibility = CommunityVisibility.PUBLIC, now = {100})).snapshot.channels.single().id
        assertIs<CommunityWriteResult.Accepted>(communities.joinPublic(caller, community, {100}))
        assertEquals(DirectCallPhase.RINGING, rings.create(caller, callee, call, id(), operation, {100}).call?.phase)
    }

    @Test fun terminalRingingCallerNonceCannotStartUnrelatedChannel() {
        rings.decline(callee, call, {101})
        val replay = sessions.begin(caller, operation, channel, id(), SfuEpoch("volty-channel-$channel", "node"), {102})
        assertNotNull(replay.rejection)
        assertNull(replay.state?.operation?.sessionId)
        assertNull(sessions.currentClaim(caller))
        assertEquals(DirectCallOutcome.CALLEE_DECLINED, rings.status(callee, call, {103}).call?.outcome)
    }

    @Test fun channelOnlyServiceRejectsCollidingCallNonceBeforeSfuOrStop() {
        val calleeOperation = id()
        val media = JdbcDirectCallMediaRepository(source)
        assertEquals(DirectCallPhase.ACCEPTED, media.accept(callee, call, calleeOperation, 0, SfuEpoch("volty-call-$call", "node"), {101}).call?.phase)
        val callerClaim = assertNotNull(sessions.currentClaim(caller)); val calleeClaim = assertNotNull(sessions.currentClaim(callee))
        val sfu = RecordingSfu(); var tokens = 0
        val service = SessionService(sessions, sfu, {_, _ -> "volty-channel-$channel"}, SessionCredentialIssuer {tokens++; error("unexpected token")}, {102})
        val denied = assertIs<SessionAction.Denied>(service.join(caller, operation, channel))
        assertEquals(SessionDenial.OPERATION_CONFLICT, denied.reason)
        assertTrue(sfu.calls.isEmpty()); assertEquals(0, tokens)
        assertEquals(callerClaim, sessions.currentClaim(caller)); assertEquals(calleeClaim, sessions.currentClaim(callee))
    }

    @Test fun repositoryDenialWithoutRowIsStableConflictInsteadOfNullFailure() {
        val rejecting = object : SessionRepository by sessions {
            override fun begin(actor: String, operation: String, destination: String, session: String, sfuEpoch: SfuEpoch, now: () -> Long, replace: Boolean) =
                SessionJournalResult(null, OperationRejection.DESTINATION_MISMATCH)
        }
        val sfu = RecordingSfu(); var tokens = 0
        val service = SessionService(rejecting, sfu, {_, _ -> "volty-channel-$channel"}, SessionCredentialIssuer {tokens++; error("unexpected token")}, {102})
        val result = runCatching {service.join(caller, id(), channel)}
        assertTrue(result.isSuccess, "A denied request must return an outcome, not throw: ${result.exceptionOrNull()?.javaClass?.simpleName}")
        val denied = assertIs<SessionAction.Denied>(result.getOrThrow())
        assertEquals(SessionDenial.OPERATION_CONFLICT, denied.reason)
        assertEquals(listOf("inspect"), sfu.calls); assertEquals(0, tokens)
    }

    @Test fun genericCallerCancelDuringRingingClosesOriginalReservationImmediately() {
        val stopped = assertNotNull(sessions.cancel(caller, operation, {101}).state)
        assertTrue(stopped.operation.terminal); assertNull(stopped.operation.sessionId)
        assertEquals(DirectCallPhase.CANCELLED, rawPhase())
        assertNull(reserved(caller))
        assertEquals(DirectCallRejection.TERMINAL, JdbcDirectCallMediaRepository(source).accept(callee, call, id(), 0, SfuEpoch("volty-call-$call", "node"), {102}).rejection)
    }

    @Test fun callerCancellationBeforeCreationCannotProduceIncomingRing() {
        val late = id(); val cancelledOperation = id()
        sessions.cancel(callee, cancelledOperation, {101})
        val result = rings.create(callee, caller, late, id(), cancelledOperation, {102})
        assertEquals(DirectCallPhase.CANCELLED, result.call?.phase)
        assertEquals(DirectCallOutcome.CALLER_CANCELLED, result.call?.outcome)
        assertNull(reserved(callee))
        assertEquals(DirectCallRejection.ACCESS_DENIED, rings.status(caller, late, {103}).rejection)
    }

    @Test fun cancelledNonceCreationSamplesClockOnceAndCannotTurnIntoTimeout() {
        val cancelledOperation = id(); val late = id()
        sessions.cancel(callee, cancelledOperation, {101})
        var samples = 0
        val result = runCatching {rings.create(callee, caller, late, id(), cancelledOperation, {
            samples++; if (samples == 1) 100 else 45_100
        })}
        assertTrue(result.isSuccess, "Private cancellation must be constructed from one server instant")
        assertEquals(DirectCallPhase.CANCELLED, result.getOrThrow().call?.phase)
        assertEquals(1, samples)
        assertNull(reserved(callee)); assertEquals(DirectCallRejection.ACCESS_DENIED, rings.status(caller, late, {103}).rejection)
    }

    @Test fun knownOldChannelOperationCannotBeReusedAsNewCall() {
        val old = id(); val session = id(); val epoch = SfuEpoch("volty-channel-$channel", "node")
        assertNull(sessions.begin(callee, old, channel, session, epoch, {101}).rejection)
        sessions.cancel(callee, old, {102})
        val original = assertNotNull(sessions.get(callee, old))
        assertEquals(DirectCallRejection.IDENTITY_MISMATCH, rings.create(callee, caller, id(), id(), old, {103}).rejection)
        assertEquals(original, sessions.get(callee, old)); assertNull(reserved(callee))
    }

    @Test fun cancellationWaitingAtOriginalActorLockHandsOffToWholePairAfterCreationCommits() {
        val createEntered = CountDownLatch(1); val releaseCreate = CountDownLatch(1); val cancelAtActor = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2); val late = id(); val lateOperation = id()
        val traced = object : DataSource by source {
            override fun getConnection(): Connection {
                val delegate = source.connection
                return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) {_, method, args ->
                    val result = invoke(method, delegate, args)
                    if (method.name == "prepareStatement" && args?.firstOrNull()?.toString()?.startsWith("SELECT actor_id FROM nearby_actor_claims") == true) {
                        val statement = result as PreparedStatement
                        Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) {_, command, parameters ->
                            if (command.name == "executeQuery") cancelAtActor.countDown()
                            invoke(command, statement, parameters)
                        }
                    } else result
                } as Connection
            }
        }
        try {
            val creating = pool.submit<DirectCallDecision> {rings.create(callee, caller, late, id(), lateOperation, {
                createEntered.countDown(); check(releaseCreate.await(5, TimeUnit.SECONDS)); 100
            })}
            assertTrue(createEntered.await(5, TimeUnit.SECONDS))
            val cancelling = pool.submit<SessionJournalResult> {JdbcSessionRepository(traced, JdbcChannelAccess()).cancel(callee, lateOperation, {101})}
            // Owned pair cancellation would block at UPDATE fence before reaching actors.
            // This observed SQL execution proves the original single-actor fallback was issued
            // while creation still held SHARE fence + pair locks, not merely scheduled.
            assertTrue(cancelAtActor.await(5, TimeUnit.SECONDS)); releaseCreate.countDown()
            assertEquals(DirectCallPhase.RINGING, creating.get(10, TimeUnit.SECONDS).call?.phase)
            val result = assertNotNull(cancelling.get(10, TimeUnit.SECONDS).state)
            assertTrue(result.operation.terminal); assertNull(result.operation.sessionId)
            assertNull(reserved(callee))
            assertEquals(DirectCallPhase.CANCELLED, rings.status(caller, late, {102}).call?.phase)
        } finally {releaseCreate.countDown(); pool.shutdownNow()}
    }

    private fun invoke(method: Method, target: Any, args: Array<out Any?>?): Any? =
        try {method.invoke(target, *(args ?: emptyArray()))}
        catch (failure: InvocationTargetException) {throw failure.targetException}

    private fun rawPhase() = source.connection.use {c -> c.prepareStatement("SELECT phase FROM nearby_direct_calls WHERE call_id=?::uuid").use {
        it.setString(1, call); it.executeQuery().use {r -> assertTrue(r.next()); DirectCallPhase.valueOf(r.getString(1))}
    }}
    private fun reserved(actor: String): String? = source.connection.use {c -> c.prepareStatement("SELECT reserved_call_id FROM nearby_actor_claims WHERE actor_id=?::uuid").use {
        it.setString(1, actor); it.executeQuery().use {r -> if (r.next()) r.getString(1) else null}
    }}
    private class RecordingSfu : SfuLeaseClient {
        val calls = mutableListOf<String>()
        override fun inspect(room: String): SfuReply<SfuNodeStatus> {calls += "inspect"; return SfuReply.Accepted(SfuNodeStatus("node", true, true, true))}
        override fun register(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt> {calls += "register"; return SfuReply.Rejected(SfuFailure.UNAVAILABLE)}
        override fun renew(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt> {calls += "renew"; return SfuReply.Rejected(SfuFailure.UNAVAILABLE)}
        override fun revoke(room: String, session: String, expectedNodeEpoch: String): SfuReply<Unit> {calls += "revoke"; return SfuReply.Accepted(Unit)}
    }
    private fun id() = UUID.randomUUID().toString()
}
