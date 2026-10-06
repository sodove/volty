package ru.sodovaya.volty.presentation.root.nearby

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import ru.sodovaya.volty.domain.voice.VoiceOperationId
import ru.sodovaya.volty.data.social.DefaultSocialRepository
import ru.sodovaya.volty.data.voice.IncomingCallActionRegistry

data class NearbyIncomingCallView(val bindingId:String, val offerId:String)
class NearbyIncomingCallActions(val accept:(NearbyIncomingCallView,String)->Unit, val decline:(NearbyIncomingCallView)->Unit)

/** Borrowed process offer. Screens own collectors/waiters, never the incoming account lifetime. */
internal class NearbyIncomingCallUi(private val repository:DefaultSocialRepository,
    private val registry:IncomingCallActionRegistry, private val acceptance:NearbyIncomingCallAcceptance,
    private val queued:CoroutineDispatcher, private val nextOperation:()->String) {
    val state:Flow<NearbyIncomingCallView?> = combine(registry.offer,repository.authenticatedAccount) {offer,account ->
        offer?.takeIf {it.account===account && it.account.job.isActive}
            ?.let {NearbyIncomingCallView(it.binding.id,it.original.id)}
    }
    suspend fun accept(original:NearbyIncomingCallView,label:String):Boolean =
        acceptance.acceptOffer(original.bindingId,original.offerId,label)
    suspend fun decline(original:NearbyIncomingCallView):Boolean = withContext(queued) {
        val current=registry.offer.value ?: return@withContext false
        if(current.account!==repository.authenticatedAccount.value || !registry.matchesOffer(current) ||
            current.binding.id!=original.bindingId || current.original.id!=original.offerId) return@withContext false
        registry.declineOffer(original.bindingId,original.offerId,VoiceOperationId(nextOperation()))!=null
    }
}
