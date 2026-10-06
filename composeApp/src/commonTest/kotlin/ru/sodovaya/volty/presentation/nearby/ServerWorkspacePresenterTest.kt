package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ServerWorkspacePresenterTest {
    private interface Ports: CommunityPort, CommunityRemovalPort, ChannelCommandsPort
    private val server="00000000-0000-0000-0000-000000000001"
    private val channel="00000000-0000-0000-0000-000000000002"
    private val summary=CommunitySummary(server,"Riders","",CommunityVisibility.PUBLIC,1)
    private val details=CommunityDetails(1,summary,server,listOf(CommunityChannel(channel,"Общий",true)))
    private inner class Fixture(test: TestScope) {
        val account=SupervisorJob(test.coroutineContext[Job]);val screen=SupervisorJob(test.coroutineContext[Job])
        val scope=CoroutineScope(test.coroutineContext+screen)
        val issued=mutableListOf<String>();val connected=mutableListOf<String>()
        val catalogAfter=mutableListOf<String?>()
        var catalogPage=listOf(summary)
        var reply: suspend ()->CommunityResult<CommunityDetails> = {CommunityResult.Ready(details)}
        var channelReply: suspend (String,String,String)->ChannelCreationCommandResult = { community,id,name ->
            ChannelCreationCommandResult.Created(ChannelCreationReceipt(community,id,name,2))
        }
        val ports: Ports=object : Ports {
            override suspend fun create(community: String,channel: String,name: String): ChannelCreationCommandResult {
                issued+="channel:$community:$channel:$name";return channelReply(community,channel,name)
            }
            override suspend fun delete(community: String,channel: String,operation: String): ChannelDeletionCommandResult = error("unexpected channel deletion")
            override suspend fun memberships(after: String?,limit: Int): CommunityResult<List<CommunitySummary>> {issued+="mine";return CommunityResult.Ready(listOf(summary))}
            override suspend fun catalog(query: String,after: String?,limit: Int): CommunityResult<List<CommunitySummary>> {
                issued+="catalog:$query";catalogAfter+=after;return CommunityResult.Ready(catalogPage)
            }
            override suspend fun detail(community: String)=reply()
            override suspend fun create(community: String,name: String,description: String,visibility: CommunityVisibility): CommunityResult<CommunityDetails> {
                issued+="create:$name:$visibility";return reply()
            }
            override suspend fun joinPublic(community: String): CommunityResult<CommunityDetails> {issued+="join:$community";return reply()}
            override suspend fun leave(community: String,operation: String,successor: String?): CommunityRemovalResult {issued+="leave";return CommunityRemovalResult.Removed(CommunityRemovalReceipt(operation,community,2,false))}
            override suspend fun delete(community: String,operation: String): CommunityRemovalResult {issued+="delete";return CommunityRemovalResult.Removed(CommunityRemovalReceipt(operation,community,2,false))}
        }
        val workspace=CommunityWorkspace(scope,account,ports,ports) {server}
        val creation=ChannelCreationWorkspace(scope,account,server,workspace,ports) {"00000000-0000-0000-0000-000000000003"}
        val presenter=ServerWorkspacePresenter(scope,workspace,server,creation,connected::add)
        suspend fun close() {presenter.close();screen.cancel();account.cancel()}
    }
    @Test fun ownerCreatesTrimmedChannelOnceWithoutStartingVoice()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>()
        f.channelReply={community,id,name -> held.await();ChannelCreationCommandResult.Created(ChannelCreationReceipt(community,id,name,2))}
        try {
            f.presenter.openServer(server);runCurrent();f.presenter.openChannelCreation()
            f.presenter.setChannelName("  Поездка  ");f.presenter.submitChannelCreation();runCurrent()
            assertEquals(listOf("channel:$server:00000000-0000-0000-0000-000000000003:Поездка"),f.issued)
            assertTrue(f.presenter.state.value.channelCreation.busy)
            f.presenter.submitChannelCreation();runCurrent();assertEquals(1,f.issued.size)
            held.complete(Unit);runCurrent();assertFalse(f.presenter.state.value.channelForm.visible)
            assertEquals("Поездка",f.presenter.state.value.channelCreation.created?.name)
            assertTrue(f.connected.isEmpty());assertEquals(details.channels,f.presenter.state.value.browser.detail?.channels)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun queuedChannelSubmitCannotTargetReplacementServer()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openServer(server);runCurrent();f.presenter.openChannelCreation();f.presenter.setChannelName("Ride")
            runCurrent();assertTrue(f.presenter.state.value.channelForm.visible)
            assertEquals(server,f.presenter.state.value.channelForm.community)
            f.presenter.submitChannelCreation()
            f.workspace.browser.openCommunity("00000000-0000-0000-0000-000000000004")
            runCurrent();assertTrue(f.issued.isEmpty());assertFalse(f.presenter.state.value.channelForm.visible)
        } finally {f.close();runCurrent()}
    }
    @Test fun missingAuthorityCannotOpenChannelFormAndAccountRetirementClearsDraft()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openChannelCreation();runCurrent();assertFalse(f.presenter.state.value.channelForm.visible)
            f.presenter.openServer(server);runCurrent();f.presenter.openChannelCreation();f.presenter.setChannelName("Private")
            runCurrent();assertTrue(f.presenter.state.value.channelForm.visible)
            f.account.cancel();runCurrent();assertEquals(ServerWorkspaceUiState(),f.presenter.state.value);assertTrue(f.screen.isActive)
        } finally {f.close();runCurrent()}
    }
    @Test fun heldChannelCreationAllowsBackAndLateReplyCannotReopenServer()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.channelReply={community,id,name -> withContext(NonCancellable) {held.await()};returned=true
            ChannelCreationCommandResult.Created(ChannelCreationReceipt(community,id,name,2))}
        try {
            f.presenter.openServer(server);runCurrent();f.presenter.openChannelCreation();f.presenter.setChannelName("Ride")
            f.presenter.submitChannelCreation();runCurrent();assertEquals(1,f.issued.size)
            f.presenter.requestDelete();f.presenter.back();runCurrent()
            assertEquals(ServerWorkspaceSection.MY_SERVERS,f.presenter.state.value.section)
            assertNull(f.presenter.state.value.commands.confirmation);assertFalse(f.presenter.state.value.channelForm.visible)
            held.complete(Unit);runCurrent();assertTrue(returned)
            assertNull(f.presenter.state.value.browser.selectedCommunity);assertNull(f.presenter.state.value.channelCreation.created)
            assertTrue(f.connected.isEmpty());assertTrue(f.account.isActive)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun nonOwnerCannotOpenFormAndHeldReplyAfterCloseCannotRepaint()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        try {
            f.reply={CommunityResult.Ready(details.copy(ownerId=channel))}
            f.presenter.openServer(server);runCurrent();f.presenter.openChannelCreation();runCurrent()
            assertFalse(f.presenter.state.value.channelForm.visible);assertTrue(f.issued.isEmpty())
            f.reply={CommunityResult.Ready(details)};f.presenter.refresh();runCurrent()
            f.channelReply={community,id,name -> withContext(NonCancellable) {held.await()};returned=true
                ChannelCreationCommandResult.Created(ChannelCreationReceipt(community,id,name,2))}
            f.presenter.openChannelCreation();f.presenter.setChannelName("Ride");f.presenter.submitChannelCreation();runCurrent()
            assertEquals(1,f.issued.size);f.presenter.close();held.complete(Unit);runCurrent()
            assertTrue(returned);assertEquals(ServerWorkspaceUiState(),f.presenter.state.value)
            assertTrue(f.account.isActive);assertTrue(f.screen.isActive);assertTrue(f.connected.isEmpty())
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun refreshedLossOfOwnershipWithdrawsOpenPrivateDraft()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openServer(server);runCurrent();f.presenter.openChannelCreation();f.presenter.setChannelName("Private")
            runCurrent();assertTrue(f.presenter.state.value.channelForm.visible)
            f.reply={CommunityResult.Ready(details.copy(ownerId=channel))}
            f.presenter.refresh();runCurrent()
            assertEquals(ChannelCreationForm(),f.presenter.state.value.channelForm)
            f.presenter.submitChannelCreation();runCurrent();assertTrue(f.issued.isEmpty())
        } finally {f.close();runCurrent()}
    }
    @Test fun catalogJoinOpensServerWithoutStartingVoiceAndBackRestoresCatalog()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openCatalog();runCurrent();assertTrue("catalog:" in f.issued)
            f.presenter.joinPublic(server);runCurrent()
            assertEquals(ServerWorkspaceSection.SERVER,f.presenter.state.value.section)
            assertEquals(server,f.presenter.state.value.browser.detail?.community?.id)
            assertTrue(f.connected.isEmpty())
            f.presenter.back();runCurrent()
            assertEquals(ServerWorkspaceSection.CATALOG,f.presenter.state.value.section)
            assertNull(f.presenter.state.value.browser.selectedCommunity)
        } finally {f.close();runCurrent()}
    }
    @Test fun privateCreateIsDefaultAndFormCannotChangeWhileActualProducerWaits()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>()
        f.reply={withContext(NonCancellable) {held.await()};CommunityResult.Ready(details)}
        try {
            f.presenter.openCreate();f.presenter.setName("Riders");f.presenter.submitCreate();runCurrent()
            assertEquals(listOf("create:Riders:PRIVATE"),f.issued)
            f.presenter.setName("Different");f.presenter.setVisibility(CommunityVisibility.PUBLIC);f.presenter.submitCreate();runCurrent()
            assertEquals("Riders",f.presenter.state.value.draft.name)
            assertEquals(CommunityVisibility.PRIVATE,f.presenter.state.value.draft.visibility)
            assertEquals(1,f.issued.size);assertTrue(f.connected.isEmpty())
            held.complete(Unit);runCurrent();assertEquals(ServerWorkspaceSection.SERVER,f.presenter.state.value.section)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun channelCommandRequiresLoadedExactServerAndActualListedChannel()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.connect(channel);assertTrue(f.connected.isEmpty())
            f.presenter.openServer(server);runCurrent()
            f.presenter.connect(server);assertTrue(f.connected.isEmpty())
            f.presenter.connect(channel);assertEquals(listOf(channel),f.connected)
            f.account.cancel();f.presenter.connect(channel);runCurrent()
            assertEquals(listOf(channel),f.connected)
        } finally {f.close();runCurrent()}
    }
    @Test fun cancelledScreenResetsFormAndLateReturnedJoinCannotNavigateOrConnect()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.reply={withContext(NonCancellable) {held.await()};returned=true;CommunityResult.Ready(details)}
        try {
            f.presenter.openCatalog();f.presenter.joinPublic(server);runCurrent();assertTrue("join:$server" in f.issued)
            f.screen.cancel();runCurrent();held.complete(Unit);runCurrent()
            assertTrue(returned);assertEquals(ServerWorkspaceUiState(),f.presenter.state.value)
            f.presenter.connect(channel);assertTrue(f.connected.isEmpty());assertTrue(f.account.isActive)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun serverDepartureIsSeparateConfirmedMutationAndNeverConnectsVoice()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openServer(server);runCurrent();f.presenter.requestLeave();runCurrent()
            assertNotNull(f.presenter.state.value.commands.confirmation);assertTrue(f.issued.isEmpty())
            f.presenter.dismissRemoval();runCurrent();assertNull(f.presenter.state.value.commands.confirmation)
            f.presenter.requestLeave();runCurrent();f.presenter.confirmRemoval();runCurrent()
            assertTrue("leave" in f.issued);assertFalse("delete" in f.issued);assertTrue(f.connected.isEmpty())
            assertEquals(ServerWorkspaceSection.MY_SERVERS,f.presenter.state.value.section)
        } finally {f.close();runCurrent()}
    }
    @Test fun retiredAccountClearsPrivateFormAndIdentityWithoutClosingBorrowedScreen()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openCreate();f.presenter.setName("Private server");runCurrent()
            assertEquals("Private server",f.presenter.state.value.draft.name)
            assertEquals(server,f.presenter.state.value.actorId)
            f.account.cancel();runCurrent()
            assertEquals(ServerWorkspaceUiState(),f.presenter.state.value)
            f.presenter.setName("Late");f.presenter.submitCreate();runCurrent()
            assertEquals(ServerWorkspaceUiState(),f.presenter.state.value);assertTrue(f.issued.isEmpty());assertTrue(f.screen.isActive)
        } finally {f.close();runCurrent()}
    }
    @Test fun catalogSidePanelKeepsItsOwnPaginationAfterServerSelection()=runTest {
        val f=Fixture(this)
        f.catalogPage=(1..20).map {i -> summary.copy(id="00000000-0000-0000-0000-${i.toString().padStart(12,'0')}")}
        try {
            f.presenter.openCatalog();runCurrent();assertTrue(f.presenter.state.value.browser.catalog.hasMore)
            f.presenter.joinPublic(server);runCurrent();assertEquals(ServerWorkspaceSection.SERVER,f.presenter.state.value.section)
            val mineReads=f.issued.count {it=="mine"}
            f.catalogPage=emptyList();f.presenter.loadMore();runCurrent()
            assertEquals(listOf(null,"00000000-0000-0000-0000-000000000020"),f.catalogAfter)
            assertEquals(mineReads,f.issued.count {it=="mine"})
            assertFalse(f.presenter.state.value.browser.catalog.hasMore)
        } finally {f.close();runCurrent()}
    }
}
