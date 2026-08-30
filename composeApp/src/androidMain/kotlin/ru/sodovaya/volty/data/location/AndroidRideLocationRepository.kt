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
    private var registeredProviders = emptyList<String>()
    private var activeListener: LocationListener? = null
    private var platformStarted = false

    override val requiredPermissions: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    override val state: StateFlow<RideLocationState> = _state.asStateFlow()

    override suspend fun setDemand(consumer: LocationConsumer, enabled: Boolean) =
        withContext(Dispatchers.Main.immediate) {
            val transition = LocationDemandPolicy.setDemand(policyState, consumer, enabled)
            if (!transition.changed) return@withContext

            policyState = transition.state
            publishState()
            when {
                transition.shouldStart -> tryStartPlatformUpdates()
                transition.shouldStop -> stopPlatformUpdates()
            }
        }

    override suspend fun refreshPermissionAndProviders() =
        withContext(Dispatchers.Main.immediate) {
            if (policyState.demands.isEmpty()) {
                policyState = LocationDemandPolicy.setStatus(
                    policyState,
                    RideLocationStatus.NotRequested,
                )
                publishState()
                return@withContext
            }
            if (!hasLocationPermission()) {
                stopPlatformUpdates()
                policyState = LocationDemandPolicy.setStatus(
                    policyState,
                    RideLocationStatus.PermissionRequired,
                )
                publishState()
                return@withContext
            }
            val providers = enabledProviders()
            if (providers.isEmpty()) {
                stopPlatformUpdates()
                policyState = LocationDemandPolicy.setStatus(
                    policyState,
                    RideLocationStatus.ProviderDisabled,
                )
                publishState()
                return@withContext
            }
            if (!platformStarted) {
                policyState = LocationDemandPolicy.restart(policyState)
                publishState()
                tryStartPlatformUpdates(providers, rethrow = false)
            }
        }

    private fun tryStartPlatformUpdates(
        providers: List<String> = enabledProviders(),
        rethrow: Boolean = true,
    ) {
        try {
            startPlatformUpdates(providers)
        } catch (error: LocationPermissionRequiredException) {
            policyState = LocationDemandPolicy.setStatus(policyState, RideLocationStatus.PermissionRequired)
            publishState()
            if (rethrow) throw error
        } catch (error: SecurityException) {
            policyState = LocationDemandPolicy.setStatus(policyState, RideLocationStatus.PermissionDenied)
            publishState()
            if (rethrow) throw error
        } catch (error: NoEnabledLocationProviderException) {
            policyState = LocationDemandPolicy.setStatus(policyState, RideLocationStatus.ProviderDisabled)
            publishState()
            if (rethrow) throw error
        }
    }

    private fun startPlatformUpdates(providers: List<String>) {
        checkLocationPermission()
        if (providers.isEmpty()) throw NoEnabledLocationProviderException()

        val generation = policyState.generation
        val listener = listenerFor(generation)
        val registered = registerProvidersTransactional(
            providers = providers,
            register = { provider -> requestUpdates(provider, listener) },
            unregister = { manager.removeUpdates(listener) },
        )
        registeredProviders = registered
        activeListener = listener
        platformStarted = true
        emitRecentLastKnownLocation(providers, generation)
    }

    private fun stopPlatformUpdates() {
        activeListener?.let { manager.removeUpdates(it) }
        registeredProviders = emptyList()
        activeListener = null
        platformStarted = false
    }

    private fun requestUpdates(provider: String, listener: LocationListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val request = LocationRequest.Builder(1_000L)
                .setMinUpdateDistanceMeters(5f)
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
            manager.requestLocationUpdates(provider, 1_000L, 5f, listener)
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
        val ageMillis = now - lastKnown.time
        if (ageMillis !in 0..MAX_LAST_KNOWN_AGE_MILLIS) return
        acceptLocation(lastKnown, generation, now)
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
        if (!platformStarted) return
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

    private companion object {
        const val MAX_LAST_KNOWN_AGE_MILLIS = 10_000L
    }

    private class LocationPermissionRequiredException : IllegalStateException(
        "Location permission is required before a location demand can start",
    )

    private class NoEnabledLocationProviderException : IllegalStateException(
        "No location provider is enabled",
    )
}

private fun registerProvidersTransactional(
    providers: List<String>,
    register: (String) -> Unit,
    unregister: (String) -> Unit,
): List<String> {
    val registered = mutableListOf<String>()
    try {
        providers.forEach { provider ->
            register(provider)
            registered += provider
        }
        return registered
    } catch (error: Throwable) {
        registered.asReversed().forEach { provider ->
            runCatching { unregister(provider) }
        }
        throw error
    }
}
