package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.nearby.community.*

data class OwnerDepartureConfirmation(val community:String,val successor:CommunityMemberSummary)
data class OwnerDepartureState(val community:String?=null,val items:List<CommunityMemberSummary>?=null,
    val loading:Boolean=false,val hasMore:Boolean=false,val error:CommunityFailure?=null,
    val confirmation:OwnerDepartureConfirmation?=null)

class OwnerDepartureWorkspace(parent:CoroutineScope,private val account:Job,private val actor:String,
    private val workspace:CommunityWorkspace,private val members:CommunityMembersPort) {
    private val screen=requireNotNull(parent.coroutineContext[Job])
    init {invitationDraftId(actor);require(workspace.belongsTo(screen,account))}
    private val lifetime=SupervisorJob(screen)
    private val scope=CoroutineScope(parent.coroutineContext+lifetime)
    private val mutex=Mutex()
    private val output=MutableStateFlow(OwnerDepartureState())
    val state=output.asStateFlow()
    private var closed=false
    private var owner:String?=null
    private var version=0L
    private var read:Job?=null
    private var guardian:Job?=null
    init {
        guardian=CoroutineScope(parent.coroutineContext+account).launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {close()}
        }
        scope.launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {close()}}
        scope.launch {workspace.retired.first {it};close()}
        scope.launch {workspace.browser.state.collect {mutex.withLock {if(active()) synchronizeOwner()}}}
    }
    private fun active()=!closed && screen.isActive && account.isActive && lifetime.isActive && workspace.active
    private fun ownedCommunity():String? {
        val browser=workspace.browser.state.value
        return browser.detail?.takeIf {!browser.detailLoading && browser.detailError==null &&
            it.community.id==browser.selectedCommunity && it.ownerId==actor}?.community?.id
    }
    private fun synchronizeOwner() {
        val current=ownedCommunity()
        if(current==owner) return
        owner=current;withdraw()
    }
    private fun withdraw() {
        version++;read?.cancel();read=null;output.value=OwnerDepartureState()
    }
    suspend fun refresh():Boolean=mutex.withLock {
        if(!active() || workspace.state.value.busy) return@withLock false
        synchronizeOwner()
        val community=owner ?: return@withLock false
        startRead(community,null);true
    }
    suspend fun loadMore():Boolean=mutex.withLock {
        if(!active() || workspace.state.value.busy) return@withLock false
        synchronizeOwner()
        val current=output.value
        val community=owner ?: return@withLock false
        if(current.community!=community || current.loading || current.error!=null || !current.hasMore) return@withLock false
        val after=current.items?.lastOrNull()?.userId ?: return@withLock false
        startRead(community,after);true
    }
    private fun startRead(community:String,after:String?) {
        read?.cancel()
        val generation=++version
        val previous=if(after==null) emptyList() else output.value.items.orEmpty()
        output.value=OwnerDepartureState(community,if(after==null) null else previous,loading=true)
        read=scope.launch {
            if(!mutex.withLock {active() && ownedCommunity()==community && generation==version}) return@launch
            val result=try {members.list(community,after,20)} catch(cancelled:CancellationException) {
                if(!currentCoroutineContext().isActive) throw cancelled
                CommunityResult.Failed(CommunityFailure.UNAVAILABLE)
            } catch(_:Exception) {CommunityResult.Failed(CommunityFailure.UNAVAILABLE)}
            mutex.withLock {
                if(!active()) return@withLock
                synchronizeOwner()
                if(owner!=community || generation!=version) return@withLock
                val failure=when(result) {
                    is CommunityResult.Failed -> result.reason
                    is CommunityResult.Ready -> if(validPage(result.value,community,after)) null else CommunityFailure.INVALID_RESPONSE
                }
                if(failure!=null) {
                    val denied=failure==CommunityFailure.UNAUTHORIZED || failure==CommunityFailure.ACCESS_DENIED
                    output.value=OwnerDepartureState(community,if(after==null || denied) null else previous,error=failure)
                } else {
                    val rows=(result as CommunityResult.Ready).value.items.toList()
                    output.value=OwnerDepartureState(community,previous+rows,hasMore=rows.size==20)
                }
            }
        }
    }
    private fun validPage(page:CommunityMembersPage,community:String,after:String?):Boolean {
        if(page.communityId!=community || page.ownerId!=actor || page.items.size>20) return false
        var previous=after
        return page.items.all {row ->
            val canonical=runCatching {invitationDraftId(row.userId)}.isSuccess
            val ordered=previous==null || row.userId>requireNotNull(previous)
            previous=row.userId
            canonical && ordered && row.displayName.length in 2..80 && row.displayName.isNotBlank() && row.joinedAtMillis>=0
        }
    }
    suspend fun requestSuccessor(userId:String):Boolean=mutex.withLock {
        if(!active() || workspace.state.value.busy) return@withLock false
        synchronizeOwner()
        val community=owner ?: return@withLock false
        val current=output.value
        if(current.community!=community || current.loading || current.error!=null || userId==actor) return@withLock false
        val row=current.items?.firstOrNull {it.userId==userId} ?: return@withLock false
        output.value=current.copy(confirmation=OwnerDepartureConfirmation(community,row));true
    }
    suspend fun dismissConfirmation():Unit=mutex.withLock {output.value=output.value.copy(confirmation=null)}
    suspend fun confirmDeparture():Boolean=mutex.withLock {
        if(!active() || workspace.state.value.busy) return@withLock false
        synchronizeOwner()
        val current=output.value
        val confirmation=current.confirmation ?: return@withLock false
        if(current.loading || current.error!=null || confirmation.community!=owner ||
            confirmation.successor.userId==actor || current.items?.contains(confirmation.successor)!=true) return@withLock false
        val generation=version
        if(!workspace.requestRemoval(confirmation.community,false)) return@withLock false
        if(!active() || ownedCommunity()!=confirmation.community || generation!=version) {
            workspace.dismissRemoval();return@withLock false
        }
        // Reuse the existing original-operation retry and authoritative removal receipt handling.
        workspace.confirmRemoval(confirmation.successor.userId,CommunityRemovalConfirmation(confirmation.community,false))
    }
    suspend fun clearPresentation():Unit=mutex.withLock {if(active()) withdraw()}
    suspend fun close():Unit=withContext(NonCancellable) {
        mutex.withLock {
            if(closed) return@withLock
            closed=true;owner=null;output.value=OwnerDepartureState();lifetime.cancel();guardian?.cancel()
        }
    }
}
