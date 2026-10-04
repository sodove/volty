package ru.sodovaya.volty.data.voice

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import org.koin.android.ext.android.inject
import ru.sodovaya.volty.MainActivity
import ru.sodovaya.volty.R

/** No automatic restart or account restoration. UI lifetime does not own the conversation. */
class NearbyVoiceService : Service() {
    private val controller: AndroidVoiceForegroundController by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val owners = mutableSetOf<AuthenticatedVoiceRuntime>()
    private var bound: VoiceForegroundTicket? = null
    private var foregroundStarted = false
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        scope.launch {controller.current.collect {maybeFinish()}}
        // PackageManager permission-change listeners require privileged observe permission.
        // Recheck our live grant while this service exists; this pulse is never a stop ACK.
        scope.launch {while (isActive) {delay(1_000L); controller.refreshPermissions(); maybeFinish()}}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ticket = intent?.getStringExtra(TICKET)?.let(controller::find)
        if (intent?.action == STOP) {
            if (ticket != null && bound === ticket) controller.stop(ticket)
            maybeFinish()
            if (!foregroundStarted) stopSelf(startId)
            return START_NOT_STICKY
        }
        val runtime = ticket?.owner as? AuthenticatedVoiceRuntime
        if (intent?.action != START || ticket == null || runtime == null) {
            // A stale request cannot stop an already bound newer foreground owner.
            if (!foregroundStarted) stopSelf(startId)
            return START_NOT_STICKY
        }
        observe(runtime)
        try {
            val notification = notification(ticket)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification)
            foregroundStarted = true
            finishing = false
            if (!controller.acknowledge(ticket, this)) {
                controller.reject(ticket, VoiceForegroundLaunchResult.REJECTED)
                maybeFinish()
                return START_NOT_STICKY
            }
            bound = ticket
        } catch (_: Exception) {
            controller.reject(ticket, VoiceForegroundLaunchResult.REJECTED)
            if (!foregroundStarted) stopSelf(startId) else maybeFinish()
        }
        return START_NOT_STICKY
    }

    private fun observe(runtime: AuthenticatedVoiceRuntime) {
        if (!owners.add(runtime)) return
        scope.launch {
            combine(runtime.state, runtime.nativeInFlight, runtime.failedLocalCleanup, runtime.retired,
                runtime.disconnectFailed) {_, _, _, _, _ -> Unit}
                .combine(runtime.retirementFailed) {_, _ -> Unit}.collect {
                    // Wanted is conflated and cannot prove a user request finished. Only
                    // immutable retirement can revoke here; caller/notification stop is explicit.
                    if (runtime.retired.value)
                        controller.current.value?.takeIf {it.owner === runtime}?.let(controller::stop)
                    maybeFinish()
                }
        }
    }

    private fun maybeFinish() {
        if (!foregroundStarted || finishing) return
        val resources = owners.map {
            VoiceForegroundResourceState(it.state.value.wanted, it.state.value.cleanupPending,
                it.failedLocalCleanup.value, it.nativeInFlight.value,
                it.retirementFailed.value || it.disconnectFailed.value)
        }
        if (canReleaseVoiceForeground(controller.current.value != null, resources)) {
            finishing = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(ticket: VoiceForegroundTicket): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.nearby_voice_notification_title), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, NOTIFICATION,
            Intent(this, javaClass).setAction(STOP).setData(Uri.parse("volty://voice-foreground/${ticket.id}/stop"))
                .putExtra(TICKET, ticket.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, NOTIFICATION,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.nearby_voice_notification_title))
            .setContentText(getString(R.string.nearby_voice_notification_body)).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.nearby_voice_notification_disconnect), stop).build()
    }

    override fun onDestroy() {
        // Original root-owned disconnect enters NonCancellable before service observers cancel.
        controller.destroyed(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val START = "ru.sodovaya.volty.nearby.voice.START"
        const val STOP = "ru.sodovaya.volty.nearby.voice.STOP"
        const val TICKET = "foreground_ticket"
        private const val CHANNEL = "nearby_voice_active"
        private const val NOTIFICATION = 1042
    }
}
