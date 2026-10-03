package ru.sodovaya.volty.domain.voice

/** Caller supplies the current monotonic clock including sleep; no inferred/default credit. */
fun VoiceSessionState.authorizesEngine(tag: VoiceSessionTag, nowMillis: Long): Boolean =
    wanted && generation == tag.generation && sessionId == tag.sessionId &&
        phase in setOf(VoicePhase.CONNECTING, VoicePhase.CONNECTED, VoicePhase.RECONNECTING) &&
        lastLeaseRequestId != null && nowMillis >= 0 && leaseDeadlineMillis?.let { nowMillis < it } == true
