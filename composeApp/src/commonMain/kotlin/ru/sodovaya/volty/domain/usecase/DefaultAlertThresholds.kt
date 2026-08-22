package ru.sodovaya.volty.domain.usecase

import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.Chemistry

data class ResolvedAlertConfig(
    val cellHighV: Float,
    val cellLowV: Float,
    val cellDeltaMv: Int,
    val temperatureWarnC: Float,
    val temperatureHighC: Float,
    val socLowPercent: Int,
    val socCutoffPercent: Int?,
    val cellHighEnabled: Boolean,
    val cellLowEnabled: Boolean,
    val cellDeltaEnabled: Boolean,
    val temperatureWarnEnabled: Boolean,
    val temperatureHighEnabled: Boolean,
    val socLowEnabled: Boolean,
    val socCutoffEnabled: Boolean,
    val disconnectNotify: Boolean,
    val chargeCompleteNotify: Boolean
)

fun resolveAlertConfig(config: AlertConfig, chemistry: Chemistry): ResolvedAlertConfig =
    ResolvedAlertConfig(
        cellHighV = config.cellHighV ?: chemistry.defaultHighV,
        cellLowV = config.cellLowV ?: chemistry.defaultLowV,
        cellDeltaMv = config.cellDeltaMv ?: 200,
        temperatureWarnC = config.temperatureWarnC ?: 50f,
        temperatureHighC = config.temperatureHighC ?: 60f,
        socLowPercent = config.socLowPercent ?: 15,
        socCutoffPercent = config.socCutoffPercent,
        cellHighEnabled = config.cellHighEnabled,
        cellLowEnabled = config.cellLowEnabled,
        cellDeltaEnabled = config.cellDeltaEnabled,
        temperatureWarnEnabled = config.temperatureWarnEnabled,
        temperatureHighEnabled = config.temperatureHighEnabled,
        socLowEnabled = config.socLowEnabled,
        socCutoffEnabled = config.socCutoffEnabled,
        disconnectNotify = config.disconnectNotify,
        chargeCompleteNotify = config.chargeCompleteNotify
    )
