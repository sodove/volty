package com.volty.app.notification

import com.volty.app.domain.usecase.AlertSeverity

data class LiveSummary(
    val vehicleName: String,
    val socPercent: Int,
    val voltageV: Float,
    val currentA: Float,
    val etaText: String?
)

interface Notifier {
    fun showLive(summary: LiveSummary)
    fun cancelLive()
    fun showAlert(title: String, text: String, severity: AlertSeverity, alertId: Int)
}
