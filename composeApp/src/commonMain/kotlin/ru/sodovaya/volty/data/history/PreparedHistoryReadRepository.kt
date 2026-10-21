package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Clock recovery must complete before a screen can observe unfinished V2 rows. */
class PreparedHistoryReadRepository(private val delegate: HistoryReadRepository,
    private val prepare: suspend () -> Unit) : HistoryReadRepository by delegate {
    override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> {
        prepare(); currentCoroutineContext().ensureActive()
        return delegate.rides(before)
    }
    override suspend fun ride(rideId: String): HistoryRide? {
        prepare(); currentCoroutineContext().ensureActive()
        return delegate.ride(rideId)
    }
}
