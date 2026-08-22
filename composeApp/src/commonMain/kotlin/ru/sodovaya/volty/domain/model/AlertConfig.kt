package ru.sodovaya.volty.domain.model

data class AlertConfig(
    val cellHighV: Float? = null,
    val cellLowV: Float? = null,
    val cellDeltaMv: Int? = 200,
    val temperatureWarnC: Float? = 50f,
    val temperatureHighC: Float? = 60f,
    val socLowPercent: Int? = 15,
    val socCutoffPercent: Int? = null,
    val cellHighEnabled: Boolean = true,
    val cellLowEnabled: Boolean = true,
    val cellDeltaEnabled: Boolean = true,
    val temperatureWarnEnabled: Boolean = true,
    val temperatureHighEnabled: Boolean = true,
    val socLowEnabled: Boolean = true,
    val socCutoffEnabled: Boolean = true,
    val disconnectNotify: Boolean = true,
    val chargeCompleteNotify: Boolean = true
)
