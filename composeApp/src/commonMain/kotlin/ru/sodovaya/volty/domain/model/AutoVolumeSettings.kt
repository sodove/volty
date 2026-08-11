package ru.sodovaya.volty.domain.model

/**
 * Per-vehicle speed-based media-volume automation.
 *
 * The feature is opt-in. Values are percentages and km/h so the profile stays
 * portable between Android devices with different media-volume step counts;
 * the Android service quantizes the target to the phone's actual steps.
 */
data class AutoVolumeSettings(
    val enabled: Boolean = false,
    val minVolumePercent: Int = 30,
    val maxVolumePercent: Int = 80,
    val minSpeedKmh: Int = 5,
    val maxSpeedKmh: Int = 30,
    val deadbandKmh: Int = 2
) {
    init {
        require(minVolumePercent in 0..100 && maxVolumePercent in 0..100)
        require(minVolumePercent <= maxVolumePercent)
        require(minSpeedKmh >= 0 && maxSpeedKmh > minSpeedKmh)
        require(deadbandKmh in 0..10)
    }
}
