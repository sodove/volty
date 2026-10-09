package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelDeletionWorkspaceTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val stranger = "10000000-0000-0000-0000-000000000009"
    private val server = "20000000-0000-0000-0000-000000000002"
    private val otherServer = "20000000-0000-0000-0000-000000000003"
    private val channel = "40000000-0000-0000-0000-000000000004"
    private val otherChannel = "40000000-0000-0000-0000-000000000005"
    private val operation = "50000000-0000-0000-0000-000000000006"
    private val otherOperation = "50000000-0000-0000-0000-000000000007"

    private inner class Ports : CommunityPort, CommunityRemovalPort, ChannelCommandsPort {
        val owners = mutableMapOf(server to actor, otherServer to actor)
        val channels = mutableMapOf(
            server to listOf(CommunityChannel(channel, "Общий", true), CommunityChannel(otherChannel, "Поездки", false)),
            otherServer to listOf(CommunityChannel(otherChannel, "Другой", true)),
        )
        val reads = mutableListOf<String>()
        val issued = mutableListOf<Triple<String, String, String>>()
        var deleteReply: suspend (String, String, String) -> ChannelDeletionCommandResult = { community, child, op ->
            ChannelDeletionCommandResult.Deleted(ChannelDeletionReceipt(op, community, child, 8, false))
        }

        override suspend fun memberships(after: String?, limit: Int) = CommunityResult.Ready(emptyList<CommunitySummary>())
        override suspend fun catalog(query: String, after: String?, limit: Int) = CommunityResult.Ready(emptyList<CommunitySummary>())
        override suspend fun detail(community: String): CommunityResult<CommunityDetails> {
            reads += community
            return CommunityResult.Ready(CommunityDetails(
                1,
                CommunitySummary(community, "Server", "", CommunityVisibility.PRIVATE, 0),
                owners[community] ?: stranger,
                channels[community].orEmpty(),
            ))
        }
        override suspend fun create(community: String, name: String, description: String,
            visibility: CommunityVisibility): CommunityResult<CommunityDetails> = error("No server creation")
        override suspend fun joinPublic(community: String): CommunityResult<CommunityDetails> = error("No join")
        override suspend fun leave(community: String, operation: String, successor: String?): CommunityRemovalResult = error("No departure")
        override suspend fun delete(community: String, operation: String): CommunityRemovalResult = error("No server deletion")
        override suspend fun create(community: String, channel: String, name: String): ChannelCreationCommandResult = error("No channel creation")
        override suspend fun delete(community: String, channel: String, operation: String): ChannelDeletionCommandResult {
            issued += Triple(community, channel, operation)
            return deleteReply(community, channel, operation)
        }
    }

    private inner class Fixture(test: TestScope) {
        private val testScope = test
        val screen = SupervisorJob(test.coroutineContext[Job])
        val account = SupervisorJob(test.coroutineContext[Job])
        val scope = CoroutineScope(test.coroutineContext + screen)
        val ports = Ports()
        val workspace = CommunityWorkspace(scope, account, ports, ports) { error("No server operation ID") }
        private val operationIds = listOf(operation, otherOperation)
        var generatedIds = 0
        val deletion = ChannelDeletionWorkspace(scope, account, actor, workspace, ports) {
            operationIds[generatedIds++]
        }

        suspend fun open(community: String = server) {
            workspace.browser.openCommunity(community)
            testScope.runCurrent()
        }

        suspend fun close() {
            deletion.close()
            workspace.close()
            screen.cancel()
            account.cancel()
        }
    }

    @Test fun delete_requires_fresh_owner_detail_and_a_listed_channel_before_generating_id() = runTest {
        val f = Fixture(this)
        try {
            assertFalse(f.deletion.delete(server, channel))
            f.workspace.browser.openCommunity(server)
            assertFalse(f.deletion.delete(server, channel)) // Selection exists, but its detail is still loading.
            runCurrent()

            f.ports.owners[server] = stranger
            f.open()
            assertFalse(f.deletion.delete(server, channel))

            f.ports.owners[server] = actor
            f.ports.channels[server] = listOf(CommunityChannel(otherChannel, "Поездки", false))
            f.open()
            assertFalse(f.deletion.delete(server, channel))

            assertEquals(0, f.generatedIds)
            assertTrue(f.ports.issued.isEmpty())
        } finally { f.close(); runCurrent() }
    }

    @Test fun retry_reuses_target_operation_and_another_channel_gets_its_own_id() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            f.open()
            var first = true
            f.ports.deleteReply = { community, child, op ->
                if (child == channel && first) {
                    first = false
                    release.await()
                    throw CancellationException("Live producer stopped")
                } else ChannelDeletionCommandResult.Deleted(ChannelDeletionReceipt(op, community, child, 0, true))
            }

            assertTrue(f.deletion.delete(server, channel))
            runCurrent()
            assertTrue(f.deletion.state.value.busy)
            assertFalse(f.deletion.delete(server, channel))
            assertEquals(listOf(Triple(server, channel, operation)), f.ports.issued)

            release.complete(Unit)
            runCurrent()
            assertEquals(ChannelCommandFailure.UNAVAILABLE, f.deletion.state.value.error)

            assertTrue(f.deletion.delete(server, otherChannel))
            runCurrent()
            assertEquals(otherOperation, f.deletion.state.value.deleted?.operationId)
            assertTrue(f.deletion.state.value.deleted?.cleanupPending == true)

            // The earlier uncertain target still reuses its original id after another target succeeds.
            assertTrue(f.deletion.delete(server, channel))
            runCurrent()
            assertEquals(listOf(
                Triple(server, channel, operation),
                Triple(server, otherChannel, otherOperation),
                Triple(server, channel, operation),
            ), f.ports.issued)
            assertEquals(2, f.generatedIds)
            assertEquals(0L, f.deletion.state.value.deleted?.revision)
            assertTrue(f.ports.reads.count { it == server } >= 3) // Initial detail plus one refresh per success.
        } finally { release.complete(Unit); f.close(); runCurrent() }
    }

    @Test fun foreign_receipt_identity_or_negative_revision_cannot_publish_or_refresh() = runTest {
        val f = Fixture(this)
        try {
            f.open()
            val badReceipts = listOf(
                ChannelDeletionReceipt(otherOperation, server, channel, 8, false),
                ChannelDeletionReceipt(operation, otherServer, channel, 8, false),
                ChannelDeletionReceipt(operation, server, otherChannel, 8, false),
                ChannelDeletionReceipt(operation, server, channel, -1, false),
            )
            for (receipt in badReceipts) {
                f.ports.deleteReply = { _, _, _ -> ChannelDeletionCommandResult.Deleted(receipt) }
                assertTrue(f.deletion.delete(server, channel))
                runCurrent()
                assertEquals(ChannelCommandFailure.INVALID_RESPONSE, f.deletion.state.value.error)
                assertNull(f.deletion.state.value.deleted)
            }
            assertEquals(List(4) { Triple(server, channel, operation) }, f.ports.issued)
            assertEquals(1, f.generatedIds)
            assertEquals(listOf(server), f.ports.reads)
        } finally { f.close(); runCurrent() }
    }

    @Test fun held_delete_result_cannot_repaint_or_refresh_a_replacement_server() = runTest {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var returned = false
        f.ports.deleteReply = { community, child, op ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                returned = true
                ChannelDeletionCommandResult.Deleted(ChannelDeletionReceipt(op, community, child, 9, false))
            }
        }
        try {
            f.open()
            assertTrue(f.deletion.delete(server, channel))
            runCurrent()
            entered.await()
            f.open(otherServer)
            release.complete(Unit)
            runCurrent()

            assertTrue(returned)
            assertEquals(otherServer, f.workspace.browser.state.value.selectedCommunity)
            assertEquals(ChannelDeletionState(), f.deletion.state.value)
            assertEquals(listOf(server, otherServer), f.ports.reads)
            assertEquals(listOf(Triple(server, channel, operation)), f.ports.issued)
        } finally { release.complete(Unit); f.close(); runCurrent() }
    }

    @Test fun held_delete_result_after_ownership_loss_is_suppressed() = runTest {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var returned = false
        f.ports.deleteReply = { community, child, op ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                returned = true
                ChannelDeletionCommandResult.Deleted(ChannelDeletionReceipt(op, community, child, 9, false))
            }
        }
        try {
            f.open()
            assertTrue(f.deletion.delete(server, channel))
            runCurrent()
            entered.await()
            f.ports.owners[server] = stranger
            f.open()
            release.complete(Unit)
            runCurrent()

            assertTrue(returned)
            assertEquals(ChannelDeletionState(), f.deletion.state.value)
            assertEquals(listOf(server, server), f.ports.reads)
            assertEquals(listOf(Triple(server, channel, operation)), f.ports.issued)
        } finally { release.complete(Unit); f.close(); runCurrent() }
    }

    @Test fun account_cancellation_with_held_delete_withdraws_state_before_write_returns() = runTest {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var returned = false
        f.ports.deleteReply = { community, child, op ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                returned = true
                ChannelDeletionCommandResult.Deleted(ChannelDeletionReceipt(op, community, child, 9, false))
            }
        }
        try {
            f.open()
            assertTrue(f.deletion.delete(server, channel))
            runCurrent()
            entered.await()
            f.account.cancel()
            runCurrent()

            assertEquals(ChannelDeletionState(), f.deletion.state.value)
            assertFalse(f.deletion.delete(server, channel))
            release.complete(Unit)
            runCurrent()
            assertTrue(returned)
            assertEquals(ChannelDeletionState(), f.deletion.state.value)
            assertTrue(f.screen.isActive)
            assertFalse(f.workspace.active)
        } finally { release.complete(Unit); f.close(); runCurrent() }
    }
}
