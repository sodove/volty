package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcDirectCallRosterTest {
    private fun id() = UUID.randomUUID().toString()
    private val caller = id(); private val callee = id(); private val other = id()
    private val call = id(); private val callerOperation = id(); private val calleeOperation = id()
    private val epoch = SfuEpoch("volty-call-$call", "node")
    private val schema = "call_roster_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $schema")}}
        NearbyPostgresFixture.source(schema)
    }
    private var now = 101L
    private val rings = JdbcDirectCallRingingRepository(source)
    private val media = JdbcDirectCallMediaRepository(source)
    private val sessions = JdbcSessionRepository(source, JdbcVoiceAccess {now})
    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(caller, callee, other))
                s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
        }}
        assertEquals(DirectCallPhase.RINGING, rings.create(caller, callee, call, id(), callerOperation, {100}).call?.phase)
    }
    private fun accept() {assertEquals(DirectCallPhase.ACCEPTED, media.accept(callee, call, calleeOperation, 0, epoch, {now}).call?.phase)}

    @Test fun bothParticipantsReadSameOriginalPendingPairWithoutInventingMedia() {
        accept()
        val snapshot = assertNotNull(media.roster(caller, call, {now}))
        assertEquals(snapshot, media.roster(callee, call, {now}))
        assertEquals(call, snapshot.callId); assertEquals(now, snapshot.serverTimeEpochMillis)
        assertEquals(2, snapshot.revision)
        assertEquals(setOf(caller, callee), snapshot.peers.map {it.actorId}.toSet())
        assertTrue(snapshot.peers.all {it.phase == RosterConnectionPhase.JOINING && it.mediaPresence == MediaPresence.UNKNOWN && it.participantSid == null})
    }

    @Test fun ringingUnknownAndOutsiderNeverGetMediaRoster() {
        assertNull(media.roster(caller, call, {now})); assertNull(media.roster(callee, call, {now}))
        accept()
        assertNull(media.roster(other, call, {now})); assertNull(media.roster(caller, id(), {now}))
        assertEquals(DirectCallPhase.ACCEPTED, rings.status(caller, call, {now}).call?.phase)
    }

    @Test fun registeredParticipantStillHasUnknownMediaAndUsesShorterPeerCredit() {
        accept()
        val row = assertNotNull(sessions.get(callee, calleeOperation))
        assertNull(sessions.admit(callee, calleeOperation, row.operation.sessionId!!, epoch, {now}, 30101).rejection)
        val snapshot = assertNotNull(media.roster(caller, call, {now}))
        val registered = snapshot.peers.single {it.actorId == callee}
        assertEquals(RosterConnectionPhase.CONNECTING, registered.phase)
        assertEquals(MediaPresence.UNKNOWN, registered.mediaPresence); assertNull(registered.participantSid)
        assertEquals(30101, sessions.get(callee, calleeOperation)!!.operation.deadlineEpochMillis)
        assertTrue(snapshot.peers.all {it.deadlineEpochMillis == 15101L})
    }

    @Test fun friendshipRevocationWithdrawsRosterAndCancelsOnlyOriginalPair() {
        accept()
        source.connection.use {c -> c.createStatement().use {it.execute("DELETE FROM friendships")}}
        assertNull(media.roster(caller, call, {now}))
        for ((actor, op) in listOf(caller to callerOperation, callee to calleeOperation)) {
            val row = sessions.get(actor, op)!!
            assertTrue(row.operation.terminal); assertTrue(row.cleanupPending); assertNull(sessions.currentClaim(actor))
        }
        assertEquals(DirectCallOutcome.FRIENDSHIP_REVOKED, rings.status(caller, call, {now}).call?.outcome)
    }

    @Test fun elapsedOriginalLeaseWithdrawsPairWithoutMakingCleanupAcknowledgment() {
        accept(); now = 15101
        assertNull(media.roster(callee, call, {now}))
        assertEquals(DirectCallOutcome.MEDIA_TIMEOUT, rings.status(caller, call, {now}).call?.outcome)
        assertTrue(sessions.get(caller, callerOperation)!!.cleanupPending)
        assertTrue(sessions.get(callee, calleeOperation)!!.cleanupPending)
    }

    @Test fun sameUuidChannelCannotLeakIntoCallSnapshotOrExposeCallInChannelSnapshot() {
        accept()
        val channelOperation = id(); val channelSession = id()
        // Deliberately create independent CHANNEL history with the exact call destination UUID.
        assertNull(JdbcSessionRepository(source).begin(other, channelOperation, call, channelSession,
            SfuEpoch("volty-channel-$call", "node"), {now}).rejection)
        val direct = assertNotNull(media.roster(caller, call, {now}))
        assertEquals(setOf(caller, callee), direct.peers.map {it.actorId}.toSet())
        assertFalse(direct.peers.any {it.operationId == channelOperation || it.sessionId == channelSession})
        assertEquals(listOf(other), JdbcRosterRepository(source) {now}.snapshot(call).peers.map {it.actorId})
    }

    @Test fun joinedSidIsUnknownUntilActualActiveTransportObservation() {
        accept()
        var row = sessions.get(callee, calleeOperation)!!
        val sid = row.operation.sessionId!!
        assertNull(sessions.admit(callee, calleeOperation, sid, epoch, {now}, 30101).rejection)
        row = sessions.get(callee, calleeOperation)!!
        assertNull(sessions.observeMedia(callee, calleeOperation, sid, epoch, row.media.observationVersion,
            SfuParticipantObservation("original-sid", SfuParticipantState.JOINED), {now}).rejection)
        var peer = assertNotNull(media.roster(caller, call, {now})).peers.single {it.actorId == callee}
        assertEquals(RosterConnectionPhase.CONNECTING, peer.phase); assertEquals(MediaPresence.UNKNOWN, peer.mediaPresence)
        assertEquals("original-sid", peer.participantSid)
        row = sessions.get(callee, calleeOperation)!!
        assertNull(sessions.observeMedia(callee, calleeOperation, sid, epoch, row.media.observationVersion,
            SfuParticipantObservation("original-sid", SfuParticipantState.ACTIVE), {now}).rejection)
        peer = assertNotNull(media.roster(caller, call, {now})).peers.single {it.actorId == callee}
        assertEquals(RosterConnectionPhase.CONNECTED, peer.phase); assertEquals(MediaPresence.CONNECTED, peer.mediaPresence)
        assertEquals("original-sid", peer.participantSid)
        assertEquals(media.roster(caller, call, {now}), media.roster(callee, call, {now}))
    }

    @Test fun deletedPeerWithdrawsRosterEvenWhileFriendshipRowRemainsAccepted() {
        accept()
        source.connection.use {c -> c.createStatement().use {it.execute("UPDATE users SET deleted_at=101 WHERE id='$callee'")}}
        assertNull(media.roster(caller, call, {now}))
        assertTrue(sessions.get(caller, callerOperation)!!.cleanupPending)
        assertTrue(sessions.get(callee, calleeOperation)!!.cleanupPending)
    }
}
