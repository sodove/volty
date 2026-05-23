package com.volty.app.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.volty.app.MainActivity
import com.volty.app.domain.usecase.AlertSeverity
import kotlin.math.abs

private const val LIVE_NOTIFICATION_ID = 1001

class AndroidNotifier(private val context: Context) : Notifier {

    init { NotificationChannels.ensureCreated(context) }

    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun showLive(summary: LiveSummary) {
        val signedCurrent = (if (summary.currentA >= 0) "+" else "") + format1(summary.currentA) + " A"
        val voltageText = format1(summary.voltageV) + " V"
        val socText = "${summary.socPercent}%"
        val text = listOfNotNull(socText, voltageText, signedCurrent, summary.etaText).joinToString(" · ")
        val builder = NotificationCompat.Builder(context, NotificationChannels.LIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Volty · ${summary.vehicleName}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, "Disconnect", disconnectIntent())
        manager?.notify(LIVE_NOTIFICATION_ID, builder.build())
    }

    override fun cancelLive() { manager?.cancel(LIVE_NOTIFICATION_ID) }

    override fun showAlert(title: String, text: String, severity: AlertSeverity, alertId: Int) {
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

    private fun disconnectIntent(): PendingIntent {
        val intent = Intent("com.volty.app.ACTION_DISCONNECT").setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun format1(v: Float): String {
        val sign = if (v < 0) "-" else ""
        val abs = abs(v)
        val whole = abs.toInt()
        val frac = ((abs - whole) * 10).toInt()
        return "$sign$whole.$frac"
    }
}
