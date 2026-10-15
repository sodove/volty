package ru.sodovaya.volty.domain.history

/** Canonical recorded values. A null origin denotes legacy wall-time-only data. */
data class HistorySeries(
    val key: HistorySeriesKey,
    val samples: List<HistorySample>,
    val markers: List<HistoryMarker>,
    /** True when the read budget, rather than overview thinning, omitted stored rows. */
    val truncated: Boolean,
    val origin: HistoryTick? = null,
)

interface HistoryReadRepository {
    suspend fun rides(before: HistoryCursor? = null): HistoryPage<HistoryRide>
    suspend fun ride(rideId: String): HistoryRide?
    suspend fun sources(rideId: String): List<HistorySource>
    suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int = 600): HistorySeries
    /** Keep the cursor even for equal-time fixes; narrowing the range cannot replace it. */
    suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor? = null): HistoryPage<HistoryGpsFix>
    suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats
}
