package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionCommunityRepositoryTest {
    private val source = schema()
    private val owner = id(); private val visitor = id()
    private val repository = JdbcCommunityRepository(source)

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use { c -> c.createStatement().use { s ->
            for (actor in listOf(owner, visitor)) s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
        } }
    }

    @Test fun createIsPrivateAtomicAndDoesNotJoinVoiceOrPublishPosition() {
        val community = id()
        val result = assertIs<CommunityWriteResult.Accepted>(repository.create(owner, community, "Riders", now = { 100 })).snapshot
        assertEquals(CommunityVisibility.PRIVATE, result.community.visibility)
        assertEquals(owner, result.community.ownerId)
        assertEquals("Общий", result.channels.single().name)
        assertTrue(result.channels.single().isDefault)
        assertEquals(1, count("nearby_community_members")); assertEquals(1, count("nearby_voice_channels"))
        assertEquals(1, count("nearby_community_outbox")); assertEquals(1, JdbcRosterJournal(source).revision())
        assertEquals(0, count("nearby_session_operations")); assertEquals(0, count("sharing_sessions"))
        assertNull(repository.detail(visitor, community))
        assertTrue(repository.catalog(visitor, "", limit = 10).isEmpty())
    }

    @Test fun createReplayDoesNotInventAnotherRevisionAndPayloadConflictCannotMutate() {
        val community = id()
        val first = assertIs<CommunityWriteResult.Accepted>(repository.create(owner, community, "Riders", now = { 100 })).snapshot
        val replay = assertIs<CommunityWriteResult.Accepted>(repository.create(owner, community, "Riders", now = { 200 })).snapshot
        assertEquals(first, replay)
        assertIs<CommunityWriteResult.Conflict>(repository.create(owner, community, "Different", now = { 200 }))
        assertIs<CommunityWriteResult.Denied>(repository.create(visitor, community, "Riders", now = { 200 }))
        assertEquals(1, JdbcRosterJournal(source).revision()); assertEquals(1, count("nearby_community_outbox"))
        assertEquals(first, repository.detail(owner, community))
    }

    @Test fun failedOutboxInsertRollsBackCommunityMembershipChannelAndRevision() {
        source.connection.use { c -> c.createStatement().use { it.execute("ALTER TABLE nearby_community_outbox ADD CONSTRAINT fixture_reject CHECK (FALSE) NOT VALID") } }
        assertFailsWith<SQLException> { repository.create(owner, id(), "Riders", now = { 100 }) }
        assertEquals(0, count("nearby_communities")); assertEquals(0, count("nearby_community_members"))
        assertEquals(0, count("nearby_voice_channels")); assertEquals(0, JdbcRosterJournal(source).revision())
    }

    @Test fun catalogFiltersPrivateDeletedAndBannedServersWithStablePagingAndLiteralSearch() {
        val first = create("Riders %_", CommunityVisibility.PUBLIC)
        val second = create("Riders alpha", CommunityVisibility.PUBLIC)
        create("Riders hidden", CommunityVisibility.PRIVATE)
        val banned = create("Riders banned", CommunityVisibility.PUBLIC)
        val deleted = create("Riders deleted", CommunityVisibility.PUBLIC)
        source.connection.use { c -> c.createStatement().use { s ->
            s.execute("INSERT INTO nearby_community_bans VALUES ('$banned','$visitor',100)")
            s.execute("UPDATE nearby_communities SET state='DELETED' WHERE id='$deleted'")
        } }
        val page1 = repository.catalog(visitor, "riders", limit = 1)
        assertEquals(1, page1.size)
        val page2 = repository.catalog(visitor, "riders", after = page1.single().id, limit = 1)
        assertEquals(setOf(first, second), (page1 + page2).map { it.id }.toSet())
        assertTrue(repository.catalog(visitor, "riders", after = page2.single().id, limit = 1).isEmpty())
        assertEquals(listOf(first), repository.catalog(visitor, "%_", limit = 10).map { it.id })
        assertNull(repository.detail(visitor, first), "Discovery does not expose member-only channels")
    }

    @Test fun publicJoinIsIdempotentAndDoesNotReplaceAnExistingVoiceClaim() {
        val community = create("Public", CommunityVisibility.PUBLIC)
        val sessions = JdbcSessionRepository(source)
        val operation = id(); val session = id()
        sessions.begin(visitor, operation, id(), session, SfuEpoch("other-room", "node"), { 100 })
        val before = JdbcRosterJournal(source).revision()
        assertIs<CommunityWriteResult.Accepted>(repository.joinPublic(visitor, community, { 101 }))
        assertIs<CommunityWriteResult.Accepted>(repository.joinPublic(visitor, community, { 102 }))
        assertEquals(before + 1, JdbcRosterJournal(source).revision())
        assertEquals(session, sessions.currentClaim(visitor)!!.sessionId)
        assertEquals(2, count("nearby_community_members")); assertEquals(0, count("sharing_sessions"))
        assertNotNull(repository.detail(visitor, community))
    }

    @Test fun privateAndBannedPublicJoinFailWithoutMembershipOrRevision() {
        val private = create("Private", CommunityVisibility.PRIVATE)
        val banned = create("Public", CommunityVisibility.PUBLIC)
        source.connection.use { c -> c.createStatement().use { it.execute("INSERT INTO nearby_community_bans VALUES ('$banned','$visitor',100)") } }
        val before = JdbcRosterJournal(source).revision()
        assertIs<CommunityWriteResult.Denied>(repository.joinPublic(visitor, private, { 101 }))
        assertIs<CommunityWriteResult.Denied>(repository.joinPublic(visitor, banned, { 101 }))
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(2, count("nearby_community_members"))
    }

    @Test fun memberDetailAndMembershipListSuppressBannedAndDeletedChannels() {
        val community = create("Public", CommunityVisibility.PUBLIC)
        repository.joinPublic(visitor, community, { 101 })
        assertEquals(listOf(community), repository.memberships(visitor).map { it.id })
        source.connection.use { c -> c.createStatement().use { it.execute("UPDATE nearby_voice_channels SET state='DELETED' WHERE community_id='$community'") } }
        assertTrue(repository.detail(visitor, community)!!.channels.isEmpty())
        source.connection.use { c -> c.createStatement().use { it.execute("INSERT INTO nearby_community_bans VALUES ('$community','$visitor',102)") } }
        assertNull(repository.detail(visitor, community)); assertTrue(repository.memberships(visitor).isEmpty())
    }

    private fun create(name: String, visibility: CommunityVisibility): String = id().also {
        assertIs<CommunityWriteResult.Accepted>(repository.create(owner, it, name, visibility = visibility, now = { 100 }))
    }
    private fun count(table: String) = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT count(*) FROM $table").use { assertTrue(it.next()); it.getInt(1) }
    } }
    private fun schema(): DataSource {
        val name = "community_repository_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}
