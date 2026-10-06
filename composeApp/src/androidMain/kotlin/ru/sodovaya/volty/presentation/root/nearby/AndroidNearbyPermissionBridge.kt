package ru.sodovaya.volty.presentation.root.nearby

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import ru.sodovaya.volty.data.voice.*

/** Construct before STARTED. Borrows process controllers; only this Activity's callbacks are disposed. */
internal class AndroidNearbyPermissionBridge(
    private val activity: ComponentActivity,
    private val voice: NearbyVoiceController,
    private val sharing: NearbySharingController,
    voiceForeground: AndroidVoiceForegroundController,
    locationForeground: AndroidChannelLocationForegroundController,
) {
    private val gate = NearbyPermissionGate(restore(activity.savedStateRegistry.consumeRestoredStateForKey(KEY)))
    private val lifetime = SupervisorJob(activity.lifecycleScope.coroutineContext[Job])
    private val scope = CoroutineScope(activity.lifecycleScope.coroutineContext + lifetime + Dispatchers.Main)
    private val submitted = mutableSetOf<NearbyPermissionRequest>()
    private var closed = false
    private val boundary = NearbyVisibleLaunchBoundary(scope,
        visible = { activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
        voice = { ticket -> when (voiceForeground.launch(activity, ticket)) {
            VoiceForegroundLaunchResult.REQUESTED, VoiceForegroundLaunchResult.ALREADY_READY -> true
            else -> false
        } },
        location = { ticket -> locationForeground.launchSharing(activity, ticket) })
    private val launcher = activity.activityResultRegistry.register(KEY, activity,
        ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val original = gate.slot?.request ?: return@register
        val granted = when (original.kind) {
            NearbyPermissionKind.MICROPHONE -> result[Manifest.permission.RECORD_AUDIO] == true
            NearbyPermissionKind.LOCATION -> result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
        if (gate.complete(granted)) schedule()
    }
    private val observer = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) { schedule() }
        override fun onDestroy(owner: LifecycleOwner) { close() }
    }
    init {
        activity.savedStateRegistry.registerSavedStateProvider(KEY) { save(gate.slot) }
        activity.lifecycle.addObserver(observer)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { close() } }
        scope.launch { combine(voice.state, sharing.state) { _, _ -> Unit }.collect { drain() } }
    }
    private fun schedule() { if (!closed) scope.launch { drain() } }
    private fun granted(kind: NearbyPermissionKind): Boolean {
        fun permission(name: String) = ContextCompat.checkSelfPermission(activity, name) == PackageManager.PERMISSION_GRANTED
        return when (kind) {
            NearbyPermissionKind.MICROPHONE -> permission(Manifest.permission.RECORD_AUDIO)
            NearbyPermissionKind.LOCATION -> permission(Manifest.permission.ACCESS_COARSE_LOCATION) || permission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    private fun drain() {
        if (closed || !boundary.available) return
        gate.takeReady(true)?.let { decision ->
            submitted += decision.request
            val allowed = decision.granted && granted(decision.request.kind)
            when (decision.request.kind) {
                NearbyPermissionKind.MICROPHONE -> voice.begin(decision.request.id, allowed, boundary)
                NearbyPermissionKind.LOCATION -> sharing.begin(decision.request.id, allowed, boundary)
            }
            schedule()
            return
        }
        if (gate.slot != null) return
        val candidate = voice.state.value.pending?.takeIf { it.confirmed }
            ?.let { NearbyPermissionRequest(NearbyPermissionKind.MICROPHONE, it.id) }
            ?: sharing.state.value.pending?.let { NearbyPermissionRequest(NearbyPermissionKind.LOCATION, it.id) }
            ?: return
        // begin is queued on the process owner: never resubmit while its pending projection still exists.
        if (candidate in submitted || !gate.reserve(candidate)) return
        if (granted(candidate.kind)) { gate.complete(true); schedule(); return }
        val permissions = when (candidate.kind) {
            NearbyPermissionKind.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
            NearbyPermissionKind.LOCATION -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        try { launcher.launch(permissions) } catch (_: Exception) { gate.complete(false); schedule() }
    }
    fun close() {
        if (closed) return
        closed = true
        boundary.close()
        gate.close()
        submitted.clear()
        launcher.unregister()
        activity.lifecycle.removeObserver(observer)
        activity.savedStateRegistry.unregisterSavedStateProvider(KEY)
        lifetime.cancel()
    }
    private companion object {
        const val KEY = "nearby.v2.permission"
        fun save(slot: NearbyPermissionSlot?) = Bundle().apply {
            if (slot != null) {
                putString("kind", slot.request.kind.name)
                putString("id", slot.request.id)
                putInt("result", when (slot.granted) { null -> -1; false -> 0; true -> 1 })
            }
        }
        fun restore(saved: Bundle?): NearbyPermissionSlot? {
            val id = saved?.getString("id")?.takeIf { it.isNotBlank() } ?: return null
            val kind = NearbyPermissionKind.entries.firstOrNull { it.name == saved.getString("kind") } ?: return null
            val result = when (saved.getInt("result", -1)) { -1 -> null; 0 -> false; 1 -> true; else -> return null }
            return NearbyPermissionSlot(NearbyPermissionRequest(kind, id), result)
        }
    }
}
