package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.nearby.community.*

data class InvitationRevocationConfirmation(val community:String,val invitation:String)
data class InvitationManagementState(val community:String?=null,val items:List<InvitationMetadata>?=null,
    val loading:Boolean=false,val hasMore:Boolean=false,val error:InvitationCommandFailure?=null,
    val revoking:Boolean=false,val confirmation:InvitationRevocationConfirmation?=null,
    val commandError:InvitationCommandFailure?=null,val confirmedRevocations:Set<String> = emptySet())

class InvitationManagementWorkspace(parent:CoroutineScope,private val account:Job,private val actor:String,
    private val workspace:CommunityWorkspace,private val listing:InvitationListingPort,private val commands:InvitationPort) {
    private val screen=requireNotNull(parent.coroutineContext[Job])
    init {invitationDraftId(actor);require(workspace.belongsTo(screen,account))}
    private val lifetime=SupervisorJob(screen)
    private val scope=CoroutineScope(parent.coroutineContext+lifetime)
    private val mutex=Mutex()
    private val output=MutableStateFlow(InvitationManagementState())
    val state=output.asStateFlow()
    private var closed=false
    private var owner:String?=null
    private var ownershipVersion=0L
    private var readVersion=0L
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
        owner=current;ownershipVersion++;readVersion++;read?.cancel();read=null
        output.value=InvitationManagementState(revoking=output.value.revoking)
    }
    suspend fun refresh():Boolean=mutex.withLock {
        if(!active()) return@withLock false
        synchronizeOwner()
        val community=owner ?: return@withLock false
        startRead(community,null);true
    }
    suspend fun loadMore():Boolean=mutex.withLock {
        if(!active()) return@withLock false
        synchronizeOwner()
        val current=output.value
        val community=owner ?: return@withLock false
        if(current.community!=community || current.loading || current.error!=null || !current.hasMore) return@withLock false
        val after=current.items?.lastOrNull()?.invitationId ?: return@withLock false
        startRead(community,after);true
    }
    /** Called under the mutex. Replacing a read does not surrender ownership of its producer. */
    private fun startRead(community:String,after:String?) {
        read?.cancel()
        val version=++readVersion
        val ownership=ownershipVersion
        val previous=if(after==null) emptyList() else output.value.items.orEmpty()
        output.value=output.value.copy(community=community,items=if(after==null) null else previous,
            loading=true,hasMore=false,error=null,confirmation=null)
        read=scope.launch {
            val admitted=mutex.withLock {
                active() && ownedCommunity()==community && ownershipVersion==ownership && readVersion==version
            }
            if(!admitted) return@launch
            val result=try {listing.list(community,after,20)} catch(cancelled:CancellationException) {
                if(!currentCoroutineContext().isActive) throw cancelled
                InvitationListingCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE)
            } catch(_:Exception) {InvitationListingCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE)}
            mutex.withLock {
                if(!active()) return@withLock
                synchronizeOwner()
                if(owner!=community || ownershipVersion!=ownership || readVersion!=version) return@withLock
                val failure=when(result) {
                    is InvitationListingCommandResult.Failed -> result.reason
                    is InvitationListingCommandResult.Listed -> if(validPage(result.items,community,after)) null else InvitationCommandFailure.INVALID_RESPONSE
                }
                if(failure!=null) {
                    val withdrawn=failure==InvitationCommandFailure.ACCESS_DENIED || failure==InvitationCommandFailure.UNAUTHORIZED
                    output.value=output.value.copy(loading=false,error=failure,
                        items=if(after==null || withdrawn) null else previous,hasMore=false)
                } else {
                    val rows=(result as InvitationListingCommandResult.Listed).items.toList()
                    output.value=output.value.copy(loading=false,items=previous+rows,hasMore=rows.size==20,error=null)
                }
            }
        }
    }
    private fun validPage(rows:List<InvitationMetadata>,community:String,after:String?):Boolean {
        if(rows.size>20) return false
        var previous=after
        return rows.all {row ->
            val canonical=runCatching {invitationDraftId(row.invitationId)}.isSuccess
            val ordered=previous==null || row.invitationId>requireNotNull(previous)
            previous=row.invitationId
            canonical && ordered && row.communityId==community && row.createdAtMillis>=0 &&
                row.expiresAtMillis>row.createdAtMillis && row.expiresAtMillis-row.createdAtMillis in 900_000L..604_800_000L &&
                row.maxUses in 1..1000 && row.uses in 0..row.maxUses && row.creationRevision>0 &&
                (row.revokedAtMillis==null)==(row.revocationRevision==null) &&
                (row.revokedAtMillis==null || (row.revokedAtMillis>=0 && requireNotNull(row.revocationRevision)>row.creationRevision))
        }
    }
    private fun revocable(invitation:String):InvitationMetadata? {
        val current=output.value
        if(current.community!=owner || current.loading || current.error!=null || current.revoking || workspace.state.value.busy) return null
        return current.items?.firstOrNull {it.invitationId==invitation && it.revokedAtMillis==null && invitation !in current.confirmedRevocations}
    }
    suspend fun requestRevoke(invitation:String):Boolean=mutex.withLock {
        if(!active()) return@withLock false
        synchronizeOwner()
        val community=owner ?: return@withLock false
        revocable(invitation) ?: return@withLock false
        output.value=output.value.copy(confirmation=InvitationRevocationConfirmation(community,invitation),commandError=null)
        true
    }
    suspend fun confirmRevoke():Boolean=mutex.withLock {
        if(!active()) return@withLock false
        synchronizeOwner()
        val confirmation=output.value.confirmation ?: return@withLock false
        if(owner!=confirmation.community) return@withLock false
        val row=revocable(confirmation.invitation) ?: return@withLock false
        val ownership=ownershipVersion
        output.value=output.value.copy(revoking=true,confirmation=null,commandError=null)
        scope.launch {
            val admitted=mutex.withLock {
                if(!active()) return@withLock false
                synchronizeOwner()
                if(owner==confirmation.community && ownershipVersion==ownership) true
                else {output.value=output.value.copy(revoking=false);false}
            }
            if(!admitted) return@launch
            val result=try {commands.revoke(confirmation.community,confirmation.invitation)} catch(cancelled:CancellationException) {
                if(!currentCoroutineContext().isActive) throw cancelled
                InvitationRevocationCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE)
            } catch(_:Exception) {InvitationRevocationCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE)}
            mutex.withLock {
                if(!active()) return@withLock
                synchronizeOwner()
                output.value=output.value.copy(revoking=false)
                if(owner!=confirmation.community || ownershipVersion!=ownership) return@withLock
                when(result) {
                    is InvitationRevocationCommandResult.Failed -> output.value=output.value.copy(commandError=result.reason)
                    is InvitationRevocationCommandResult.Revoked -> {
                        val receipt=result.receipt
                        if(receipt.communityId!=confirmation.community || receipt.invitationId!=confirmation.invitation || receipt.revision<=row.creationRevision)
                            output.value=output.value.copy(commandError=InvitationCommandFailure.INVALID_RESPONSE)
                        else {
                            // The ACK earns a historical fact, never an invented timestamp in a metadata row.
                            output.value=output.value.copy(confirmedRevocations=output.value.confirmedRevocations+confirmation.invitation)
                            startRead(confirmation.community,null)
                        }
                    }
                }
            }
        }
        true
    }
    suspend fun dismissRevocation():Unit=mutex.withLock {output.value=output.value.copy(confirmation=null)}
    /** Withdraws this view without cancelling an already-issued revoke producer. */
    suspend fun clearPresentation():Unit=mutex.withLock {
        if(!active()) return@withLock
        ownershipVersion++;readVersion++;read?.cancel();read=null
        output.value=InvitationManagementState(revoking=output.value.revoking)
    }
    suspend fun close():Unit=withContext(NonCancellable) {
        mutex.withLock {
            if(closed) return@withLock
            closed=true;output.value=InvitationManagementState();owner=null
            lifetime.cancel();guardian?.cancel()
        }
    }
}
