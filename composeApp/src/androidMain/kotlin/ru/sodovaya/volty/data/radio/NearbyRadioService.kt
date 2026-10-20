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
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import ru.sodovaya.volty.MainActivity
import ru.sodovaya.volty.R
import ru.sodovaya.volty.domain.radio.RadioController
import ru.sodovaya.volty.domain.radio.RadioError
import ru.sodovaya.volty.domain.radio.RadioState
import ru.sodovaya.volty.presentation.root.nearby.RadioTitle
import ru.sodovaya.volty.presentation.root.nearby.radioNotificationTitle

/**
 * Keeps the process and the microphone alive while the offline radio is running. It owns no audio:
 * [RadioRuntime] does, and starts and stops this service. The notification's title is the radio
 * card's status line ([radioNotificationTitle]: connecting, lost, or how many people) and it carries
 * a leave action. It stops itself when the radio goes Idle or Error, with `stopSelf(startId)` of the
 * latest start so a start that is already on its way is not lost (M7).
 *
 * Needs a device: foreground-service rules differ per Android version and cannot be unit-tested here.
 */
class NearbyRadioService : Service() {
    private val controller: RadioController by inject()
    private val runtime: RadioRuntime by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false
    private var observing = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // startForegroundService() promises a startForeground() call, whatever the command is
        // and whatever the radio's state has become by now (even Idle a few ms after Starting).
        if (!foregroundStarted) {
            try {
                createChannel()
                val notification = notification(radioNotificationTitle(controller.state.value, runtime.members.value), runtime.members.value.size)
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
                if (!observing) {
                    observing = true
                    observe()
                }
            } catch (_: Exception) {
                // Refused (permission, background start): report it, never leave() silently.
                scope.launch { controller.onRuntimeFailure(RadioError.AUDIO_UNAVAILABLE) }
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (intent?.action == LEAVE) {
            // Same as the card's Leave (I1): leaving must not be undone by the next auto-join poll.
            runtime.suppressAutoJoin()
            controller.leave()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        scope.launch {
            combine(controller.state, runtime.members) { state, members ->
                Triple(state, radioNotificationTitle(state, members), members.size)
            }
                .distinctUntilChanged { a, b -> (a.first.isOver() == b.first.isOver()) && a.second == b.second && a.third == b.third }
                .collect { (state, title, count) ->
                if (state.isOver()) {
                    if (!foregroundStarted) return@collect
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foregroundStarted = false
                    // Only if no newer start is pending; otherwise that start promotes the service again.
                    stopSelfResult(lastStartId)
                } else if (foregroundStarted) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(title, count))
                }
            }
        }
    }

    private fun RadioState.isOver(): Boolean = this == RadioState.Idle || this is RadioState.Error

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.nearby_radio_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(title: RadioTitle, people: Int): Notification {
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
            .setContentTitle(
                when (title) {
                    RadioTitle.MEMBERS -> getString(R.string.nearby_radio_notification_people, people)
                    RadioTitle.LOST -> getString(R.string.nearby_radio_notification_lost)
                    else -> getString(R.string.nearby_radio_notification_connecting)
                },
            )
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
