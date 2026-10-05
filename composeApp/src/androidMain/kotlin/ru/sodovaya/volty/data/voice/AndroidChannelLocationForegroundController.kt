package ru.sodovaya.volty.data.voice

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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Actual location FGS proof only; microphone foreground never substitutes for it. */
internal class AndroidChannelLocationForegroundController(context: Context,
    private val authority: ChannelLocationForegroundAuthority) : ChannelLocationForegroundPort {
    private val application=context.applicationContext
    override val current=authority.current
    override fun request(owner: ChannelLocationForegroundOwner): ChannelLocationForegroundTicket {
        mainOwner();return authority.request(owner)
    }
    fun launch(activity: ComponentActivity,ticket: ChannelLocationForegroundTicket): VoiceForegroundLaunchResult {
        mainOwner()
        if(authority.find(ticket.id)!==ticket) return VoiceForegroundLaunchResult.OBSOLETE
        val owner=ticket.owner as? ChannelLocationForegroundOwner ?: return reject(ticket,VoiceForegroundLaunchResult.REJECTED)
        if(authority.ready.value===ticket && authority.allows(owner)) return VoiceForegroundLaunchResult.ALREADY_READY
        if(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return reject(ticket,VoiceForegroundLaunchResult.NOT_VISIBLE)
        if(!channelLocationPermission(application)) return reject(ticket,VoiceForegroundLaunchResult.PERMISSION_REQUIRED)
        return try {
            val started=ContextCompat.startForegroundService(activity,Intent(activity,NearbyChannelLocationService::class.java)
                .setAction(NearbyChannelLocationService.START).putExtra(NearbyChannelLocationService.TICKET,ticket.id))
            if(started==null) reject(ticket,VoiceForegroundLaunchResult.REJECTED) else VoiceForegroundLaunchResult.REQUESTED
        } catch(_: Exception) {reject(ticket,VoiceForegroundLaunchResult.REJECTED)}
    }
    override suspend fun awaitReady(ticket: ChannelLocationForegroundTicket): Boolean = withContext(Dispatchers.Main) {
        withTimeoutOrNull(10_000) {
            combine(authority.current,authority.ready) {selected,ready ->
                when {
                    selected!==ticket -> false
                    ready===ticket -> (ticket.owner as? ChannelLocationForegroundOwner)?.let(authority::allows)==true
                    else -> null
                }
            }.filterNotNull().first()
        } ?: run {authority.stop(ticket);false}
    }
    fun launchSharing(activity: ComponentActivity,ticket: ChannelLocationForegroundTicket): ChannelSharingForegroundLaunch =
        when(launch(activity,ticket)) {
            VoiceForegroundLaunchResult.REQUESTED,VoiceForegroundLaunchResult.ALREADY_READY -> ChannelSharingForegroundLaunch.STARTED
            VoiceForegroundLaunchResult.PERMISSION_REQUIRED -> ChannelSharingForegroundLaunch.PERMISSION_REQUIRED
            VoiceForegroundLaunchResult.NOT_VISIBLE -> ChannelSharingForegroundLaunch.NOT_VISIBLE
            VoiceForegroundLaunchResult.OBSOLETE,VoiceForegroundLaunchResult.REJECTED -> ChannelSharingForegroundLaunch.REJECTED
        }
    override fun allows(owner: ChannelLocationForegroundOwner): Boolean {mainOwner();return authority.allows(owner)}
    fun find(id: String): ChannelLocationForegroundTicket? {mainOwner();return authority.find(id)}
    fun acknowledge(ticket: ChannelLocationForegroundTicket,service: Any): Boolean {mainOwner();return authority.acknowledge(ticket,service)}
    override fun stop(ticket: ChannelLocationForegroundTicket): Boolean {mainOwner();return authority.stop(ticket)}
    fun destroyed(service: Any) {mainOwner();authority.destroyed(service)}
    fun refreshPermissions() {mainOwner();authority.refreshPermissions()}
    private fun reject(ticket: ChannelLocationForegroundTicket,result: VoiceForegroundLaunchResult): VoiceForegroundLaunchResult {
        authority.stop(ticket);return result
    }
    private fun mainOwner() {check(Looper.myLooper()==Looper.getMainLooper()) {"Location foreground requires Main owner"}}
}

internal fun channelLocationPermission(context: Context): Boolean = try {
    val granted=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    val manager=context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    granted && manager!=null && LocationManagerCompat.isLocationEnabled(manager)
} catch(_: Exception) {false}
