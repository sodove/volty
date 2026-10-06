package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import ru.sodovaya.volty.data.nearby.IncomingCallReply
import ru.sodovaya.volty.data.social.DefaultSocialRepository
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount

/** Process-owned metadata discovery. No microphone, foreground service or membership commands. */
internal class IncomingCallDiscovery(private val repository: DefaultSocialRepository,
    private val queued: CoroutineDispatcher, private val notifications: IncomingCallAccountCoordinator,
    private val acquire: suspend (SocialAuthenticatedAccount) -> AuthenticatedVoiceRuntime) {
    private val parent=repository.applicationScope()
    private val lifetime=SupervisorJob(requireNotNull(parent.coroutineContext[Job]))
    private val scope=CoroutineScope(parent.coroutineContext+lifetime+queued)
    private var started=false
    private var closed=false
    private var reader:Job?=null
    init {require(queued.isDispatchNeeded(parent.coroutineContext))}

    /** Called on the queued process owner; Activity recreation must not create another poller. */
    fun start() {
        if(started || closed || !lifetime.isActive) return
        started=true
        scope.launch(start=CoroutineStart.UNDISPATCHED) {try {awaitCancellation()} finally {close()}}
        scope.launch {
            repository.authenticatedAccount.collect {account ->
                // Do not join an old HTTP request: an uncooperative transport cannot hold a new login.
                reader?.cancel();reader=null
                notifications.retireAll()
                if(account!=null && live(account)) reader=scope.launch {discover(account)}
            }
        }
    }
    private fun live(account:SocialAuthenticatedAccount)=!closed && lifetime.isActive &&
        account.job.isActive && repository.authenticatedAccount.value===account

    private suspend fun discover(account:SocialAuthenticatedAccount) {
        var runtime:AuthenticatedVoiceRuntime?=null
        var attachment:IncomingCallNotificationAttachment?=null
        var offered:String?=null
        while(live(account)) {
            try {
                val original=runtime ?: acquire(account).also {
                    check(live(account) && it.belongsTo(account) && !it.retired.value)
                    runtime=it
                }
                if(original.retired.value) return
                val owner=attachment ?: notifications.acquire(account,original).also {
                    check(live(account) && it.owner.belongsTo(account))
                    attachment=it
                }
                // Offer one call at a time from the bounded first page. Other callers follow once
                // the first ring leaves the list. Status and relative credit are verified separately.
                val reply=original.incomingCalls(limit=1)
                if(!live(account) || original.retired.value) return
                if(reply is IncomingCallReply.Ready) {
                    val call=reply.calls.firstOrNull()?.callId
                    if(offered!=call) {
                        offered?.let {owner.owner.withdrawCall(it)}
                        offered=call
                    }
                    if(call!=null && live(account)) owner.owner.discoveryHint(call)
                }
            } catch(cancelled:CancellationException) {
                if(!currentCoroutineContext().isActive) throw cancelled
            } catch(_:Exception) { /* Bounded retry; failures never create or extend ring credit. */ }
            delay(2_000L)
        }
    }
    suspend fun close()=withContext(NonCancellable+queued) {
        if(closed) return@withContext
        closed=true;reader?.cancel();reader=null;lifetime.cancel()
        notifications.retireAll()
    }
}
