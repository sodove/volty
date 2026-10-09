package ru.sodovaya.volty.backend.nearby

internal class FcmCallPushPreparation(private val authorization: FcmOAuthProvider,
                                      private val sender: HttpFcmSender) : CallPushPreparation {
    override fun prepare(): PreparedCallPush = when (val result = authorization.authorize()) {
        is FcmAuthorization.Retry -> PreparedCallPush.Retry(FcmSendResult.Retry(result.reason, result.afterMillis))
        is FcmAuthorization.Ready -> PreparedCallPush.Ready(CallPushAttempt { claim, ttl ->
            val sent = sender.send(result.bearer, claim.target, claim.hint, ttl)
            if (sent is FcmSendResult.Retry && sent.reason == FcmRetryReason.CONFIGURATION) authorization.invalidate(result.bearer)
            sent
        })
    }
}
