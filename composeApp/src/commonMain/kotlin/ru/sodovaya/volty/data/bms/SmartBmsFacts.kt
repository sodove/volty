package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.DeviceFact
import ru.sodovaya.volty.domain.model.DeviceFactKey

/**
 * The device facts the smart-BMS decoders (ANT, JK, JBD, Daly) share: what the BMS itself
 * declares about its layout - never what the app counted. Every input is null until the wire has
 * said it, and a null input is simply absent from the list.
 *
 * Returns the SAME list instance while the inputs are unchanged, so a caller that polls after
 * every notification neither allocates nor republishes; [reset] forgets the last inputs.
 */
internal class SmartBmsFactsCache {
    private var lastInputs: List<Any?>? = null
    private var cached: List<DeviceFact> = emptyList()

    fun facts(firmware: String?, hardware: String?, declaredCells: Int?, declaredTempSensors: Int?): List<DeviceFact> {
        val inputs = listOf(firmware, hardware, declaredCells, declaredTempSensors)
        if (inputs == lastInputs) return cached
        lastInputs = inputs
        cached = buildList {
            firmware?.let { add(DeviceFact(DeviceFactKey.FIRMWARE, it)) }
            hardware?.let { add(DeviceFact(DeviceFactKey.HARDWARE_VERSION, it)) }
            declaredCells?.takeIf { it > 0 }?.let { add(DeviceFact(DeviceFactKey.BMS_DECLARED_CELLS, it.toString())) }
            declaredTempSensors?.takeIf { it > 0 }?.let { add(DeviceFact(DeviceFactKey.BMS_DECLARED_TEMP_SENSORS, it.toString())) }
        }
        return cached
    }

    fun reset() {
        lastInputs = null
        cached = emptyList()
    }
}
