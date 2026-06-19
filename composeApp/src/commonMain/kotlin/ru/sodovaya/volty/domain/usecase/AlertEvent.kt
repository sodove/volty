package ru.sodovaya.volty.domain.usecase

enum class AlertSeverity { CRITICAL, WARNING, INFO }

enum class AlertKind {
    CELL_HIGH, CELL_LOW, CELL_DELTA, TEMPERATURE_WARN, TEMPERATURE_HIGH,
    SOC_LOW, SOC_CUTOFF, DISCONNECT, CHARGE_COMPLETE
}

data class AlertEvent(
    val kind: AlertKind,
    val severity: AlertSeverity,
    val title: String,
    val text: String,
    val vehicleId: String
)
