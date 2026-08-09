package ru.sodovaya.volty.presentation.common

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.stats.BmsReadings
import ru.sodovaya.volty.util.formatFixed
import ru.sodovaya.volty.util.formatSigned

/** Pure numeric text for pack current/power; null tells a renderer to show the shared dash. */
object BmsMetricMapper {
    fun currentValue(data: BmsData): String? =
        BmsReadings.current(data).takeIf { data.isConnected }?.let { formatSigned(it, 1) }

    fun powerValue(data: BmsData): String? =
        BmsReadings.power(data).takeIf { data.isConnected }?.let { formatFixed(it, 0) }
}
