package ru.sodovaya.volty.presentation.root.nearby

import kotlinx.coroutines.*
import ru.sodovaya.volty.data.social.DefaultSocialRepository
import ru.sodovaya.volty.data.voice.IncomingCallActionRegistry
import ru.sodovaya.volty.data.voice.IncomingCallActionAddress
import ru.sodovaya.volty.data.voice.IncomingCallActionKind
import ru.sodovaya.volty.data.nearby.DirectCallSnapshot

/** Activity borrows verification; the existing permission bridge alone admits microphone work. */
internal class NearbyIncomingCallAcceptance(private val repository:DefaultSocialRepository,
    private val registry:IncomingCallActionRegistry, private val voice:NearbyVoiceController,
    private val queued:CoroutineDispatcher) {
    suspend fun accept(address:String,label:String):Boolean = withContext(queued) {
        val target=IncomingCallActionAddress.parse(address)?.takeIf {it.kind==IncomingCallActionKind.ACCEPT}
            ?: return@withContext false
        verifyAndQueue(label) {registry.accept(target.bindingId,target.ticketId)}
    }
    suspend fun acceptOffer(bindingId:String,offerId:String,label:String):Boolean = withContext(queued) {
        verifyAndQueue(label) {registry.acceptOffer(bindingId,offerId)}
    }
    private suspend fun verifyAndQueue(label:String,verify:suspend ()->DirectCallSnapshot?):Boolean {
        val account=repository.authenticatedAccount.value?.takeIf {it.job.isActive} ?: return false
        val call=try {verify()}
            catch(cancelled:CancellationException) {
                // Account retirement rejects metadata; actual Activity cancellation still propagates.
                currentCoroutineContext().ensureActive()
                return false
            } catch(_:Exception) {return false}
            ?: return false
        currentCoroutineContext().ensureActive()
        if(!account.job.isActive || repository.authenticatedAccount.value!==account) return false
        return voice.requestIncomingCall(account,call,label)!=null
    }
}
