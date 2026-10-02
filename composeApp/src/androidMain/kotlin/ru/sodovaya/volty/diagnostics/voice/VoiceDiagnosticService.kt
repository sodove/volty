package ru.sodovaya.volty.diagnostics.voice

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import ru.sodovaya.volty.R
import ru.sodovaya.volty.data.voice.VoiceDiagnosticPhase
import ru.sodovaya.volty.domain.voice.*

class VoiceDiagnosticService : Service() {
    private val runtime: AndroidVoiceDiagnosticRuntime by inject()
    private var watcher: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            runtime.scope.launch { if (runtime.controller.stop()) finishService() }
            return START_NOT_STICKY
        }
        if (intent?.action != START) { stopSelf(startId); return START_NOT_STICKY }
        val group = intent.getStringExtra(GROUP) ?: run { stopSelf(startId); return START_NOT_STICKY }
        val preferences = try {
            VoiceAudioPreferences(VoiceInputPreference.valueOf(checkNotNull(intent.getStringExtra(INPUT))),
                VoiceOutputPreference.valueOf(checkNotNull(intent.getStringExtra(OUTPUT))))
        } catch (_: Exception) { stopSelf(startId); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.voice_diag_title), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, VoiceDiagnosticActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(getString(R.string.voice_diag_title))
            .setContentText(getString(R.string.voice_diag_notification)).setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.voice_diag_stop), stop).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION, notification)
        runtime.controller.start(group, preferences)
        if (watcher == null) watcher = runtime.scope.launch {
            runtime.controller.state.collect {
                if (it.phase == VoiceDiagnosticPhase.IDLE || it.phase == VoiceDiagnosticPhase.ERROR) finishService()
            }
        }
        return START_NOT_STICKY
    }
    private fun finishService() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        watcher?.cancel()
        runtime.scope.launch { runtime.controller.stop() }
        super.onDestroy()
    }
    companion object {
        const val START = "ru.sodovaya.volty.voice.diagnostic.START"
        const val STOP = "ru.sodovaya.volty.voice.diagnostic.STOP"
        const val GROUP = "group"
        const val INPUT = "input"
        const val OUTPUT = "output"
        private const val CHANNEL = "voice_diagnostic"
        private const val NOTIFICATION = 1041
    }
}
