package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.sql.SQLException
import kotlin.test.*

/** Deliberately colliding fixtures: no accepted call producer is enabled yet. */
@Tag("postgres")
class JdbcVoiceDestinationKindTest {
    private val owner = id(); private val peer = id(); private val community = id()
    private val schemaName = "destination_kind_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $schemaName")}}
        NearbyPostgresFixture.source(schemaName)
    }
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
    private lateinit var channel: String

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(owner, peer))
                s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
        }}
        val communities = JdbcCommunityRepository(source)
        channel = assertIs<CommunityWriteResult.Accepted>(communities.create(owner, community, "Riders",
            visibility = CommunityVisibility.PUBLIC, now = {100})).snapshot.channels.single().id
        assertIs<CommunityWriteResult.Accepted>(communities.joinPublic(peer, community, {100}))
    }

    @Test fun collidingCallIsAbsentFromChannelRosterAndSurvivesChannelDeletion() {
        val operation = collidingCall(peer)
        val original = assertNotNull(sessions.currentClaim(peer))
        assertTrue(JdbcRosterRepository(source, {101}).snapshot(channel).peers.isEmpty())
        JdbcChannelDeletionRepository(source).deleteChannel(owner, id(), community, channel, {101})
        assertEquals(original, sessions.currentClaim(peer))
        assertEquals(OperationPhase.ACTIVE, sessions.get(peer, operation)?.operation?.phase)
    }

    @Test fun leavingServerPreservesCollidingCall() {
        val operation = collidingCall(peer)
        val original = assertNotNull(sessions.currentClaim(peer))
        JdbcCommunityMembershipRepository(source).leave(peer, id(), community, now = {101})
        assertEquals(original, sessions.currentClaim(peer))
        assertEquals(OperationPhase.ACTIVE, sessions.get(peer, operation)?.operation?.phase)
        assertNull(JdbcCommunityRepository(source).detail(peer, community))
    }

    @Test fun deletingOwnerAccountPreservesPeersCallButPeerDeletionClosesItsOwnCall() {
        val operation = collidingCall(peer)
        val original = assertNotNull(sessions.currentClaim(peer))
        assertEquals(1, JdbcAccountDeletionRepository(source).deleteAccount(owner, {101}))
        assertEquals(original, sessions.currentClaim(peer))
        assertEquals(1, JdbcAccountDeletionRepository(source).deleteAccount(peer, {102}))
        assertNull(sessions.currentClaim(peer))
        val stopped = assertNotNull(sessions.get(peer, operation))
        assertTrue(stopped.operation.terminal); assertTrue(stopped.cleanupPending)
        assertEquals(VoiceDestinationKind.DIRECT_CALL, stopped.operation.destinationKind)
        assertEquals(VoiceDestinationKind.DIRECT_CALL, JdbcRosterJournal(source).eventsAfter(0, 10).last().destinationKind)
    }

    @Test fun channelCredentialProducerCannotUseCallEvenWithChannelShapedRoom() {
        val operation = collidingCall(peer, channelShapedRoom = true)
        var produced = false
        val credentials = sessions.withActiveSession(peer, operation, {101}) {produced = true; "credential"}
        assertNull(credentials); assertFalse(produced)
    }

    @Test fun malformedUnparentedCallStillClosesItsOwnOriginalMediaOnInvalidation() {
        val operation = collidingCall(peer)
        val original = assertNotNull(sessions.get(peer, operation))
        val stopped = assertNotNull(sessions.invalidateMedia(peer, operation, {101}).state)
        assertTrue(stopped.operation.terminal); assertTrue(stopped.cleanupPending)
        assertEquals(original.operation.sessionId, stopped.operation.sessionId)
        assertEquals(original.sfuEpoch, stopped.sfuEpoch)
        assertEquals(VoiceDestinationKind.DIRECT_CALL, stopped.operation.destinationKind)
        assertNull(sessions.currentClaim(peer))
    }

    @Test fun channelBeginCannotReplayCallOperationWithSameUuid() {
        val operation = collidingCall(peer, channelShapedRoom = true)
        val original = assertNotNull(sessions.get(peer, operation))
        val claim = assertNotNull(sessions.currentClaim(peer))
        val replay = sessions.begin(peer, operation, channel, id(), original.sfuEpoch!!, {101}, true)
        assertEquals(OperationRejection.DESTINATION_MISMATCH, replay.rejection)
        assertEquals(original, replay.state); assertEquals(claim, sessions.currentClaim(peer))
    }

    @Test fun concreteSessionCannotLoseItsKindInDatabase() {
        val operation = collidingCall(peer)
        source.connection.use {c -> c.prepareStatement("UPDATE nearby_session_operations SET destination_kind=? WHERE actor_id=?::uuid AND operation_id=?::uuid").use {
            for (kind in listOf(null, "UNKNOWN")) {
                it.setString(1, kind); it.setString(2, peer); it.setString(3, operation)
                assertFailsWith<SQLException> {it.executeUpdate()}
            }
        }}
        assertEquals(VoiceDestinationKind.DIRECT_CALL, sessions.get(peer, operation)?.operation?.destinationKind)
    }

    @Test fun migrationPreservesClaimsMediaEvidenceOutboxAndDestinationlessTombstone() {
        val name = "kind_upgrade_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $name")}}
        val old = NearbyPostgresFixture.source(name)
        SchemaMigrator(old, listOf(
            1 to "/db/migration/V1__initial.sql", 2 to "/db/migration/V2__nearby_session_journal.sql",
            3 to "/db/migration/V3__nearby_roster_journal.sql", 4 to "/db/migration/V4__nearby_media_webhook_inbox.sql",
            5 to "/db/migration/V5__nearby_media_observations.sql",
        )).migrate()
        val operation = id(); val tombstone = id(); val session = id()
        old.connection.use {c -> c.createStatement().use {s ->
            s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$peer','$peer@example.invalid','test','test',0)")
            s.execute("INSERT INTO nearby_session_operations(actor_id,operation_id,destination_id,session_id,room_epoch,node_epoch,phase,deadline_epoch_millis,participant_sid,media_state,media_observation_version) VALUES ('$peer','$operation','$channel','$session','volty-channel-$channel','node','ACTIVE',30100,'PA_original','ACTIVE',9)")
            s.execute("INSERT INTO nearby_session_operations(actor_id,operation_id,phase) VALUES ('$peer','$tombstone','CANCELLED')")
            s.execute("INSERT INTO nearby_actor_claims(actor_id,operation_id,session_id,room_epoch,node_epoch) VALUES ('$peer','$operation','$session','volty-channel-$channel','node')")
            s.execute("INSERT INTO nearby_roster_outbox(revision,actor_id,operation_id,destination_id,session_id,phase,deadline_epoch_millis,participant_sid,media_state) VALUES (7,'$peer','$operation','$channel','$session','ACTIVE',30100,'PA_original','ACTIVE')")
            s.execute("UPDATE nearby_roster_clock SET revision=7")
        }}
        SchemaMigrator(old).migrate(); SchemaMigrator(old).migrate()
        val upgraded = JdbcSessionRepository(old)
        assertEquals(SessionClaim(operation, session, SfuEpoch("volty-channel-$channel", "node")), upgraded.currentClaim(peer))
        val active = assertNotNull(upgraded.get(peer, operation))
        assertEquals(VoiceDestinationKind.CHANNEL, active.operation.destinationKind)
        assertEquals(30_100, active.operation.deadlineEpochMillis)
        assertEquals(SessionMedia("PA_original", SfuParticipantState.ACTIVE, 9), active.media)
        assertNull(upgraded.get(peer, tombstone)?.operation?.destinationKind)
        val journal = JdbcRosterJournal(old)
        assertEquals(7, journal.revision())
        val event = journal.eventsAfter(0, 10).single()
        assertEquals(VoiceDestinationKind.CHANNEL, event.destinationKind)
        assertEquals("PA_original", event.participantSid); assertEquals(session, event.sessionId)
    }

    private fun collidingCall(actor: String, channelShapedRoom: Boolean = false): String {
        val operation = id(); val session = id()
        val epoch = SfuEpoch("volty-channel-$channel", "node")
        assertNull(sessions.begin(actor, operation, channel, session, epoch, {100}).rejection)
        assertNull(sessions.admit(actor, operation, session, epoch, {100}, 30_100).rejection)
        val room = if (channelShapedRoom) epoch.roomEpoch else "volty-call-$channel"
        source.connection.use {c -> c.prepareStatement("UPDATE nearby_session_operations SET destination_kind='DIRECT_CALL',room_epoch=? WHERE actor_id=?::uuid AND operation_id=?::uuid").use {
            it.setString(1, room); it.setString(2, actor); it.setString(3, operation); assertEquals(1, it.executeUpdate())
        }; c.prepareStatement("UPDATE nearby_actor_claims SET room_epoch=? WHERE actor_id=?::uuid").use {
            it.setString(1, room); it.setString(2, actor); assertEquals(1, it.executeUpdate())
        }}
        return operation
    }
    private fun id() = UUID.randomUUID().toString()
}
