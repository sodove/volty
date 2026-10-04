package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import ru.sodovaya.volty.backend.prepareBackendSchema
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionLegacyRetirementTest {
    private val source = schema()
    private val owner = id(); private val member = id(); private val oldGroup = id()
    private val communities = JdbcCommunityRepository(source)
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())

    @Test fun startupPreservesAccountsFriendsAndLegacyDataWithoutActiveImportedServers() {
        legacy()
        prepareBackendSchema(source)
        assertEquals(2, count("users")); assertEquals(1, count("friendships", "status='ACCEPTED'"))
        assertEquals(1, count("groups")); assertEquals(2, count("group_members"))
        assertEquals(1, count("sharing_sessions")); assertEquals(1, count("live_updates"))
        assertTrue(communities.memberships(owner).isEmpty()); assertTrue(communities.memberships(member).isEmpty())
        assertTrue(communities.catalog(member, "").isEmpty()); assertNull(communities.detail(owner, oldGroup))
        assertEquals(1, count("nearby_communities", "state='DELETED'"))
        assertEquals(1, count("nearby_voice_channels", "state='DELETED'"))
        assertEquals(0, count("nearby_session_operations"))
    }
    @Test fun nativeServersWithSameNameSurviveAndOldIdsCannotBeReused() {
        legacy(); SchemaMigrator(source).migrate()
        val native = id(); val channel = create(native)
        assertIs<CommunityWriteResult.Accepted>(communities.joinPublic(member, native, { 100 }))
        assertEquals(1, JdbcLegacyImportRetirement(source).retire { 101 })
        assertEquals(listOf(native), communities.memberships(owner).map { it.id })
        assertEquals(channel, communities.detail(member, native)!!.channels.single().id)
        assertEquals(listOf(native), communities.catalog(member, "Riders").map { it.id })
        assertIs<CommunityWriteResult.Conflict>(communities.create(owner, oldGroup, "Riders", now = { 102 }))
        assertEquals(1, count("groups")); assertEquals(1, count("friendships"))
    }
    @Test fun exactImportedVoiceIsClosedAndNativePeerRemainsAcrossRetirementReplay() {
        legacy(); SchemaMigrator(source).migrate()
        val oldChannel = channel(oldGroup); val native = id(); val nativeChannel = create(native)
        communities.joinPublic(member, native, { 100 })
        val doomed = id(); val operation = id(); val survivor = id()
        assertNull(sessions.begin(owner, operation, oldChannel, doomed, epoch(oldChannel), { 100 }).rejection)
        assertNull(sessions.begin(member, id(), nativeChannel, survivor, epoch(nativeChannel), { 100 }).rejection)
        val retirement = JdbcLegacyImportRetirement(source)
        assertEquals(1, retirement.retire { 101 })
        val row = sessions.findSession(owner, doomed)!!
        assertEquals(OperationPhase.CANCELLED, row.operation.phase); assertTrue(row.cleanupPending)
        assertNull(sessions.currentClaim(owner)); assertEquals(survivor, sessions.currentClaim(member)!!.sessionId)
        assertEquals(OperationRejection.ACCESS_DENIED, sessions.begin(owner, id(), oldChannel, id(), epoch(oldChannel), { 102 }).rejection)
        val revision = JdbcRosterJournal(source).revision()
        assertEquals(0, retirement.retire { 103 }); assertEquals(revision, JdbcRosterJournal(source).revision())
        assertEquals(survivor, sessions.currentClaim(member)!!.sessionId)
        assertTrue(sessions.findSession(owner, doomed)!!.cleanupPending)
    }
    @Test fun finalOutboxFailureRollsBackTombstonesAndExactVoiceCleanup() {
        legacy(); SchemaMigrator(source).migrate()
        val oldChannel = channel(oldGroup); val voice = id()
        assertNull(sessions.begin(owner, id(), oldChannel, voice, epoch(oldChannel), { 100 }).rejection)
        val revision = JdbcRosterJournal(source).revision()
        sql("ALTER TABLE nearby_community_outbox ADD CONSTRAINT reject_retire CHECK(kind<>'DELETED')")
        assertFailsWith<SQLException> { JdbcLegacyImportRetirement(source).retire { 101 } }
        assertEquals(voice, sessions.currentClaim(owner)!!.sessionId)
        assertNotNull(communities.detail(owner, oldGroup)); assertEquals(revision, JdbcRosterJournal(source).revision())
        assertEquals(0, count("nearby_session_operations", "cleanup_pending"))
        assertEquals(1, count("friendships")); assertEquals(1, count("groups"))
    }
    @Test fun repeatedBootstrapDoesNotImportGroupsCreatedAfterHistoricalMigration() {
        legacy(); prepareBackendSchema(source)
        val native = id(); create(native)
        val late = id()
        sql("INSERT INTO groups VALUES ('$late','Late','$owner','LATE_GROUP',1000,100)")
        sql("INSERT INTO group_members VALUES ('$late','$owner','OWNER',100)")
        val revision = JdbcRosterJournal(source).revision()
        prepareBackendSchema(source)
        assertEquals(revision, JdbcRosterJournal(source).revision())
        assertEquals(listOf(native), communities.memberships(owner).map { it.id })
        assertNull(communities.detail(owner, late)); assertEquals(2, count("groups"))
        assertEquals(1, count("friendships")); assertEquals(14, count("schema_migrations"))
    }
    private fun legacy() {
        SchemaMigrator(source, listOf(1 to "/db/migration/V1__initial.sql")).migrate()
        for (actor in listOf(owner, member)) sql("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','Rider',0)")
        sql("INSERT INTO groups VALUES ('$oldGroup','Riders','$owner','OLD_GROUP',1000,100)")
        sql("INSERT INTO group_members VALUES ('$oldGroup','$owner','OWNER',100),('$oldGroup','$member','MEMBER',100)")
        sql("INSERT INTO friendships VALUES ('${id()}','$owner','$member','ACCEPTED',100)")
        sql("INSERT INTO sharing_sessions VALUES ('${id()}','$member','$oldGroup','LOCATION',100,1000,NULL)")
        sql("INSERT INTO live_updates VALUES ('$member','$oldGroup','{}',NULL,100,100)")
    }
    private fun create(community: String) = assertIs<CommunityWriteResult.Accepted>(communities.create(owner, community,
        "Riders", visibility = CommunityVisibility.PUBLIC, now = { 100 })).snapshot.channels.single().id
    private fun channel(community: String): String = source.connection.use { c -> c.createStatement().use {
        it.executeQuery("SELECT id FROM nearby_voice_channels WHERE community_id='$community'").use { rows -> assertTrue(rows.next()); rows.getString(1) }
    } }
    private fun epoch(channel: String) = SfuEpoch("volty-channel-$channel", "node")
    private fun sql(query: String) = source.connection.use { c -> c.createStatement().use { it.execute(query) } }
    private fun count(table: String, where: String = "TRUE") = source.connection.use { c -> c.createStatement().use {
        it.executeQuery("SELECT count(*) FROM $table WHERE $where").use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    } }
    private fun schema(): DataSource {
        val name = "session_legacy_retire_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}
