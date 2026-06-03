package ru.sodovaya.volty.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val LIVE = "volty.live"
    const val CRITICAL = "volty.critical"
    const val WARNING = "volty.warning"
    const val INFO = "volty.info"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            NotificationChannel(LIVE, "Live monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Volty is monitoring your battery"
                setShowBadge(false)
            },
            NotificationChannel(CRITICAL, "Critical alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Battery is in a dangerous state"
                enableVibration(true)
            },
            NotificationChannel(WARNING, "Warnings", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Things to keep an eye on"
                enableVibration(true)
            },
            NotificationChannel(INFO, "Info", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Charge complete, disconnects, etc."
            }
        ).forEach { mgr.createNotificationChannel(it) }
    }
}
