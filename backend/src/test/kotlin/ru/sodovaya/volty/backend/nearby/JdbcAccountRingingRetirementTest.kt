package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Tag("postgres")
class JdbcAccountRingingRetirementTest {
    private val caller = id(); private val callee = id(); private val other = id()
    private val name = "account_ringing_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $name")}}
        NearbyPostgresFixture.source(name)
    }
    private val rings = JdbcDirectCallRingingRepository(source)
    private val accounts = JdbcAccountDeletionRepository(source)

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(caller, callee, other))
                s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$other','$callee','ACCEPTED',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$other','ACCEPTED',0)")
        }}
    }

    @Test fun deletingCallerImmediatelyClosesRingWithoutStoppingCalleeChannel() {
        val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
        val detail = assertIs<CommunityWriteResult.Accepted>(JdbcCommunityRepository(source).create(callee, id(), "Riders", now = {100})).snapshot
        val channel = detail.channels.single().id
        assertNull(sessions.begin(callee, id(), channel, id(), SfuEpoch("volty-channel-$channel", "node"), {100}).rejection)
        val calleeClaim = assertNotNull(sessions.currentClaim(callee))
        val call = ring(caller, callee)
        assertEquals(1, accounts.deleteAccount(caller, {101}))
        assertEquals("CANCELLED", phase(call)); assertNull(reservation(caller))
        assertEquals(calleeClaim, sessions.currentClaim(callee))
        assertEquals(DirectCallOutcome.FRIENDSHIP_REVOKED, rings.status(callee, call, {102}).call?.outcome)
    }

    @Test fun deletingCalleeReleasesAllIncomingCallerReservations() {
        val first = ring(caller, callee); val second = ring(other, callee)
        assertEquals(1, accounts.deleteAccount(callee, {101}))
        assertEquals("CANCELLED", phase(first)); assertEquals("CANCELLED", phase(second))
        assertNull(reservation(caller)); assertNull(reservation(other)); assertNull(reservation(callee))
        assertEquals(0, scalar("SELECT count(*) FROM nearby_direct_calls WHERE phase='RINGING'"))
    }

    @Test fun failedAccountTombstoneRollsBackCallAndReservationRetirement() {
        val call = ring(caller, callee)
        source.connection.use {c -> c.createStatement().use {
            it.execute("ALTER TABLE users ADD CONSTRAINT fixture_reject_delete CHECK (deleted_at IS NULL) NOT VALID")
        }}
        assertFailsWith<SQLException> {accounts.deleteAccount(callee, {101})}
        assertEquals("RINGING", phase(call)); assertEquals(call, reservation(caller))
        assertEquals(0, scalar("SELECT count(*) FROM users WHERE deleted_at IS NOT NULL"))
        assertEquals(DirectCallPhase.RINGING, rings.status(caller, call, {102}).call?.phase)
    }

    @Test fun deletingFormerRecipientPreservesNewerCallAndOldTerminalReason() {
        val old = ring(caller, callee)
        rings.decline(callee, old, {101})
        val newer = ring(caller, other)
        assertEquals(1, accounts.deleteAccount(callee, {102}))
        assertEquals("DECLINED", phase(old)); assertEquals("RINGING", phase(newer))
        assertEquals(newer, reservation(caller))
        assertEquals(DirectCallOutcome.CALLEE_DECLINED, rings.status(caller, old, {103}).call?.outcome)
    }

    @Test fun concurrentCreateAndDeleteCannotLeaveLiveDeletedUserRing() {
        val issued = CountDownLatch(2); val pool = Executors.newFixedThreadPool(2)
        val blocker = source.connection; blocker.autoCommit = false
        try {
            blocker.createStatement().use {it.executeQuery("SELECT singleton FROM nearby_access_fence WHERE singleton=1 FOR UPDATE").close()}
            val create = pool.submit<DirectCallDecision> {issued.countDown(); rings.create(caller, callee, id(), id(), id(), {100})}
            val delete = pool.submit<Int> {issued.countDown(); accounts.deleteAccount(callee, {101})}
            assertTrue(issued.await(5, TimeUnit.SECONDS)); blocker.commit()
            val result = create.get(10, TimeUnit.SECONDS)
            assertEquals(1, delete.get(10, TimeUnit.SECONDS))
            assertTrue(result.rejection == null || result.rejection == DirectCallRejection.NOT_FRIENDS)
            assertEquals(0, scalar("SELECT count(*) FROM nearby_direct_calls WHERE phase='RINGING'"))
            assertNull(reservation(caller))
            assertEquals(1, scalar("SELECT count(*) FROM users WHERE deleted_at IS NOT NULL"))
        } finally {try {blocker.rollback()} finally {blocker.close(); pool.shutdownNow()}}
    }

    @Test fun createHoldingAccessFenceCommitsBeforeDeleteAndIsThenRetired() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val deleteIssued = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2); val call = id()
        try {
            val create = pool.submit<DirectCallDecision> {rings.create(caller, callee, call, id(), id(), {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); 100
            })}
            assertTrue(entered.await(5, TimeUnit.SECONDS)) // Inside SQL after acquiring SHARE fence and actor locks.
            val delete = pool.submit<Int> {deleteIssued.countDown(); accounts.deleteAccount(callee, {101})}
            assertTrue(deleteIssued.await(5, TimeUnit.SECONDS)); release.countDown()
            assertEquals(DirectCallPhase.RINGING, create.get(10, TimeUnit.SECONDS).call?.phase)
            assertEquals(1, delete.get(10, TimeUnit.SECONDS))
            assertEquals("CANCELLED", phase(call)); assertNull(reservation(caller))
        } finally {release.countDown(); pool.shutdownNow()}
    }

    private fun ring(from: String, to: String): String = id().also {
        assertEquals(DirectCallPhase.RINGING, rings.create(from, to, it, id(), id(), {100}).call?.phase)
    }
    private fun phase(call: String) = source.connection.use {c -> c.prepareStatement("SELECT phase FROM nearby_direct_calls WHERE call_id=?::uuid").use {
        it.setString(1, call); it.executeQuery().use {r -> assertTrue(r.next()); r.getString(1)}
    }}
    private fun reservation(actor: String): String? = source.connection.use {c -> c.prepareStatement("SELECT reserved_call_id FROM nearby_actor_claims WHERE actor_id=?::uuid").use {
        it.setString(1, actor); it.executeQuery().use {r -> if (r.next()) r.getString(1) else null}
    }}
    private fun scalar(sql: String) = source.connection.use {c -> c.createStatement().use {it.executeQuery(sql).use {r -> assertTrue(r.next()); r.getInt(1)}}}
    private fun id() = UUID.randomUUID().toString()
}
