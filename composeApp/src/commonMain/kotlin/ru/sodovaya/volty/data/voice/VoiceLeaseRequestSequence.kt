package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.voice.VoiceLeaseRequestId

/** One composite voice owner shares this across call admission and session maintenance. */
class VoiceLeaseRequestSequence {
    private val mutex = Mutex()
    private var nextOrdinal = 0L

    internal suspend fun reserve(monotonicMillis: () -> Long): Pair<VoiceLeaseRequestId, Long> = mutex.withLock {
        check(nextOrdinal < Long.MAX_VALUE)
        VoiceLeaseRequestId(nextOrdinal++) to monotonicMillis().also {require(it >= 0)}
    }
}
