package ru.sodovaya.volty.backend.nearby

internal class FcmBearer(val value: String) { override fun toString() = "FcmBearer(<redacted>)" }
internal data class CallPushHint(val callId: String, val revision: Long)
internal enum class FcmRetryReason { TRANSPORT, INVALID_RESPONSE, CONFIGURATION, THROTTLED, UNAVAILABLE }
internal sealed interface FcmSendResult {
    data object Accepted : FcmSendResult
    data object Unregistered : FcmSendResult
    data object InvalidRequest : FcmSendResult
    data class Retry(val reason: FcmRetryReason, val afterMillis: Long = 1000) : FcmSendResult
}
