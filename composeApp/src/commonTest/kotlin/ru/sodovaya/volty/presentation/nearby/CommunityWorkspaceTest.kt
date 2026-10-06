package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityWorkspaceTest {
    private val first="00000000-0000-0000-0000-000000000001"
    private val second="00000000-0000-0000-0000-000000000002"
    private fun detail(id: String)=CommunityDetails(1,CommunitySummary(id,"Riders","",CommunityVisibility.PUBLIC,0),first,emptyList())
    private class Ports : CommunityPort,CommunityRemovalPort {
        val issued=mutableListOf<String>()
        var createReply: suspend (String)->CommunityResult<CommunityDetails> = {error("Set create reply")}
        var joinReply: suspend (String)->CommunityResult<CommunityDetails> = {error("Set join reply")}
        var leaveReply: suspend (String,String,String?)->CommunityRemovalResult = {_,_,_ -> error("Set leave reply")}
        override suspend fun memberships(after: String?,limit: Int)=CommunityResult.Ready(emptyList<CommunitySummary>())
        override suspend fun catalog(query: String,after: String?,limit: Int)=CommunityResult.Ready(emptyList<CommunitySummary>())
        override suspend fun detail(community: String)=CommunityResult.Failed(CommunityFailure.ACCESS_DENIED)
        override suspend fun create(community: String,name: String,description: String,visibility: CommunityVisibility): CommunityResult<CommunityDetails> {
            issued+="create:$community:$name:$visibility";return createReply(community)
        }
        override suspend fun joinPublic(community: String): CommunityResult<CommunityDetails> {issued+="join:$community";return joinReply(community)}
        override suspend fun leave(community: String,operation: String,successor: String?): CommunityRemovalResult {
            issued+="leave:$community:$operation:$successor";return leaveReply(community,operation,successor)
        }
        override suspend fun delete(community: String,operation: String): CommunityRemovalResult {
            issued+="delete:$community:$operation";return CommunityRemovalResult.Removed(CommunityRemovalReceipt(operation,community,2,false))
        }
    }
    private class Fixture(test: TestScope, val ports: Ports=Ports()) {
        val screen=SupervisorJob(test.coroutineContext[Job]);val account=SupervisorJob(test.coroutineContext[Job])
        var ids=0
        val workspace=CommunityWorkspace(CoroutineScope(test.coroutineContext+screen),account,ports,ports) {
            ids++;"00000000-0000-0000-0000-${ids.toString().padStart(12,'0')}"
        }
        suspend fun close() {workspace.close();screen.cancel();account.cancel()}
    }
    @Test fun expectedOriginalRemovalCannotConfirmAReplacementTarget()=runTest {
        val f=Fixture(this)
        f.ports.leaveReply={community,op,_->CommunityRemovalResult.Removed(CommunityRemovalReceipt(op,community,2,false))}
        try {
            f.workspace.browser.openCommunity(first);runCurrent();f.workspace.requestRemoval(first,false)
            val original=assertNotNull(f.workspace.state.value.confirmation)
            f.workspace.browser.openCommunity(second);runCurrent();f.workspace.requestRemoval(second,false)
            assertFalse(f.workspace.confirmRemoval(successor=first,expected=original));runCurrent()
            assertTrue(f.ports.issued.isEmpty());assertEquals(0,f.ids)
            assertTrue(f.workspace.confirmRemoval(successor=first,expected=CommunityRemovalConfirmation(second,false)));runCurrent()
            assertEquals(listOf("leave:$second:00000000-0000-0000-0000-000000000001:$first"),f.ports.issued)
        } finally {f.close();runCurrent()}
    }
    @Test fun uncertainCreateRetriesOriginalIdAndBlocksDoubleSubmit()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>()
        f.ports.createReply={withContext(NonCancellable) {held.await()};CommunityResult.Failed(CommunityFailure.UNAVAILABLE)}
        try {
            assertTrue(f.workspace.create("Riders","",CommunityVisibility.PUBLIC));runCurrent()
            assertFalse(f.workspace.create("Riders","",CommunityVisibility.PUBLIC));assertEquals(1,f.ports.issued.size)
            held.complete(Unit);runCurrent()
            assertEquals(CommunityWorkspaceError.Community(CommunityFailure.UNAVAILABLE),f.workspace.state.value.error)
            f.ports.createReply={CommunityResult.Ready(detail(it))}
            assertTrue(f.workspace.create("Riders","",CommunityVisibility.PUBLIC));runCurrent()
            assertEquals(f.ports.issued[0],f.ports.issued[1]);assertEquals(1,f.ids)
            assertEquals(first,f.workspace.browser.state.value.selectedCommunity)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun staleCreateActuallyReturnsAfterScreenCloseWithoutPublishingSelection()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.ports.createReply={id -> withContext(NonCancellable) {held.await()};returned=true;CommunityResult.Ready(detail(id))}
        try {
            f.workspace.create("Riders","",CommunityVisibility.PUBLIC);runCurrent();assertEquals(1,f.ports.issued.size)
            f.workspace.close();held.complete(Unit);runCurrent()
            assertTrue(returned);assertEquals(CommunityWorkspaceState(),f.workspace.state.value)
            assertEquals(CommunityBrowserState(),f.workspace.browser.state.value);assertTrue(f.account.isActive)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun retiredAccountFencesCommandsAndLateActualJoinResult()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.ports.joinReply={id -> withContext(NonCancellable) {held.await()};returned=true;CommunityResult.Ready(detail(id))}
        try {
            assertTrue(f.workspace.join(first));runCurrent();assertEquals(listOf("join:$first"),f.ports.issued)
            f.account.cancel();runCurrent();held.complete(Unit);runCurrent()
            assertTrue(returned);assertEquals(CommunityWorkspaceState(),f.workspace.state.value)
            assertEquals(CommunityBrowserState(),f.workspace.browser.state.value)
            assertFalse(f.workspace.join(second));assertEquals(1,f.ports.issued.size);assertTrue(f.screen.isActive)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun removalRequiresSameSelectedTargetAndExplicitConfirmation()=runTest {
        val f=Fixture(this)
        try {
            f.workspace.browser.openCommunity(first);runCurrent()
            assertTrue(f.workspace.requestRemoval(first,delete=false));assertTrue(f.ports.issued.isEmpty())
            f.workspace.browser.openCommunity(second);runCurrent()
            assertFalse(f.workspace.confirmRemoval());assertTrue(f.ports.issued.isEmpty())
            assertTrue(f.workspace.requestRemoval(second,delete=true));assertTrue(f.workspace.confirmRemoval());runCurrent()
            assertEquals(listOf("delete:$second:$first"),f.ports.issued)
            assertNull(f.workspace.browser.state.value.selectedCommunity)
        } finally {f.close();runCurrent()}
    }
    @Test fun ownerLeaveFailureNeverAutomaticallyDeletesAndChangedSuccessorUsesNewOperation()=runTest {
        val f=Fixture(this)
        f.ports.leaveReply={_,_,_ -> CommunityRemovalResult.Failed(CommunityRemovalFailure.SUCCESSOR_REQUIRED)}
        try {
            f.workspace.browser.openCommunity(first);runCurrent()
            f.workspace.requestRemoval(first,delete=false);assertTrue(f.workspace.confirmRemoval());runCurrent()
            assertEquals(listOf("leave:$first:$first:null"),f.ports.issued)
            assertEquals(CommunityWorkspaceError.Removal(CommunityRemovalFailure.SUCCESSOR_REQUIRED),f.workspace.state.value.error)
            f.ports.leaveReply={community,operation,_ -> CommunityRemovalResult.Removed(CommunityRemovalReceipt(operation,community,2,true))}
            f.workspace.requestRemoval(first,delete=false);assertTrue(f.workspace.confirmRemoval(second));runCurrent()
            // Different successor is a different request body: use a new operation identity.
            assertEquals("leave:$first:$second:$second",f.ports.issued.last())
            assertTrue(f.workspace.state.value.cleanupPending);assertNull(f.workspace.browser.state.value.selectedCommunity)
        } finally {f.close();runCurrent()}
    }
    @Test fun incoherentForeignCreateAndRemovalReceiptsCannotNavigateOrClearSelection()=runTest {
        val f=Fixture(this)
        f.ports.createReply={CommunityResult.Ready(detail(second))}
        f.ports.leaveReply={_,operation,_ -> CommunityRemovalResult.Removed(CommunityRemovalReceipt(operation,second,2,false))}
        try {
            f.workspace.create("Riders","",CommunityVisibility.PUBLIC);runCurrent()
            assertEquals(CommunityWorkspaceError.Community(CommunityFailure.INVALID_RESPONSE),f.workspace.state.value.error)
            assertNull(f.workspace.browser.state.value.selectedCommunity)
            f.workspace.browser.openCommunity(first);runCurrent();f.workspace.requestRemoval(first,false)
            f.workspace.confirmRemoval();runCurrent()
            assertEquals(CommunityWorkspaceError.Removal(CommunityRemovalFailure.INVALID_RESPONSE),f.workspace.state.value.error)
            assertEquals(first,f.workspace.browser.state.value.selectedCommunity)
        } finally {f.close();runCurrent()}
    }
    @Test fun unchangedUncertainLeaveRetriesExactOperationAndNeverClaimsCleanupComplete()=runTest {
        val f=Fixture(this)
        f.ports.leaveReply={_,_,_ -> CommunityRemovalResult.Failed(CommunityRemovalFailure.UNAVAILABLE)}
        try {
            f.workspace.browser.openCommunity(first);runCurrent()
            f.workspace.requestRemoval(first,false);f.workspace.confirmRemoval();runCurrent()
            f.ports.leaveReply={community,operation,_ -> CommunityRemovalResult.Removed(CommunityRemovalReceipt(operation,community,2,true))}
            f.workspace.requestRemoval(first,false);f.workspace.confirmRemoval();runCurrent()
            assertEquals(2,f.ports.issued.size);assertEquals(f.ports.issued[0],f.ports.issued[1]);assertEquals(1,f.ids)
            assertTrue(f.workspace.state.value.cleanupPending)
        } finally {f.close();runCurrent()}
    }
    @Test fun returningOldRemovalCannotClearNewServerSelection()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.ports.leaveReply={community,operation,_ -> withContext(NonCancellable) {held.await()};returned=true
            CommunityRemovalResult.Removed(CommunityRemovalReceipt(operation,community,2,false))}
        try {
            f.workspace.browser.openCommunity(first);runCurrent()
            f.workspace.requestRemoval(first,false);f.workspace.confirmRemoval();runCurrent();assertEquals(1,f.ports.issued.size)
            f.workspace.browser.openCommunity(second);runCurrent();held.complete(Unit);runCurrent()
            assertTrue(returned);assertEquals(second,f.workspace.browser.state.value.selectedCommunity)
            assertFalse(f.workspace.state.value.busy)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun screenCancellationBeforeQueuedCreatePreventsEveryHttpCall()=runTest {
        val f=Fixture(this)
        try {
            assertTrue(f.workspace.create("Riders","",CommunityVisibility.PUBLIC));assertTrue(f.workspace.state.value.busy)
            f.screen.cancel();runCurrent()
            assertTrue(f.ports.issued.isEmpty());assertEquals(CommunityWorkspaceState(),f.workspace.state.value)
            assertTrue(f.account.isActive)
        } finally {f.close();runCurrent()}
    }
    @Test fun invalidNamesAndImplicitRemovalIssueNoMutation()=runTest {
        val f=Fixture(this)
        try {
            assertFalse(f.workspace.create("  ","",CommunityVisibility.PRIVATE))
            assertFalse(f.workspace.create("Bad\nname","",CommunityVisibility.PUBLIC))
            assertFalse(f.workspace.confirmRemoval())
            assertFalse(f.workspace.requestRemoval(first,false))
            runCurrent();assertTrue(f.ports.issued.isEmpty());assertEquals(0,f.ids)
        } finally {f.close();runCurrent()}
    }
    @Test fun accountCancellationBeforeQueuedCreatePreventsEveryProducerCall()=runTest {
        val f=Fixture(this)
        f.ports.createReply={CommunityResult.Ready(detail(it))}
        try {
            assertTrue(f.workspace.create("Riders","",CommunityVisibility.PUBLIC))
            f.account.cancel();runCurrent()
            assertTrue(f.ports.issued.isEmpty());assertEquals(CommunityWorkspaceState(),f.workspace.state.value)
            assertTrue(f.screen.isActive)
        } finally {f.close();runCurrent()}
    }
    @Test fun cancelledOwnerIsRejectedWithoutBorrowedJobMutation()=runTest {
        val account=SupervisorJob(coroutineContext[Job]);val screen=SupervisorJob(coroutineContext[Job]);val ports=Ports()
        try {
            account.cancel()
            assertFailsWith<IllegalArgumentException> {CommunityWorkspace(CoroutineScope(coroutineContext+screen),account,ports,ports) {first}}
            assertTrue(screen.isActive);assertTrue(ports.issued.isEmpty())
        } finally {screen.cancel();account.cancel()}
    }
}
