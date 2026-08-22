package ru.sodovaya.volty.data.social

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.social.LocationProvider
import ru.sodovaya.volty.domain.social.LocationSnapshot

/** Foreground-only GPS source. Calling start is itself an explicit share action. */
class AndroidLocationProvider(context: Context) : LocationProvider {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _updates = MutableSharedFlow<LocationSnapshot>(extraBufferCapacity = 1)
    override val requiredPermissions: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    override val updates: Flow<LocationSnapshot> = _updates.asSharedFlow()
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val capturedAt = System.currentTimeMillis()
            _updates.tryEmit(
                LocationSnapshot(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy.toDouble().coerceAtLeast(0.0),
                    capturedAtEpochMillis = capturedAt,
                    staleAfterEpochMillis = capturedAt + LOCATION_STALE_AFTER_MILLIS,
                )
            )
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override suspend fun start() = withContext(Dispatchers.Main.immediate) {
        if (started) return@withContext
        checkPermission()
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> throw IllegalStateException("No location provider is enabled")
        }
        manager.requestLocationUpdates(provider, 1_000L, 5f, listener)
        started = true
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        if (!started) return@withContext
        manager.removeUpdates(listener)
        started = false
    }

    private fun checkPermission() {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        check(fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            "Location permission is required before sharing"
        }
    }

    private companion object {
        const val LOCATION_STALE_AFTER_MILLIS = 15_000L
    }
}
