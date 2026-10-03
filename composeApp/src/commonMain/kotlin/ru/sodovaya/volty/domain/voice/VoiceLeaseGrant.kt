package ru.sodovaya.volty.domain.voice

import kotlin.jvm.JvmInline

/** Increasing request ordinal within one generation; equal clock ticks are not an ordering key. */
@JvmInline
value class VoiceLeaseRequestId(val value: Long) {
    init { require(value >= 0) }
}

/**
 * Remaining credit authorized by the server, charged from local request start.
 * Both local times must use the same monotonic clock including device sleep
 * (Android elapsedRealtime), never the server or phone wall clock.
 * The SessionPort owns request ordinals and captures start before sending HTTP.
 */
data class VoiceLeaseGrant(
    val requestId: VoiceLeaseRequestId,
    val requestStartedMillis: Long,
    val responseReceivedMillis: Long,
    val remainingMillis: Long,
) {
    init {
        require(requestStartedMillis >= 0)
        require(responseReceivedMillis >= requestStartedMillis)
        require(remainingMillis in 1..30_000)
        require(requestStartedMillis <= Long.MAX_VALUE - remainingMillis)
    }
    val deadlineMillis: Long get() = requestStartedMillis + remainingMillis
    val expiredOnReceipt: Boolean get() = responseReceivedMillis >= deadlineMillis
}
