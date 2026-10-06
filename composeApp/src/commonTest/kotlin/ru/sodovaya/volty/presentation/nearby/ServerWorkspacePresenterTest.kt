package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ServerWorkspacePresenterTest {
    private interface Ports: CommunityPort, CommunityRemovalPort, ChannelCommandsPort, InvitationPort
    private val server="00000000-0000-0000-0000-000000000001"
    private val channel="00000000-0000-0000-0000-000000000002"
    private val summary=CommunitySummary(server,"Riders","",CommunityVisibility.PUBLIC,1)
    private val details=CommunityDetails(1,summary,server,listOf(CommunityChannel(channel,"Общий",true)))
    private inner class Fixture(test: TestScope) {
        val account=SupervisorJob(test.coroutineContext[Job]);val screen=SupervisorJob(test.coroutineContext[Job])
        val scope=CoroutineScope(test.coroutineContext+screen)
        val issued=mutableListOf<String>();val connected=mutableListOf<String>()
        val catalogAfter=mutableListOf<String?>()
        val inviteRequests=mutableListOf<InvitationCreationDraft>()
        val savedInvites=mutableMapOf<String,InvitationCreationDraft>()
        val acceptRequests=mutableListOf<Pair<String,InvitationSecret>>()
        var acceptReply:suspend (String)->InvitationAcceptanceCommandResult={operation ->
            InvitationAcceptanceCommandResult.Accepted(InvitationAcceptanceReceipt(operation,channel,server,1,true))}
        var inviteReply:suspend (InvitationCreationDraft)->InvitationCreationCommandResult={draft ->
            InvitationCreationCommandResult.Created(InvitationCreationReceipt(draft.invitationId,draft.communityId,100,100+draft.ttlMillis,draft.maxUses,0,1))
        }
        var catalogPage=listOf(summary)
        var reply: suspend ()->CommunityResult<CommunityDetails> = {CommunityResult.Ready(details)}
        var channelReply: suspend (String,String,String)->ChannelCreationCommandResult = { community,id,name ->
            ChannelCreationCommandResult.Created(ChannelCreationReceipt(community,id,name,2))
        }
        val ports: Ports=object : Ports {
            override suspend fun create(community:String,invitation:String,token:String,ttlMillis:Long,maxUses:Int):InvitationCreationCommandResult {
                val draft=assertNotNull(savedInvites[invitation]);assertEquals(token,draft.secret.reveal())
                inviteRequests+=draft;return inviteReply(draft)
            }
            override suspend fun revoke(community:String,invitation:String):InvitationRevocationCommandResult=error("No revoke")
            override suspend fun accept(operation:String,token:String):InvitationAcceptanceCommandResult {
                acceptRequests+=operation to InvitationSecret.fromToken(token);return acceptReply(operation)
            }
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
        val drafts=InvitationCreationDraftOwner(server,object:InvitationDraftStore {
            override val actorId=server
            override suspend fun read(invitationId:String)=savedInvites[invitationId]
            override suspend fun retain(draft:InvitationCreationDraft)=savedInvites.getOrPut(draft.invitationId) {draft}
        },InvitationSecretGenerator {InvitationSecret.fromToken("A".repeat(43))},ports,account)
        val invites=InvitationCreationWorkspace(scope,account,server,workspace,drafts) {"00000000-0000-0000-0000-000000000004"}
        val acceptance=InvitationAcceptanceWorkspace(scope,account,workspace,ports) {"00000000-0000-0000-0000-000000000005"}
        val presenter=ServerWorkspacePresenter(scope,workspace,server,creation,invites,acceptance,connected::add)
        suspend fun close() {presenter.close();screen.cancel();account.cancel()}
    }
    @Test fun acceptFormRetriesOriginalCodeAndOpensActualMembershipListWithoutVoice()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();val token="A".repeat(43)
        f.acceptReply={held.await();InvitationAcceptanceCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE)}
        try {
            f.presenter.openInvitationAcceptance();f.presenter.setInvitationCode("  $token  ");runCurrent()
            assertTrue(f.presenter.state.value.acceptanceForm.visible)
            assertFalse(f.presenter.state.value.toString().contains(token))
            f.presenter.submitInvitationAcceptance();runCurrent();assertEquals(1,f.acceptRequests.size)
            f.presenter.submitInvitationAcceptance();f.presenter.setInvitationCode("B".repeat(43));runCurrent()
            assertEquals("  $token  ",f.presenter.state.value.acceptanceForm.code.reveal());assertEquals(1,f.acceptRequests.size)
            held.complete(Unit);runCurrent();assertEquals(InvitationCommandFailure.UNAVAILABLE,f.presenter.state.value.acceptance.error)
            f.acceptReply={operation -> InvitationAcceptanceCommandResult.Accepted(InvitationAcceptanceReceipt(operation,channel,server,1,true))}
            f.presenter.submitInvitationAcceptance();runCurrent()
            assertEquals(listOf("00000000-0000-0000-0000-000000000005","00000000-0000-0000-0000-000000000005"),f.acceptRequests.map {it.first})
            assertTrue(f.acceptRequests.all {it.second.reveal()==token});assertEquals(listOf("mine"),f.issued)
            assertFalse(f.presenter.state.value.acceptanceForm.visible);assertEquals("",f.presenter.state.value.acceptanceForm.code.reveal())
            assertEquals(ServerWorkspaceSection.MY_SERVERS,f.presenter.state.value.section)
            assertEquals(listOf(summary),f.presenter.state.value.browser.memberships.items);assertTrue(f.connected.isEmpty())
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun editedInvitationCodeBeforeQueuedSubmitCannotIssueOldCommand()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openInvitationAcceptance();f.presenter.setInvitationCode("A".repeat(43));runCurrent()
            assertTrue(f.presenter.state.value.acceptanceForm.visible)
            f.presenter.submitInvitationAcceptance();f.presenter.setInvitationCode("E".repeat(43));runCurrent()
            assertTrue(f.acceptRequests.isEmpty());assertTrue(f.issued.isEmpty())
        } finally {f.close();runCurrent()}
    }
    @Test fun dismissedHeldAcceptanceCannotNavigateOrRefreshCatalog()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.acceptReply={operation -> withContext(NonCancellable) {held.await()};returned=true
            InvitationAcceptanceCommandResult.Accepted(InvitationAcceptanceReceipt(operation,channel,server,1,true))}
        try {
            f.presenter.openCatalog();runCurrent();f.presenter.openInvitationAcceptance();f.presenter.setInvitationCode("A".repeat(43))
            f.presenter.submitInvitationAcceptance();runCurrent();assertEquals(1,f.acceptRequests.size)
            f.presenter.dismissInvitationAcceptance();runCurrent();assertTrue(f.presenter.state.value.acceptance.busy)
            held.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(ServerWorkspaceSection.CATALOG,f.presenter.state.value.section)
            assertEquals(listOf("catalog:"),f.issued);assertNull(f.presenter.state.value.acceptance.accepted);assertTrue(f.connected.isEmpty())
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun invalidAcceptanceCodeIssuesNothingAndRetirementClearsPrivateInput()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openInvitationAcceptance();f.presenter.setInvitationCode("bad");f.presenter.submitInvitationAcceptance();runCurrent()
            assertTrue(f.presenter.state.value.acceptance.invalidInput);assertTrue(f.acceptRequests.isEmpty())
            f.presenter.setInvitationCode("A".repeat(43));runCurrent();f.account.cancel();runCurrent()
            assertEquals(ServerWorkspaceUiState(),f.presenter.state.value);assertTrue(f.screen.isActive)
        } finally {f.close();runCurrent()}
    }
    @Test fun navigatingBetweenListsFencesHeldAcceptanceEvenWhenSelectionStaysNull()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.acceptReply={operation -> withContext(NonCancellable) {held.await()};returned=true
            InvitationAcceptanceCommandResult.Accepted(InvitationAcceptanceReceipt(operation,channel,server,1,true))}
        try {
            f.presenter.openInvitationAcceptance();f.presenter.setInvitationCode("A".repeat(43))
            f.presenter.submitInvitationAcceptance();runCurrent();assertEquals(1,f.acceptRequests.size)
            assertNull(f.presenter.state.value.browser.selectedCommunity)
            f.presenter.openCatalog();runCurrent();assertEquals(listOf("catalog:"),f.issued)
            assertFalse(f.presenter.state.value.acceptanceForm.visible)
            held.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(ServerWorkspaceSection.CATALOG,f.presenter.state.value.section)
            assertEquals(listOf("catalog:"),f.issued);assertNull(f.presenter.state.value.acceptance.accepted)
            assertTrue(f.connected.isEmpty());assertTrue(f.account.isActive)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun invitationFormUsesOriginalRetainedPayloadAndCopiesOnlyEarnedReceipt()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();val copied=mutableListOf<String>()
        f.inviteReply={held.await();InvitationCreationCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE)}
        try {
            f.presenter.openServer(server);runCurrent();f.presenter.openInvitationCreation();f.presenter.setInvitationTtl(900_000)
            f.presenter.setInvitationUses("4");runCurrent();assertTrue(f.presenter.state.value.invitationForm.visible)
            assertFalse(f.presenter.copyInvitation(copied::add));f.presenter.submitInvitationCreation();runCurrent()
            assertEquals(1,f.inviteRequests.size);assertEquals(900_000L,f.inviteRequests.single().ttlMillis)
            f.presenter.submitInvitationCreation();f.presenter.openChannelCreation();runCurrent()
            assertEquals(1,f.inviteRequests.size);assertFalse(f.presenter.state.value.channelForm.visible)
            held.complete(Unit);runCurrent();assertEquals(InvitationDraftFailure.UNAVAILABLE,f.presenter.state.value.invitations.error)
            f.inviteReply={draft -> InvitationCreationCommandResult.Created(InvitationCreationReceipt(draft.invitationId,server,100,900_100,4,0,1))}
            f.presenter.submitInvitationCreation();runCurrent();assertSame(f.inviteRequests.first(),f.inviteRequests.last())
            assertTrue(f.presenter.copyInvitation(copied::add));assertEquals(listOf("A".repeat(43)),copied)
            assertTrue(f.connected.isEmpty());f.presenter.dismissInvitationCreation();runCurrent()
            assertNull(f.presenter.state.value.invitations.created);assertFalse(f.presenter.copyInvitation(copied::add))
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun queuedInvitationCannotMutateReplacementServer()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openServer(server);runCurrent();f.presenter.openInvitationCreation();runCurrent()
            assertTrue(f.presenter.state.value.invitationForm.visible);assertEquals(server,f.presenter.state.value.invitationForm.community)
            f.presenter.submitInvitationCreation();f.workspace.browser.clearSelection();runCurrent()
            assertTrue(f.inviteRequests.isEmpty());assertTrue(f.savedInvites.isEmpty());assertFalse(f.presenter.state.value.invitationForm.visible)
        } finally {f.close();runCurrent()}
    }
    @Test fun dismissDuringHeldInvitationSuppressesLateDisclosureWithoutClosingAccount()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.inviteReply={draft -> withContext(NonCancellable) {held.await()};returned=true
            InvitationCreationCommandResult.Created(InvitationCreationReceipt(draft.invitationId,server,100,100+draft.ttlMillis,draft.maxUses,0,1))}
        try {
            f.presenter.openServer(server);runCurrent();f.presenter.openInvitationCreation();f.presenter.submitInvitationCreation();runCurrent()
            assertEquals(1,f.inviteRequests.size);f.presenter.dismissInvitationCreation();runCurrent()
            assertTrue(f.presenter.state.value.invitations.busy);held.complete(Unit);runCurrent();assertTrue(returned)
            assertNull(f.presenter.state.value.invitations.created);assertFalse(f.presenter.copyInvitation {error("No late secret")})
            assertEquals(1,f.savedInvites.size);assertTrue(f.account.isActive);assertTrue(f.workspace.active)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun invitationCopyRequiresCurrentOwnershipEvenBeforeProjectionUpdates()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openInvitationCreation();runCurrent();assertFalse(f.presenter.state.value.invitationForm.visible)
            f.presenter.openServer(server);runCurrent();f.presenter.openInvitationCreation();f.presenter.submitInvitationCreation();runCurrent()
            assertNotNull(f.presenter.state.value.invitations.created)
            f.account.cancel();assertFalse(f.presenter.copyInvitation {error("Retired secret")});runCurrent()
            assertEquals(ServerWorkspaceUiState(),f.presenter.state.value);assertTrue(f.screen.isActive)
        } finally {f.close();runCurrent()}
    }
    @Test fun reopeningInvitationFormCannotCopyPreviousReceiptBeforeQueuedClear()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openServer(server);runCurrent();f.presenter.openInvitationCreation();f.presenter.submitInvitationCreation();runCurrent()
            assertNotNull(f.presenter.state.value.invitations.created);assertTrue(f.presenter.copyInvitation {})
            f.presenter.openInvitationCreation()
            assertFalse(f.presenter.copyInvitation {error("Old form receipt")})
            runCurrent();assertNull(f.presenter.state.value.invitations.created)
        } finally {f.close();runCurrent()}
    }
    @Test fun invitationInputLimitsRejectBeforeStorageAndInvalidEditsCannotChangeBody()=runTest {
        val f=Fixture(this)
        try {
            f.presenter.openServer(server);runCurrent();f.presenter.openInvitationCreation()
            f.presenter.setInvitationTtl(1);f.presenter.setInvitationUses("1001");f.presenter.setInvitationUses("not a number")
            f.presenter.submitInvitationCreation();runCurrent()
            assertTrue(f.presenter.state.value.invitations.invalidInput);assertTrue(f.savedInvites.isEmpty());assertTrue(f.inviteRequests.isEmpty())
            assertEquals(86_400_000L,f.presenter.state.value.invitationForm.ttlMillis)
            assertEquals("1001",f.presenter.state.value.invitationForm.maxUses)
        } finally {f.close();runCurrent()}
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
