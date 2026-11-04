package ru.sodovaya.volty.domain.history

/**
 * The GPS speed series of a ride whose vehicle has no motion source (a battery-only vehicle), 2026-11-04.
 *
 * Before this a battery-only ride stored no speed at all: `speed_kmh` is registered only for CONTROLLER and
 * VEHICLE_MOTION sources, and the GPS track keeps coordinates, not the provider's speed. Ride detail had no speed
 * curve, and the summary had no top speed and no moving time (its average fell back to wall duration).
 *
 * Shape, chosen to change nothing that exists:
 *  - **One source of its own**, [HistorySourceKind.GPS] with the fixed id [HISTORY_GPS_SPEED_SOURCE_ID], added by
 *    [RideRecordingController] to the ride's registry only when the registry has no motion source
 *    ([historyRecordedSources]). A vehicle with a controller never gets it, so its `speed_kmh` cannot be joined,
 *    shadowed or averaged with GPS: there is nothing to mix.
 *  - **Its own metric**, `gps_speed_kmh`, never `speed_kmh`. Readers that ask the motion tier for `speed_kmh` keep
 *    asking exactly that; the readers that fall back to GPS speed ask for it by name.
 *  - **Written with the fix it came from**, in the same batch and at the same tick ([historyGpsSpeedSample]), so it
 *    is held, released and dropped with the GPS track across a BLE dropout, never on its own.
 *  - **No schema change.** Source kinds are stored by name (`HistorySourceV2.kind TEXT`) and the compact series key
 *    is (ride, source, metric, cell) text, so a new kind and a new metric are rows, not columns. An app version
 *    before this one cannot read such a ride (`HistorySourceKind.valueOf("GPS")` throws); downgrades to those are
 *    not supported, stated rather than hidden. From this build on, readers skip a kind they do not know
 *    (`HistoryTolerantParsing.kt`, review I1), so the NEXT new kind does not repeat the trap.
 */
const val HISTORY_GPS_SPEED_SOURCE_ID = "gps-speed-v1"
const val HISTORY_GPS_SPEED_METRIC = "gps_speed_kmh"

/** A fix less accurate than this records no speed: the dashboard's and the distance fill's 50 m bound. */
const val HISTORY_GPS_SPEED_MAX_ACCURACY_METERS = 50.0

/** The stored label is not shown to the rider (the UI labels a GPS source by its kind); it is fixed text. */
val historyGpsSpeedSource = HistorySource(HISTORY_GPS_SPEED_SOURCE_ID, HistorySourceKind.GPS, "GPS")

private val historyMotionSourceKinds = setOf(HistorySourceKind.CONTROLLER, HistorySourceKind.VEHICLE_MOTION)

/** Whether a ride recorded with [sources] gets the GPS speed series: only when no source can report a speed. */
fun historyRecordsGpsSpeed(sources: List<HistorySource>): Boolean =
    sources.none { it.kind in historyMotionSourceKinds }

/**
 * The registry a ride is begun with: [sources] as given, plus [historyGpsSpeedSource] when
 * [historyRecordsGpsSpeed]. A registry that already carries the GPS id is left alone (no duplicate binding).
 */
fun historyRecordedSources(sources: List<HistorySource>): List<HistorySource> =
    if (historyRecordsGpsSpeed(sources) && sources.none { it.id == HISTORY_GPS_SPEED_SOURCE_ID })
        sources + historyGpsSpeedSource else sources

/**
 * The speed sample stored beside a recorded fix, at the fix's own tick: the provider's speed in km/h, or an unknown.
 * A fix that did not report a speed is UNAVAILABLE (never a 0); a reported NaN, infinite or negative speed is
 * INVALID_VALUE. The location layer already drops those before a fix exists, so the second branch is a guard.
 */
fun historyGpsSpeedSample(fix: HistoryGpsFix, speedMetersPerSecond: Double?): HistorySample {
    val key = HistorySeriesKey(HISTORY_GPS_SPEED_SOURCE_ID, HISTORY_GPS_SPEED_METRIC)
    return when {
        speedMetersPerSecond == null -> HistorySample(fix.tick, key, null, HistoryUnknownReason.UNAVAILABLE)
        // Review M2: the dashboard refuses a fix worse than 50 m (RideSpeed) and the distance fill does too, so a run
        // of coarse network fixes must not feed top speed and moving time here either. Not a usable reading: UNAVAILABLE.
        !(fix.accuracyMeters <= HISTORY_GPS_SPEED_MAX_ACCURACY_METERS) ->
            HistorySample(fix.tick, key, null, HistoryUnknownReason.UNAVAILABLE)
        !speedMetersPerSecond.isFinite() || speedMetersPerSecond < 0.0 ->
            HistorySample(fix.tick, key, null, HistoryUnknownReason.INVALID_VALUE)
        else -> HistorySample(fix.tick, key, speedMetersPerSecond * 3.6)
    }
}
