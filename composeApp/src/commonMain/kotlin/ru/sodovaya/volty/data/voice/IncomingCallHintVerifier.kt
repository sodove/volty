package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.sodovaya.volty.data.nearby.DirectCallFailure
import ru.sodovaya.volty.data.nearby.DirectCallPhase
import ru.sodovaya.volty.data.nearby.DirectCallReply
import ru.sodovaya.volty.data.nearby.DirectCallSnapshot
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.nearby.community.invitationDraftId

internal sealed interface IncomingCallHintReply {
    data class Ringing(val call: DirectCallSnapshot) : IncomingCallHintReply
    data object Gone : IncomingCallHintReply
    data class Failed(val reason: DirectCallFailure) : IncomingCallHintReply
}

/** Read-only evidence. The consumer must own notification lifetime; this cannot accept a call. */
internal class IncomingCallHintVerifier(private val account: SocialAuthenticatedAccount,
    private val runtime: AuthenticatedVoiceRuntime) {
    init {
        require(runtime.belongsTo(account)) {"Hint runtime belongs to another authenticated account"}
        check(account.job.isActive && !runtime.retired.value) {"Hint account retired"}
    }

    suspend fun verify(callId: String): IncomingCallHintReply {
        ensureLive()
        invitationDraftId(callId)
        val reply = runtime.callStatus(callId)
        ensureLive()
        return when (reply) {
            is DirectCallReply.Failed -> IncomingCallHintReply.Failed(reply.reason)
            is DirectCallReply.Ready -> when {
                reply.call.callId != callId -> IncomingCallHintReply.Failed(DirectCallFailure.INVALID_RESPONSE)
                reply.call.phase != DirectCallPhase.RINGING || reply.call.calleeId != account.userId.value ||
                    reply.call.callerId == account.userId.value -> IncomingCallHintReply.Gone
                else -> IncomingCallHintReply.Ringing(reply.call)
            }
        }
    }

    private suspend fun ensureLive() {
        currentCoroutineContext().ensureActive()
        if (!account.job.isActive || runtime.retired.value) throw CancellationException("Hint account retired")
    }
}
