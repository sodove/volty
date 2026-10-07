package ru.sodovaya.volty.data.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.sodovaya.volty.R
import ru.sodovaya.volty.data.nearby.DirectCallSnapshot

internal const val INCOMING_CALL_NOTIFICATION_CHANNEL = "nearby_voice_incoming"

/** Acceptance must open an Activity directly; decline resolves the original nonexported ticket. */
internal interface IncomingCallNotificationPendingIntents {
    fun accept(ticket: IncomingCallNotificationTicket): PendingIntent
    fun decline(ticket: IncomingCallNotificationTicket): PendingIntent
}

/** Native submission only. Seeing/hearing a notification still requires device observation. */
internal class AndroidIncomingCallNotificationPort(context: Context,
    private val actions: IncomingCallNotificationPendingIntents) : IncomingCallNotificationPort {
    private val application = context.applicationContext
    private val manager = application.getSystemService(NotificationManager::class.java)
    private val tickets = mutableMapOf<String, IncomingCallNotificationTicket>()

    override fun show(ticket: IncomingCallNotificationTicket, call: DirectCallSnapshot, remainingMillis: Long): Boolean {
        mainOwner()
        require(remainingMillis in 1..45_000)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(application,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(application).areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel(INCOMING_CALL_NOTIFICATION_CHANNEL,
            application.getString(R.string.nearby_incoming_notification_channel), NotificationManager.IMPORTANCE_HIGH))
        if (manager.getNotificationChannel(INCOMING_CALL_NOTIFICATION_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        if (tickets.containsKey(ticket.id)) return false
        // Record before the native call so a partially failed submission remains cancellable.
        tickets[ticket.id] = ticket
        post(ticket, remainingMillis, null)
        return true
    }

    override fun label(ticket: IncomingCallNotificationTicket, callerName: String, remainingMillis: Long): Boolean {
        mainOwner()
        if (tickets[ticket.id] !== ticket || incomingCallDisplayName(callerName) == null) return false
        require(remainingMillis in 1..45_000)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(application,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(application).areNotificationsEnabled()) return false
        if (manager.getNotificationChannel(INCOMING_CALL_NOTIFICATION_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        post(ticket, remainingMillis, callerName)
        return true
    }

    private fun post(ticket: IncomingCallNotificationTicket, remainingMillis: Long, callerName: String?) {
        val accept = actions.accept(ticket)
        val decline = actions.decline(ticket)
        val notification = NotificationCompat.Builder(application, INCOMING_CALL_NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(application.getString(R.string.nearby_incoming_notification_title))
            .setContentText(if (callerName == null) application.getString(R.string.nearby_incoming_notification_body)
                else application.getString(R.string.nearby_incoming_notification_named_body, callerName))
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(accept).setAutoCancel(false).setTimeoutAfter(remainingMillis)
            .addAction(android.R.drawable.ic_menu_call, application.getString(R.string.nearby_incoming_notification_accept), accept)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, application.getString(R.string.nearby_incoming_notification_decline), decline)
            .build()
        manager.notify(ticket.id, NOTIFICATION, notification)
    }

    override fun cancel(ticket: IncomingCallNotificationTicket) {
        mainOwner()
        if (tickets[ticket.id] !== ticket) return
        manager.cancel(ticket.id, NOTIFICATION)
        tickets.remove(ticket.id)
    }
    private fun mainOwner() = check(Looper.myLooper() == Looper.getMainLooper()) {"Incoming notifications require Main owner"}
    private companion object {
        const val NOTIFICATION = 1043
    }
}
