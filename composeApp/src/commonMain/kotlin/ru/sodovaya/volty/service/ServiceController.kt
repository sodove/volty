package ru.sodovaya.volty.service

import ru.sodovaya.volty.domain.model.ConnectionState

expect class ServiceController {
    fun start()
    fun stop()
    internal fun reconcileLocationRetention()
    internal fun setRecordingEnabled(enabled: Boolean)
    internal fun shouldKeepService(connection: ConnectionState): Boolean
}
