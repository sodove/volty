package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.stats.MotionReadings
import ru.sodovaya.volty.presentation.navigation.LocationUiStatus

/** Which instrument the speed a dashboard shows came from. */
enum class RideSpeedSource { CONTROLLER, GPS }

/** A speed some instrument actually reported, in km/h, and which one. */
data class RideSpeedReading(val kmh: Float, val source: RideSpeedSource)

/**
 * The one speed rule of every Ride renderer (Light, Classic, Clean): the controller's speed while the controller is
 * connected and its speed is known; otherwise a fresh GPS speed; otherwise nothing (the renderer prints `—`). Never a
 * fabricated 0.
 *
 * Lifted out of `LightDashboardMapper.speedKmh` (which now delegates here) so Classic and Clean decide the same way.
 * Retraction: before this, Classic and Clean read `MotionReadings.speedKmh(motion)` alone and never saw the GPS speed
 * RootScreen already computed, so a battery-only vehicle showed `—` on two of the three dashboards although the phone
 * knew how fast it was moving (investigation `gps-speed-battery-only.md`, 2026-11-04). They also skipped the
 * `isConnected` gate Light had. Production never notices that half: `MotionAggregator` publishes
 * `ControllerData(isConnected = false)` with no speed source once every controller is offline, so a disconnected
 * controller already had an unknown speed; the gate matters only for an incoherent sample, and there it now agrees
 * with Light.
 */
object RideSpeed {
    /** A fix older than this is not a live speed: the same 5 s bound navigation's FRESH status uses. */
    const val GPS_MAX_AGE_MILLIS = 5_000L

    /** Worse than this the fix is POOR_ACCURACY for navigation, and its speed is not trusted either. */
    const val GPS_MAX_ACCURACY_METERS = 50.0

    fun reading(motion: ControllerData, gpsSpeedKmh: Float?): RideSpeedReading? {
        MotionReadings.speedKmh(motion)?.takeIf { motion.isConnected }?.let {
            return RideSpeedReading(it, RideSpeedSource.CONTROLLER)
        }
        return gpsSpeedKmh?.takeIf { it.isFinite() && it >= 0f }?.let { RideSpeedReading(it, RideSpeedSource.GPS) }
    }

    fun kmh(motion: ControllerData, gpsSpeedKmh: Float?): Float? = reading(motion, gpsSpeedKmh)?.kmh

    /** Whether [reading] would take the controller's speed, i.e. no GPS fallback is needed. */
    fun controllerSpeedKnown(motion: ControllerData): Boolean = reading(motion, null) != null

    /** How full a speed gauge is drawn, 0..1; 0 (a parked needle, not a reading) when no speed is shown. */
    fun fraction(motion: ControllerData, maxSpeedKmh: Float, gpsSpeedKmh: Float?): Float {
        val speed = kmh(motion, gpsSpeedKmh) ?: return 0f
        if (!(maxSpeedKmh > 0f)) return 0f
        return (speed / maxSpeedKmh).coerceIn(0f, 1f)
    }

    /**
     * The GPS speed the dashboards may show, in km/h, or null. Extracted from RootScreen, where it was
     * `fix.speedMetersPerSecond * 3.6` gated on navigation's FRESH status only.
     *
     * FRESH is decided when the location repository EMITS (`LightNavigationComponent.locationUiStatus`), so a fix
     * stream that simply stops (a tunnel, the provider going quiet) left the last fix FRESH, and its speed on screen,
     * indefinitely. This therefore re-checks the fix's own age against [nowEpochMillis] as well; RootScreen recomposes
     * when the newest fix expires.
     */
    fun freshGpsSpeedKmh(status: LocationUiStatus, fix: RideLocationFix?, nowEpochMillis: Long): Float? =
        fix?.let {
            freshGpsSpeedKmh(status, it.capturedAtEpochMillis, it.accuracyMeters, it.speedMetersPerSecond, nowEpochMillis)
        }

    /**
     * The same rule over raw fields. [RideLocationFix] refuses a NaN or negative speed at construction; this overload
     * exists so the guard here is tested too, rather than trusted to a constructor somebody may relax.
     */
    internal fun freshGpsSpeedKmh(
        status: LocationUiStatus,
        capturedAtEpochMillis: Long,
        accuracyMeters: Double,
        speedMetersPerSecond: Double?,
        nowEpochMillis: Long,
    ): Float? {
        if (status != LocationUiStatus.FRESH) return null
        val age = nowEpochMillis - capturedAtEpochMillis
        if (age !in 0L..GPS_MAX_AGE_MILLIS) return null
        if (!accuracyMeters.isFinite() || accuracyMeters > GPS_MAX_ACCURACY_METERS) return null
        val speed = speedMetersPerSecond ?: return null
        if (!speed.isFinite() || speed < 0.0) return null
        return (speed * 3.6).toFloat()
    }

    /** Milliseconds until a fix captured at [capturedAtEpochMillis] stops being fresh (0 when it already has). */
    fun gpsExpiresInMillis(capturedAtEpochMillis: Long, nowEpochMillis: Long): Long =
        (capturedAtEpochMillis + GPS_MAX_AGE_MILLIS + 1L - nowEpochMillis).coerceAtLeast(0L)
}
