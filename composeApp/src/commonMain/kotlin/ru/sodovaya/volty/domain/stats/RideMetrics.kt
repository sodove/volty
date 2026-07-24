package ru.sodovaya.volty.domain.stats

import kotlin.math.abs

/** Pure ride math. Null means "not meaningful yet", never a fake 0. */
object RideMetrics {
    /** Below this the vehicle is standing still and Wh/km is a division blow-up. */
    private const val MIN_SPEED_KMH = 0.5f

    fun instantWhPerKm(powerW: Float, speedKmh: Float): Float? =
        if (speedKmh < MIN_SPEED_KMH) null else abs(powerW) / speedKmh

    fun sessionWhPerKm(consumedWh: Float, tripKm: Float): Float? =
        if (tripKm <= 0f) null else consumedWh / tripKm
}
