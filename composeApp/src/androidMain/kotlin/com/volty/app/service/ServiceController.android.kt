package com.volty.app.service

import android.content.Context
import android.content.Intent
import android.os.Build

actual class ServiceController(private val context: Context) {
    actual fun start() {
        val intent = Intent(context, MonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }
    actual fun stop() {
        context.stopService(Intent(context, MonitoringService::class.java))
    }
}
