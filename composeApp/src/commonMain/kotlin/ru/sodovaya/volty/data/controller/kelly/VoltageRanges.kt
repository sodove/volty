package ru.sodovaya.volty.data.controller.kelly

object VoltageRanges {
    private data class VoltageRange(val min: Int, val max: Int)
    private val ranges = mapOf(
        11 to VoltageRange(18, 132), 12 to VoltageRange(18, 136), 14 to VoltageRange(18, 180),
        16 to VoltageRange(18, 200), 24 to VoltageRange(8, 35), 32 to VoltageRange(18, 380),
        36 to VoltageRange(18, 45), 48 to VoltageRange(18, 62), 60 to VoltageRange(18, 80),
        72 to VoltageRange(18, 90), 84 to VoltageRange(18, 105), 96 to VoltageRange(18, 120)
    )

    fun getMin(voltageCode: Int): Int = ranges[voltageCode]?.min ?: 0
    fun getMax(voltageCode: Int): Int = ranges[voltageCode]?.max ?: 0
    fun getRangeForCode80(controllerVolt: Int): Pair<Int, Int> = 18 to (controllerVolt * 125) / 100
    fun getVoltageRange(voltageCodeStr: String, controllerVolt: Int): Pair<Int, Int> {
        val code = voltageCodeStr.toIntOrNull() ?: return 0 to 0
        return if (code == 80) getRangeForCode80(controllerVolt) else getMin(code) to getMax(code)
    }
}
