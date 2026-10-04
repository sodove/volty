package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcIncomingCallDiscoveryTest {
    private fun id() = UUID.randomUUID().toString()
    private val callee = id(); private val outsider = id(); private val callers = List(3) {id()}
    private val calls = List(3) {id()}.sorted(); private val operations = List(3) {id()}
    private val schema = "incoming_call_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $schema")}}
        NearbyPostgresFixture.source(schema)
    }
    private val ringing = JdbcDirectCallRingingRepository(source)
    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in callers + callee + outsider)
                s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            for (caller in callers) s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
        }}
    }
    private fun create(index: Int) {
        assertEquals(DirectCallPhase.RINGING, ringing.create(callers[index], callee, calls[index], id(), operations[index], {100}).call?.phase)
    }

    @Test fun incomingShowsOnlyActualCalleeRingsAndNeverPrivateCancellation() {
        create(0)
        ringing.cancelOutgoingRinging(callers[1], callee, calls[1], id(), operations[1], {100})
        assertEquals(listOf(calls[0]), ringing.incoming(callee, 10, null, {101}).calls.map {it.callId})
        assertTrue(ringing.incoming(outsider, 10, null, {101}).calls.isEmpty())
        assertTrue(ringing.incoming(callers[0], 10, null, {101}).calls.isEmpty())
        assertNull(ringing.incoming(callee, 10, null, {101}).nextCursor)
        assertEquals(DirectCallPhase.CANCELLED, ringing.status(callers[1], calls[1], {101}).call?.phase)
    }

    @Test fun expiredAndFriendRevokedCandidatesRetireAndReleaseOriginalCallerSlots() {
        create(0); create(1)
        source.connection.use {c -> c.createStatement().use {it.execute("DELETE FROM friendships WHERE requester_id='${callers[1]}'")}}
        assertTrue(ringing.incoming(callee, 10, null, {45_100}).calls.isEmpty())
        // Read raw persisted evidence: calling status here would itself expire the ring,
        // allowing an empty no-op incoming implementation to pass this contract.
        assertEquals("MISSED", phase(calls[0])); assertEquals("MISSED", phase(calls[1]))
        for (caller in callers.take(2)) assertNull(reservation(caller))
    }

    @Test fun paginationAdvancesAcrossRetiredCandidatesWithoutRepeatingDeliveredCall() {
        for (index in calls.indices) create(index)
        val first = ringing.incoming(callee, 1, null, {101})
        assertEquals(listOf(calls[0]), first.calls.map {it.callId}); assertEquals(calls[0], first.nextCursor)
        ringing.cancelRinging(callers[1], calls[1], {102})
        val second = ringing.incoming(callee, 1, first.nextCursor, {103})
        assertEquals(listOf(calls[2]), second.calls.map {it.callId}); assertNull(second.nextCursor)
        assertFailsWith<IllegalArgumentException> {ringing.incoming(callee, 0, null, {103})}
        assertFailsWith<IllegalArgumentException> {ringing.incoming(callee, 51, null, {103})}
        assertFailsWith<IllegalArgumentException> {ringing.incoming(callee, 1, "invalid", {103})}
    }

    @Test fun listingLeavesCalleeChannelAndSharingUntouched() {
        val communities = JdbcCommunityRepository(source)
        val channel = assertIs<CommunityWriteResult.Accepted>(communities.create(callee, id(), "Riders", now = {100})).snapshot.channels.single().id
        val sessions = JdbcSessionRepository(source, JdbcChannelAccess()); val operation = id(); val session = id()
        val epoch = SfuEpoch("volty-channel-$channel", "node")
        assertNull(sessions.begin(callee, operation, channel, session, epoch, {100}).rejection)
        assertNull(sessions.admit(callee, operation, session, epoch, {100}, 30_100).rejection)
        val group = id()
        source.connection.use {c -> c.createStatement().use {
            it.execute("INSERT INTO groups VALUES ('$group','Legacy ride','$callee','KEEPME',1000,100)")
            it.execute("INSERT INTO sharing_sessions VALUES ('${id()}','$callee','$group','LOCATION',100,1000,NULL)")
        }}
        val before = sessions.get(callee, operation); create(0)
        assertEquals(1, ringing.incoming(callee, 10, null, {101}).calls.size)
        assertEquals(before, sessions.get(callee, operation)); assertEquals(SessionClaim(operation, session, epoch), sessions.currentClaim(callee))
        source.connection.use {c -> c.createStatement().use {s -> s.executeQuery("SELECT COUNT(*) FROM sharing_sessions WHERE user_id='$callee' AND revoked_at IS NULL").use {r -> assertTrue(r.next()); assertEquals(1, r.getInt(1))}}}
    }

    @Test fun unexpiredRevocationAndSupersededReservationAreRetiredWithoutClearingNewerChannel() {
        for (index in calls.indices) create(index)
        source.connection.use {c -> c.createStatement().use {
            it.execute("DELETE FROM friendships WHERE requester_id='${callers[0]}'")
            // Deliberately incoherent original ownership, followed by a real newer channel claim.
            it.execute("UPDATE nearby_actor_claims SET reserved_call_id=NULL WHERE actor_id='${callers[1]}'")
        }}
        val channel = assertIs<CommunityWriteResult.Accepted>(JdbcCommunityRepository(source).create(callers[1], id(), "Newer ride", now = {101})).snapshot.channels.single().id
        val sessions = JdbcSessionRepository(source, JdbcChannelAccess()); val operation = id(); val session = id()
        val epoch = SfuEpoch("volty-channel-$channel", "node")
        assertNull(sessions.begin(callers[1], operation, channel, session, epoch, {101}).rejection)
        assertEquals(listOf(calls[2]), ringing.incoming(callee, 10, null, {102}).calls.map {it.callId})
        assertEquals("CANCELLED", phase(calls[0])); assertEquals("CANCELLED", phase(calls[1]))
        assertNull(reservation(callers[0]))
        assertEquals(SessionClaim(operation, session, epoch), sessions.currentClaim(callers[1]))
        assertFalse(sessions.get(callers[1], operation)!!.operation.terminal)
    }

    @Test fun cursorAdvancesEvenWhenReconciliationSuppressesEntireScannedPage() {
        for (index in calls.indices) create(index)
        source.connection.use {c -> c.createStatement().use {it.execute("DELETE FROM friendships WHERE requester_id='${callers[0]}'")}}
        val first = ringing.incoming(callee, 1, null, {101})
        assertTrue(first.calls.isEmpty()); assertEquals(calls[0], first.nextCursor)
        val second = ringing.incoming(callee, 1, first.nextCursor, {102})
        assertEquals(listOf(calls[1]), second.calls.map {it.callId}); assertEquals(calls[1], second.nextCursor)
        val third = ringing.incoming(callee, 1, second.nextCursor, {103})
        assertEquals(listOf(calls[2]), third.calls.map {it.callId}); assertNull(third.nextCursor)
    }

    @Test fun acceptedConversationIsNotAdvertisedAsAnIncomingRing() {
        create(0); create(1)
        assertEquals(DirectCallPhase.ACCEPTED, JdbcDirectCallMediaRepository(source).accept(callee, calls[0], id(), 0, SfuEpoch("volty-call-${calls[0]}", "node"), {101}).call?.phase)
        val before = JdbcSessionRepository(source, JdbcVoiceAccess {102}).currentClaim(callee)
        assertEquals(listOf(calls[1]), ringing.incoming(callee, 10, null, {102}).calls.map {it.callId})
        assertEquals(before, JdbcSessionRepository(source).currentClaim(callee))
        assertEquals("ACCEPTED", phase(calls[0]))
    }

    @Test fun originalCancellationTombstoneSuppressesEvenRetainedMatchingReservation() {
        create(0)
        // Deliberate incoherent fixture: retained ring reservation cannot override
        // the original operation's authoritative cancellation tombstone.
        source.connection.use {c -> c.createStatement().use {it.execute("INSERT INTO nearby_session_operations(actor_id,operation_id,phase,destination_kind) VALUES ('${callers[0]}','${operations[0]}','CANCELLED',NULL)")}}
        assertEquals(calls[0], reservation(callers[0]))
        assertTrue(ringing.incoming(callee, 10, null, {101}).calls.isEmpty())
        assertEquals("CANCELLED", phase(calls[0])); assertNull(reservation(callers[0]))
    }

    private fun reservation(actor: String): String? = source.connection.use {c -> c.prepareStatement("SELECT reserved_call_id FROM nearby_actor_claims WHERE actor_id=?::uuid").use {
        it.setString(1, actor); it.executeQuery().use {r -> if (r.next()) r.getString(1) else null}
    }}
    private fun phase(call: String): String? = source.connection.use {c -> c.prepareStatement("SELECT phase FROM nearby_direct_calls WHERE call_id=?::uuid").use {
        it.setString(1, call); it.executeQuery().use {r -> if (r.next()) r.getString(1) else null}
    }}
}
