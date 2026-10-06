package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import ru.sodovaya.volty.domain.nearby.community.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelCreationWorkspaceTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val server = "20000000-0000-0000-0000-000000000002"
    private val other = "30000000-0000-0000-0000-000000000003"
    private val channel = "40000000-0000-0000-0000-000000000004"
    private inner class Ports : CommunityPort, CommunityRemovalPort, ChannelCommandsPort {
        var owner = actor
        val reads = mutableListOf<String>()
        val issued = mutableListOf<String>()
        var reply: suspend () -> ChannelCreationCommandResult = {
            ChannelCreationCommandResult.Created(ChannelCreationReceipt(server, channel, "Ride", 2)) }
        override suspend fun memberships(after: String?, limit: Int) = CommunityResult.Ready(emptyList<CommunitySummary>())
        override suspend fun catalog(query: String, after: String?, limit: Int) = CommunityResult.Ready(emptyList<CommunitySummary>())
        override suspend fun detail(community: String): CommunityResult<CommunityDetails> {
            reads += community
            return CommunityResult.Ready(CommunityDetails(1,CommunitySummary(community,"Server","",CommunityVisibility.PRIVATE,0),owner,emptyList()))
        }
        override suspend fun create(community: String,name: String,description: String,visibility: CommunityVisibility):CommunityResult<CommunityDetails> = error("No server creation")
        override suspend fun joinPublic(community: String):CommunityResult<CommunityDetails> = error("No join")
        override suspend fun leave(community: String,operation: String,successor: String?):CommunityRemovalResult = error("No departure")
        override suspend fun delete(community: String,operation: String):CommunityRemovalResult = error("No server deletion")
        override suspend fun create(community: String,channel: String,name: String):ChannelCreationCommandResult {
            issued += "$community:$channel:$name";return reply()
        }
        override suspend fun delete(community: String,channel: String,operation: String):ChannelDeletionCommandResult = error("No channel deletion")
    }
    private inner class Fixture(test: TestScope) {
        val screen = SupervisorJob(test.coroutineContext[Job]);val account = SupervisorJob(test.coroutineContext[Job])
        val scope = CoroutineScope(test.coroutineContext + screen);val ports = Ports();var ids = 0
        val workspace = CommunityWorkspace(scope,account,ports,ports) { error("No community entropy") }
        val creation = ChannelCreationWorkspace(scope,account,actor,workspace,ports) { ids++;channel }
        suspend fun close() {creation.close();workspace.close();screen.cancel();account.cancel()}
    }
    @Test fun uncertain_retry_keeps_actual_channel_id_and_blocks_double_submit()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>()
        f.ports.reply={release.await();ChannelCreationCommandResult.Failed(ChannelCommandFailure.UNAVAILABLE)}
        try {
            f.workspace.browser.openCommunity(server);runCurrent()
            assertTrue(f.creation.create("  Ride  "));runCurrent()
            assertFalse(f.creation.create("Ride"));assertEquals(listOf("$server:$channel:Ride"),f.ports.issued)
            release.complete(Unit);runCurrent();assertEquals(ChannelCommandFailure.UNAVAILABLE,f.creation.state.value.error)
            f.ports.reply={ChannelCreationCommandResult.Created(ChannelCreationReceipt(server,channel,"Ride",2))}
            assertTrue(f.creation.create("Ride"));runCurrent()
            assertEquals(1,f.ids);assertEquals(f.ports.issued.first(),f.ports.issued.last())
            assertEquals(channel,f.creation.state.value.created?.channelId)
            assertEquals(listOf(server,server),f.ports.reads)
            assertTrue(f.account.isActive);assertTrue(f.workspace.active)
        } finally {release.complete(Unit);f.close();runCurrent()}
    }
    @Test fun missing_detail_nonowner_and_invalid_name_refuse_before_entropy()=runTest {
        val f=Fixture(this)
        try {
            assertFalse(f.creation.create("Ride"));assertEquals(0,f.ids)
            f.ports.owner=other;f.workspace.browser.openCommunity(server);runCurrent()
            assertFalse(f.creation.create("Ride"));assertEquals(0,f.ids)
            f.ports.owner=actor;f.workspace.browser.openCommunity(server);runCurrent()
            assertFalse(f.creation.create("\n"));assertEquals(0,f.ids);assertTrue(f.ports.issued.isEmpty())
            assertTrue(f.creation.state.value.invalidInput)
        } finally {f.close();runCurrent()}
    }
    @Test fun foreign_receipt_cannot_refresh_or_publish_created_channel()=runTest {
        val f=Fixture(this)
        f.ports.reply={ChannelCreationCommandResult.Created(ChannelCreationReceipt(other,channel,"Ride",2))}
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create("Ride"));runCurrent()
            assertEquals(listOf("$server:$channel:Ride"),f.ports.issued)
            assertEquals(ChannelCommandFailure.INVALID_RESPONSE,f.creation.state.value.error)
            assertNull(f.creation.state.value.created);assertEquals(listOf(server),f.ports.reads)
            for(receipt in listOf(ChannelCreationReceipt(server,other,"Ride",2),
                ChannelCreationReceipt(server,channel,"Foreign name",2),ChannelCreationReceipt(server,channel,"Ride",-1),
                ChannelCreationReceipt(server,channel,"Ride",0))) {
                f.ports.reply={ChannelCreationCommandResult.Created(receipt)}
                assertTrue(f.creation.create("Ride"));runCurrent()
                assertEquals(ChannelCommandFailure.INVALID_RESPONSE,f.creation.state.value.error)
                assertNull(f.creation.state.value.created);assertEquals(listOf(server),f.ports.reads)
            }
            assertEquals(5,f.ports.issued.size);assertEquals(1,f.ids)
        } finally {f.close();runCurrent()}
    }
    @Test fun held_original_reply_never_refreshes_replacement_selection()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>();var returned=false
        f.ports.reply={withContext(NonCancellable) {release.await();returned=true
            ChannelCreationCommandResult.Created(ChannelCreationReceipt(server,channel,"Ride",2))}}
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create("Ride"));runCurrent()
            assertEquals(1,f.ports.issued.size)
            f.workspace.browser.openCommunity(other);runCurrent();release.complete(Unit);runCurrent()
            assertTrue(returned);assertEquals(other,f.workspace.browser.state.value.selectedCommunity)
            assertEquals(listOf(server,other),f.ports.reads);assertNull(f.creation.state.value.created)
        } finally {release.complete(Unit);f.close();runCurrent()}
    }
    @Test fun earned_success_is_withdrawn_when_user_selects_another_server()=runTest {
        val f=Fixture(this)
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create("Ride"));runCurrent()
            assertEquals(channel,f.creation.state.value.created?.channelId)
            assertEquals(listOf("$server:$channel:Ride"),f.ports.issued)
            f.workspace.browser.openCommunity(other);runCurrent()
            assertEquals(ChannelCreationState(),f.creation.state.value)
            assertEquals(other,f.workspace.browser.state.value.selectedCommunity)
            assertTrue(f.workspace.active);assertTrue(f.account.isActive)
        } finally {f.close();runCurrent()}
    }
    @Test fun closing_consumer_fences_late_write_without_closing_borrowed_owners()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>();var returned=false
        f.ports.reply={withContext(NonCancellable) {release.await();returned=true
            ChannelCreationCommandResult.Created(ChannelCreationReceipt(server,channel,"Ride",2))}}
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create("Ride"));runCurrent()
            assertEquals(1,f.ports.issued.size);f.creation.close();release.complete(Unit);runCurrent()
            assertTrue(returned);assertEquals(ChannelCreationState(),f.creation.state.value)
            assertTrue(f.workspace.active);assertTrue(f.account.isActive);assertTrue(f.screen.isActive)
            assertEquals(server,f.workspace.browser.state.value.selectedCommunity)
            assertFalse(f.creation.create("Another"));assertEquals(1,f.ports.issued.size)
        } finally {release.complete(Unit);f.close();runCurrent()}
    }
    @Test fun foreign_account_or_screen_cannot_borrow_workspace_even_for_same_actor()=runTest {
        val f=Fixture(this);val foreign=SupervisorJob(coroutineContext[Job])
        try {
            assertFailsWith<IllegalArgumentException> {ChannelCreationWorkspace(f.scope,foreign,actor,f.workspace,f.ports) {error("No entropy")}}
            assertFailsWith<IllegalArgumentException> {ChannelCreationWorkspace(CoroutineScope(coroutineContext+foreign),f.account,actor,f.workspace,f.ports) {error("No entropy")}}
            assertTrue(f.account.isActive);assertTrue(f.screen.isActive);assertTrue(f.workspace.active)
        } finally {foreign.cancel();f.close();runCurrent()}
    }
    @Test fun workspace_retirement_fences_held_channel_write_without_retiring_account()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>();var returned=false
        f.ports.reply={withContext(NonCancellable) {release.await();returned=true
            ChannelCreationCommandResult.Created(ChannelCreationReceipt(server,channel,"Ride",2))}}
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create("Ride"));runCurrent()
            assertEquals(1,f.ports.issued.size)
            f.workspace.close();runCurrent();assertEquals(ChannelCreationState(),f.creation.state.value)
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(ChannelCreationState(),f.creation.state.value);assertTrue(f.account.isActive);assertTrue(f.screen.isActive)
        } finally {release.complete(Unit);f.close();runCurrent()}
    }
    @Test fun account_cancellation_with_held_child_withdraws_before_actual_write_returns()=runTest {
        val f=Fixture(this);val release=CompletableDeferred<Unit>();val childRelease=CompletableDeferred<Unit>();var returned=false
        f.ports.reply={withContext(NonCancellable) {release.await();returned=true
            ChannelCreationCommandResult.Created(ChannelCreationReceipt(server,channel,"Ride",2))}}
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create("Ride"));runCurrent()
            assertEquals(1,f.ports.issued.size)
            CoroutineScope(f.scope.coroutineContext+f.account).launch(start=CoroutineStart.UNDISPATCHED) {withContext(NonCancellable) {childRelease.await()}}
            f.account.cancel();runCurrent();assertFalse(f.account.isCompleted)
            assertEquals(ChannelCreationState(),f.creation.state.value);assertFalse(f.creation.create("Another"))
            release.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(ChannelCreationState(),f.creation.state.value);assertTrue(f.screen.isActive)
        } finally {release.complete(Unit);childRelease.complete(Unit);f.close();runCurrent()}
    }
}
