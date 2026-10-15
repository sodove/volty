package ru.sodovaya.volty.data.history

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.data.location.LocationDemandCoordinator
import ru.sodovaya.volty.service.MonitoringService

/** Native service promotion only. A submitted intent is never a foreground acknowledgement. */
internal class AndroidHistoryLocationForegroundController(
    context: Context,
    private val authority: HistoryLocationForegroundAuthority,
    private val demand: LocationDemandCoordinator,
) {
    private val application = context.applicationContext
    private var host = WeakReference<ComponentActivity>(null)
    private val mutableOpportunities = MutableStateFlow(0L)
    val resumeOpportunities = mutableOpportunities.asStateFlow()
    val watchesPermissions: Boolean get() = authority.state.value.ticket != null
    val retentionChanges = combine(authority.state, demand.nativeDemandHeld) { _, _ -> Unit }
    // Read live at the service stop decision; a delayed copied flag can outrun acquisition.
    val retainsLocation: Boolean get() = authority.state.value.ticket != null || demand.nativeDemandHeld.value
    private class Binding(val service: Any, val promote: () -> Unit, val demote: () -> Unit,
        val isLocationForeground: () -> Boolean, var locationMayBeHeld: Boolean = false)
    private var binding: Binding? = null

    fun attachHost(activity: ComponentActivity) {
        mainOwner()
        activity.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        host = WeakReference(activity)
                        if (mutableOpportunities.value < Long.MAX_VALUE) mutableOpportunities.value++
                    }
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> {
                        if (host.get() === activity) host.clear()
                        if (event == Lifecycle.Event.ON_DESTROY) source.lifecycle.removeObserver(this)
                    }
                    else -> Unit
                }
            }
        })
    }

    suspend fun launch(ticket: HistoryLocationForegroundTicket): Boolean = withContext(Dispatchers.Main) {
        if (authority.find(ticket.id) !== ticket) return@withContext false
        if (!historyLocationPermission(application)) return@withContext reject(ticket)
        val current = binding
        if (current != null && runCatching(current.isLocationForeground).getOrDefault(false)) {
            if (authority.acknowledge(ticket, current.service)) return@withContext true
            return@withContext reject(ticket)
        }
        val activity = resumedHost() ?: return@withContext reject(ticket)
        try {
            val started = ContextCompat.startForegroundService(activity, Intent(activity, MonitoringService::class.java)
                .setAction(START).putExtra(TICKET, ticket.id))
            if (started == null) reject(ticket) else true
        } catch (_: Exception) { reject(ticket) }
    }

    fun created(service: Any, promote: () -> Unit, demote: () -> Unit, isLocationForeground: () -> Boolean) {
        mainOwner()
        if (binding?.service === service) return
        binding?.let { authority.destroyed(it.service) }
        binding = Binding(service, promote, demote, isLocationForeground)
    }

    fun start(service: Any, ticketId: String?): Boolean {
        mainOwner()
        val current = binding?.takeIf { it.service === service } ?: return false
        val ticket = ticketId?.let(authority::find) ?: return false
        try {
            if (!historyLocationPermission(application)) return reject(ticket)
            if (!current.isLocationForeground()) {
                if (resumedHost() == null) return reject(ticket)
                // Reserve before native call: promotion may partly apply and then throw.
                current.locationMayBeHeld = true
                current.promote()
            }
            if (!current.isLocationForeground() || !authority.acknowledge(ticket, service)) return reject(ticket)
            return true
        } catch (_: Exception) { return reject(ticket) }
        finally { reconcile(service) }
    }

    fun refreshPermissions(service: Any) {
        mainOwner()
        if (watchesPermissions) authority.refreshPermissions()
        reconcile(service)
    }

    fun reconcile(service: Any) {
        mainOwner()
        val current = binding?.takeIf { it.service === service } ?: return
        if (!retainsLocation) {
            try {
                if (current.locationMayBeHeld) {
                    current.demote()
                    if (!current.isLocationForeground()) current.locationMayBeHeld = false
                }
            } catch (_: Exception) {
                // Retain the original optional type until a later explicit demotion succeeds.
                current.locationMayBeHeld = true
            }
        }
    }

    fun destroyed(service: Any) {
        mainOwner()
        if (binding?.service === service) binding = null
        authority.destroyed(service)
    }

    private fun resumedHost(): ComponentActivity? = host.get()?.takeIf {
        it.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    private fun reject(ticket: HistoryLocationForegroundTicket): Boolean { authority.stop(ticket); return false }
    private fun mainOwner() = check(Looper.myLooper() == Looper.getMainLooper()) { "History foreground requires Main" }
    companion object {
        const val START = "ru.sodovaya.volty.history.location.START"
        const val TICKET = "history_location_ticket"
    }
}

internal fun historyLocationPermission(context: Context): Boolean = try {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    granted && manager != null && LocationManagerCompat.isLocationEnabled(manager)
} catch (_: Exception) { false }
