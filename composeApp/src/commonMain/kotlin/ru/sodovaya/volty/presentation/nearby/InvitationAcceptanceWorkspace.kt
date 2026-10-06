package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.nearby.community.*

data class InvitationAcceptanceState(val busy:Boolean=false,val invalidInput:Boolean=false,
    val error:InvitationCommandFailure?=null,val accepted:InvitationAcceptanceReceipt?=null)

/** Original explicit membership command; its receipt never fabricates current membership or voice. */
class InvitationAcceptanceWorkspace(parent:CoroutineScope,private val account:Job,
    private val workspace:CommunityWorkspace,private val invitations:InvitationPort,private val nextId:()->String) {
    private val screen=requireNotNull(parent.coroutineContext[Job])
    init {require(workspace.belongsTo(screen,account))}
    private val lifetime=SupervisorJob(screen)
    private val scope=CoroutineScope(parent.coroutineContext+lifetime)
    private val mutex=Mutex()
    private val output=MutableStateFlow(InvitationAcceptanceState())
    val state=output.asStateFlow()
    private data class Request(val operation:String,val secret:InvitationSecret)
    private var retry:Request?=null
    private var displayVersion=0L
    private var closed=false
    private var guardian:Job?=null
    init {
        guardian=CoroutineScope(parent.coroutineContext+account).launch(start=CoroutineStart.UNDISPATCHED) {
            try {awaitCancellation()} finally {close()}
        }
        scope.launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {close()}}
        scope.launch {workspace.retired.first {it};close()}
    }
    private fun active()=!closed && screen.isActive && account.isActive && lifetime.isActive && workspace.active
    suspend fun accept(code:String):Boolean=mutex.withLock {
        if(!active() || output.value.busy || workspace.state.value.busy) return@withLock false
        val secret=try {InvitationSecret.fromToken(code.trim())} catch(_:IllegalArgumentException) {
            output.value=InvitationAcceptanceState(invalidInput=true);return@withLock false
        }
        val request=retry?.takeIf {it.secret.reveal()==secret.reveal()}
            ?: Request(nextId().also(::invitationDraftId),secret).also {retry=it}
        val version=++displayVersion
        output.value=InvitationAcceptanceState(busy=true)
        scope.launch {
            mutex.withLock {
                if(!active()) return@launch
                if(version!=displayVersion) {output.value=InvitationAcceptanceState();return@launch}
            }
            val result=try {invitations.accept(request.operation,request.secret.reveal())}
            catch(cancelled:CancellationException) {
                if(!currentCoroutineContext().isActive) throw cancelled
                InvitationAcceptanceCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE)
            } catch(_:Exception) {InvitationAcceptanceCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE)}
            mutex.withLock {
                if(!active()) return@withLock
                if(version!=displayVersion) {output.value=InvitationAcceptanceState();return@withLock}
                when(result) {
                    is InvitationAcceptanceCommandResult.Failed -> output.value=InvitationAcceptanceState(error=result.reason)
                    is InvitationAcceptanceCommandResult.Accepted -> {
                        val receipt=result.receipt
                        val valid=receipt.operationId==request.operation && receipt.revision>=0 &&
                            runCatching {invitationDraftId(receipt.invitationId);invitationDraftId(receipt.communityId)}.isSuccess
                        if(!valid) output.value=InvitationAcceptanceState(error=InvitationCommandFailure.INVALID_RESPONSE)
                        else {
                            retry=null;output.value=InvitationAcceptanceState(accepted=receipt)
                            // A replay may refer to membership that was subsequently left. Read current authority.
                            workspace.browser.refreshMemberships()
                        }
                    }
                }
            }
        }
        true
    }
    suspend fun clearPresentation():Unit=mutex.withLock {
        if(!active()) return@withLock
        displayVersion++
        output.value=InvitationAcceptanceState(busy=output.value.busy)
    }
    suspend fun close():Unit=withContext(NonCancellable) {
        mutex.withLock {
            if(closed) return@withLock
            closed=true;retry=null;output.value=InvitationAcceptanceState()
            lifetime.cancel();guardian?.cancel()
        }
    }
}
