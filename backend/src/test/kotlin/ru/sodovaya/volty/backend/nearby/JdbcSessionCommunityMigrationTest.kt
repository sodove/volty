package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionCommunityMigrationTest {
    private val source = schema()
    private val owner = id(); private val member = id(); private val group = id()

    @Test fun legacyGroupsBecomePrivateWithStableDefaultChannelWithoutLosingData() {
        legacy(ownerMembership = true)
        source.connection.use { c -> c.createStatement().use {
            it.execute("INSERT INTO friendships VALUES ('${id()}','$owner','$member','ACCEPTED',12)")
            it.execute("INSERT INTO sharing_sessions VALUES ('${id()}','$member','$group','LOCATION',15,100,NULL)")
        } }
        SchemaMigrator(source).migrate()
        source.connection.use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT id,name,owner_id,visibility,created_at FROM nearby_communities").use {
                assertTrue(it.next()); assertEquals(group, it.getString(1)); assertEquals("Old riders", it.getString(2))
                assertEquals(owner, it.getString(3)); assertEquals("PRIVATE", it.getString(4)); assertEquals(10, it.getLong(5)); assertFalse(it.next())
            }
            s.executeQuery("SELECT user_id,joined_at FROM nearby_community_members ORDER BY joined_at").use {
                assertTrue(it.next()); assertEquals(owner, it.getString(1)); assertEquals(10, it.getLong(2))
                assertTrue(it.next()); assertEquals(member, it.getString(1)); assertEquals(11, it.getLong(2)); assertFalse(it.next())
            }
            assertEquals(1, count("groups")); assertEquals(2, count("group_members"))
            assertEquals(1, count("friendships")); assertEquals(1, count("sharing_sessions"))
            assertEquals(0, count("nearby_community_outbox")); assertEquals(0, count("nearby_session_operations"))
            s.executeQuery("SELECT invite_code FROM groups").use { assertTrue(it.next()); assertEquals("KEEPME", it.getString(1)) }
        } }
        val channel = defaultChannel()
        SchemaMigrator(source).migrate()
        assertEquals(channel, defaultChannel()); assertEquals(1, count("nearby_voice_channels")); assertEquals(13, count("schema_migrations"))
    }

    @Test fun missingLegacyOwnerMembershipIsRepairedWithoutRemovingExistingMember() {
        legacy(ownerMembership = false)
        SchemaMigrator(source).migrate()
        assertEquals(1, count("group_members"), "Original legacy state remains intact")
        assertEquals(2, count("nearby_community_members"))
        source.connection.use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT joined_at FROM nearby_community_members WHERE user_id='$owner'").use {
                assertTrue(it.next()); assertEquals(10, it.getLong(1))
            }
        } }
    }

    @Test fun deferredOwnerMembershipConstraintRejectsOrphanAtCommit() {
        legacy(ownerMembership = true); SchemaMigrator(source).migrate()
        source.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("DELETE FROM nearby_community_members WHERE community_id='$group' AND user_id='$owner'") }
            assertFailsWith<SQLException> { c.commit() }
            c.rollback()
        }
        assertEquals(2, count("nearby_community_members"))
        assertEquals(1, count("nearby_communities"))
    }

    @Test fun failureAfterNewMigrationRollsBackSchemaAndBackfillTogether() {
        legacy(ownerMembership = true)
        val resources = listOf(
            1 to "/db/migration/V1__initial.sql", 2 to "/db/migration/V2__nearby_session_journal.sql",
            3 to "/db/migration/V3__nearby_roster_journal.sql", 4 to "/db/migration/V4__nearby_media_webhook_inbox.sql",
            5 to "/db/migration/V5__nearby_media_observations.sql", 6 to "/db/migration/V6__nearby_room_webhook_targets.sql",
            7 to "/db/migration/V7__nearby_communities.sql", 8 to "/db/migration/fixture_missing.sql",
        )
        assertFailsWith<IllegalStateException> { SchemaMigrator(source, resources).migrate() }
        assertEquals(1, count("schema_migrations")); assertEquals(1, count("groups"))
        source.connection.use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT to_regclass('nearby_communities')").use { assertTrue(it.next()); assertNull(it.getString(1)) }
        } }
        SchemaMigrator(source).migrate()
        assertEquals(1, count("nearby_communities")); assertEquals(2, count("nearby_community_members"))
    }

    private fun legacy(ownerMembership: Boolean) {
        SchemaMigrator(source, listOf(1 to "/db/migration/V1__initial.sql")).migrate()
        source.connection.use { c -> c.createStatement().use { s ->
            for (actor in listOf(owner, member)) s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO groups VALUES ('$group','Old riders','$owner','KEEPME',1000,10)")
            if (ownerMembership) s.execute("INSERT INTO group_members VALUES ('$group','$owner','OWNER',10)")
            s.execute("INSERT INTO group_members VALUES ('$group','$member','MEMBER',11)")
        } }
    }
    private fun defaultChannel(): String = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT id,community_id,name,is_default,state FROM nearby_voice_channels").use {
            assertTrue(it.next()); assertEquals(group, it.getString(2)); assertEquals("Общий", it.getString(3))
            assertTrue(it.getBoolean(4)); assertEquals("ACTIVE", it.getString(5)); it.getString(1).also { _ -> assertFalse(it.next()) }
        }
    } }
    private fun count(table: String) = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT count(*) FROM $table").use { assertTrue(it.next()); it.getInt(1) }
    } }
    private fun schema(): DataSource {
        val name = "community_migration_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}
