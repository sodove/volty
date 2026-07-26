package ru.sodovaya.volty.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.domain.alert.AlarmDriver
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.notification.AudibleAlarmHolder
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

        /**
         * "Заглушить" on the live notification: stop the sounding alarm and
         * **keep riding** (F §14).
         *
         * Deliberately separate from [ACTION_DISCONNECT], which is the only
         * escape a rider had before it and which ends the BLE session. See
         * [ru.sodovaya.volty.domain.alert.AlarmSilencer] for what the silence
         * means over time and what it costs.
         */
        const val ACTION_SILENCE = "ru.sodovaya.volty.ACTION_SILENCE"
        private const val FOREGROUND_ID = 1001
    }

    private val bmsRepository: BmsRepository by inject()
    private val notifier: Notifier by inject()
    private val appPrefs: AppPrefs by inject()
    private val alarms: AudibleAlarmHolder by inject()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * The live driver, kept so [silenceReceiver] can reach it.
     *
     * Not an `AudibleAlarm` and not resolved from Koin: the alarm binding is the
     * holder's, and an instance captured here would be dead from the first
     * service restart ([AudibleAlarmHolder]). Null until the driver is built, and
     * on the early-return path below it never is — a silence press then finds
     * nothing to silence, which is the truth.
     *
     * Written in `onCreate` and read in `onReceive`, both on the main thread.
     */
    private var alarmDriver: AlarmDriver? = null

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                bmsRepository.disconnect()
                stopSelf()
            }
        }
    }

    /**
     * "Заглушить" (F §14): silence the alarm, **leave the session alone**.
     *
     * The only escape from a stuck alarm used to be `Disconnect`, which ends the
     * ride's telemetry. This one goes to [AlarmDriver.silence], which hands the
     * request to the driver's single collector rather than touching the speaker
     * from here.
     */
    private val silenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            alarmDriver?.silence()
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        registerNotificationAction(disconnectReceiver, ACTION_DISCONNECT)
        registerNotificationAction(silenceReceiver, ACTION_SILENCE)

        val seed = NotificationCompat.Builder(this, NotificationChannels.LIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Volty")
            .setContentText("Starting…")
            .setOngoing(true)
            .build()
        startForeground(FOREGROUND_ID, seed)

        // START_STICKY can resurrect the service in a FRESH process (after the
        // system killed us). There the repo singleton has no session, no target
        // and nothing will reconnect on its own — without this check the user
        // is left with a frozen "Starting…" notification forever.
        when (bmsRepository.connectionState.value) {
            is ConnectionState.Idle,
            is ConnectionState.Disconnected,
            is ConnectionState.Failed -> {
                stopSelf()
                return
            }
            else -> Unit
        }

        // The audible alarm (F §5). It lives here and nowhere else because this
        // service is the only thing that keeps the BLE link alive with the screen
        // off and the phone in a pocket, which is the entire premise of the
        // feature — an alarm that sounds only while the dashboard is on screen
        // warns a rider of nothing.
        //
        // Note what it does NOT share with the live notification below: the
        // notification is sample(2.seconds)-throttled, and the alarm is not. A
        // duty spike is a fraction of a second of warning, and spending two of
        // them waiting for a throttle window would be spending most of it. The
        // driver reads `activeMotion` directly, on every sample.
        alarmDriver = AlarmDriver(
            repository = bmsRepository,
            modalities = appPrefs.alarmModalities,
            alarm = alarms.openOutput()
        ).also { it.start(scope) }

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

    /**
     * **The alarm must not outlive the service, whatever state it was in.**
     *
     * Two paths reach here and they are deliberately different:
     *
     *  - **the link dropped mid-alarm.** This method does not run at all. The
     *    service stays up, `activeMotion` emits its disconnected placeholder (or
     *    `connectionState` goes Disconnected/Failed), and [AlarmDriver] silences
     *    within a sample while staying armed — the first fresh reading re-arms
     *    instantly, because attack is immediate and only release is damped. A
     *    reconnect after a pothole must not need a service restart to warn again;
     *  - **the rider hit "disconnect" (or the system stopped us).** The ride is
     *    over, so the alarm is torn down for good: the collector is cancelled
     *    first, so nothing can hand a fresh state to an alarm that is going away,
     *    and `release()` then joins the tone thread (bounded at 500 ms) so this
     *    method returns with the speaker provably quiet rather than probably
     *    quiet. Cancelling alone would not do it — it stops the collector, not the
     *    tone thread, and a level-3 burst loop would carry on in a dead service's
     *    process. A sample already in flight is harmless: `AudibleAlarm` latches
     *    `released` under its own lock and ignores everything afterwards.
     *
     * The holder, not the alarm, is the Koin single — see [AudibleAlarmHolder] for
     * why releasing a process-scoped instance here would silently kill the alarm
     * from the rider's second ride onwards.
     */
    override fun onDestroy() {
        scope.cancel()
        alarmDriver = null
        try { unregisterReceiver(disconnectReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(silenceReceiver) } catch (_: Exception) {}
        alarms.release()
        notifier.cancelLive()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * One notification action's receiver, registered app-internal.
     *
     * `RECEIVER_NOT_EXPORTED` from API 33: these actions end a ride and silence a
     * safety alarm, so nothing outside the app has any business sending them.
     */
    private fun registerNotificationAction(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }
}
