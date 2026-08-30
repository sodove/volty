package ru.sodovaya.volty.domain.navigation

data class RouteProgressPolicy(
    val freshFixMaxAgeMillis: Long = 5_000L,
    val maxAccuracyMeters: Double = 50.0,
    val minimumOffRouteDistanceMeters: Double = 30.0,
    val offRouteConfirmationFixes: Int = 3,
    val offRouteConfirmationWindowMillis: Long = 2_000L,
    val arrivalRemainingDistanceMeters: Double = 40.0,
    val arrivalDestinationDistanceMeters: Double = 25.0,
    val arrivalConfirmationFixes: Int = 2,
    val backwardsProgressToleranceMeters: Double = 30.0,
    val projectionSearchWindowMeters: Double = 250.0,
) {
    init {
        require(freshFixMaxAgeMillis > 0L) { "Fresh fix age must be positive" }
        require(maxAccuracyMeters.isFinite() && maxAccuracyMeters > 0.0) {
            "Maximum accuracy must be finite and positive"
        }
        require(minimumOffRouteDistanceMeters.isFinite() && minimumOffRouteDistanceMeters > 0.0) {
            "Minimum off-route distance must be finite and positive"
        }
        require(offRouteConfirmationFixes >= 2) { "Off-route confirmation needs at least two fixes" }
        require(offRouteConfirmationWindowMillis > 0L) {
            "Off-route confirmation window must be positive"
        }
        require(arrivalRemainingDistanceMeters.isFinite() && arrivalRemainingDistanceMeters >= 0.0) {
            "Arrival remaining distance must be finite and non-negative"
        }
        require(arrivalDestinationDistanceMeters.isFinite() && arrivalDestinationDistanceMeters >= 0.0) {
            "Arrival destination distance must be finite and non-negative"
        }
        require(arrivalConfirmationFixes >= 2) { "Arrival confirmation needs at least two fixes" }
        require(backwardsProgressToleranceMeters.isFinite() && backwardsProgressToleranceMeters >= 0.0) {
            "Backwards progress tolerance must be finite and non-negative"
        }
        require(projectionSearchWindowMeters.isFinite() && projectionSearchWindowMeters > 0.0) {
            "Projection search window must be finite and positive"
        }
    }

    fun offRouteThreshold(accuracyMeters: Double): Double =
        maxOf(minimumOffRouteDistanceMeters, 2.0 * accuracyMeters)
}

val defaultRouteProgressPolicy = RouteProgressPolicy()
