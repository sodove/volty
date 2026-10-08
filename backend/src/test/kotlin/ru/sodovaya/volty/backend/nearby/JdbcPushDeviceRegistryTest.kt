package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.sql.SQLException
import kotlin.test.*

@Tag("postgres")
class JdbcPushDeviceRegistryTest {
    private val a = id(); private val b = id(); private val installation = id()
    private val key = "a".repeat(64); private val binding = id()
    private val schema = "push_devices_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let { base ->
        base.connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        NearbyPostgresFixture.source(schema)
    }
    private val registry = JdbcPushDeviceRegistry(source)

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use { c -> c.createStatement().use { s ->
            for (actor in listOf(a, b)) s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
        } }
    }

    @Test fun registrationReplayAndOtherDevicesAreIndependent() {
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(command(), 100))
        assertEquals(PushDeviceDecision.REPLAY, registry.apply(command(), 101))
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(command(device = id(), bindingId = id(), token = "other-token"), 102))
        assertEquals(setOf("token-1", "other-token"), registry.targets(a).map { it.token }.toSet())
        val first = registry.targets(a, 1).single()
        assertEquals(1, registry.targets(a, 1, first.installationId).size)
        assertTrue(registry.targets(b).isEmpty())
    }

    @Test fun delayedOldRegistrationAndLogoutCannotReplaceNewAccount() {
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(command(), 100))
        val next = command(actor = b, generation = 3, bindingId = id())
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(next, 102))
        assertEquals(PushDeviceDecision.STALE, registry.apply(command(), 103))
        assertEquals(PushDeviceDecision.STALE, registry.apply(command(generation = 2, token = null), 104))
        assertTrue(registry.targets(a).isEmpty())
        assertEquals(next.bindingId, registry.targets(b).single().bindingId)
    }

    @Test fun installationCapabilityAndBindingIdentityAreRequired() {
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(command(), 100))
        assertEquals(PushDeviceDecision.ACCESS_DENIED, registry.apply(command(actor = b, generation = 5, secret = "b".repeat(64)), 101))
        assertEquals(PushDeviceDecision.ACCESS_DENIED, registry.apply(command(actor = b, generation = 5, token = null), 102))
        assertEquals(PushDeviceDecision.STALE, registry.apply(command(bindingId = id()), 103))
        assertEquals("token-1", registry.targets(a).single().token)
    }

    @Test fun lateTransportRetirementCannotRemoveNewTokenAndReplayCannotReviveRetiredToken() {
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(command(), 100))
        val old = registry.targets(a).single()
        val next = command(generation = 2, bindingId = id(), token = "token-2")
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(next, 101))
        assertFalse(registry.retire(old, 102))
        assertEquals("token-2", registry.targets(a).single().token)
        val current = registry.targets(a).single()
        assertTrue(registry.retire(current, 103))
        assertFalse(registry.retire(current, 104))
        assertEquals(PushDeviceDecision.STALE, registry.apply(next, 105))
        assertTrue(registry.targets(a).isEmpty())
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(command(generation = 3, token = "token-3"), 106))
        assertEquals("token-3", registry.targets(a).single().token)
    }

    @Test fun logoutBeforeDelayedFirstRegistrationLeavesGenerationTombstone() {
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(command(generation = 2, token = null), 100))
        assertEquals(PushDeviceDecision.STALE, registry.apply(command(), 101))
        assertEquals(PushDeviceDecision.REPLAY, registry.apply(command(generation = 2, token = null), 102))
        assertTrue(registry.targets(a).isEmpty())
    }

    @Test fun currentRevocationAndDeletionFilterTargetsAndRejectOldAuthority() {
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(command(), 100))
        updateUser("tokens_revoked_at=20", a)
        assertTrue(registry.targets(a).isEmpty())
        assertEquals(PushDeviceDecision.ACCESS_DENIED, registry.apply(command(generation = 2), 101))
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(command(generation = 3, issued = 21), 102))
        assertEquals(1, registry.targets(a).size)
        updateUser("deleted_at=103", a)
        assertTrue(registry.targets(a).isEmpty())
        assertEquals(PushDeviceDecision.ACCESS_DENIED, registry.apply(command(generation = 4, issued = 21), 104))
    }

    @Test fun sameTokenCannotBeClaimedByAnotherInstallation() {
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(command(), 100))
        assertEquals(PushDeviceDecision.TOKEN_CONFLICT, registry.apply(command(actor = b, device = id(), bindingId = id()), 101))
        assertEquals(1, registry.targets(a).size)
        assertTrue(registry.targets(b).isEmpty())
    }

    @Test fun concurrentlyArrivingGenerationsCannotLeaveOlderAccountBound() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val next = command(actor = b, generation = 2, bindingId = id())
            val results = listOf(command(), next).map { request -> executor.submit<PushDeviceDecision> { registry.apply(request, 100) } }
                .map { it.get(15, TimeUnit.SECONDS) }
            assertTrue(results.all { it in setOf(PushDeviceDecision.APPLIED, PushDeviceDecision.STALE) })
            assertTrue(registry.targets(a).isEmpty())
            assertEquals(next.bindingId, registry.targets(b).singleOrNull()?.bindingId)
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS)) }
    }

    @Test fun invalidCapabilityGenerationAndOpaqueTokenHaveNoMutation() {
        assertEquals(PushDeviceDecision.INVALID, registry.apply(command(secret = "short"), 100))
        assertEquals(PushDeviceDecision.INVALID, registry.apply(command(generation = 0), 100))
        assertEquals(PushDeviceDecision.INVALID, registry.apply(command(token = "bad\ntoken"), 100))
        assertEquals(PushDeviceDecision.INVALID, registry.apply(command(token = "x".repeat(4097)), 100))
        assertTrue(registry.targets(a).isEmpty())
    }

    @Test fun databaseFailureRollsBackBindingAndCannotExposeTokenInLoggedException() {
        assertEquals(PushDeviceDecision.APPLIED, registry.apply(command(), 100))
        source.connection.use { c -> c.createStatement().use {
            it.execute("ALTER TABLE nearby_push_devices ADD CONSTRAINT inject_failure CHECK(FALSE) NOT VALID")
        } }
        val privateToken = "never-log-this-private-token"
        val failure = assertFailsWith<SQLException> { registry.apply(command(generation = 2, token = privateToken), 101) }
        assertFalse(failure.toString().contains(privateToken))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertEquals("token-1", registry.targets(a).single().token)
    }

    private fun command(actor: String = a, generation: Long = 1, bindingId: String = binding,
                        token: String? = "token-1", device: String = installation, secret: String = key,
                        issued: Long = 10) = PushDeviceRegistration(actor, issued, device, secret, generation, bindingId, token)
    private fun updateUser(expression: String, actor: String) = source.connection.use { c -> c.createStatement().use { it.execute("UPDATE users SET $expression WHERE id='$actor'") } }
    private fun id() = UUID.randomUUID().toString()
}
