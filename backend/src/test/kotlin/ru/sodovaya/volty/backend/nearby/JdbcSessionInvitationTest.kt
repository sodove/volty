package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionInvitationTest {
    private val source = schema()
    private val owner = id(); private val visitor = id(); private val another = id(); private val community = id(); private val invite = id()
    private val raw = token(1)
    private val communities = JdbcCommunityRepository(source)
    private val invitations = JdbcInvitationRepository(source)
    private val acceptance = JdbcInvitationAcceptanceRepository(source)
    private val ttl = 900_000L
    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        for (actor in listOf(owner, visitor, another)) sql("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','Rider',0)")
        communities.create(owner, community, "Private", now = { 100 })
    }
    @Test fun creationStoresHashesOnlyAndStableServerExpiryWithoutVoiceOrMembership() {
        val result = assertIs<InvitationCreationResult.Created>(create())
        assertEquals(900_100, result.invitation.expiresAtMillis); assertEquals(0, result.invitation.uses)
        assertEquals(2, result.invitation.creationRevision); assertEquals(1, count("nearby_community_members"))
        assertEquals(0, count("nearby_session_operations")); assertEquals(0, count("sharing_sessions")); assertEquals(13, count("schema_migrations"))
        source.connection.use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT * FROM nearby_invitations").use { rows ->
                assertTrue(rows.next()); assertEquals(InvitationTokens.hash(raw), rows.getString("token_hash"))
                for (column in 1..rows.metaData.columnCount) assertNotEquals(raw, rows.getString(column))
            }
        } }
    }
    @Test fun schemaRejectsRevocationWithoutRevision() {
        create()
        assertFailsWith<SQLException> { sql("UPDATE nearby_invitations SET revoked_at=101,revocation_revision=NULL WHERE id='$invite'") }
        assertEquals(0, count("nearby_invitations", "revoked_at IS NOT NULL"))
    }
    @Test fun creationReplayNeverExtendsExpiryAndChangedPayloadOrTokenReuseConflicts() {
        val first = create()
        assertEquals(first, invitations.create(owner, community, invite, raw, ttl, 10, { 500 }))
        assertEquals(InvitationCreationResult.Conflict, invitations.create(owner, community, invite, raw, ttl + 1, 10, { 500 }))
        assertEquals(InvitationCreationResult.Conflict, invitations.create(owner, community, invite, token(2), ttl, 10, { 500 }))
        assertEquals(InvitationCreationResult.Conflict, invitations.create(owner, community, id(), raw, ttl, 10, { 500 }))
        assertEquals(1, count("nearby_invitations")); assertEquals(2, JdbcRosterJournal(source).revision())
    }
    @Test fun privateAcceptanceChangesOnlyPermanentMembership() {
        create(); assertNull(communities.detail(visitor, community))
        val result = assertIs<InvitationAcceptanceResult.Accepted>(acceptance.accept(visitor, id(), raw, { 101 }))
        assertTrue(result.receipt.membershipChanged); assertEquals(community, result.receipt.communityId)
        assertNotNull(communities.detail(visitor, community)); assertEquals(2, count("nearby_community_members"))
        assertEquals(1, uses()); assertEquals(0, count("nearby_session_operations")); assertEquals(0, count("sharing_sessions"))
    }
    @Test fun originalReplayAndAlreadyMemberRequestsConsumeNoAdditionalUseOrRevision() {
        create(maxUses = 1); val op = id()
        val first = acceptance.accept(visitor, op, raw, { 101 }); val revision = JdbcRosterJournal(source).revision()
        assertEquals(first, acceptance.accept(visitor, op, raw, { 102 }))
        val existing = assertIs<InvitationAcceptanceResult.Accepted>(acceptance.accept(visitor, id(), raw, { 103 }))
        assertFalse(existing.receipt.membershipChanged); assertEquals(revision, existing.receipt.revision)
        assertEquals(1, uses()); assertEquals(revision, JdbcRosterJournal(source).revision())
    }
    @Test fun inclusiveExpiryRevocationBanAndDeletedActorOrServerDenyNewMembership() {
        create()
        assertEquals(InvitationAcceptanceResult.Denied, acceptance.accept(visitor, id(), raw, { 900_100 }))
        assertIs<InvitationRevocationResult.Revoked>(invitations.revoke(owner, community, invite, { 101 }))
        assertEquals(InvitationAcceptanceResult.Denied, acceptance.accept(visitor, id(), raw, { 102 }))
        val fresh = id(); val second = token(2)
        invitations.create(owner, community, fresh, second, ttl, 10, { 100 })
        sql("INSERT INTO nearby_community_bans VALUES ('$community','$visitor',100)")
        assertEquals(InvitationAcceptanceResult.Denied, acceptance.accept(visitor, id(), second, { 101 }))
        sql("DELETE FROM nearby_community_bans WHERE user_id='$visitor'")
        sql("UPDATE users SET deleted_at=101 WHERE id='$visitor'")
        assertEquals(InvitationAcceptanceResult.Denied, acceptance.accept(visitor, id(), second, { 102 }))
        sql("UPDATE nearby_communities SET state='DELETED' WHERE id='$community'")
        assertEquals(InvitationAcceptanceResult.Denied, acceptance.accept(another, id(), second, { 102 }))
        assertEquals(InvitationAcceptanceResult.Denied, acceptance.accept(another, id(), token(99), { 102 }))
        assertEquals(1, count("nearby_community_members")); assertEquals(0, count("nearby_invitation_acceptances")); assertEquals(0, uses())
    }
    @Test fun currentOwnerAloneManagesLinksAndRevokeIsStableAcrossReplay() {
        assertEquals(InvitationCreationResult.Denied, invitations.create(visitor, community, invite, raw, ttl, 10, { 100 }))
        create()
        assertEquals(InvitationRevocationResult.Denied, invitations.revoke(visitor, community, invite, { 101 }))
        acceptance.accept(visitor, id(), raw, { 101 })
        sql("UPDATE nearby_communities SET owner_id='$visitor' WHERE id='$community'")
        assertEquals(InvitationRevocationResult.Denied, invitations.revoke(owner, community, invite, { 102 }))
        val first = invitations.revoke(visitor, community, invite, { 102 }); val revision = JdbcRosterJournal(source).revision()
        assertIs<InvitationRevocationResult.Revoked>(first)
        assertEquals(first, invitations.revoke(visitor, community, invite, { 103 }))
        assertEquals(revision, JdbcRosterJournal(source).revision()); assertNotNull(communities.detail(owner, community))
    }
    @Test fun revocationNeverEjectsMembersOrResurrectsTheCreationId() {
        create(); val op = id(); val first = acceptance.accept(visitor, op, raw, { 101 })
        invitations.revoke(owner, community, invite, { 102 })
        assertNotNull(communities.detail(visitor, community))
        assertEquals(first, acceptance.accept(visitor, op, raw, { 103 }))
        assertEquals(InvitationAcceptanceResult.Denied, acceptance.accept(another, id(), raw, { 103 }))
        assertEquals(InvitationCreationResult.Conflict, invitations.create(owner, community, invite, raw, ttl, 10, { 104 }))
        assertEquals(1, uses())
    }
    @Test fun historicalAcceptReplayAfterLeaveDoesNotRejoinAndChangedTokenConflicts() {
        create(); val op = id(); val first = acceptance.accept(visitor, op, raw, { 101 })
        assertIs<CommunityDepartureResult.Departed>(JdbcCommunityMembershipRepository(source).leave(visitor, id(), community, now = { 102 }))
        val before = JdbcRosterJournal(source).revision()
        assertEquals(first, acceptance.accept(visitor, op, raw, { 103 })); assertNull(communities.detail(visitor, community))
        assertEquals(InvitationAcceptanceResult.Conflict, acceptance.accept(visitor, op, token(2), { 103 }))
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(1, uses())
    }
    @Test fun failedAcceptOutboxRollsBackUseMembershipAndOperationReceipt() {
        create(); val before = JdbcRosterJournal(source).revision()
        sql("ALTER TABLE nearby_community_outbox ADD CONSTRAINT reject_accept CHECK(kind<>'MEMBER_JOINED')")
        assertFailsWith<SQLException> { acceptance.accept(visitor, id(), raw, { 101 }) }
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(0, uses())
        assertEquals(1, count("nearby_community_members")); assertEquals(0, count("nearby_invitation_acceptances"))
    }
    @Test fun failedCreateOrRevokeOutboxRollsBackInvitationState() {
        sql("ALTER TABLE nearby_community_outbox ADD CONSTRAINT reject_invite CHECK(kind<>'INVITE_CHANGED')")
        assertFailsWith<SQLException> { create() }; assertEquals(0, count("nearby_invitations"))
        sql("ALTER TABLE nearby_community_outbox DROP CONSTRAINT reject_invite")
        create(); val before = JdbcRosterJournal(source).revision()
        sql("ALTER TABLE nearby_community_outbox ADD CONSTRAINT reject_invite CHECK(kind<>'INVITE_CHANGED') NOT VALID")
        assertFailsWith<SQLException> { invitations.revoke(owner, community, invite, { 101 }) }
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(0, count("nearby_invitations", "revoked_at IS NOT NULL"))
    }
    @Test fun finalUseIsConsumedByOnlyOneOfTwoActuallyCompetingAccepts() {
        create(maxUses = 1)
        val pool = Executors.newFixedThreadPool(2); val ready = CountDownLatch(2); val start = CountDownLatch(1)
        try {
            val results = listOf(visitor, another).map { actor -> pool.submit<InvitationAcceptanceResult> {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); acceptance.accept(actor, id(), raw, { 101 })
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            val values = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, values.count { it is InvitationAcceptanceResult.Accepted }); assertEquals(1, values.count { it == InvitationAcceptanceResult.Denied })
            assertEquals(1, uses()); assertEquals(2, count("nearby_community_members")); assertEquals(1, count("nearby_invitation_acceptances"))
        } finally { start.countDown(); pool.shutdownNow() }
    }
    @Test fun acceptIssuedBehindRevokeFenceReadsCommittedRevocation() {
        create(); val barrier = AccessFenceBarrierSource(source); val pool = Executors.newFixedThreadPool(2)
        try {
            val revoke = pool.submit<InvitationRevocationResult> { JdbcInvitationRepository(barrier).revoke(owner, community, invite, { 101 }) }
            assertTrue(barrier.held.await(5, TimeUnit.SECONDS))
            val accept = pool.submit<InvitationAcceptanceResult> { JdbcInvitationAcceptanceRepository(barrier).accept(visitor, id(), raw, { 102 }) }
            assertTrue(barrier.competingIssued.await(5, TimeUnit.SECONDS)); assertFalse(accept.isDone)
            barrier.release.countDown(); assertIs<InvitationRevocationResult.Revoked>(revoke.get(10, TimeUnit.SECONDS))
            assertEquals(InvitationAcceptanceResult.Denied, accept.get(10, TimeUnit.SECONDS)); assertEquals(0, uses())
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }
    @Test fun acceptCommittedBeforeRevokeKeepsMembershipButDeniesLaterConsumers() {
        create(); val barrier = AccessFenceBarrierSource(source); val pool = Executors.newFixedThreadPool(2)
        try {
            val accept = pool.submit<InvitationAcceptanceResult> { JdbcInvitationAcceptanceRepository(barrier).accept(visitor, id(), raw, { 101 }) }
            assertTrue(barrier.held.await(5, TimeUnit.SECONDS))
            val revoke = pool.submit<InvitationRevocationResult> { JdbcInvitationRepository(barrier).revoke(owner, community, invite, { 102 }) }
            assertTrue(barrier.competingIssued.await(5, TimeUnit.SECONDS)); assertFalse(revoke.isDone)
            barrier.release.countDown(); assertIs<InvitationAcceptanceResult.Accepted>(accept.get(10, TimeUnit.SECONDS))
            assertIs<InvitationRevocationResult.Revoked>(revoke.get(10, TimeUnit.SECONDS)); assertNotNull(communities.detail(visitor, community))
            assertEquals(InvitationAcceptanceResult.Denied, acceptance.accept(another, id(), raw, { 103 })); assertEquals(1, uses())
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }
    private fun create(maxUses: Int = 10) = invitations.create(owner, community, invite, raw, ttl, maxUses, { 100 })
    private fun uses() = source.connection.use { c -> c.createStatement().use { it.executeQuery("SELECT uses FROM nearby_invitations WHERE id='$invite'").use { rows -> assertTrue(rows.next()); rows.getInt(1) } } }
    private fun token(seed: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { seed.toByte() })
    private fun sql(query: String) = source.connection.use { c -> c.createStatement().use { it.execute(query) } }
    private fun count(table: String, where: String = "TRUE") = source.connection.use { c -> c.createStatement().use { it.executeQuery("SELECT count(*) FROM $table WHERE $where").use { rows -> assertTrue(rows.next()); rows.getInt(1) } } }
    private fun schema(): DataSource {
        val name = "session_invitation_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}
