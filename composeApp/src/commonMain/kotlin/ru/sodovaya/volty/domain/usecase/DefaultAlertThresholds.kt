package ru.sodovaya.volty.domain.usecase

import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.Chemistry

data class ResolvedAlertConfig(
    val cellHighV: Float,
    val cellLowV: Float,
    val cellDeltaMv: Int,
    val temperatureHighC: Float,
    val socLowPercent: Int,
    val socCutoffPercent: Int?,
    val disconnectNotify: Boolean,
    val chargeCompleteNotify: Boolean
)

fun resolveAlertConfig(config: AlertConfig, chemistry: Chemistry): ResolvedAlertConfig =
    ResolvedAlertConfig(
        cellHighV = config.cellHighV ?: chemistry.defaultHighV,
        cellLowV = config.cellLowV ?: chemistry.defaultLowV,
        cellDeltaMv = config.cellDeltaMv ?: 200,
        temperatureHighC = config.temperatureHighC ?: 60f,
        socLowPercent = config.socLowPercent ?: 15,
        socCutoffPercent = config.socCutoffPercent,
        disconnectNotify = config.disconnectNotify,
        chargeCompleteNotify = config.chargeCompleteNotify
    )
