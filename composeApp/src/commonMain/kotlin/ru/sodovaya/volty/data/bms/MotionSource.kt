package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.ControllerData

/** Optional capability a [BmsProtocol] MAY also implement to emit motion. */
interface MotionSource {
    val controllerCount: Int
    fun latestMotion(controllerIndex: Int): ControllerData?
}
