package ru.sodovaya.volty.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.notification.LiveSummary
import ru.sodovaya.volty.notification.NotificationChannels
import ru.sodovaya.volty.notification.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.time.Duration.Companion.seconds

@OptIn(FlowPreview::class)
class MonitoringService : Service() {

    companion object {
        const val ACTION_DISCONNECT = "ru.sodovaya.volty.ACTION_DISCONNECT"
        private const val FOREGROUND_ID = 1001
    }

    private val bmsRepository: BmsRepository by inject()
    private val notifier: Notifier by inject()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                bmsRepository.disconnect()
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        val filter = IntentFilter(ACTION_DISCONNECT)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(disconnectReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(disconnectReceiver, filter)
        }

        val seed = NotificationCompat.Builder(this, NotificationChannels.LIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Volty")
            .setContentText("Starting…")
            .setOngoing(true)
            .build()
        startForeground(FOREGROUND_ID, seed)

        scope.launch {
            bmsRepository.activeData
                .combine(bmsRepository.activeVehicle) { d, v -> d to v }
                .sample(2.seconds)
                .collect { (data, vehicle) ->
                    if (vehicle == null) return@collect
                    notifier.showLive(
                        LiveSummary(
                            vehicleName = vehicle.name,
                            socPercent = data.soc.toInt(),
                            voltageV = data.voltage,
                            currentA = data.current,
                            etaText = null
                        )
                    )
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        try { unregisterReceiver(disconnectReceiver) } catch (_: Exception) {}
        notifier.cancelLive()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
