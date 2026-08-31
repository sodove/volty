package ru.sodovaya.volty.domain.location

data class LocationUpdateRequest(
    val intervalMillis: Long,
    val minDistanceMeters: Float,
)

object LocationUpdatePolicy {
    val ordinaryRequest = LocationUpdateRequest(
        intervalMillis = 1_000L,
        minDistanceMeters = 5f,
    )

    val navigationRequest = LocationUpdateRequest(
        intervalMillis = 1_000L,
        minDistanceMeters = 0f,
    )

    fun requestFor(demands: Set<LocationConsumer>): LocationUpdateRequest =
        if (LocationConsumer.NAVIGATION in demands) navigationRequest else ordinaryRequest
}
