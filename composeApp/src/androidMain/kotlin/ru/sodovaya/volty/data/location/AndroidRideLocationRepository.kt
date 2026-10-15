package ru.sodovaya.volty.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.location.LocationConsumer
import ru.sodovaya.volty.domain.location.LocationDemandPolicy
import ru.sodovaya.volty.domain.location.LocationDemandPolicyState
import ru.sodovaya.volty.domain.location.LocationSource
import ru.sodovaya.volty.domain.location.LocationTimestampPolicy
import ru.sodovaya.volty.domain.location.LocationUpdateRequest
import ru.sodovaya.volty.domain.location.LocationUpdatePolicy
import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.location.RideLocationRepository
import ru.sodovaya.volty.domain.location.RideLocationState
import ru.sodovaya.volty.domain.location.RideLocationStatus

/** The process-scoped owner of Android's platform location registrations. */
class AndroidRideLocationRepository(context: Context) : RideLocationRepository {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _state = MutableStateFlow(RideLocationState())
    private var policyState: LocationDemandPolicyState = LocationDemandPolicy.initialState
    private data class NativeRequest(
        val generation: Long,
        val providers: List<String>,
        val cadence: LocationUpdateRequest,
    )

    private val registration = OwnedLocationRegistration<NativeRequest, LocationListener>(
        create = { listenerFor(it.generation) },
        register = { request, listener ->
            request.providers.forEach { requestUpdates(it, listener, request.cadence) }
        },
        unregister = { manager.removeUpdates(it) },
    )

    override val requiredPermissions: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    override val state: StateFlow<RideLocationState> = _state.asStateFlow()

    override suspend fun setDemand(consumer: LocationConsumer, enabled: Boolean) =
        withContext(Dispatchers.Main.immediate) {
            val transition = LocationDemandPolicy.setDemand(policyState, consumer, enabled)
            policyState = transition.state
            publishState()
            // Retraction: an unchanged desired demand is not a native ACK. In particular,
            // a second negative must retry the original listener after removeUpdates failed.
            if (policyState.demands.isEmpty()) stopPlatformUpdates()
            else tryStartPlatformUpdates()
        }

    override suspend fun refreshPermissionAndProviders() =
        withContext(Dispatchers.Main.immediate) {
            if (policyState.demands.isEmpty()) {
                publishUnavailable(RideLocationStatus.NotRequested)
            } else {
                tryStartPlatformUpdates(rethrow = false)
            }
        }

    private fun tryStartPlatformUpdates(
        providers: List<String> = enabledProviders(),
        rethrow: Boolean = true,
    ) {
        try {
            startPlatformUpdates(providers)
        } catch (error: LocationPermissionRequiredException) {
            publishUnavailable(RideLocationStatus.PermissionRequired)
            if (rethrow) throw error
        } catch (error: SecurityException) {
            publishUnavailable(RideLocationStatus.PermissionDenied)
            if (rethrow) throw error
        } catch (error: NoEnabledLocationProviderException) {
            publishUnavailable(RideLocationStatus.ProviderDisabled)
            if (rethrow) throw error
        }
    }

    private fun publishUnavailable(status: RideLocationStatus) {
        policyState = LocationDemandPolicy.setStatus(policyState, status)
        // Publish only after cleanup: a synchronous observer can start a replacement, which
        // must not subsequently be stopped by the error path belonging to this old attempt.
        try { stopPlatformUpdates() } finally { publishState() }
    }

    private fun startPlatformUpdates(providers: List<String>) {
        checkLocationPermission()
        if (providers.isEmpty()) throw NoEnabledLocationProviderException()

        val cadence = LocationUpdatePolicy.requestFor(policyState.demands)
        val current = registration.key
        if (registration.active && current?.generation == policyState.generation &&
            current.providers == providers && current.cadence == cadence) return

        // Fence the old callback generation before native removal, including failed cleanup.
        val preparing = LocationDemandPolicy.restart(policyState)
        policyState = preparing
        publishState()
        val request = NativeRequest(preparing.generation, providers.toList(), cadence)
        // Retraction: storing only after registration orphaned partially registered listeners
        // when rollback failed. This owner reserves before native calls and retains on failure.
        // Retraction: delivery guards alone do not prevent an orphan native allocation.
        // A synchronous observer may remove demand or install a successor during publication.
        val changed = registration.reconcile(request) { policyState === preparing }
        if (changed && policyState === preparing && registration.active && registration.key == request)
            emitRecentLastKnownLocation(providers, request.generation)
    }

    private fun stopPlatformUpdates() {
        registration.reconcile(null)
    }

    private fun requestUpdates(
        provider: String,
        listener: LocationListener,
        requestPolicy: LocationUpdateRequest,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val request = LocationRequest.Builder(requestPolicy.intervalMillis)
                .setMinUpdateDistanceMeters(requestPolicy.minDistanceMeters)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .build()
            manager.requestLocationUpdates(
                provider,
                request,
                ContextCompat.getMainExecutor(appContext),
                listener,
            )
        } else {
            @Suppress("DEPRECATION")
            manager.requestLocationUpdates(
                provider,
                requestPolicy.intervalMillis,
                requestPolicy.minDistanceMeters,
                listener,
            )
        }
    }

    private fun checkLocationPermission() {
        if (!hasLocationPermission()) throw LocationPermissionRequiredException()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun enabledProviders(): List<String> = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    ).filter { provider ->
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
    }

    private fun emitRecentLastKnownLocation(providers: List<String>, generation: Long) {
        val now = System.currentTimeMillis()
        val lastKnown = providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
            ?: return
        // Keep even an old last-known coordinate available for route planning.
        // The navigation component still marks it stale and never uses it for
        // live guidance; dropping it here would make the explicit "route from
        // the last point" behavior impossible.
        acceptLocation(
            lastKnown,
            generation,
            LocationTimestampPolicy.capturedAtForLastKnown(
                lastKnownEpochMillis = lastKnown.time,
                nowEpochMillis = now,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun listenerFor(generation: Long): LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            acceptLocation(location, generation, System.currentTimeMillis())
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit

        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private fun acceptLocation(location: Location, generation: Long, capturedAtEpochMillis: Long) {
        if (!registration.active) return
        val accuracy = location.accuracy.toDouble()
        if (!location.hasAccuracy() || !accuracy.isFinite() || accuracy < 0.0) return
        val source = when (location.provider) {
            LocationManager.GPS_PROVIDER -> LocationSource.GPS
            LocationManager.NETWORK_PROVIDER -> LocationSource.NETWORK
            else -> LocationSource.PASSIVE
        }
        val fix = RideLocationFix(
            coordinate = ru.sodovaya.volty.domain.navigation.GeoCoordinate(
                latitude = location.latitude,
                longitude = location.longitude,
            ),
            accuracyMeters = accuracy,
            speedMetersPerSecond = location.speed.toDouble().takeIf {
                location.hasSpeed() && it.isFinite() && it >= 0.0
            },
            bearingDegrees = location.bearing.toDouble().takeIf {
                location.hasBearing() && it.isFinite() && it >= 0.0 && it < 360.0
            },
            capturedAtEpochMillis = capturedAtEpochMillis,
            elapsedRealtimeMillis = location.elapsedRealtimeNanos
                .takeIf { it > 0L }
                ?.div(1_000_000L),
            source = source,
            measurementAtEpochMillis = location.time,
        )
        val acceptance = LocationDemandPolicy.acceptFix(policyState, generation, fix)
        if (!acceptance.accepted) return
        policyState = acceptance.state
        publishState()
    }

    private fun publishState() {
        _state.value = RideLocationState(
            status = policyState.status,
            demands = policyState.demands,
        )
    }

    private class LocationPermissionRequiredException : IllegalStateException(
        "Location permission is required before a location demand can start",
    )

    private class NoEnabledLocationProviderException : IllegalStateException(
        "No location provider is enabled",
    )
}
