package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InvitationAcceptanceWorkspaceTest {
    private val operation="10000000-0000-0000-0000-000000000001"
    private val invitation="20000000-0000-0000-0000-000000000002"
    private val community="30000000-0000-0000-0000-000000000003"
    private val token="A".repeat(43)
    private val receipt=InvitationAcceptanceReceipt(operation,invitation,community,1,true)
    private inner class Fixture(test:TestScope) {
        val screen=SupervisorJob(test.coroutineContext[Job]);val account=SupervisorJob(test.coroutineContext[Job])
        val scope=CoroutineScope(test.coroutineContext+screen)
        var ids=0;var reads=0;val issued=mutableListOf<Pair<String,InvitationSecret>>()
        var reply:suspend ()->InvitationAcceptanceCommandResult={InvitationAcceptanceCommandResult.Accepted(receipt)}
        val communities=object:CommunityPort {
            override suspend fun memberships(after:String?,limit:Int):CommunityResult<List<CommunitySummary>> {reads++;return CommunityResult.Ready(emptyList())}
            override suspend fun catalog(query:String,after:String?,limit:Int)=CommunityResult.Ready(emptyList<CommunitySummary>())
            override suspend fun detail(community:String):CommunityResult<CommunityDetails> = error("No automatic navigation")
            override suspend fun create(community:String,name:String,description:String,visibility:CommunityVisibility):CommunityResult<CommunityDetails> = error("No create")
            override suspend fun joinPublic(community:String):CommunityResult<CommunityDetails> = error("No public join")
        }
        val removal=object:CommunityRemovalPort {
            override suspend fun leave(community:String,operation:String,successor:String?):CommunityRemovalResult=error("No leave")
            override suspend fun delete(community:String,operation:String):CommunityRemovalResult=error("No delete")
        }
        val invitations=object:InvitationPort {
            override suspend fun accept(operation:String,token:String):InvitationAcceptanceCommandResult {
                issued+=operation to InvitationSecret.fromToken(token);return reply()
            }
            override suspend fun create(community:String,invitation:String,token:String,ttlMillis:Long,maxUses:Int):InvitationCreationCommandResult=error("No create")
            override suspend fun revoke(community:String,invitation:String):InvitationRevocationCommandResult=error("No revoke")
        }
        val workspace=CommunityWorkspace(scope,account,communities,removal) {error("No server entropy")}
        val acceptance=InvitationAcceptanceWorkspace(scope,account,workspace,invitations) {ids++;operation}
        suspend fun close() {acceptance.close();workspace.close();screen.cancel();account.cancel()}
    }
    @Test fun uncertainRetryUsesOriginalOperationAndReceiptRefreshCannotInventMembership()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>()
        f.reply={held.await();InvitationAcceptanceCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE)}
        try {
            assertTrue(f.acceptance.accept("  $token  "));runCurrent();assertFalse(f.acceptance.accept(token))
            assertEquals(1,f.issued.size);assertNull(f.acceptance.state.value.accepted)
            held.complete(Unit);runCurrent();assertEquals(InvitationCommandFailure.UNAVAILABLE,f.acceptance.state.value.error)
            f.reply={InvitationAcceptanceCommandResult.Accepted(receipt)};assertTrue(f.acceptance.accept(token));runCurrent()
            assertEquals(1,f.ids);assertEquals(listOf(operation,operation),f.issued.map {it.first})
            assertTrue(f.issued.all {it.second.reveal()==token});assertEquals(receipt,f.acceptance.state.value.accepted)
            assertEquals(1,f.reads);assertEquals(emptyList(),f.workspace.browser.state.value.memberships.items)
            assertNull(f.workspace.browser.state.value.selectedCommunity);assertTrue(f.account.isActive)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun invalidCodeRefusesBeforeEntropyAndRetiredWorkspaceNeverIssuesCommand()=runTest {
        val f=Fixture(this)
        try {
            assertFalse(f.acceptance.accept(""));assertFalse(f.acceptance.accept("bad"));assertFalse(f.acceptance.accept("A".repeat(42)+"B"))
            assertEquals(0,f.ids);assertTrue(f.issued.isEmpty());assertTrue(f.acceptance.state.value.invalidInput)
            f.workspace.close();assertFalse(f.acceptance.accept(token));assertEquals(0,f.ids)
        } finally {f.close();runCurrent()}
    }
    @Test fun incoherentReceiptCannotPublishOrRefreshAndAlreadyMemberZeroRevisionIsValid()=runTest {
        val f=Fixture(this)
        try {
            for(bad in listOf(receipt.copy(operationId=invitation),receipt.copy(invitationId="bad"),receipt.copy(communityId="bad"),receipt.copy(revision=-1))) {
                f.reply={InvitationAcceptanceCommandResult.Accepted(bad)};assertTrue(f.acceptance.accept(token));runCurrent()
                assertEquals(InvitationCommandFailure.INVALID_RESPONSE,f.acceptance.state.value.error)
                assertNull(f.acceptance.state.value.accepted);assertEquals(0,f.reads)
            }
            val already=receipt.copy(revision=0,membershipChanged=false)
            f.reply={InvitationAcceptanceCommandResult.Accepted(already)};assertTrue(f.acceptance.accept(token));runCurrent()
            assertEquals(already,f.acceptance.state.value.accepted);assertEquals(1,f.ids);assertEquals(1,f.reads)
        } finally {f.close();runCurrent()}
    }
    @Test fun dismissedHeldResultCannotPublishOrRefreshButExplicitRetryRetainsOperation()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.reply={withContext(NonCancellable) {held.await()};returned=true;InvitationAcceptanceCommandResult.Accepted(receipt)}
        try {
            assertTrue(f.acceptance.accept(token));runCurrent();assertEquals(1,f.issued.size)
            f.acceptance.clearPresentation();assertTrue(f.acceptance.state.value.busy)
            held.complete(Unit);runCurrent();assertTrue(returned);assertEquals(InvitationAcceptanceState(),f.acceptance.state.value);assertEquals(0,f.reads)
            assertTrue(f.acceptance.accept(token));runCurrent();assertEquals(receipt,f.acceptance.state.value.accepted)
            assertEquals(1,f.ids);assertEquals(2,f.issued.size);assertEquals(1,f.reads)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun closeFencesHeldReplyAndPreservesBorrowedScreenAccountAndBrowser()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.reply={withContext(NonCancellable) {held.await()};returned=true;InvitationAcceptanceCommandResult.Accepted(receipt)}
        try {
            assertTrue(f.acceptance.accept(token));runCurrent();assertEquals(1,f.issued.size)
            f.acceptance.close();held.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(InvitationAcceptanceState(),f.acceptance.state.value);assertEquals(0,f.reads)
            assertTrue(f.account.isActive);assertTrue(f.screen.isActive);assertTrue(f.workspace.active)
            f.workspace.browser.refreshMemberships();runCurrent();assertEquals(1,f.reads)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun wrongAccountOrScreenOwnershipRefusesBeforeAllocatingConsumer()=runTest {
        val f=Fixture(this);val other=SupervisorJob(coroutineContext[Job])
        try {
            assertFailsWith<IllegalArgumentException> {InvitationAcceptanceWorkspace(f.scope,other,f.workspace,f.invitations) {operation}}
            assertFailsWith<IllegalArgumentException> {InvitationAcceptanceWorkspace(CoroutineScope(coroutineContext+other),f.account,f.workspace,f.invitations) {operation}}
            assertTrue(f.issued.isEmpty());assertEquals(0,f.ids)
        } finally {other.cancel();f.close();runCurrent()}
    }
    @Test fun accountCancellationWithdrawsReceiptBeforeUnrelatedChildrenComplete()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>()
        CoroutineScope(coroutineContext+f.account).launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable) {held.await()}}
        }
        try {
            assertTrue(f.acceptance.accept(token));runCurrent();assertNotNull(f.acceptance.state.value.accepted)
            f.account.cancel();runCurrent();assertFalse(f.account.isCompleted)
            assertEquals(InvitationAcceptanceState(),f.acceptance.state.value);assertTrue(f.screen.isActive)
            assertFalse(f.acceptance.accept(token));assertEquals(1,f.issued.size)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
}
