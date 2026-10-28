package ru.sodovaya.volty.domain.history

data class HistoryTick(
    val epochMillis: Long,
    val elapsedMillis: Long,
    val clockGeneration: Long,
) {
    init {
        require(elapsedMillis >= 0L)
        require(clockGeneration >= 0L)
    }
}

enum class HistorySourceKind { VEHICLE_BATTERY, VEHICLE_MOTION, BATTERY, CONTROLLER, BATTERY_ASSEMBLY, DERIVED_BATTERY, REPORTED_CAN }

data class HistorySeriesKey(
    val sourceId: String,
    val metricId: String,
    val cellIndex: Int? = null,
) {
    init {
        require(sourceId.isNotBlank())
        require(metricId.isNotBlank())
        require(cellIndex == null || cellIndex >= 0)
    }
}

enum class HistoryUnknownReason { UNAVAILABLE, PARTIAL_SOURCE, INVALID_VALUE, GAP, LEGACY }

data class HistorySample(
    val tick: HistoryTick,
    val key: HistorySeriesKey,
    val value: Double?,
    val unknown: HistoryUnknownReason? = null,
) {
    init {
        require(
            (value == null && unknown != null) ||
                (value != null && value.isFinite() && unknown == null),
        )
    }
}

data class HistorySource(
    val id: String,
    val kind: HistorySourceKind,
    val label: String,
) {
    init {
        require(id.isNotBlank())
    }
}

enum class HistoryGpsProvider { GPS, NETWORK, PASSIVE }

data class HistoryGpsFix(
    val tick: HistoryTick,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Double,
    val heading: Double?,
    /** Null for recorded fixes whose provider was not retained; never infer GPS. */
    val provider: HistoryGpsProvider? = null,
) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0)
        require(lon.isFinite() && lon in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters >= 0.0)
        require(heading == null || (heading.isFinite() && heading >= 0.0 && heading < 360.0))
    }
}

enum class HistoryRideStatus { RECORDING, COMPLETED, INTERRUPTED, LEGACY }

data class HistoryRide(
    val id: String,
    val vehicleId: String,
    val started: HistoryTick,
    val ended: HistoryTick?,
    val status: HistoryRideStatus,
) {
    init {
        require(id.isNotBlank())
        require(vehicleId.isNotBlank())
    }
}

enum class HistoryMarkerKind {
    CONNECTED,
    BLE_GAP,
    GPS_GAP,
    CLOCK_CHANGE,
    OVERFLOW,
    MANUAL_STOP,
    VEHICLE_SWITCH,
    SOURCE_CHANGE,
    INTERRUPTED,
}

/** Null source retains the whole-recording meaning of existing durable markers. */
data class HistoryMarker(val tick: HistoryTick, val kind: HistoryMarkerKind, val sourceId: String? = null) {
    init {
        require(sourceId == null || (sourceId.isNotBlank() &&
            (kind == HistoryMarkerKind.BLE_GAP || kind == HistoryMarkerKind.CONNECTED)))
    }
}

data class HistoryRange(val startEpochMillis: Long, val endEpochMillis: Long) {
    init {
        require(endEpochMillis >= startEpochMillis)
    }
}

data class HistoryBatch(
    val rideId: String,
    val samples: List<HistorySample>,
    val gps: List<HistoryGpsFix>,
    val markers: List<HistoryMarker>,
    /** Stable across retry of this exact immutable batch; null is a one-shot append. */
    val receiptId: String? = null,
    val buckets: List<HistoryBucket> = emptyList(),
) {
    init {
        require(rideId.isNotBlank())
        require(receiptId == null || receiptId.isNotBlank())
    }
}

data class HistoryCursor(val epochMillis: Long, val stableId: String) {
    init {
        require(stableId.isNotBlank())
    }
}

data class HistoryPage<T>(val items: List<T>, val next: HistoryCursor?, val truncated: Boolean)

data class HistoryStats(
    val minimum: Double?,
    val maximum: Double?,
    val energyWh: Double?,
    val complete: Boolean,
    /** Mean of the recorded samples in the range (sample-weighted, not time-weighted); null when none. */
    val average: Double? = null,
    /**
     * The store's cheap proof failed but the series might still be exact within the unknown-or-gap tolerance; the
     * read repository walks the detail and settles it (it clears this flag). Never shown to the rider.
     */
    val pendingWalk: Boolean = false,
    /**
     * A genuine gap (an interval over the limit, a marker break, a clock jump) lies inside a series that is still
     * [complete] by tolerance. Short unknown runs do not set it. Energy reads approximate when set: the integral
     * skipped real time, which can hold far more than the same fraction of the energy.
     */
    val hasGap: Boolean = false,
) {
    init {
        require(minimum == null || minimum.isFinite())
        require(maximum == null || maximum.isFinite())
        require(energyWh == null || energyWh.isFinite())
        require(average == null || average.isFinite())
        require(minimum == null || maximum == null || minimum <= maximum)
    }
}
