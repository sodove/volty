package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionAccessTest {
    private val source = schema()
    private val owner = id(); private val actor = id(); private val community = id()
    private val operation = id(); private val session = id()
    private val access = JdbcChannelAccess()
    private val repository = JdbcSessionRepository(source, access)
    private lateinit var channel: String
    private val epoch get() = SfuEpoch("volty-channel-$channel", "node")

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use { c -> c.createStatement().use { s ->
            for (user in listOf(owner, actor)) s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$user','$user@example.invalid','test','test',0)")
        } }
        val communities = JdbcCommunityRepository(source)
        channel = assertIs<CommunityWriteResult.Accepted>(communities.create(owner, community, "Public", visibility = CommunityVisibility.PUBLIC, now = { 100 })).snapshot.channels.single().id
        communities.joinPublic(actor, community, { 100 })
    }

    @Test fun deniedReplacementDoesNotCancelUnrelatedCurrentClaim() {
        begin()
        val before = JdbcRosterJournal(source).revision()
        val denied = repository.begin(actor, id(), id(), id(), SfuEpoch("foreign", "node"), { 101 }, replace = true)
        assertEquals(OperationRejection.ACCESS_DENIED, denied.rejection); assertNull(denied.state)
        assertEquals(session, repository.currentClaim(actor)!!.sessionId)
        assertEquals(before, JdbcRosterJournal(source).revision())
        assertEquals(OperationPhase.PENDING, repository.get(actor, operation)!!.operation.phase)
    }

    @Test fun channelMembershipCannotAuthorizeCredentialsForAnotherRoom() {
        val result = repository.begin(actor, operation, channel, session, SfuEpoch("foreign-room", "node"), { 100 })
        assertEquals(OperationRejection.ACCESS_DENIED, result.rejection)
        assertNull(result.state); assertNull(repository.currentClaim(actor))
    }

    @Test fun staleResolverCannotMakeServiceDereferenceDeniedStateOrRegisterMedia() {
        ban()
        var inspected = 0; var registered = 0; var issued = 0
        val sfu = object : SfuLeaseClient {
            override fun inspect(room: String): SfuReply<SfuNodeStatus> { inspected++; return SfuReply.Accepted(SfuNodeStatus("node", true, true, true)) }
            override fun register(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt> { registered++; return SfuReply.Accepted(LeaseReceipt(30_100)) }
            override fun renew(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt> = error("unused")
            override fun revoke(room: String, session: String, expectedNodeEpoch: String): SfuReply<Unit> = error("No session admitted")
        }
        val service = SessionService(repository, sfu, { _, _ -> epoch.roomEpoch }, SessionCredentialIssuer { row ->
            issued++
            SessionCredentials("wss://test", "opaque", row.operation.sessionId!!, row.sfuEpoch!!.roomEpoch, row.operation.deadlineEpochMillis!!)
        }, { 100 })
        assertEquals(SessionAction.Denied(SessionDenial.MEMBERSHIP), service.join(actor, operation, channel))
        assertEquals(1, inspected); assertEquals(0, registered); assertEquals(0, issued)
        assertNull(repository.get(actor, operation)); assertNull(repository.currentClaim(actor))
    }

    @Test fun delayedAdmissionAfterRemovalCannotRestorePendingSession() {
        begin(); removeMembership()
        val result = repository.admit(actor, operation, session, epoch, { 101 }, 30_101)
        assertEquals(OperationRejection.ACCESS_DENIED, result.rejection)
        assertEquals(OperationPhase.CANCELLED, result.state!!.operation.phase)
        assertTrue(result.state!!.cleanupPending); assertNull(repository.currentClaim(actor))
        assertEquals(OperationPhase.CANCELLED, repository.admit(actor, operation, session, epoch, { 102 }, 30_102).state!!.operation.phase)
    }

    @Test fun revokedAccessCannotEnterCpuCredentialProducer() {
        active(); ban()
        var issued = 0
        assertNull(repository.withActiveSession(actor, operation, { 101 }) { issued++; "secret" })
        assertEquals(0, issued, "Producer must never be invoked after denied atomic access")
        assertTrue(repository.get(actor, operation)!!.cleanupPending); assertNull(repository.currentClaim(actor))
    }

    @Test fun staleMediaObservationCannotPublishConnectedAfterAccessRemoval() {
        active(); removeMembership()
        val result = repository.observeMedia(actor, operation, session, epoch, 0, SfuParticipantObservation("PA_old", SfuParticipantState.ACTIVE), { 101 })
        assertEquals(OperationRejection.ACCESS_DENIED, result.rejection)
        assertEquals(OperationPhase.CANCELLED, result.state!!.operation.phase)
        assertNull(result.state!!.media.participantSid)
    }

    @Test fun connectedRenewReceiptCannotExtendBannedSession() {
        active()
        repository.observeMedia(actor, operation, session, epoch, 0, SfuParticipantObservation("PA_live", SfuParticipantState.ACTIVE), { 101 })
        ban()
        val result = repository.heartbeatConnected(actor, operation, session, epoch, 1, "PA_live", { 102 }, 30_102)
        assertEquals(OperationRejection.ACCESS_DENIED, result.rejection)
        assertEquals(OperationPhase.CANCELLED, result.state!!.operation.phase)
        assertEquals(30_100, result.state!!.operation.deadlineEpochMillis)
        assertNull(repository.currentClaim(actor))
    }

    @Test fun deletedChannelAndDeletedAccountCannotAuthorizeNewAdmission() {
        source.connection.use { c -> c.createStatement().use { it.execute("UPDATE nearby_voice_channels SET state='DELETED' WHERE id='$channel'") } }
        assertEquals(OperationRejection.ACCESS_DENIED, repository.begin(actor, operation, channel, session, epoch, { 100 }).rejection)
        source.connection.use { c -> c.createStatement().use {
            it.execute("UPDATE nearby_voice_channels SET state='ACTIVE' WHERE id='$channel'")
            it.execute("UPDATE users SET deleted_at=101 WHERE id='$actor'")
        } }
        assertEquals(OperationRejection.ACCESS_DENIED, repository.begin(actor, operation, channel, session, epoch, { 102 }).rejection)
        assertNull(repository.currentClaim(actor))
    }

    @Test fun issuedCredentialTransactionHoldsAccessFenceUntilRevocationCanCommit() {
        active()
        val barrier = AccessFenceBarrierSource(source)
        val guarded = JdbcSessionRepository(barrier, access)
        val pool = Executors.newFixedThreadPool(2)
        try {
            var issued = 0
            val grant = pool.submit<String?> { guarded.withActiveSession(actor, operation, { 101 }) { issued++; "issued-before-removal" } }
            assertTrue(barrier.held.await(5, TimeUnit.SECONDS), "Real SHARE lock acquired")
            val removal = pool.submit { removeMembership(barrier) }
            assertTrue(barrier.competingIssued.await(5, TimeUnit.SECONDS), "Real competing UPDATE lock query issued")
            assertFalse(removal.isDone)
            barrier.release.countDown()
            assertEquals("issued-before-removal", grant.get(10, TimeUnit.SECONDS)); removal.get(10, TimeUnit.SECONDS)
            assertNull(guarded.withActiveSession(actor, operation, { 102 }) { issued++; "late-secret" })
            assertEquals(1, issued); assertNull(guarded.currentClaim(actor))
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }

    @Test fun admissionQueuedBehindRevocationReadsCommittedMembershipLoss() {
        val barrier = AccessFenceBarrierSource(source)
        val guarded = JdbcSessionRepository(barrier, access)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val removal = pool.submit { removeMembership(barrier) }
            assertTrue(barrier.held.await(5, TimeUnit.SECONDS), "Real UPDATE lock acquired")
            val join = pool.submit<SessionJournalResult> { guarded.begin(actor, operation, channel, session, epoch, { 101 }) }
            assertTrue(barrier.competingIssued.await(5, TimeUnit.SECONDS), "Real competing SHARE lock query issued")
            assertFalse(join.isDone)
            barrier.release.countDown()
            removal.get(10, TimeUnit.SECONDS)
            assertEquals(OperationRejection.ACCESS_DENIED, join.get(10, TimeUnit.SECONDS).rejection)
            assertNull(guarded.currentClaim(actor)); assertNull(guarded.get(actor, operation))
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }

    @Test fun rosterChecksMembershipInsideSamePinnedSnapshotAndFreshRetryDenies() {
        active()
        val barrier = RevisionReadBarrierSource(source)
        val rosters = JdbcRosterRepository(barrier)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val initial = pool.submit<ChannelRosterSnapshot?> { rosters.authorizedSnapshot(actor, channel, access) }
            assertTrue(barrier.readIssued.await(5, TimeUnit.SECONDS), "Actual RR revision SELECT issued before ACL read")
            removeMembership()
            barrier.release.countDown()
            assertEquals(session, initial.get(10, TimeUnit.SECONDS)!!.peers.single().sessionId)
            assertNull(rosters.authorizedSnapshot(actor, channel, access), "New read must not reuse prior permission")
            assertNull(rosters.authorizedSnapshot(id(), channel, access), "Public server is not voice membership")
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }

    private fun begin() { assertNull(repository.begin(actor, operation, channel, session, epoch, { 100 }).rejection) }
    private fun active() { begin(); assertNull(repository.admit(actor, operation, session, epoch, { 100 }, 30_100).rejection) }
    // Fixture ACL mutation only, not the forthcoming atomic member-leave cleanup API.
    private fun removeMembership(data: DataSource = source) = communityTransaction(data, true) { c ->
        c.createStatement().use { it.execute("DELETE FROM nearby_community_members WHERE community_id='$community' AND user_id='$actor'") }
        appendCommunityChange(c, community, owner, "MEMBER_REMOVED", subject = actor)
    }
    private fun ban() = communityTransaction(source, true) { c ->
        c.createStatement().use { it.execute("INSERT INTO nearby_community_bans VALUES ('$community','$actor',101)") }
        appendCommunityChange(c, community, owner, "MEMBER_BANNED", subject = actor)
    }
    private fun schema(): DataSource {
        val name = "session_access_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}

private class AccessFenceBarrierSource(private val delegate: DataSource) : DataSource by delegate {
    val held = CountDownLatch(1); val competingIssued = CountDownLatch(1); val release = CountDownLatch(1)
    private val attempts = AtomicInteger()
    override fun getConnection(): Connection {
        val connection = delegate.connection
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val result = try { method.invoke(connection, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }
            if (method.name == "prepareStatement" && (args?.firstOrNull() as? String)?.contains("FROM nearby_access_fence") == true) {
                val statement = result as PreparedStatement
                Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, call, parameters ->
                    val attempt = if (call.name == "executeQuery") attempts.incrementAndGet() else 0
                    if (attempt == 2) competingIssued.countDown()
                    val rows = try { call.invoke(statement, *(parameters ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }
                    if (attempt == 1) { held.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    rows
                }
            } else result
        } as Connection
    }
}
