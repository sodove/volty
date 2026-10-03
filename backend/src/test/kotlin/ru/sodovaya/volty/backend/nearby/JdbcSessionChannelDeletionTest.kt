package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionChannelDeletionTest {
    private val source = schema()
    private val owner = id(); private val member = id(); private val community = id()
    private val channel = id(); private val other = id(); private val operation = id()
    private val communities = JdbcCommunityRepository(source)
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
    private val deletion = JdbcChannelDeletionRepository(source)
    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        for (actor in listOf(owner, member)) sql("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','Rider',0)")
        communities.create(owner, community, "Riders", visibility = CommunityVisibility.PUBLIC, now = { 100 })
        communities.joinPublic(member, community, { 100 })
        for (c in listOf(channel, other)) assertIs<ChannelCreationResult.Created>(JdbcChannelCreationRepository(source).create(owner, community, c, "Extra", { 100 }))
    }
    @Test fun onlyDeletedChannelClaimClosesWhileMembershipAndOtherVoiceRemain() {
        val doomed = voice(member, channel); val survivor = voice(owner, other)
        val result = assertIs<ChannelDeletionResult.Deleted>(delete())
        assertEquals(channel, result.channelId); assertEquals(community, result.communityId)
        assertEquals(listOf(doomed.session), result.cleanupSessions.map { it.operation.sessionId })
        assertTrue(result.cleanupSessions.single().cleanupPending)
        assertNull(sessions.currentClaim(member)); assertEquals(survivor.session, sessions.currentClaim(owner)!!.sessionId)
        assertEquals(2, count("nearby_community_members")); assertNotNull(communities.detail(member, community))
        assertFalse(communities.detail(owner, community)!!.channels.any { it.id == channel })
        assertEquals(1, count("nearby_resource_deletion_targets"))
    }
    @Test fun defaultAndLastChannelDeletionKeepsEmptyServerWithoutSyntheticReplacement() {
        val defaults = communities.detail(owner, community)!!.channels.single { it.isDefault }.id
        for (c in listOf(channel, other, defaults)) assertIs<ChannelDeletionResult.Deleted>(deletion.deleteChannel(owner, id(), community, c, { 101 }))
        assertTrue(communities.detail(member, community)!!.channels.isEmpty())
        assertEquals(2, count("nearby_community_members")); assertEquals(3, count("nearby_voice_channels"))
        assertEquals(0, count("nearby_session_operations"))
        val newChannel = id()
        assertIs<ChannelCreationResult.Created>(JdbcChannelCreationRepository(source).create(owner, community, newChannel, "Fresh", { 102 }))
        assertEquals(listOf(CommunityChannel(newChannel, "Fresh", false)), communities.detail(member, community)!!.channels)
    }
    @Test fun receiptReplayAfterCleanupNeverTargetsANewVoiceClaim() {
        val old = voice(member, channel); val first = assertIs<ChannelDeletionResult.Deleted>(delete())
        val target = first.cleanupSessions.single()
        assertTrue(sessions.completeCleanup(member, old.operation, old.session, target.sfuEpoch!!))
        val fresh = voice(member, other); val before = JdbcRosterJournal(source).revision()
        val replay = assertIs<ChannelDeletionResult.Deleted>(delete())
        assertEquals(first.revision, replay.revision); assertEquals(old.session, replay.cleanupSessions.single().operation.sessionId)
        assertFalse(replay.cleanupSessions.single().cleanupPending)
        assertEquals(fresh.session, sessions.currentClaim(member)!!.sessionId); assertEquals(before, JdbcRosterJournal(source).revision())
    }
    @Test fun memberUnknownAndForeignParentDenialCannotMutateVoice() {
        val v = voice(member, channel); val before = JdbcRosterJournal(source).revision()
        assertEquals(ChannelDeletionResult.Denied, deletion.deleteChannel(member, operation, community, channel, { 101 }))
        assertEquals(ChannelDeletionResult.Denied, deletion.deleteChannel(owner, operation, community, id(), { 101 }))
        val foreign = id(); communities.create(owner, foreign, "Foreign", now = { 100 })
        val afterCreate = JdbcRosterJournal(source).revision()
        assertEquals(ChannelDeletionResult.Denied, deletion.deleteChannel(owner, operation, foreign, channel, { 101 }))
        assertEquals(afterCreate, JdbcRosterJournal(source).revision()); assertTrue(afterCreate > before)
        assertEquals(v.session, sessions.currentClaim(member)!!.sessionId); assertEquals(0, count("nearby_resource_deletions"))
    }
    @Test fun sharedOperationNamespaceRejectsBothCommunityAndOtherChannelRetargeting() {
        assertIs<ChannelDeletionResult.Deleted>(delete())
        assertEquals(ChannelDeletionResult.Conflict, deletion.deleteChannel(owner, operation, community, other, { 102 }))
        val communityDeletion = JdbcCommunityDeletionRepository(source)
        assertEquals(CommunityDeletionResult.Conflict, communityDeletion.deleteCommunity(owner, operation, community, { 102 }))
        val foreign = id(); communities.create(owner, foreign, "Foreign", now = { 100 })
        val foreignOp = id()
        assertIs<CommunityDeletionResult.Deleted>(communityDeletion.deleteCommunity(owner, foreignOp, foreign, { 101 }))
        assertEquals(ChannelDeletionResult.Conflict, deletion.deleteChannel(owner, foreignOp, community, other, { 102 }))
        assertTrue(communities.detail(owner, community)!!.channels.any { it.id == other })
    }
    @Test fun finalOutboxFailureRollsBackClaimTombstoneReceiptAndTargets() {
        val v = voice(member, channel); val before = JdbcRosterJournal(source).revision()
        sql("ALTER TABLE nearby_community_outbox ADD CONSTRAINT reject_channel_delete CHECK(kind<>'CHANNEL_DELETED')")
        assertFailsWith<SQLException> { delete() }
        assertEquals(v.session, sessions.currentClaim(member)!!.sessionId)
        assertTrue(communities.detail(owner, community)!!.channels.any { it.id == channel })
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(0, count("nearby_resource_deletions"))
        assertEquals(0, count("nearby_resource_deletion_targets")); assertFalse(sessions.findSession(member, v.session)!!.cleanupPending)
    }
    @Test fun incoherentExactClaimAbortsInsteadOfGuessingAnEpoch() {
        voice(member, channel); val before = JdbcRosterJournal(source).revision()
        sql("UPDATE nearby_actor_claims SET node_epoch='foreign' WHERE actor_id='$member'")
        assertFailsWith<IllegalStateException> { delete() }
        assertTrue(communities.detail(owner, community)!!.channels.any { it.id == channel })
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(0, count("nearby_resource_deletions"))
    }
    @Test fun foreignDestinationWithMatchingDeletedRoomCannotBeSilentlySkipped() {
        val v = voice(member, other); val before = JdbcRosterJournal(source).revision()
        sql("UPDATE nearby_actor_claims SET room_epoch='volty-channel-$channel' WHERE actor_id='$member'")
        sql("UPDATE nearby_session_operations SET room_epoch='volty-channel-$channel' WHERE actor_id='$member' AND operation_id='${v.operation}'")
        // Claim and journal agree with EACH OTHER, but disagree with the actual
        // channel binding. The OR-room candidate query must reach this helper.
        assertEquals(v.session, sessions.currentClaim(member)!!.sessionId)
        assertEquals(other, sessions.findSession(member, v.session)!!.operation.destinationId)
        assertFailsWith<IllegalStateException> { delete() }
        assertTrue(communities.detail(owner, community)!!.channels.any { it.id == channel })
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(0, count("nearby_resource_deletions"))
        assertEquals(v.session, sessions.currentClaim(member)!!.sessionId)
    }
    @Test fun deletedChannelCannotBeRecreatedOrAdmittedWithAnotherOperation() {
        delete()
        assertEquals(ChannelCreationResult.Conflict, JdbcChannelCreationRepository(source).create(owner, community, channel, "Extra", { 102 }))
        assertEquals(OperationRejection.ACCESS_DENIED, sessions.begin(member, id(), channel, id(), epoch(channel), { 102 }).rejection)
        assertEquals(ChannelDeletionResult.Denied, deletion.deleteChannel(owner, id(), community, channel, { 102 }))
        assertEquals(2, count("nearby_community_members")); assertNull(sessions.currentClaim(member))
    }
    @Test fun actualCredentialShareFenceFinishesBeforeDeleteAndNoProducerRunsAfter() {
        val v = voice(member, channel); val barrier = AccessFenceBarrierSource(source)
        val guarded = JdbcSessionRepository(barrier, JdbcChannelAccess()); val pool = Executors.newFixedThreadPool(2)
        try {
            var issued = 0
            val grant = pool.submit<String?> { guarded.withActiveSession(member, v.operation, { 101 }) { issued++; "before" } }
            assertTrue(barrier.held.await(5, TimeUnit.SECONDS))
            val remove = pool.submit<ChannelDeletionResult> { JdbcChannelDeletionRepository(barrier).deleteChannel(owner, operation, community, channel, { 101 }) }
            assertTrue(barrier.competingIssued.await(5, TimeUnit.SECONDS)); assertFalse(remove.isDone)
            barrier.release.countDown(); assertEquals("before", grant.get(10, TimeUnit.SECONDS)); assertIs<ChannelDeletionResult.Deleted>(remove.get(10, TimeUnit.SECONDS))
            assertNull(guarded.withActiveSession(member, v.operation, { 102 }) { issued++; "after" }); assertEquals(1, issued)
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }
    @Test fun joinIssuedBehindDeleteReadsCommittedChannelLoss() {
        val barrier = AccessFenceBarrierSource(source); val pool = Executors.newFixedThreadPool(2)
        val guarded = JdbcSessionRepository(barrier, JdbcChannelAccess())
        try {
            val remove = pool.submit<ChannelDeletionResult> { JdbcChannelDeletionRepository(barrier).deleteChannel(owner, operation, community, channel, { 101 }) }
            assertTrue(barrier.held.await(5, TimeUnit.SECONDS))
            val join = pool.submit<SessionJournalResult> { guarded.begin(member, id(), channel, id(), epoch(channel), { 102 }) }
            assertTrue(barrier.competingIssued.await(5, TimeUnit.SECONDS)); assertFalse(join.isDone)
            barrier.release.countDown(); assertIs<ChannelDeletionResult.Deleted>(remove.get(10, TimeUnit.SECONDS))
            assertEquals(OperationRejection.ACCESS_DENIED, join.get(10, TimeUnit.SECONDS).rejection); assertNull(guarded.currentClaim(member))
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }
    private data class Voice(val operation: String, val session: String)
    private fun voice(actor: String, destination: String) = Voice(id(), id()).also {
        assertNull(sessions.begin(actor, it.operation, destination, it.session, epoch(destination), { 100 }).rejection)
        assertNull(sessions.admit(actor, it.operation, it.session, epoch(destination), { 100 }, 30_100).rejection)
    }
    private fun delete() = deletion.deleteChannel(owner, operation, community, channel, { 101 })
    private fun epoch(c: String) = SfuEpoch("volty-channel-$c", "node")
    private fun sql(query: String) = source.connection.use { c -> c.createStatement().use { it.execute(query) } }
    private fun count(table: String) = source.connection.use { c -> c.createStatement().use { it.executeQuery("SELECT count(*) FROM $table").use { rows -> assertTrue(rows.next()); rows.getInt(1) } } }
    private fun schema(): DataSource {
        val name = "session_channel_delete_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}
