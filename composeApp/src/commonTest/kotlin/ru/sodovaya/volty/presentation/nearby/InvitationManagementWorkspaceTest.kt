package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InvitationManagementWorkspaceTest {
    private val actor="10000000-0000-0000-0000-000000000001"
    private val server="20000000-0000-0000-0000-000000000002"
    private val other="30000000-0000-0000-0000-000000000003"
    private fun id(index:Int)="40000000-0000-0000-0000-${index.toString().padStart(12,'0')}"
    private fun row(index:Int,community:String=server)=InvitationMetadata(id(index),community,100,900100,10,0,null,1,null)
    private inner class Fixture(private val test:TestScope) {
        val account=SupervisorJob(test.coroutineContext[Job]);val screen=SupervisorJob(test.coroutineContext[Job])
        val scope=CoroutineScope(test.coroutineContext+screen)
        var owner=actor;val reads=mutableListOf<Pair<String,String?>>()
        val revoked=mutableListOf<Pair<String,String>>()
        var page:suspend (String,String?)->InvitationListingCommandResult={_,_->InvitationListingCommandResult.Listed(listOf(row(1)))}
        var revokeReply:suspend (String,String)->InvitationRevocationCommandResult={community,invitation ->
            InvitationRevocationCommandResult.Revoked(InvitationRevocationReceipt(invitation,community,2))}
        val communities=object:CommunityPort {
            override suspend fun memberships(after:String?,limit:Int)=CommunityResult.Ready(emptyList<CommunitySummary>())
            override suspend fun catalog(query:String,after:String?,limit:Int)=CommunityResult.Ready(emptyList<CommunitySummary>())
            override suspend fun detail(community:String)=CommunityResult.Ready(CommunityDetails(1,CommunitySummary(community,"Riders","",CommunityVisibility.PRIVATE,0),owner,emptyList()))
            override suspend fun create(community:String,name:String,description:String,visibility:CommunityVisibility):CommunityResult<CommunityDetails> = error("No server create")
            override suspend fun joinPublic(community:String):CommunityResult<CommunityDetails> = error("No join")
        }
        val removal=object:CommunityRemovalPort {
            override suspend fun leave(community:String,operation:String,successor:String?):CommunityRemovalResult=error("No leave")
            override suspend fun delete(community:String,operation:String):CommunityRemovalResult=error("No delete")
        }
        val listing=object:InvitationListingPort {
            override suspend fun list(community:String,after:String?,limit:Int):InvitationListingCommandResult {
                assertEquals(20,limit);reads+=community to after;return page(community,after)
            }
        }
        val commands=object:InvitationPort {
            override suspend fun revoke(community:String,invitation:String):InvitationRevocationCommandResult {
                revoked+=community to invitation;return revokeReply(community,invitation)
            }
            override suspend fun create(community:String,invitation:String,token:String,ttlMillis:Long,maxUses:Int):InvitationCreationCommandResult=error("No secret generation")
            override suspend fun accept(operation:String,token:String):InvitationAcceptanceCommandResult=error("No membership mutation")
        }
        val workspace=CommunityWorkspace(scope,account,communities,removal) {error("No entropy")}
        val management=InvitationManagementWorkspace(scope,account,actor,workspace,listing,commands)
        suspend fun select(community:String=server) {workspace.browser.openCommunity(community);test.runCurrent()}
        suspend fun close() {management.close();workspace.close();screen.cancel();account.cancel()}
    }
    @Test fun paginationUsesActualLastIdAndRefreshCannotAdoptHeldOlderPage()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var oldReturned=false
        try {
            f.select();f.page={_,_->InvitationListingCommandResult.Listed((1..20).map {row(it)})}
            assertTrue(f.management.refresh());runCurrent();assertTrue(f.management.state.value.hasMore)
            f.page={_,after -> assertEquals(id(20),after);withContext(NonCancellable) {held.await()};oldReturned=true
                InvitationListingCommandResult.Listed(listOf(row(21)))}
            assertTrue(f.management.loadMore());runCurrent();assertEquals(server to id(20),f.reads.last())
            f.page={_,after -> assertNull(after);InvitationListingCommandResult.Listed(listOf(row(3)))}
            assertTrue(f.management.refresh());runCurrent();held.complete(Unit);runCurrent()
            assertTrue(oldReturned);assertEquals(listOf(row(3)),f.management.state.value.items);assertFalse(f.management.state.value.hasMore)
            assertEquals(listOf(server to null,server to id(20),server to null),f.reads)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun onlyActualOwnerCanReadOrConfirmAnActuallyListedUnrevokedTarget()=runTest {
        val f=Fixture(this)
        try {
            assertFalse(f.management.refresh());f.owner=other;f.select();assertFalse(f.management.refresh());assertTrue(f.reads.isEmpty())
            f.owner=actor;f.select();assertTrue(f.management.refresh());runCurrent()
            assertFalse(f.management.requestRevoke(id(9)));assertTrue(f.management.requestRevoke(id(1)))
            assertEquals(InvitationRevocationConfirmation(server,id(1)),f.management.state.value.confirmation)
            assertTrue(f.revoked.isEmpty());f.management.dismissRevocation();assertNull(f.management.state.value.confirmation)
            assertFalse(f.management.confirmRevoke());assertTrue(f.revoked.isEmpty())
        } finally {f.close();runCurrent()}
    }
    @Test fun revokeIsSingleFlightAndAckNeverInventsMetadataTimestamp()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>()
        f.revokeReply={community,invitation -> held.await();InvitationRevocationCommandResult.Revoked(InvitationRevocationReceipt(invitation,community,2))}
        try {
            f.select();f.management.refresh();runCurrent();assertTrue(f.management.requestRevoke(id(1)));assertTrue(f.management.confirmRevoke());runCurrent()
            assertEquals(listOf(server to id(1)),f.revoked);assertFalse(f.management.confirmRevoke())
            held.complete(Unit);runCurrent();assertEquals(2,f.reads.size)
            assertNull(f.management.state.value.items?.single()?.revokedAtMillis)
            assertEquals(setOf(id(1)),f.management.state.value.confirmedRevocations)
            assertFalse(f.management.requestRevoke(id(1)));assertTrue(f.workspace.active);assertTrue(f.account.isActive)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun malformedPagesAndRevocationReceiptsNeverPublishSuccess()=runTest {
        val f=Fixture(this)
        try {
            f.select()
            for(bad in listOf(listOf(row(1,other)),listOf(row(2),row(1)),listOf(row(1),row(1)),listOf(row(1).copy(uses=11)),listOf(row(1).copy(revokedAtMillis=100,revocationRevision=null)))) {
                f.page={_,_->InvitationListingCommandResult.Listed(bad)};assertTrue(f.management.refresh());runCurrent()
                assertEquals(InvitationCommandFailure.INVALID_RESPONSE,f.management.state.value.error);assertNull(f.management.state.value.items)
            }
            f.page={_,_->InvitationListingCommandResult.Listed(listOf(row(1)))};f.management.refresh();runCurrent()
            for(bad in listOf(InvitationRevocationReceipt(id(1),other,2),InvitationRevocationReceipt(id(2),server,2),InvitationRevocationReceipt(id(1),server,1))) {
                f.revokeReply={_,_->InvitationRevocationCommandResult.Revoked(bad)}
                assertTrue(f.management.requestRevoke(id(1)));assertTrue(f.management.confirmRevoke());runCurrent()
                assertEquals(InvitationCommandFailure.INVALID_RESPONSE,f.management.state.value.commandError)
                assertTrue(f.management.state.value.confirmedRevocations.isEmpty())
            }
        } finally {f.close();runCurrent()}
    }
    @Test fun heldOldRevocationCannotOverwriteNewServerRead()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.revokeReply={community,invitation -> withContext(NonCancellable) {held.await()};returned=true
            InvitationRevocationCommandResult.Revoked(InvitationRevocationReceipt(invitation,community,2))}
        try {
            f.select();f.management.refresh();runCurrent();f.management.requestRevoke(id(1));f.management.confirmRevoke();runCurrent()
            assertEquals(1,f.revoked.size);f.select(other)
            f.page={community,_->InvitationListingCommandResult.Listed(listOf(row(2,community)))}
            assertTrue(f.management.refresh());runCurrent();assertEquals(listOf(row(2,other)),f.management.state.value.items)
            held.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(other,f.management.state.value.community);assertEquals(listOf(row(2,other)),f.management.state.value.items)
            assertTrue(f.management.state.value.confirmedRevocations.isEmpty());assertFalse(f.management.state.value.revoking)
            assertEquals(2,f.reads.size)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun ownerLossAndAccountCancellationWithdrawMetadataAndConfirmation()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>()
        CoroutineScope(coroutineContext+f.account).launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable) {held.await()}}
        }
        try {
            f.select();f.management.refresh();runCurrent();f.management.requestRevoke(id(1));assertNotNull(f.management.state.value.items)
            f.owner=other;f.select();assertEquals(InvitationManagementState(),f.management.state.value)
            f.owner=actor;f.select();f.management.refresh();runCurrent();assertNotNull(f.management.state.value.items)
            f.account.cancel();runCurrent();assertFalse(f.account.isCompleted);assertEquals(InvitationManagementState(),f.management.state.value)
            assertTrue(f.screen.isActive);assertTrue(f.revoked.isEmpty())
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun dismissedHeldRevocationCannotRefreshOrDiscloseOnReopenedPresentation()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.revokeReply={community,invitation -> withContext(NonCancellable) {held.await()};returned=true
            InvitationRevocationCommandResult.Revoked(InvitationRevocationReceipt(invitation,community,2))}
        try {
            f.select();f.management.refresh();runCurrent();f.management.requestRevoke(id(1));f.management.confirmRevoke();runCurrent()
            assertEquals(1,f.revoked.size);f.management.clearPresentation();assertTrue(f.management.state.value.revoking)
            assertNull(f.management.state.value.items);f.management.refresh();runCurrent();assertEquals(2,f.reads.size)
            held.complete(Unit);runCurrent();assertTrue(returned);assertEquals(2,f.reads.size)
            assertTrue(f.management.state.value.confirmedRevocations.isEmpty());assertFalse(f.management.state.value.revoking)
            assertEquals(listOf(row(1)),f.management.state.value.items)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun changedTargetBeforeQueuedRevokeDispatchIssuesNoMutation()=runTest {
        val f=Fixture(this)
        try {
            f.select();f.management.refresh();runCurrent();f.management.requestRevoke(id(1));assertTrue(f.management.confirmRevoke())
            f.workspace.browser.openCommunity(other);runCurrent()
            assertTrue(f.revoked.isEmpty());assertFalse(f.management.state.value.revoking)
        } finally {f.close();runCurrent()}
    }
    @Test fun closeFencesHeldReadAndPreservesBorrowedAccountAndWorkspace()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.page={_,_->withContext(NonCancellable) {held.await()};returned=true;InvitationListingCommandResult.Listed(listOf(row(1)))}
        try {
            f.select();f.management.refresh();runCurrent();assertEquals(1,f.reads.size)
            f.management.close();held.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(InvitationManagementState(),f.management.state.value)
            assertTrue(f.workspace.active);assertTrue(f.account.isActive);assertTrue(f.screen.isActive)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
}
