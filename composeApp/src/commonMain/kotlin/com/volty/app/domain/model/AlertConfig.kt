package com.volty.app.domain.model

data class AlertConfig(
    val cellHighV: Float? = null,
    val cellLowV: Float? = null,
    val cellDeltaMv: Int? = 200,
    val temperatureHighC: Float? = 60f,
    val socLowPercent: Int? = 15,
    val socCutoffPercent: Int? = null,
    val disconnectNotify: Boolean = true,
    val chargeCompleteNotify: Boolean = true
)
