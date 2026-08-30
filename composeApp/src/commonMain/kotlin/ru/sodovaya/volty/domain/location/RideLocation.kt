package ru.sodovaya.volty.domain.location

import ru.sodovaya.volty.domain.navigation.GeoCoordinate

enum class LocationConsumer {
    MAP,
    NAVIGATION,
    SOCIAL_SHARING,
}

enum class LocationSource {
    GPS,
    NETWORK,
    PASSIVE,
}

data class RideLocationFix(
    val coordinate: GeoCoordinate,
    val accuracyMeters: Double,
    val speedMetersPerSecond: Double?,
    val bearingDegrees: Double?,
    val capturedAtEpochMillis: Long,
    val elapsedRealtimeMillis: Long?,
    val source: LocationSource,
) {
    init {
        require(accuracyMeters.isFinite() && accuracyMeters >= 0.0) {
            "Location accuracy must be finite and non-negative"
        }
        require(speedMetersPerSecond == null || speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0) {
            "Location speed must be finite and non-negative when present"
        }
        require(bearingDegrees == null || bearingDegrees.isFinite() && bearingDegrees in 0.0..<360.0) {
            "Location bearing must be finite and within [0, 360) when present"
        }
        require(elapsedRealtimeMillis == null || elapsedRealtimeMillis >= 0L) {
            "Elapsed realtime must be non-negative when present"
        }
    }
}

sealed interface RideLocationStatus {
    data object NotRequested : RideLocationStatus
    data object PermissionRequired : RideLocationStatus
    data object PermissionDenied : RideLocationStatus
    data object ProviderDisabled : RideLocationStatus
    data object Searching : RideLocationStatus
    data class Available(val fix: RideLocationFix) : RideLocationStatus
}

data class RideLocationState(
    val status: RideLocationStatus = RideLocationStatus.NotRequested,
    val demands: Set<LocationConsumer> = emptySet(),
)
