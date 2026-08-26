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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.social.LocationProvider
import ru.sodovaya.volty.domain.social.LocationSnapshot

/** Foreground-only GPS source. Calling start is itself an explicit share action. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidLocationProvider(context: Context) : LocationProvider {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    // Replay the first usable fix so start() cannot race the publishing
    // collector. extraBufferCapacity still keeps platform callbacks non-blocking.
    private val _updates = MutableSharedFlow<LocationUpdate>(replay = 1, extraBufferCapacity = 1)
    override val requiredPermissions: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    override val updates: Flow<LocationSnapshot> = flow {
        _updates.asSharedFlow().collect { update ->
            if (shouldAcceptLocationUpdate(update.generation, currentGeneration, started) &&
                isLocationFromCurrentGeneration(update.snapshot.capturedAtEpochMillis, replayGenerationEpochMillis)
            ) {
                emit(update.snapshot)
            }
        }
    }
    @Volatile
    private var started = false
    private val registeredProviders = mutableListOf<String>()
    @Volatile
    private var currentGeneration = 0L
    @Volatile
    private var replayGenerationEpochMillis = 0L
    private var activeListener: LocationListener? = null

    override suspend fun start() = withContext(Dispatchers.Main.immediate) {
        if (started) return@withContext
        checkPermission()
        val generation = currentGeneration + 1L
        currentGeneration = generation
        replayGenerationEpochMillis = System.currentTimeMillis()
        _updates.resetReplayCache()
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        check(providers.isNotEmpty()) { "No location provider is enabled" }
        val listener = listenerFor(generation)
        registeredProviders += registerProvidersTransactional(
            providers = providers,
            register = { provider -> manager.requestLocationUpdates(provider, 1_000L, 5f, listener) },
            unregister = { manager.removeUpdates(listener) },
        )
        activeListener = listener
        started = true
        emitRecentLastKnownLocation()
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        if (!started) return@withContext
        activeListener?.let { manager.removeUpdates(it) }
        registeredProviders.clear()
        started = false
        currentGeneration += 1L
        activeListener = null
    }

    private fun checkPermission() {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        check(fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            "Location permission is required before sharing"
        }
    }

    private fun emitRecentLastKnownLocation() {
        val now = System.currentTimeMillis()
        val lastKnown = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
            ?: return
        val ageMillis = now - lastKnown.time
        if (ageMillis !in 0..MAX_LAST_KNOWN_AGE_MILLIS) return
        _updates.tryEmit(LocationUpdate(currentGeneration, lastKnown.toSnapshot(now)))
    }

    private fun Location.toSnapshot(capturedAt: Long): LocationSnapshot = LocationSnapshot(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.toDouble().coerceAtLeast(0.0),
        capturedAtEpochMillis = capturedAt,
        staleAfterEpochMillis = capturedAt + LOCATION_STALE_AFTER_MILLIS,
    )

    private fun listenerFor(generation: Long): LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!shouldAcceptLocationUpdate(generation, currentGeneration, started)) return
            val capturedAt = System.currentTimeMillis()
            _updates.tryEmit(
                LocationUpdate(
                    generation,
                    LocationSnapshot(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy.toDouble().coerceAtLeast(0.0),
                        capturedAtEpochMillis = capturedAt,
                        staleAfterEpochMillis = capturedAt + LOCATION_STALE_AFTER_MILLIS,
                    ),
                ),
            )
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private data class LocationUpdate(
        val generation: Long,
        val snapshot: LocationSnapshot,
    )

    private companion object {
        const val LOCATION_STALE_AFTER_MILLIS = 15_000L
        const val MAX_LAST_KNOWN_AGE_MILLIS = 10_000L
    }
}

internal fun registerProvidersTransactional(
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

internal fun isLocationFromCurrentGeneration(capturedAtEpochMillis: Long, generationEpochMillis: Long): Boolean =
    capturedAtEpochMillis >= generationEpochMillis

internal fun shouldAcceptLocationUpdate(
    updateGeneration: Long,
    currentGeneration: Long,
    isStarted: Boolean,
): Boolean = isStarted && updateGeneration == currentGeneration
