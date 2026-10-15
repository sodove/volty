package ru.sodovaya.volty.service

import android.content.Context
import android.content.Intent
import android.os.Build
import ru.sodovaya.volty.data.history.HistoryServiceOwnership
import ru.sodovaya.volty.domain.model.ConnectionState

actual class ServiceController(private val context: Context) {
    private val ownership = HistoryServiceOwnership(::startNative, ::stopNative)

    actual fun start() = ownership.setBleEnabled(true)
    actual fun stop() = ownership.setBleEnabled(false)
    internal actual fun setRecordingEnabled(enabled: Boolean) = ownership.setRecordingEnabled(enabled)
    internal actual fun shouldKeepService(connection: ConnectionState): Boolean = ownership.shouldKeepService(connection)

    private fun startNative() {
        val intent = Intent(context, MonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    private fun stopNative() {
        context.stopService(Intent(context, MonitoringService::class.java))
    }
}
