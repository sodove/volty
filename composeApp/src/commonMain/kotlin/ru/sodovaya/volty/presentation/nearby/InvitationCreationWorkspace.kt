package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.nearby.community.*

data class InvitationCreationState(val community:String?=null,val busy:Boolean=false,
    val invalidInput:Boolean=false,val error:InvitationDraftFailure?=null,val created:InvitationDraftExecution.Created?=null)

/** Ephemeral screen disclosure of a durably retained original-account invitation. */
class InvitationCreationWorkspace(parent:CoroutineScope,private val account:Job,private val actor:String,
    private val workspace:CommunityWorkspace,private val drafts:InvitationCreationDraftOwner,private val nextId:()->String) {
    private val screen=requireNotNull(parent.coroutineContext[Job])
    init {invitationDraftId(actor);require(workspace.belongsTo(screen,account))}
    private val lifetime=SupervisorJob(screen)
    private val scope=CoroutineScope(parent.coroutineContext+lifetime)
    private val mutex=Mutex()
    private val output=MutableStateFlow(InvitationCreationState())
    val state=output.asStateFlow()
    private data class Request(val community:String,val invitation:String,val ttl:Long,val uses:Int)
    private var retry:Request?=null
    private var closed=false
    private var guardian:Job?=null
    init {
        guardian=CoroutineScope(parent.coroutineContext+account).launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {close()}
        }
        scope.launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {close()}}
        scope.launch {workspace.retired.first {it};close()}
        scope.launch {
            workspace.browser.state.collect {
                mutex.withLock {
                    if(active() && !output.value.busy && output.value.community!=ownedCommunity())
                        output.value=InvitationCreationState()
                }
            }
        }
    }
    private fun active()=!closed && screen.isActive && account.isActive && lifetime.isActive && workspace.active
    private fun ownedCommunity():String? {
        val browser=workspace.browser.state.value
        return browser.detail?.takeIf {!browser.detailLoading && browser.detailError==null &&
            it.community.id==browser.selectedCommunity && it.ownerId==actor}?.community?.id
    }
    suspend fun create(ttlMillis:Long,maxUses:Int):Boolean=mutex.withLock {
        if(!active() || output.value.busy || workspace.state.value.busy) return@withLock false
        val community=ownedCommunity() ?: return@withLock false
        if(ttlMillis !in 900_000L..604_800_000L || maxUses !in 1..1000) {
            output.value=InvitationCreationState(community,invalidInput=true);return@withLock false
        }
        val request=retry?.takeIf {it.community==community && it.ttl==ttlMillis && it.uses==maxUses}
            ?: Request(community,nextId().also(::invitationDraftId),ttlMillis,maxUses).also {retry=it}
        output.value=InvitationCreationState(community,busy=true)
        scope.launch {
            // Selection may have changed between admission and actual storage/HTTP dispatch.
            mutex.withLock {
                if(!active()) return@launch
                if(ownedCommunity()!=request.community) {output.value=InvitationCreationState();return@launch}
            }
            val result=try {
                when(val prepared=drafts.prepare(request.invitation,request.community,request.ttl,request.uses)) {
                    is InvitationDraftPreparation.Failed -> InvitationDraftExecution.Failed(prepared.reason)
                    is InvitationDraftPreparation.Prepared -> drafts.execute(request.invitation)
                }
            } catch(cancelled:CancellationException) {
                if(!currentCoroutineContext().isActive) throw cancelled
                InvitationDraftExecution.Failed(InvitationDraftFailure.UNAVAILABLE)
            } catch(_:Exception) {InvitationDraftExecution.Failed(InvitationDraftFailure.UNAVAILABLE)}
            mutex.withLock {
                if(!active()) return@withLock
                if(ownedCommunity()!=request.community) {output.value=InvitationCreationState();return@withLock}
                when(result) {
                    is InvitationDraftExecution.Failed -> output.value=InvitationCreationState(request.community,error=result.reason)
                    is InvitationDraftExecution.Created -> {
                        val draft=result.draft
                        if(draft.actorId!=actor || draft.invitationId!=request.invitation || draft.communityId!=request.community ||
                            draft.ttlMillis!=request.ttl || draft.maxUses!=request.uses)
                            output.value=InvitationCreationState(request.community,error=InvitationDraftFailure.INVALID_RESPONSE)
                        else {
                            retry=null;output.value=InvitationCreationState(request.community,created=result)
                        }
                    }
                }
            }
        }
        true
    }
    suspend fun close():Unit=withContext(NonCancellable) {
        mutex.withLock {
            if(closed) return@withLock
            closed=true;retry=null;output.value=InvitationCreationState()
            lifetime.cancel();guardian?.cancel()
        }
    }
}
