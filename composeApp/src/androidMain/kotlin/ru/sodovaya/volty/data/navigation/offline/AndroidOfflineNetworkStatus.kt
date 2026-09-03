package ru.sodovaya.volty.data.navigation.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ru.sodovaya.volty.domain.navigation.region.OfflineNetworkAvailability
import ru.sodovaya.volty.domain.navigation.region.OfflineNetworkStatus

class AndroidOfflineNetworkStatus(context: Context) : OfflineNetworkStatus {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    override fun current(): OfflineNetworkAvailability {
        val activeNetwork = connectivity?.activeNetwork ?: return OfflineNetworkAvailability.OFFLINE
        val capabilities = connectivity?.getNetworkCapabilities(activeNetwork)
            ?: return OfflineNetworkAvailability.OFFLINE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return OfflineNetworkAvailability.OFFLINE
        }
        return if (connectivity?.isActiveNetworkMetered == true) {
            OfflineNetworkAvailability.METERED
        } else {
            OfflineNetworkAvailability.UNMETERED
        }
    }
}
