package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.data.bms.vesc.VescSetupConfig

/** Optional capability for protocols that can report a controller's setup. */
interface ControllerConfigSource {
    /** Number of logical controllers this source can describe. */
    val controllerConfigCount: Int

    /** Latest setup reported for [controllerIndex], or null when it is unknown. */
    fun latestControllerConfig(controllerIndex: Int): VescSetupConfig?
}
