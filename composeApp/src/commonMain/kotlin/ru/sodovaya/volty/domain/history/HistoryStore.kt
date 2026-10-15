package ru.sodovaya.volty.domain.history

/** Durable canonical data, before UI conversion and overview projection. */
interface HistoryStore {
    suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int = 20000): HistoryRecordedKeys
    suspend fun begin(ride: HistoryRide, sources: List<HistorySource>)
    suspend fun ride(rideId: String): HistoryRide?
    suspend fun append(batch: HistoryBatch)
    suspend fun finish(rideId: String, ended: HistoryTick, status: HistoryRideStatus)
    suspend fun recoverInterrupted(): Int
    suspend fun rides(before: HistoryCursor?, limit: Int = 20): HistoryPage<HistoryRide>
    suspend fun sources(rideId: String): List<HistorySource>
    suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange,
        after: HistoryCursor?, limit: Int = 20000): HistoryPage<HistorySample>
    suspend fun gps(rideId: String, range: HistoryRange,
        after: HistoryCursor?, limit: Int = 20000): HistoryPage<HistoryGpsFix>
    suspend fun markers(rideId: String, range: HistoryRange): List<HistoryMarker>
    suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats
    suspend fun buckets(rideId: String, key: HistorySeriesKey, range: HistoryRange,
        after: HistoryCursor?, limit: Int = 20000): HistoryPage<HistoryBucket>
    suspend fun delete(rideId: String)
}
