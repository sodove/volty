package ru.sodovaya.volty.data.navigation.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.sodovaya.volty.domain.navigation.region.OfflineNetworkAvailability
import ru.sodovaya.volty.domain.navigation.region.OfflineNetworkStatus

class AndroidOfflineNetworkStatus(context: Context) : OfflineNetworkStatus {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    override fun current(): OfflineNetworkAvailability {
        val activeNetwork = connectivity?.activeNetwork ?: return OfflineNetworkAvailability.OFFLINE
        val capabilities = connectivity?.getNetworkCapabilities(activeNetwork)
            ?: return OfflineNetworkAvailability.OFFLINE
        return availability(capabilities)
    }

    override val changes: Flow<OfflineNetworkAvailability> = callbackFlow {
        trySend(current())
        val manager = connectivity ?: return@callbackFlow
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(availability(capabilities))
            }

            override fun onLost(network: Network) {
                trySend(current())
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private fun availability(capabilities: NetworkCapabilities): OfflineNetworkAvailability {
        return offlineNetworkAvailability(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isMetered = connectivity?.isActiveNetworkMetered == true,
        )
    }
}

/** INTERNET is enough to attempt online services; validation can lag during reconnects. */
internal fun offlineNetworkAvailability(
    hasInternet: Boolean,
    @Suppress("UNUSED_PARAMETER") isValidated: Boolean,
    isMetered: Boolean,
): OfflineNetworkAvailability {
    if (!hasInternet) {
        return OfflineNetworkAvailability.OFFLINE
    }
    return if (isMetered) OfflineNetworkAvailability.METERED else OfflineNetworkAvailability.UNMETERED
}
