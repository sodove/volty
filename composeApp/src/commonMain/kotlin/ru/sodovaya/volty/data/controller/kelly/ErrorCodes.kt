package ru.sodovaya.volty.data.controller.kelly

object ErrorCodes {
    val ERROR_NAMES = arrayOf(
        "Identify Err", "Over Volt", "Low Volt", "Reserved", "Locking", "V+ Err", "Overtemp", "High Pedel",
        "Reserved", "Reset Error", "Pedel Error", "Hall Sensor Error", "Reserved", "Emergency Rev Err",
        "Motor OverTemp Err", "Current Meter Err"
    )

    fun decode(errorCode: Int): List<String> = when {
        errorCode <= 0 || errorCode > 0xFFFF -> emptyList()
        else -> (0 until 16).filter { (errorCode shr it) and 1 == 1 }.map { ERROR_NAMES[it] }
    }

    fun decodeToString(errorCode: Int): String = decode(errorCode).joinToString(",")
}
