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

/** Recorded keys for one expanded source group, never a guessed dense cell range. */
data class HistoryRecordedKeys(val keys: List<HistorySeriesKey>, val truncated: Boolean)

/** One storage snapshot: route coordinates cannot outlive their recorded gap facts. */
data class HistoryRoutePage(val origin: HistoryTick?, val fixes: HistoryPage<HistoryGpsFix>, val markers: List<HistoryMarker>)

interface HistoryReadRepository {
    /** Optional persisted wall-time window for unclosed legacy recordings.
     * It is available data, not a manufactured completion time. */
    suspend fun legacyRecordedRange(rideId: String): HistoryRange? = null
    suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int = 20000): HistoryRecordedKeys
    suspend fun rides(before: HistoryCursor? = null): HistoryPage<HistoryRide>
    suspend fun ride(rideId: String): HistoryRide?
    suspend fun sources(rideId: String): List<HistorySource>
    suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int = 600): HistorySeries
    /** Keep the cursor even for equal-time fixes; narrowing the range cannot replace it. */
    suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor? = null): HistoryPage<HistoryGpsFix>
    suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor? = null): HistoryRoutePage
    suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats
}
