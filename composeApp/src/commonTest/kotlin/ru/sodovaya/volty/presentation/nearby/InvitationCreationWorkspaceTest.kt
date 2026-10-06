package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InvitationCreationWorkspaceTest {
    private val actor="10000000-0000-0000-0000-000000000001"
    private val server="20000000-0000-0000-0000-000000000002"
    private val invitation="30000000-0000-0000-0000-000000000003"
    private val other="40000000-0000-0000-0000-000000000004"
    private val ttl=900_000L
    private val receipt=InvitationCreationReceipt(invitation,server,100,ttl+100,4,0,1)
    private inner class Fixture(test: TestScope) {
        val account=SupervisorJob(test.coroutineContext[Job]);val screen=SupervisorJob(test.coroutineContext[Job])
        val scope=CoroutineScope(test.coroutineContext+screen)
        var owner=actor;var ids=0;var secrets=0;var failStorage=false
        val stored=mutableMapOf<String,InvitationCreationDraft>()
        val issued=mutableListOf<InvitationCreationDraft>()
        var reply:suspend ()->InvitationCreationCommandResult={InvitationCreationCommandResult.Created(receipt)}
        val store=object:InvitationDraftStore {
            override val actorId=actor
            override suspend fun read(invitationId:String)=stored[invitationId]
            override suspend fun retain(draft:InvitationCreationDraft):InvitationCreationDraft {
                if(failStorage) error("storage unavailable")
                return stored.getOrPut(draft.invitationId) {draft}
            }
        }
        val invitations=object:InvitationPort {
            override suspend fun create(community:String,invitation:String,token:String,ttlMillis:Long,maxUses:Int):InvitationCreationCommandResult {
                val saved=assertNotNull(stored[invitation]);assertEquals(community,saved.communityId)
                assertEquals(token,saved.secret.reveal());assertEquals(ttlMillis,saved.ttlMillis);assertEquals(maxUses,saved.maxUses)
                issued+=saved;return reply()
            }
            override suspend fun revoke(community:String,invitation:String):InvitationRevocationCommandResult=error("No revoke")
            override suspend fun accept(operation:String,token:String):InvitationAcceptanceCommandResult=error("No membership mutation")
        }
        val communities=object:CommunityPort {
            override suspend fun memberships(after:String?,limit:Int)=CommunityResult.Ready(emptyList<CommunitySummary>())
            override suspend fun catalog(query:String,after:String?,limit:Int)=CommunityResult.Ready(emptyList<CommunitySummary>())
            override suspend fun detail(community:String)=CommunityResult.Ready(CommunityDetails(1,
                CommunitySummary(community,"Riders","",CommunityVisibility.PRIVATE,0),owner,emptyList()))
            override suspend fun create(community:String,name:String,description:String,visibility:CommunityVisibility):CommunityResult<CommunityDetails> = error("No server create")
            override suspend fun joinPublic(community:String):CommunityResult<CommunityDetails> = error("No join")
        }
        val removals=object:CommunityRemovalPort {
            override suspend fun leave(community:String,operation:String,successor:String?):CommunityRemovalResult=error("No leave")
            override suspend fun delete(community:String,operation:String):CommunityRemovalResult=error("No delete")
        }
        val workspace=CommunityWorkspace(scope,account,communities,removals) {error("No membership entropy")}
        val drafts=InvitationCreationDraftOwner(actor,store,InvitationSecretGenerator {secrets++;InvitationSecret.fromToken("A".repeat(43))},invitations,account)
        val creation=InvitationCreationWorkspace(scope,account,actor,workspace,drafts) {ids++;invitation}
        suspend fun close() {creation.close();workspace.close();screen.cancel();account.cancel()}
    }
    @Test fun actualStoredPayloadAndSecretSurviveUncertainRetryWithoutDuplicateSubmit()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>()
        f.reply={held.await();InvitationCreationCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE)}
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create(ttl,4));runCurrent()
            assertFalse(f.creation.create(ttl,4));assertEquals(1,f.issued.size);assertNull(f.creation.state.value.created)
            held.complete(Unit);runCurrent();assertEquals(InvitationDraftFailure.UNAVAILABLE,f.creation.state.value.error)
            f.reply={InvitationCreationCommandResult.Created(receipt)};assertTrue(f.creation.create(ttl,4));runCurrent()
            assertEquals(1,f.ids);assertEquals(1,f.secrets);assertSame(f.issued.first(),f.issued.last())
            val result=assertNotNull(f.creation.state.value.created);assertEquals(receipt,result.receipt)
            assertSame(f.issued.first(),result.draft);assertFalse(result.toString().contains("A".repeat(43)))
            assertTrue(f.account.isActive);assertTrue(f.workspace.active)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun missingOwnerOrInvalidLimitsRefuseBeforeEntropyAndStorageFailureIssuesNothing()=runTest {
        val f=Fixture(this)
        try {
            assertFalse(f.creation.create(ttl,4));f.owner=other;f.workspace.browser.openCommunity(server);runCurrent()
            assertFalse(f.creation.create(ttl,4));assertEquals(0,f.ids);assertEquals(0,f.secrets)
            f.owner=actor;f.workspace.browser.openCommunity(server);runCurrent()
            assertFalse(f.creation.create(1,4));assertFalse(f.creation.create(ttl,0));assertTrue(f.creation.state.value.invalidInput)
            assertEquals(0,f.ids);f.failStorage=true;assertTrue(f.creation.create(ttl,4));runCurrent()
            assertEquals(InvitationDraftFailure.STORAGE_UNAVAILABLE,f.creation.state.value.error)
            assertTrue(f.issued.isEmpty());assertNull(f.creation.state.value.created)
        } finally {f.close();runCurrent()}
    }
    @Test fun heldReplyAfterSelectionChangeCannotRevealOriginalSecretUnderAnotherServer()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.reply={withContext(NonCancellable) {held.await()};returned=true;InvitationCreationCommandResult.Created(receipt)}
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create(ttl,4));runCurrent();assertEquals(1,f.issued.size)
            f.workspace.browser.openCommunity(other);runCurrent();held.complete(Unit);runCurrent()
            assertTrue(returned);assertEquals(InvitationCreationState(),f.creation.state.value)
            assertEquals(other,f.workspace.browser.state.value.selectedCommunity);assertEquals(1,f.stored.size)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun earnedSecretIsWithdrawnOnOwnerLossButDurableDraftRemains()=runTest {
        val f=Fixture(this)
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create(ttl,4));runCurrent()
            assertNotNull(f.creation.state.value.created)
            f.owner=other;f.workspace.browser.openCommunity(server);runCurrent()
            assertEquals(InvitationCreationState(),f.creation.state.value);assertEquals(1,f.stored.size)
        } finally {f.close();runCurrent()}
    }
    @Test fun closeFencesHeldCompletionAndPreservesBorrowedDraftOwnerAndAuthentication()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>();var returned=false
        f.reply={withContext(NonCancellable) {held.await()};returned=true;InvitationCreationCommandResult.Created(receipt)}
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create(ttl,4));runCurrent();assertEquals(1,f.issued.size)
            f.creation.close();held.complete(Unit);runCurrent();assertTrue(returned)
            assertEquals(InvitationCreationState(),f.creation.state.value);assertTrue(f.workspace.active);assertTrue(f.account.isActive);assertTrue(f.screen.isActive)
            assertIs<InvitationDraftExecution.Created>(f.drafts.execute(invitation));assertEquals(2,f.issued.size)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
    @Test fun foreignReceiptNeverPublishesSecretAndWrongConsumerOwnershipIsRejected()=runTest {
        val f=Fixture(this);val foreign=SupervisorJob(coroutineContext[Job])
        f.reply={InvitationCreationCommandResult.Created(receipt.copy(communityId=other))}
        try {
            assertFailsWith<IllegalArgumentException> {InvitationCreationWorkspace(f.scope,foreign,actor,f.workspace,f.drafts) {invitation}}
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create(ttl,4));runCurrent()
            assertEquals(InvitationDraftFailure.INVALID_RESPONSE,f.creation.state.value.error);assertNull(f.creation.state.value.created)
        } finally {foreign.cancel();f.close();runCurrent()}
    }
    @Test fun queuedPreparationLosingSelectionCannotTouchSecretStorageOrHttp()=runTest {
        val f=Fixture(this)
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create(ttl,4))
            f.workspace.browser.clearSelection();runCurrent()
            assertEquals(InvitationCreationState(),f.creation.state.value)
            assertEquals(0,f.secrets);assertTrue(f.stored.isEmpty());assertTrue(f.issued.isEmpty())
        } finally {f.close();runCurrent()}
    }
    @Test fun accountCancellationWithdrawsSecretBeforeUnrelatedHeldChildrenFinish()=runTest {
        val f=Fixture(this);val held=CompletableDeferred<Unit>()
        CoroutineScope(coroutineContext+f.account).launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {withContext(NonCancellable) {held.await()}}
        }
        try {
            f.workspace.browser.openCommunity(server);runCurrent();assertTrue(f.creation.create(ttl,4));runCurrent()
            assertNotNull(f.creation.state.value.created);f.account.cancel();runCurrent();assertFalse(f.account.isCompleted)
            assertEquals(InvitationCreationState(),f.creation.state.value);assertEquals(1,f.stored.size);assertTrue(f.screen.isActive)
            assertFalse(f.creation.create(ttl,4));assertEquals(1,f.issued.size)
        } finally {held.complete(Unit);f.close();runCurrent()}
    }
}
