package ru.sodovaya.volty.data.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal enum class VoiceForegroundLaunchResult { REQUESTED, ALREADY_READY, NOT_VISIBLE, PERMISSION_REQUIRED, OBSOLETE, REJECTED }

/** Process registry only; it neither restores a call nor owns the account cleanup lifetime. */
internal class AndroidVoiceForegroundController(context: Context, private val gate: VoiceForegroundGate) {
    private val application = context.applicationContext
    val current = gate.current
    private val failure = MutableStateFlow<VoiceForegroundLaunchResult?>(null)
    val lastFailure = failure.asStateFlow()

    fun request(runtime: AuthenticatedVoiceRuntime): VoiceForegroundTicket {
        mainOwner()
        check(!runtime.retired.value) { "Voice runtime retired" }
        return gate.request(runtime, runtime::requestDisconnect)
    }

    fun launch(activity: ComponentActivity, ticket: VoiceForegroundTicket): VoiceForegroundLaunchResult {
        mainOwner()
        if (gate.find(ticket.id) !== ticket) return VoiceForegroundLaunchResult.OBSOLETE
        if (gate.ready.value === ticket && gate.allows(ticket.owner)) return VoiceForegroundLaunchResult.ALREADY_READY
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            return reject(ticket, VoiceForegroundLaunchResult.NOT_VISIBLE)
        if (!microphoneGranted()) return reject(ticket, VoiceForegroundLaunchResult.PERMISSION_REQUIRED)
        return try {
            val started = ContextCompat.startForegroundService(activity,
                Intent(activity, NearbyVoiceService::class.java).setAction(NearbyVoiceService.START)
                    .putExtra(NearbyVoiceService.TICKET, ticket.id))
            if (started == null) reject(ticket, VoiceForegroundLaunchResult.REJECTED)
            else VoiceForegroundLaunchResult.REQUESTED
        } catch (_: Exception) { reject(ticket, VoiceForegroundLaunchResult.REJECTED) }
    }

    suspend fun awaitReady(ticket: VoiceForegroundTicket): Boolean = withContext(Dispatchers.Main) {
        combine(gate.current, gate.ready) { selected, ready ->
            when {
                selected !== ticket -> false
                ready === ticket -> gate.allows(ticket.owner)
                else -> null
            }
        }.filterNotNull().first()
    }

    fun find(id: String): VoiceForegroundTicket? { mainOwner(); return gate.find(id) }
    fun acknowledge(ticket: VoiceForegroundTicket, service: Any): Boolean {
        mainOwner()
        return gate.acknowledge(ticket, service).also {if (it) failure.value = null}
    }
    fun stop(ticket: VoiceForegroundTicket): Boolean {mainOwner(); return gate.stop(ticket)}
    fun destroyed(service: Any) {mainOwner(); gate.destroyed(service)}
    fun refreshPermissions() {
        mainOwner()
        if (gate.current.value != null && !microphoneGranted()) failure.value = VoiceForegroundLaunchResult.PERMISSION_REQUIRED
        gate.refreshPermissions()
    }
    fun reject(ticket: VoiceForegroundTicket, reason: VoiceForegroundLaunchResult): VoiceForegroundLaunchResult {
        mainOwner()
        if (gate.find(ticket.id) === ticket) {failure.value = reason; gate.stop(ticket)}
        return reason
    }
    private fun microphoneGranted() = ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun mainOwner() {check(Looper.myLooper() == Looper.getMainLooper()) {"Voice foreground requires Main owner"}}
}
