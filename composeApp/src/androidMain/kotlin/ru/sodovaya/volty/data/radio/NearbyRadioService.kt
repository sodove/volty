package ru.sodovaya.volty.data.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import ru.sodovaya.volty.MainActivity
import ru.sodovaya.volty.R
import ru.sodovaya.volty.domain.radio.RadioController
import ru.sodovaya.volty.domain.radio.RadioError
import ru.sodovaya.volty.domain.radio.RadioState

/**
 * Keeps the process and the microphone alive while the offline radio is running. It owns no audio:
 * [RadioRuntime] does, and starts and stops this service. The notification shows how many people are
 * on the radio and a leave action. It stops itself when the radio goes Idle or Error.
 *
 * Needs a device: foreground-service rules differ per Android version and cannot be unit-tested here.
 */
class NearbyRadioService : Service() {
    private val controller: RadioController by inject()
    private val runtime: RadioRuntime by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() promises a startForeground() call, whatever the command is
        // and whatever the radio's state has become by now (even Idle a few ms after Starting).
        if (!foregroundStarted) {
            try {
                createChannel()
                val notification = notification(runtime.members.value.size)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                } else {
                    startForeground(NOTIFICATION, notification)
                }
                foregroundStarted = true
                observe()
            } catch (_: Exception) {
                // Refused (permission, background start): report it, never leave() silently.
                scope.launch { controller.onRuntimeFailure(RadioError.AUDIO_UNAVAILABLE) }
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (intent?.action == LEAVE) controller.leave()
        return START_NOT_STICKY
    }

    private fun observe() {
        scope.launch {
            combine(controller.state, runtime.members.map { it.size }.distinctUntilChanged()) { state, count -> state to count }
                .distinctUntilChanged()
                .collect { (state, count) ->
                if (state == RadioState.Idle || state is RadioState.Error) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(count))
                }
            }
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.nearby_radio_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(people: Int): Notification {
        val leave = PendingIntent.getService(
            this, NOTIFICATION,
            Intent(this, javaClass).setAction(LEAVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, NOTIFICATION,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.nearby_radio_notification_people, people))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.nearby_radio_notification_leave), leave)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val LEAVE = "ru.sodovaya.volty.nearby.radio.LEAVE"
        private const val CHANNEL = "nearby_radio_active"
        private const val NOTIFICATION = 1043
    }
}
