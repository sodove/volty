package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsData

/** Evidence-aware pack readings: null is unknown, including a controller-derived pack's zero. */
object BmsReadings {
    fun current(data: BmsData): Float? = data.current.takeIf { data.hasCurrent }
    fun power(data: BmsData): Float? = data.power.takeIf { data.hasPower }
}
