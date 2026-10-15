package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.HistoryGpsFix
import ru.sodovaya.volty.domain.history.HistoryGpsProvider
import ru.sodovaya.volty.domain.location.LocationSource
import ru.sodovaya.volty.domain.history.HistoryTick
import ru.sodovaya.volty.domain.history.historyAxisEpochMillis
import ru.sodovaya.volty.domain.location.RideLocationFix

/** Converts an admitted location fix into the immutable GPS point stored for a ride. */
internal fun historyGpsFix(
    origin: HistoryTick,
    now: HistoryTick,
    fix: RideLocationFix,
    maxAgeMillis: Long,
): HistoryGpsFix? {
    require(maxAgeMillis >= 0L) { "Maximum GPS fix age must be non-negative" }

    if (historyAxisEpochMillis(origin, now) == null) return null
    // Retraction: capturedAtEpochMillis follows navigation's receipt/last-known
    // freshness policy; on live Android callbacks it is not acquisition time.
    val measurementEpochMillis = fix.measurementAtEpochMillis ?: return null

    val capturedElapsedMillis = fix.elapsedRealtimeMillis ?: return null
    if (capturedElapsedMillis < origin.elapsedMillis || capturedElapsedMillis > now.elapsedMillis) return null
    if (now.elapsedMillis - capturedElapsedMillis > maxAgeMillis) return null

    val latitude = fix.coordinate.latitude
    val longitude = fix.coordinate.longitude
    if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
    if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
    if (!fix.accuracyMeters.isFinite() || fix.accuracyMeters < 0.0) return null
    val bearing = fix.bearingDegrees
    if (bearing != null && (!bearing.isFinite() || bearing !in 0.0..<360.0)) return null

    return HistoryGpsFix(
        tick = HistoryTick(
            epochMillis = measurementEpochMillis,
            elapsedMillis = capturedElapsedMillis,
            clockGeneration = origin.clockGeneration,
        ),
        lat = latitude,
        lon = longitude,
        accuracyMeters = fix.accuracyMeters,
        heading = bearing,
        provider = when (fix.source) {
            LocationSource.GPS -> HistoryGpsProvider.GPS
            LocationSource.NETWORK -> HistoryGpsProvider.NETWORK
            LocationSource.PASSIVE -> HistoryGpsProvider.PASSIVE
        },
    )
}
