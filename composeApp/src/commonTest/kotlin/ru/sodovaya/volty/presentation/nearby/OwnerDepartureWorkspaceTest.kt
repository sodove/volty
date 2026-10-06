package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerDepartureWorkspaceTest {
    private val actor="10000000-0000-0000-0000-000000000001"
    private val server="20000000-0000-0000-0000-000000000002"
    private val other="30000000-0000-0000-0000-000000000003"
    private val operation="40000000-0000-0000-0000-000000000004"
    private fun row(index:Int)=CommunityMemberSummary("50000000-0000-0000-0000-${index.toString().padStart(12,'0')}","Member $index",100)
    private inner class Fixture(private val test:TestScope) {
        val account=SupervisorJob(test.coroutineContext[Job]);val screen=SupervisorJob(test.coroutineContext[Job])
        val scope=CoroutineScope(test.coroutineContext+screen)
        var owner=actor;val reads=mutableListOf<Pair<String,String?>>()
        val leaves=mutableListOf<Triple<String,String,String?>>()
        var entropy=0;var membershipReads=0
        var page:suspend (String,String?)->CommunityResult<CommunityMembersPage> = {community,_->CommunityResult.Ready(CommunityMembersPage(community,actor,listOf(row(1))))}
        var leaveReply:suspend (String,String,String?)->CommunityRemovalResult={community,op,_->CommunityRemovalResult.Removed(CommunityRemovalReceipt(op,community,2,false))}
        val communities=object:CommunityPort {
            override suspend fun memberships(after:String?,limit:Int):CommunityResult<List<CommunitySummary>> {membershipReads++;return CommunityResult.Ready(emptyList())}
            override suspend fun catalog(query:String,after:String?,limit:Int)=CommunityResult.Ready(emptyList<CommunitySummary>())
            override suspend fun detail(community:String)=CommunityResult.Ready(CommunityDetails(1,CommunitySummary(community,"Riders","",CommunityVisibility.PRIVATE,0),owner,emptyList()))
            override suspend fun create(community:String,name:String,description:String,visibility:CommunityVisibility):CommunityResult<CommunityDetails> = error("No server mutation")
            override suspend fun joinPublic(community:String):CommunityResult<CommunityDetails> = error("No join")
        }
        val removal=object:CommunityRemovalPort {
            override suspend fun leave(community:String,operation:String,successor:String?):CommunityRemovalResult {
                leaves+=Triple(community,operation,successor);return leaveReply(community,operation,successor)
            }
            override suspend fun delete(community:String,operation:String):CommunityRemovalResult=error("No automatic deletion")
        }
        val members=object:CommunityMembersPort {
            override suspend fun list(community:String,after:String?,limit:Int):CommunityResult<CommunityMembersPage> {
                assertEquals(20,limit);reads+=community to after;return page(community,after)
            }
        }
        val workspace=CommunityWorkspace(scope,account,communities,removal) {entropy++;operation}
        val departure=OwnerDepartureWorkspace(scope,account,actor,workspace,members)
        suspend fun select(community:String=server) {workspace.browser.openCommunity(community);test.runCurrent()}
        suspend fun close() {departure.close();workspace.close();screen.cancel();account.cancel()}
    }
    @Test fun ownerCanOnlyConfirmActuallyListedOtherMemberAndUncertainRetryKeepsOriginalOperation()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>()
        try {
            assertFalse(f.departure.refresh());f.owner=other;f.select();assertFalse(f.departure.refresh());assertTrue(f.reads.isEmpty())
            f.owner=actor;f.select();f.departure.refresh();runCurrent()
            assertFalse(f.departure.requestSuccessor(actor));assertFalse(f.departure.requestSuccessor(other))
            assertTrue(f.departure.requestSuccessor(row(1).userId));assertEquals(OwnerDepartureConfirmation(server,row(1)),f.departure.state.value.confirmation)
            assertTrue(f.leaves.isEmpty());assertEquals(0,f.entropy)
            f.leaveReply={_,_,_->held.await();CommunityRemovalResult.Failed(CommunityRemovalFailure.UNAVAILABLE)}
            assertTrue(f.departure.confirmDeparture());runCurrent();assertEquals(1,f.leaves.size);assertFalse(f.departure.confirmDeparture())
            held.complete(Unit);runCurrent();assertEquals(CommunityWorkspaceError.Removal(CommunityRemovalFailure.UNAVAILABLE),f.workspace.state.value.error)
            f.leaveReply={community,op,_->CommunityRemovalResult.Removed(CommunityRemovalReceipt(op,community,2,false))}
            assertTrue(f.departure.confirmDeparture());runCurrent()
            assertEquals(List(2) {Triple<String,String,String?>(server,operation,row(1).userId)},f.leaves);assertEquals(1,f.entropy)
            assertNull(f.workspace.browser.state.value.selectedCommunity);assertEquals(1,f.membershipReads)
            assertEquals(OwnerDepartureState(),f.departure.state.value);assertTrue(f.account.isActive);assertTrue(f.screen.isActive)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun paginationAndReplacementUseActualCursorAndRejectHeldOlderRead()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        try {
            f.select();f.page={community,_->CommunityResult.Ready(CommunityMembersPage(community,actor,(1..20).map(::row)))}
            f.departure.refresh();runCurrent();assertTrue(f.departure.state.value.hasMore)
            f.page={community,after -> assertEquals(row(20).userId,after);withContext(NonCancellable) {held.await()};returned=true
                CommunityResult.Ready(CommunityMembersPage(community,actor,listOf(row(21))))}
            assertTrue(f.departure.loadMore());runCurrent();assertEquals(server to row(20).userId,f.reads.last())
            f.page={community,_->CommunityResult.Ready(CommunityMembersPage(community,actor,listOf(row(3))))}
            f.departure.refresh();runCurrent();held.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(listOf(row(3)),f.departure.state.value.items);assertFalse(f.departure.state.value.hasMore)
            assertTrue(f.leaves.isEmpty())
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun incoherentPageIdentityOrderAndFieldsNeverSupplySuccessors()=runTest {
        val f=Fixture(this)
        try {
            f.select()
            for(bad in listOf(CommunityMembersPage(other,actor,listOf(row(1))),CommunityMembersPage(server,other,listOf(row(1))),
                CommunityMembersPage(server,actor,listOf(row(2),row(1))),CommunityMembersPage(server,actor,listOf(row(1),row(1))),
                CommunityMembersPage(server,actor,listOf(row(1).copy(joinedAtMillis=-1))),CommunityMembersPage(server,actor,listOf(row(1).copy(displayName=" "))))) {
                f.page={_,_->CommunityResult.Ready(bad)};f.departure.refresh();runCurrent()
                assertEquals(CommunityFailure.INVALID_RESPONSE,f.departure.state.value.error);assertNull(f.departure.state.value.items)
                assertFalse(f.departure.requestSuccessor(row(1).userId));assertFalse(f.departure.confirmDeparture())
            }
            assertTrue(f.leaves.isEmpty());assertEquals(0,f.entropy)
        } finally {f.close();runCurrent()}
    }
    @Test fun dismissedSelectionAndOwnerLossWithdrawOldConfirmationWithoutMutation()=runTest {
        val f=Fixture(this)
        try {
            f.select();f.departure.refresh();runCurrent();f.departure.requestSuccessor(row(1).userId)
            f.departure.dismissConfirmation();assertFalse(f.departure.confirmDeparture())
            f.departure.requestSuccessor(row(1).userId);f.departure.clearPresentation();assertFalse(f.departure.confirmDeparture())
            f.departure.refresh();runCurrent();f.departure.requestSuccessor(row(1).userId)
            f.owner=other;f.select();assertEquals(OwnerDepartureState(),f.departure.state.value);assertFalse(f.departure.confirmDeparture())
            assertTrue(f.leaves.isEmpty());assertEquals(0,f.entropy)
        } finally {f.close();runCurrent()}
    }
    @Test fun heldReadCannotCrossSelectedServerOrClosedConsumerAndBorrowedWorkspaceSurvives()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.page={community,_->withContext(NonCancellable) {held.await()};returned=true;CommunityResult.Ready(CommunityMembersPage(community,actor,listOf(row(1))))}
        try {
            f.select();f.departure.refresh();runCurrent();assertEquals(1,f.reads.size)
            f.select(other);f.page={community,_->CommunityResult.Ready(CommunityMembersPage(community,actor,listOf(row(2))))}
            f.departure.refresh();runCurrent();assertEquals(listOf(row(2)),f.departure.state.value.items)
            held.complete(Unit);runCurrent();assertTrue(returned);assertEquals(other,f.departure.state.value.community)
            assertEquals(listOf(row(2)),f.departure.state.value.items)
            f.departure.close();assertEquals(OwnerDepartureState(),f.departure.state.value)
            assertTrue(f.workspace.active);assertTrue(f.account.isActive);assertTrue(f.screen.isActive)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun accountCancellationWithdrawsRowsBeforeUnrelatedHeldChildCompletes()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>()
        CoroutineScope(coroutineContext+f.account).launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable) {held.await()}}
        }
        try {
            f.select();f.departure.refresh();runCurrent();assertNotNull(f.departure.state.value.items)
            f.account.cancel();runCurrent();assertFalse(f.account.isCompleted)
            assertEquals(OwnerDepartureState(),f.departure.state.value);assertTrue(f.screen.isActive);assertTrue(f.leaves.isEmpty())
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
}
