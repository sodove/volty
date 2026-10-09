package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.CancellationException

/** Observes the actual HTTP receipt without logging the private registration intent. */
internal class AndroidObservedPushRegistration(private val delegate: PushRegistrationClient) : PushRegistrationClient {
    override suspend fun submit(intent: PushRegistrationIntent): PushRegistrationResult {
        AndroidCallPushDiagnostics.record(CallPushDiagnosticEvent.REGISTRATION_SUBMITTED)
        return try {
            val result = delegate.submit(intent)
            AndroidCallPushDiagnostics.record(when (result) {
                PushRegistrationResult.APPLIED -> CallPushDiagnosticEvent.REGISTRATION_APPLIED
                PushRegistrationResult.STALE -> CallPushDiagnosticEvent.REGISTRATION_STALE
                PushRegistrationResult.TOKEN_CONFLICT -> CallPushDiagnosticEvent.REGISTRATION_TOKEN_CONFLICT
                PushRegistrationResult.ACCESS_DENIED -> CallPushDiagnosticEvent.REGISTRATION_ACCESS_DENIED
                PushRegistrationResult.INVALID -> CallPushDiagnosticEvent.REGISTRATION_INVALID
                PushRegistrationResult.INVALID_RESPONSE -> CallPushDiagnosticEvent.REGISTRATION_INVALID_RESPONSE
                PushRegistrationResult.UNAVAILABLE -> CallPushDiagnosticEvent.REGISTRATION_UNAVAILABLE
            })
            result
        } catch (cancelled: CancellationException) {
            AndroidCallPushDiagnostics.record(CallPushDiagnosticEvent.CANCELLED); throw cancelled
        } catch (failure: Exception) {
            AndroidCallPushDiagnostics.record(CallPushDiagnosticEvent.REGISTRATION_ERROR); throw failure
        }
    }
    override fun retire() = delegate.retire()
}
