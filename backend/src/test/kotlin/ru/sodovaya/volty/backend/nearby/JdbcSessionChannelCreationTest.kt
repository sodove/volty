package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionChannelCreationTest {
    private val source = schema()
    private val owner = id(); private val member = id(); private val community = id(); private val channel = id()
    private val communities = JdbcCommunityRepository(source)
    private val channels = JdbcChannelCreationRepository(source)
    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        for (actor in listOf(owner, member)) sql("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','Rider',0)")
        communities.create(owner, community, "Riders", visibility = CommunityVisibility.PUBLIC, now = { 100 })
        communities.joinPublic(member, community, { 100 })
    }
    @Test fun ownerCreatesAnAdditionalChannelWithoutChangingVoiceOrSharing() {
        val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
        val initial = communities.detail(owner, community)!!.channels.single().id
        val voice = id()
        assertNull(sessions.begin(owner, id(), initial, voice, SfuEpoch("volty-channel-$initial", "node"), { 100 }).rejection)
        val before = JdbcRosterJournal(source).revision()
        val result = assertIs<ChannelCreationResult.Created>(channels.create(owner, community, channel, "  Evening  ", { 101 }))
        assertEquals(CommunityChannel(channel, "Evening", false), result.channel)
        assertEquals(before + 1, result.revision); assertEquals(voice, sessions.currentClaim(owner)!!.sessionId)
        assertEquals(2, communities.detail(member, community)!!.channels.size)
        assertEquals(0, count("sharing_sessions")); assertEquals(1, count("nearby_channel_creations"))
        assertEquals(14, count("schema_migrations"))
    }
    @Test fun exactReplayHasStableRevisionAndPayloadOrExistingIdConflictCannotMutate() {
        val first = assertIs<ChannelCreationResult.Created>(channels.create(owner, community, channel, "Evening", { 101 }))
        assertEquals(first, channels.create(owner, community, channel, " Evening ", { 102 }))
        assertEquals(ChannelCreationResult.Conflict, channels.create(owner, community, channel, "Other", { 102 }))
        val other = id(); communities.create(owner, other, "Other", now = { 103 })
        assertEquals(ChannelCreationResult.Conflict, channels.create(owner, other, channel, "Evening", { 104 }))
        val default = communities.detail(owner, community)!!.channels.single { it.isDefault }.id
        assertEquals(ChannelCreationResult.Conflict, channels.create(owner, community, default, "Общий", { 104 }))
        assertEquals(1, count("nearby_channel_creations")); assertEquals("Evening", communities.detail(owner, community)!!.channels.single { it.id == channel }.name)
        sql("UPDATE nearby_voice_channels SET state='DELETED' WHERE id='$channel'")
        assertEquals(ChannelCreationResult.Conflict, channels.create(owner, community, channel, "Evening", { 105 }))
    }
    @Test fun memberAndFormerOwnerCannotCreateOrReplayAnOwnedResource() {
        assertEquals(ChannelCreationResult.Denied, channels.create(member, community, channel, "Evening", { 101 }))
        assertEquals(ChannelCreationResult.Denied, channels.create(member, id(), channel, "Evening", { 101 }))
        channels.create(owner, community, channel, "Evening", { 101 })
        sql("UPDATE nearby_communities SET owner_id='$member' WHERE id='$community'")
        val before = JdbcRosterJournal(source).revision()
        assertEquals(ChannelCreationResult.Denied, channels.create(owner, community, channel, "Evening", { 102 }))
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(2, count("nearby_voice_channels"))
    }
    @Test fun activeLimitIncludesDefaultButExactReplayAtLimitStillSucceeds() {
        sql("INSERT INTO nearby_voice_channels(id,community_id,name,created_at) SELECT md5('limit-'||n::text)::uuid,'$community','Fixture',100 FROM generate_series(1,126) n")
        val first = assertIs<ChannelCreationResult.Created>(channels.create(owner, community, channel, "Last", { 101 }))
        assertEquals(128, communities.detail(member, community)!!.channels.size)
        assertEquals(first, channels.create(owner, community, channel, "Last", { 102 }))
        assertEquals(ChannelCreationResult.LimitReached, channels.create(owner, community, id(), "Overflow", { 102 }))
        assertEquals(128, count("nearby_voice_channels")); assertEquals(first.revision, JdbcRosterJournal(source).revision())
    }
    @Test fun outboxFailureRollsBackChannelReceiptAndRevisionTogether() {
        val before = JdbcRosterJournal(source).revision()
        sql("ALTER TABLE nearby_community_outbox ADD CONSTRAINT reject_channel CHECK(kind<>'CHANNEL_CREATED')")
        assertFailsWith<SQLException> { channels.create(owner, community, channel, "Evening", { 101 }) }
        assertEquals(1, count("nearby_voice_channels")); assertEquals(0, count("nearby_channel_creations"))
        assertEquals(before, JdbcRosterJournal(source).revision())
    }
    @Test fun inactiveParentActorAndInvalidNamesNeverCreateAChannel() {
        assertFailsWith<IllegalArgumentException> { channels.create(owner, community, channel, "\n", { 101 }) }
        assertFailsWith<IllegalArgumentException> { channels.create(owner, community, channel, "x".repeat(81), { 101 }) }
        sql("UPDATE nearby_communities SET state='DELETED' WHERE id='$community'")
        assertEquals(ChannelCreationResult.Denied, channels.create(owner, community, channel, "Evening", { 101 }))
        sql("UPDATE nearby_communities SET state='ACTIVE' WHERE id='$community'")
        sql("UPDATE users SET deleted_at=101 WHERE id='$owner'")
        assertEquals(ChannelCreationResult.Denied, channels.create(owner, community, channel, "Evening", { 102 }))
        assertEquals(1, count("nearby_voice_channels")); assertEquals(0, count("nearby_channel_creations"))
    }
    @Test fun incoherentMatchingReceiptCannotTurnTheDefaultIntoAnAdditionalChannelReplay() {
        val default = communities.detail(owner, community)!!.channels.single().id
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(listOf(owner, community, default, "Общий")
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val before = JdbcRosterJournal(source).revision()
        // No producer emits this combination: default creation is part of the
        // community operation, never an additional-channel creation receipt.
        sql("INSERT INTO nearby_channel_creations VALUES ('$default','$owner','$community','$hash',$before)")
        assertEquals(1, count("nearby_channel_creations"))
        assertEquals(ChannelCreationResult.Conflict, channels.create(owner, community, default, "Общий", { 101 }))
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(1, count("nearby_voice_channels"))
    }
    private fun sql(query: String) = source.connection.use { c -> c.createStatement().use { it.execute(query) } }
    private fun count(table: String) = source.connection.use { c -> c.createStatement().use {
        it.executeQuery("SELECT count(*) FROM $table").use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    } }
    private fun schema(): DataSource {
        val name = "session_channel_create_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}
