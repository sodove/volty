package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcCallSessionServiceTest {
    private fun id() = UUID.randomUUID().toString()
    private val caller = id(); private val callee = id(); private val outsider = id()
    private val callerOperation = id(); private val calleeOperation = id(); private val call = id()
    private val schema = "call_service_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $schema")}}
        NearbyPostgresFixture.source(schema)
    }
    private var at = 102L
    private val repository = JdbcSessionRepository(source, JdbcVoiceAccess {at})
    private val epoch = SfuEpoch("volty-call-$call", "node")
    private val sfu = RecordingSfu()
    private var tokens = 0
    private val service = SessionService(repository, sfu, {_, _ -> error("CALL reached channel resolver")},
        SessionCredentialIssuer {row ->
            tokens++
            SessionCredentials("ws://test", "opaque", row.operation.sessionId!!, row.sfuEpoch!!.roomEpoch, row.operation.deadlineEpochMillis!!)
        }, {at}, resolveTypedRoom = {actor, destination, kind ->
            epoch.roomEpoch.takeIf {actor in listOf(caller, callee) && destination == call && kind == VoiceDestinationKind.DIRECT_CALL}
        })

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(caller, callee, outsider))
                s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
        }}
        assertEquals(DirectCallPhase.RINGING, JdbcDirectCallRingingRepository(source).create(caller, callee, call, id(), callerOperation, {100}).call?.phase)
        assertEquals(DirectCallPhase.ACCEPTED, JdbcDirectCallMediaRepository(source).accept(callee, call, calleeOperation, 0, epoch, {101}).call?.phase)
    }

    @Test fun preparedCallerReceivesOnlyCreditEarnedByPendingPeer() {
        val result = assertIs<SessionAction.Ready>(service.resumeCall(caller, callerOperation, call))
        assertEquals(15_101, result.credentials.deadlineEpochMillis)
        assertEquals(14_999, result.remainingMillis)
        assertEquals(epoch.roomEpoch, result.credentials.roomEpoch)
        assertEquals(1, tokens)
        assertEquals(SessionMedia(), repository.get(caller, callerOperation)!!.media)
        assertEquals(OperationPhase.PENDING, repository.get(callee, calleeOperation)!!.operation.phase)
        assertEquals(listOf("register:${repository.get(caller, callerOperation)!!.operation.sessionId}"), sfu.calls)
    }

    @Test fun preparedPairUsesCallRoomForCurrentObservationAndConditionalRenewal() {
        assertIs<SessionAction.Ready>(service.resumeCall(caller, callerOperation, call))
        assertIs<SessionAction.Ready>(service.resumeCall(callee, calleeOperation, call))
        val session = repository.get(caller, callerOperation)!!.operation.sessionId!!
        sfu.media = SfuParticipantObservation("PA_original", SfuParticipantState.ACTIVE)
        assertEquals(SfuParticipantState.ACTIVE, assertIs<SessionAction.Confirmed>(service.refreshMedia(caller, callerOperation)).media.state)
        at = 1_102
        val renewed = assertIs<SessionAction.Confirmed>(service.heartbeat(caller, callerOperation))
        assertEquals(30_102, renewed.deadlineEpochMillis) // Peer still owns its original shorter credit.
        assertTrue(sfu.calls.contains("renew:$session:PA_original"))
        assertEquals(2, tokens)
        assertEquals(epoch.roomEpoch, sfu.rooms.single())
    }

    @Test fun stopBeforeRegistrationAckNeverProducesBearerOrResurrectsPair() {
        sfu.beforeRegisterAck = {JdbcDirectCallMediaRepository(source).stop(callee, call, {at})}
        assertIs<SessionAction.Ended>(service.resumeCall(caller, callerOperation, call))
        assertEquals(0, tokens)
        assertNull(repository.currentClaim(caller)); assertNull(repository.currentClaim(callee))
        assertTrue(repository.get(caller, callerOperation)!!.operation.terminal)
        assertTrue(sfu.calls.any {it.startsWith("revoke:")})
    }

    @Test fun outsidersAndWrongCallBindingCannotIssueNetworkWorkOrStopOriginalPair() {
        val before = repository.get(caller, callerOperation)
        assertIs<SessionAction.Denied>(service.resumeCall(outsider, callerOperation, call))
        assertIs<SessionAction.Denied>(service.resumeCall(caller, callerOperation, id()))
        assertEquals(before, repository.get(caller, callerOperation))
        assertNotNull(repository.currentClaim(caller)); assertNotNull(repository.currentClaim(callee))
        assertTrue(sfu.calls.isEmpty()); assertEquals(0, tokens)
    }

    @Test fun activeReplayStillRequiresExplicitCallRoomResolverBeforeSigning() {
        assertIs<SessionAction.Ready>(service.resumeCall(caller, callerOperation, call))
        var issued = 0
        val channelOnly = SessionService(repository, sfu, {_, _ -> error("CALL reached channel-only resolver")},
            SessionCredentialIssuer {row ->
                issued++
                SessionCredentials("ws://test", "opaque", row.operation.sessionId!!, row.sfuEpoch!!.roomEpoch, row.operation.deadlineEpochMillis!!)
            }, {at})
        assertIs<SessionAction.Ended>(channelOnly.resumeCall(caller, callerOperation, call))
        assertEquals(0, issued)
        assertNull(repository.currentClaim(caller)); assertNull(repository.currentClaim(callee))
    }

    @Test fun elapsedPendingPeerStopsPairBeforeAnySfuRegistration() {
        at = 15_101
        assertIs<SessionAction.Ended>(service.resumeCall(caller, callerOperation, call))
        assertTrue(sfu.calls.none {it.startsWith("register:")}); assertEquals(0, tokens)
        assertNull(repository.currentClaim(caller)); assertNull(repository.currentClaim(callee))
    }

    @Test fun workerAdmitsOriginalPreparedOperationWithoutMintingCredentials() {
        val original = assertNotNull(repository.get(caller, callerOperation))
        assertTrue(service.reconcileNext(caller))
        assertEquals(0, tokens)
        val current = assertNotNull(repository.get(caller, callerOperation))
        assertEquals(original.operation.sessionId, current.operation.sessionId)
        assertEquals(OperationPhase.ACTIVE, current.operation.phase)
        assertEquals(SessionMedia(), current.media)
        assertIs<SessionAction.Ready>(service.resumeCall(caller, callerOperation, call))
        assertEquals(1, sfu.calls.count {it.startsWith("register:")})
    }

    @Test fun unavailableRegistrationRemainsPendingAndRetryUsesOriginalSession() {
        val original = assertNotNull(repository.get(caller, callerOperation))
        sfu.registrationFailure = SfuFailure.UNAVAILABLE
        assertIs<SessionAction.Pending>(service.resumeCall(caller, callerOperation, call))
        assertEquals(0, tokens); assertNotNull(repository.currentClaim(callee))
        sfu.registrationFailure = null
        val ready = assertIs<SessionAction.Ready>(service.resumeCall(caller, callerOperation, call))
        assertEquals(original.operation.sessionId, ready.credentials.sessionId)
        assertEquals(2, sfu.calls.count {it.startsWith("register:")})
    }

    @Test fun disconnectedObservationEarnsNoRenewalAndOriginalLeaseEventuallyEndsBoth() {
        assertIs<SessionAction.Ready>(service.resumeCall(caller, callerOperation, call))
        assertIs<SessionAction.Ready>(service.resumeCall(callee, calleeOperation, call))
        sfu.media = SfuParticipantObservation("PA_original", SfuParticipantState.DISCONNECTED)
        at = 1_102
        val result = assertIs<SessionAction.Confirmed>(service.heartbeat(caller, callerOperation))
        assertEquals(SfuParticipantState.DISCONNECTED, result.media.state)
        assertTrue(sfu.calls.none {it.startsWith("renew:")})
        assertEquals(30_102, result.deadlineEpochMillis)
        at = 30_102
        assertIs<SessionAction.Ended>(service.status(caller, callerOperation))
        assertNull(repository.currentClaim(caller)); assertNull(repository.currentClaim(callee))
        assertEquals(2, tokens)
    }

    private inner class RecordingSfu : SfuLeaseClient, SfuConnectedLeaseClient {
        val calls = mutableListOf<String>(); val rooms = mutableSetOf<String>()
        var beforeRegisterAck: () -> Unit = {}
        var registrationFailure: SfuFailure? = null
        var media = SfuParticipantObservation(null, null)
        override fun inspect(room: String): SfuReply<SfuNodeStatus> = error("prepared call must keep accepted node epoch")
        override fun register(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt> {
            assertEquals(epoch.roomEpoch, room); assertEquals(epoch.nodeEpoch, expectedNodeEpoch)
            rooms += room; calls += "register:$session"; beforeRegisterAck()
            registrationFailure?.let {return SfuReply.Rejected(it)}
            return SfuReply.Accepted(LeaseReceipt(at + 30_000))
        }
        override fun renew(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt> = error("unobserved renewal")
        override fun observeParticipant(room: String, session: String, expectedNodeEpoch: String): SfuReply<SfuParticipantObservation> {
            assertEquals(epoch.roomEpoch, room); rooms += room; calls += "observe:$session"
            return SfuReply.Accepted(media)
        }
        override fun renewConnected(room: String, session: String, expectedNodeEpoch: String, expectedParticipantSid: String): SfuReply<LeaseReceipt> {
            assertEquals(epoch.roomEpoch, room); assertEquals(media.participantSid, expectedParticipantSid)
            rooms += room; calls += "renew:$session:$expectedParticipantSid"
            return SfuReply.Accepted(LeaseReceipt(at + 30_000))
        }
        override fun revoke(room: String, session: String, expectedNodeEpoch: String): SfuReply<Unit> {
            assertEquals(epoch.roomEpoch, room); rooms += room; calls += "revoke:$session"
            return SfuReply.Accepted(Unit)
        }
    }
}
