package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionInvitationListingTest {
    private val source = schema()
    private val owner = "abcdef00-0000-0000-0000-000000000099"; private val member = id(); private val community = id(); private val foreign = id()
    private val ids = (1..3).map { "00000000-0000-0000-0000-00000000000$it" }
    private val listing = JdbcInvitationListingRepository(source)
    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        for (actor in listOf(owner, member)) sql("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','Rider',0)")
        JdbcCommunityRepository(source).create(owner, community, "Private", now = { 100 })
        JdbcCommunityRepository(source).create(member, foreign, "Foreign", now = { 100 })
        for ((seed, invite) in ids.withIndex()) JdbcInvitationRepository(source).create(owner, community, invite, token(seed + 1), 900000, 10, { 100 })
        JdbcInvitationRepository(source).create(member, foreign, id(), token(99), 900000, 10, { 100 })
    }
    @Test fun deterministicCursorAndBoundsListOnlyOwnedServerWithoutSecretsOrEffects() {
        val before = JdbcRosterJournal(source).revision()
        val first = assertIs<InvitationListingResult.Listed>(listing.list(owner, community, limit = 2)).items
        assertEquals(ids.take(2), first.map { it.id })
        val second = assertIs<InvitationListingResult.Listed>(listing.list(owner, community, after = first.last().id, limit = 2)).items
        assertEquals(ids.drop(2), second.map { it.id }); assertTrue(first.all { it.communityId == community })
        for (seed in 1..3) assertFalse((first + second).toString().contains(token(seed)))
        assertTrue(assertIs<InvitationListingResult.Listed>(listing.list(owner, community, after = ids.last())).items.isEmpty())
        assertEquals(before, JdbcRosterJournal(source).revision())
        assertEquals(2, count("nearby_community_members")); assertEquals(0, count("nearby_session_operations")); assertEquals(0, count("sharing_sessions"))
    }
    @Test fun currentOwnerInheritsExpiredAndRevokedMetadataWhileFormerOwnerAndMembersAreDenied() {
        val invitations = JdbcInvitationRepository(source)
        invitations.revoke(owner, community, ids.first(), { 101 })
        JdbcInvitationAcceptanceRepository(source).accept(member, id(), token(2), { 101 })
        assertEquals(InvitationListingResult.Denied, listing.list(member, community))
        sql("UPDATE nearby_communities SET owner_id='$member' WHERE id='$community'")
        assertEquals(InvitationListingResult.Denied, listing.list(owner, community))
        val items = assertIs<InvitationListingResult.Listed>(listing.list(member, community)).items
        assertEquals(ids, items.map { it.id }); assertEquals(101, items.first().revokedAtMillis)
        assertNotNull(items.first().revocationRevision); assertEquals(1, items[1].uses)
        assertEquals(900100, items.last().expiresAtMillis)
    }
    @Test fun unknownForeignDeletedActorAndDeletedServerShareDeniedResult() {
        assertEquals(InvitationListingResult.Denied, listing.list(owner, foreign))
        assertEquals(InvitationListingResult.Denied, listing.list(owner, id()))
        sql("UPDATE users SET deleted_at=101 WHERE id='$owner'")
        assertEquals(InvitationListingResult.Denied, listing.list(owner, community))
        sql("UPDATE users SET deleted_at=NULL WHERE id='$owner'")
        sql("UPDATE nearby_communities SET state='DELETED' WHERE id='$community'")
        assertEquals(InvitationListingResult.Denied, listing.list(owner, community))
    }
    @Test fun invalidPageArgumentsAreRejectedInsteadOfUnboundedOrNormalizedReads() {
        for (limit in listOf(-1, 0, 51, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> { listing.list(owner, community, limit = limit) }
        assertFailsWith<IllegalArgumentException> { listing.list(owner, community, after = "bad") }
        assertFailsWith<IllegalArgumentException> { listing.list(owner.uppercase(), community) }
    }
    private fun token(seed: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { seed.toByte() })
    private fun sql(query: String) = source.connection.use { c -> c.createStatement().use { it.execute(query) } }
    private fun count(table: String) = source.connection.use { c -> c.createStatement().use { it.executeQuery("SELECT count(*) FROM $table").use { rows -> check(rows.next()); rows.getInt(1) } } }
    private fun schema(): DataSource {
        val name = "session_invitation_listing_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}
