package ru.sodovaya.volty.data.controller.kelly

data class MonitorParam(
    val offset: Int,
    val size: ParamSize,
    val position: Int,
    val type: ParamType,
    val name: String,
    val minValue: Int,
    val maxValue: Int,
    val tips: String
)

object MonitorDefinitions {
    val MONITOR_COMMANDS = byteArrayOf(
        EtsCommand.USER_MONITOR1,
        EtsCommand.USER_MONITOR2,
        EtsCommand.USER_MONITOR3
    )

    val PARAMETERS = listOf(
        MonitorParam(16, ParamSize.WORD, 1, ParamType.HEX, "Error Status", 0, 65535, "Error status bitmask"),
        MonitorParam(0, ParamSize.BYTE, 0, ParamType.UNSIGNED, "TPS Pedel", 0, 255, "Throttle AD, 0-255 = 0-5V"),
        MonitorParam(1, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Brake Pedel", 0, 255, "Brake AD, 0-255 = 0-5V"),
        MonitorParam(2, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Brake Switch", 0, 2, "Brake switch status"),
        MonitorParam(3, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Foot Switch", 0, 2, "Throttle safety switch"),
        MonitorParam(4, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Forward Switch", 0, 2, "Forward switch status"),
        MonitorParam(5, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Reversed", 0, 2, "Reverse switch status"),
        MonitorParam(6, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Hall A", 0, 2, "Hall sensor A"),
        MonitorParam(7, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Hall B", 0, 2, "Hall sensor B"),
        MonitorParam(8, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Hall C", 0, 2, "Hall sensor C"),
        MonitorParam(9, ParamSize.BYTE, 0, ParamType.UNSIGNED, "B+ Volt", 0, 200, "Battery voltage"),
        MonitorParam(10, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Motor Temp", 0, 150, "Motor temperature C"),
        MonitorParam(11, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Controller Temp", 0, 150, "Controller temperature C"),
        MonitorParam(12, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Setting Dir", 0, 2, "Set direction: 0=forward, 1=reverse"),
        MonitorParam(13, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Actual Dir", 0, 2, "Actual direction: 0=forward, 1=reverse"),
        MonitorParam(14, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Brake Switch2", 0, 2, "Brake switch 2 status"),
        MonitorParam(15, ParamSize.BYTE, 0, ParamType.UNSIGNED, "Low Speed", 0, 2, "Low speed status"),
        MonitorParam(18, ParamSize.WORD, 1, ParamType.UNSIGNED, "Motor Speed", 0, 10000, "Motor speed RPM"),
        MonitorParam(20, ParamSize.WORD, 1, ParamType.UNSIGNED, "Phase Current", 0, 800, "Phase current RMS")
    )

    fun readMonitorValues(monitorData: IntArray): Map<String, String> = buildMap {
        for (param in PARAMETERS) {
            val length = if (param.size == ParamSize.WORD) param.position + 1 else 1
            if (param.offset + length <= monitorData.size) {
                put(param.name, ParameterCodec.readParam(monitorData, param.offset, param.size, param.position, param.type))
            }
        }
    }
}
