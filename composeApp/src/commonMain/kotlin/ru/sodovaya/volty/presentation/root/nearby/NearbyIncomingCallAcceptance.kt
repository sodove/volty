package ru.sodovaya.volty.presentation.root.nearby

import kotlinx.coroutines.*
import ru.sodovaya.volty.data.social.DefaultSocialRepository
import ru.sodovaya.volty.data.voice.IncomingCallActionRegistry
import ru.sodovaya.volty.data.voice.IncomingCallActionAddress
import ru.sodovaya.volty.data.voice.IncomingCallActionKind

/** Activity borrows verification; the existing permission bridge alone admits microphone work. */
internal class NearbyIncomingCallAcceptance(private val repository:DefaultSocialRepository,
    private val registry:IncomingCallActionRegistry, private val voice:NearbyVoiceController,
    private val queued:CoroutineDispatcher) {
    suspend fun accept(address:String,label:String):Boolean = withContext(queued) {
        val target=IncomingCallActionAddress.parse(address)?.takeIf {it.kind==IncomingCallActionKind.ACCEPT}
            ?: return@withContext false
        val account=repository.authenticatedAccount.value?.takeIf {it.job.isActive} ?: return@withContext false
        val call=try {registry.accept(target.bindingId,target.ticketId)}
            catch(cancelled:CancellationException) {
                // Account retirement rejects metadata; actual Activity cancellation still propagates.
                currentCoroutineContext().ensureActive()
                return@withContext false
            } catch(_:Exception) {return@withContext false}
            ?: return@withContext false
        currentCoroutineContext().ensureActive()
        if(!account.job.isActive || repository.authenticatedAccount.value!==account) return@withContext false
        voice.requestIncomingCall(account,call,label)!=null
    }
}
