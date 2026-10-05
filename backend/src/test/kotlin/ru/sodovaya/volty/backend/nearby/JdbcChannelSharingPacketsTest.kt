package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.*
import kotlinx.serialization.encodeToString
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcChannelSharingPacketsTest {
    private fun id() = UUID.randomUUID().toString()
    private val actor = id(); private val viewer = id(); private val member = id(); private val community = id()
    private val channel = id(); private val otherChannel = id(); private val operation = id(); private val session = id(); private val generation = id()
    private val viewerOperation = id(); private val viewerSession = id()
    private val schema = "packets_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $schema")}}
        NearbyPostgresFixture.source(schema)
    }
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
    private val shares = JdbcChannelSharingRepository(sessions)
    private val packets = JdbcChannelSharingPackets(sessions)
    private var now = 101L
    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.autoCommit = false; c.createStatement().use {s ->
            for (user in listOf(actor, viewer, member)) s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$user','$user@example.invalid','test','test',0)")
            s.execute("INSERT INTO nearby_communities(id,name,owner_id,created_by,created_at) VALUES ('$community','Riders','$actor','$actor',0)")
            for (user in listOf(actor, viewer, member)) s.execute("INSERT INTO nearby_community_members VALUES ('$community','$user',0)")
            for (destination in listOf(channel, otherChannel)) s.execute("INSERT INTO nearby_voice_channels(id,community_id,name,created_at) VALUES ('$destination','$community','Ride',0)")
        }; c.commit()}
    }
    private fun join(user: String = actor, op: String = operation, sid: String = session, destination: String = channel) {
        val epoch = SfuEpoch("volty-channel-$destination", "node")
        assertNull(sessions.begin(user, op, destination, sid, epoch, {now}).rejection)
        assertNull(sessions.admit(user, op, sid, epoch, {now}, now + 30_000).rejection)
    }
    private fun consent(gen: String = generation, sid: String = session, mode: ChannelSharingMode = ChannelSharingMode.LOCATION) =
        assertNotNull(shares.start(actor, sid, gen, mode, ChannelSharingTtl.HALF_HOUR, {now}))
    private fun packet(sequence: Long = 0) = ChannelSharingPacket(sequence, now, LocationDto(1.0, 2.0, 5.0, now, now + 15_000))
    private fun publish(value: ChannelSharingPacket = packet(), sid: String = session, gen: String = generation) = packets.publish(actor, sid, gen, value, {now})
    private fun audience() = assertNotNull(packets.snapshot(viewer, viewerSession, {now}))
    private fun count(): Int = source.connection.use {c -> c.createStatement().use {s -> s.executeQuery("SELECT COUNT(*) FROM nearby_channel_sharing_packets WHERE actor_id='$actor'").use {it.next(); it.getInt(1)}}}

    @Test fun originalPacketReplayDoesNotRefreshAndConflictingOrLowerSequenceCannotOverwrite() {
        join(); consent(); join(viewer, viewerOperation, viewerSession)
        val original = packet(1); val first = assertNotNull(publish(original)); now += 10
        assertEquals(first, publish(original)); assertEquals(first.packet, audience().peers.single().packet)
        assertNull(publish(original.copy(location = original.location!!.copy(latitude = 3.0))))
        assertNull(publish(original.copy(sequence = 0)))
        assertEquals(first.packet, audience().peers.single().packet); assertEquals(1, count())
    }

    @Test fun serverMembershipWithoutActualCurrentSameChannelSessionCannotReadCoordinates() {
        join(); consent(); assertNotNull(publish())
        assertNull(packets.snapshot(member, id(), {now})); assertNull(packets.snapshot(viewer, session, {now}))
        join(viewer, viewerOperation, viewerSession, otherChannel)
        val snapshot = audience(); assertEquals(otherChannel, snapshot.channelId); assertTrue(snapshot.peers.isEmpty())
        assertNull(packets.publish(viewer, session, generation, packet(), {now}))
    }

    @Test fun hidePurgesOriginalPacketAndOldNegativeCannotRemoveFreshGeneration() {
        join(); consent(); join(viewer, viewerOperation, viewerSession); assertNotNull(publish())
        shares.revoke(actor, session, generation, {now})
        assertEquals(0, count()); assertTrue(audience().peers.isEmpty()); assertNull(publish())
        assertEquals(session, sessions.currentClaim(actor)?.sessionId)
        val fresh = id(); consent(fresh); assertNotNull(publish(gen = fresh))
        shares.revoke(actor, session, generation, {now})
        assertEquals(fresh, audience().peers.single().generation); assertEquals(1, count())
    }

    @Test fun voiceDisconnectPurgesOnlyOriginalSessionAndLateStopCannotWithdrawReplacementPacket() {
        join(); consent(); join(viewer, viewerOperation, viewerSession); assertNotNull(publish())
        sessions.cancel(actor, operation, {now}); assertEquals(0, count()); assertTrue(audience().peers.isEmpty())
        val nextOperation = id(); val nextSession = id(); val nextGeneration = id()
        join(actor, nextOperation, nextSession); consent(nextGeneration, nextSession); assertNotNull(publish(sid = nextSession, gen = nextGeneration))
        sessions.cancel(actor, operation, {now})
        assertEquals(nextSession, audience().peers.single().sessionId); assertEquals(1, count())
    }

    @Test fun sampleAndPeerLeaseExpiryWithdrawLivePayloadWithoutInventingVoiceMediaState() {
        join(); consent(); join(viewer, viewerOperation, viewerSession)
        val old = packet(); assertNotNull(publish(old)); now += 15_000
        assertTrue(audience().peers.isEmpty()); assertEquals(session, sessions.currentClaim(actor)?.sessionId)
        assertFailsWith<IllegalArgumentException> {publish(old)}
        assertNotNull(publish(packet(1))); now += 15_000
        source.connection.use {c -> c.createStatement().use {it.execute("UPDATE nearby_session_operations SET deadline_epoch_millis=${now + 30_000} WHERE actor_id='$viewer'")}}
        assertTrue(audience().peers.isEmpty()); assertNull(publish(packet(2))); assertEquals(0, count())
        assertNull(sessions.currentClaim(actor))
    }

    @Test fun publisherAclLossHidesCoordinatesAndViewerAclLossDeniesWholeAudience() {
        join(); consent(); join(viewer, viewerOperation, viewerSession); assertNotNull(publish())
        source.connection.use {c -> c.createStatement().use {it.execute("INSERT INTO nearby_community_bans VALUES ('$community','$actor',0)")}}
        assertTrue(audience().peers.isEmpty()); assertNull(publish()); assertEquals(0, count())
        source.connection.use {c -> c.createStatement().use {it.execute("INSERT INTO nearby_community_bans VALUES ('$community','$viewer',0)")}}
        assertNull(packets.snapshot(viewer, viewerSession, {now})); assertNull(sessions.currentClaim(viewer))
    }

    @Test fun actualConsentTierAndIncoherentEarnedFlagsAreEnforcedOnWriteAndRead() {
        join(); consent(mode = ChannelSharingMode.RIDE); join(viewer, viewerOperation, viewerSession)
        val telemetry = SharedTelemetryDto(profile = "RIDE", speedKmh = TelemetryNumberDto(true, false, 4200.0),
            powerW = TelemetryNumberDto(true, true, -42.0), packVoltageV = TelemetryNumberDto(true, true, 100.0))
        val safe = assertNotNull(publish(packet().copy(telemetry = telemetry))).packet
        assertNull(safe.telemetry!!.speedKmh.value); assertFalse(safe.telemetry.packVoltageV.supported)
        assertEquals(-42.0, safe.telemetry.powerW.value)
        // Deliberately incoherent stored contributor: the reader must own its contract too.
        val corrupted = safe.copy(telemetry = safe.telemetry.copy(powerW = TelemetryNumberDto(true, false, 4200.0)))
        source.connection.use {c -> c.prepareStatement("UPDATE nearby_channel_sharing_packets SET payload_json=? WHERE actor_id=?::uuid").use {
            it.setString(1, backendJson().encodeToString(corrupted)); it.setString(2, actor); it.executeUpdate()
        }}
        val read = audience().peers.single().packet.telemetry!!
        assertFalse(read.powerW.known); assertNull(read.powerW.value); assertFalse(read.packVoltageV.supported)
    }

    @Test fun futurePreConsentStaleOrInvalidLocationCapturesCannotBeStored() {
        join(); consent(); val valid = packet(); assertNotNull(publish(valid))
        for (invalid in listOf(valid.copy(sequence = -1), valid.copy(capturedAtEpochMillis = now + 1),
            valid.copy(capturedAtEpochMillis = now - 1), valid.copy(location = valid.location!!.copy(latitude = 91.0)),
            valid.copy(location = valid.location!!.copy(accuracyMeters = -1.0)),
            valid.copy(location = valid.location!!.copy(staleAfterEpochMillis = now + 15_001)),
        )) assertFailsWith<IllegalArgumentException> {publish(invalid)}
        now += 20_000
        val stale = ChannelSharingPacket(2, now - 15_000, null)
        assertFailsWith<IllegalArgumentException> {publish(stale)}
        assertEquals(1, count())
    }

    @Test fun incoherentStoredSequenceCannotBecomeAudienceEvidenceOrBlockNewerAuthoritativeWrite() {
        join(); consent(); join(viewer, viewerOperation, viewerSession)
        val original = assertNotNull(publish()).packet
        source.connection.use {c -> c.prepareStatement("UPDATE nearby_channel_sharing_packets SET payload_json=? WHERE actor_id=?::uuid").use {
            it.setString(1, backendJson().encodeToString(original.copy(sequence = 99))); it.setString(2, actor); it.executeUpdate()
        }}
        assertEquals(1, count()); assertTrue(audience().peers.isEmpty())
        val fresh = assertNotNull(publish(original.copy(sequence = 1)))
        assertEquals(1, fresh.packet.sequence); assertEquals(1, audience().peers.single().packet.sequence)
    }

    @Test fun deliberatelyStoredCallPacketCannotJoinChannelAudienceOrInvalidateOriginalCallClaim() {
        join(); consent(); join(viewer, viewerOperation, viewerSession); assertNotNull(publish())
        // Explicitly leave the existing voice claim before ringing: busy rejection is correct.
        sessions.cancel(actor, operation, {now}); assertEquals(0, count())
        val call = id(); val callerOperation = id(); val calleeOperation = id(); val epoch = SfuEpoch("volty-call-$call", "node")
        source.connection.use {c -> c.createStatement().use {it.execute("INSERT INTO friendships VALUES ('${id()}','$actor','$member','ACCEPTED',0)")}}
        assertEquals(DirectCallPhase.RINGING, JdbcDirectCallRingingRepository(source).create(actor, member, call, id(), callerOperation, {now}).call?.phase)
        assertEquals(DirectCallPhase.ACCEPTED, JdbcDirectCallMediaRepository(source).accept(member, call, calleeOperation, 0, epoch, {now}).call?.phase)
        val callSessions = JdbcSessionRepository(source, JdbcVoiceAccess {now})
        val sid = assertNotNull(assertNotNull(callSessions.get(actor, callerOperation)).operation.sessionId)
        assertNull(callSessions.admit(actor, callerOperation, sid, epoch, {now}, now + 30_000).rejection)
        val claim = callSessions.currentClaim(actor); val sample = packet(); val encoded = backendJson().encodeToString(sample)
        // Deliberately incoherent storage: every contributor complete, except destination kind.
        source.connection.use {c -> c.autoCommit = false
            c.prepareStatement("INSERT INTO nearby_channel_sharing_consent VALUES (?::uuid,?::uuid,?::uuid,'ACTIVE','LOCATION',?,?)").use {
                it.setString(1, actor); it.setString(2, sid); it.setString(3, generation); it.setLong(4, now + 1_800_000); it.setLong(5, now); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO nearby_channel_sharing_packets VALUES (?::uuid,?::uuid,?::uuid,0,?,?,?,?)").use {
                it.setString(1, actor); it.setString(2, sid); it.setString(3, generation); it.setString(4, sha256(encoded)); it.setString(5, encoded)
                it.setLong(6, now); it.setLong(7, now + 15_000); it.executeUpdate()
            }; c.commit()
        }
        assertEquals(1, count()); assertTrue(audience().peers.isEmpty())
        val callPackets = JdbcChannelSharingPackets(callSessions)
        assertNull(callPackets.snapshot(actor, sid, {now})); assertNull(callPackets.publish(actor, sid, generation, sample, {now}))
        assertEquals(claim, callSessions.currentClaim(actor)); assertEquals(OperationPhase.ACTIVE, callSessions.get(actor, callerOperation)?.operation?.phase)
    }
}
