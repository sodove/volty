package ru.sodovaya.volty.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ru.sodovaya.volty.MainActivity
import ru.sodovaya.volty.R
import ru.sodovaya.volty.domain.usecase.AlertMessage
import ru.sodovaya.volty.domain.usecase.AlertSeverity
import ru.sodovaya.volty.service.MonitoringService
import ru.sodovaya.volty.util.UnitSystem

private const val LIVE_NOTIFICATION_ID = 1001

/**
 * @param unitSystem read at the moment an alert is formatted, so a rider who flips to mph sees mph
 * in the very next speed notification. The preference is a cheap in-memory StateFlow value
 * (`AppPrefs.unitSystem.value`), so no suspend is needed here.
 */
class AndroidNotifier(
    private val context: Context,
    private val unitSystem: () -> UnitSystem = { UnitSystem.METRIC }
) : Notifier {

    init { NotificationChannels.ensureCreated(context) }

    private val manager = context.getSystemService(NotificationManager::class.java)

    /**
     * A renderer. Every word it shows — including whether a rider's silence is in
     * force — is [LiveNotificationText]'s decision, where a test can reach it.
     */
    override fun showLive(summary: LiveSummary) {
        val builder = NotificationCompat.Builder(context, NotificationChannels.LIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Volty · ${summary.vehicleName}")
            .setContentText(LiveNotificationText.content(summary))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            // Silence comes first: it is the one a rider reaches for with the
            // alarm sounding in their pocket, and the alternative next to it ends
            // the ride's telemetry. See MonitoringService.ACTION_SILENCE.
            .addAction(0, LiveNotificationText.silenceAction(summary.alarmSilenced), silenceIntent())
            .addAction(0, LiveNotificationText.DISCONNECT, disconnectIntent())
        manager?.notify(LIVE_NOTIFICATION_ID, builder.build())
    }

    override fun cancelLive() { manager?.cancel(LIVE_NOTIFICATION_ID) }

    /**
     * Words come from `res/values` / `res/values-ru` through [AlertNotificationFormatter] in the
     * system language (the app has no language setting of its own); this only renders them.
     */
    override fun showAlert(message: AlertMessage, severity: AlertSeverity, alertId: Int) {
        val words = AlertNotificationFormatter.format(message, unitSystem(), AndroidAlertStrings(context))
        val title = words.title
        val text = words.text
        val channel = when (severity) {
            AlertSeverity.CRITICAL -> NotificationChannels.CRITICAL
            AlertSeverity.WARNING -> NotificationChannels.WARNING
            AlertSeverity.INFO -> NotificationChannels.INFO
        }
        val priority = when (severity) {
            AlertSeverity.CRITICAL -> NotificationCompat.PRIORITY_HIGH
            AlertSeverity.WARNING -> NotificationCompat.PRIORITY_DEFAULT
            AlertSeverity.INFO -> NotificationCompat.PRIORITY_LOW
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
        manager?.notify(alertId, builder.build())
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * "Silence" (F §14) — stop the alarm, keep the session.
     *
     * Request code 1, not 0: a `PendingIntent` is keyed on request code and the
     * intent's action, and giving two broadcast intents from this file the same
     * code makes them one edit away from aliasing each other under
     * `FLAG_UPDATE_CURRENT` — where the failure would be the Silence button
     * disconnecting the rider.
     *
     * The action name is taken from `MonitoringService` rather than spelled out
     * again: a typo here would produce a button that silently does nothing, which
     * is exactly the failure this action exists to remove.
     */
    private fun silenceIntent(): PendingIntent {
        val intent = Intent(MonitoringService.ACTION_SILENCE).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** The constant, for the reason [silenceIntent] gives — a typo here is a dead button. */
    private fun disconnectIntent(): PendingIntent {
        val intent = Intent(MonitoringService.ACTION_DISCONNECT).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

}
