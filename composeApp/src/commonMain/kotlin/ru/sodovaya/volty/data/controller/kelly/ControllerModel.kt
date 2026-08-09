package ru.sodovaya.volty.data.controller.kelly

enum class ControllerModel {
    KBLS_0106, KBLS_0109;

    companion object {
        fun detect(moduleName: String, softwareVersion: Int): Result<ControllerModel> {
            if (moduleName.length < 4) return Result.failure(UnsupportedControllerException("Module name too short: '$moduleName'"))
            val isKbls = moduleName.substring(1, 4) in setOf("BLS", "BSS") || moduleName.substring(1, 3) == "LS"
            if (!isKbls) return Result.failure(UnsupportedControllerException("Unsupported controller type: '$moduleName'. Only KBLS (KLS) series is supported."))
            return when {
                softwareVersion >= 265 -> Result.success(KBLS_0109)
                softwareVersion >= 262 -> Result.success(KBLS_0106)
                else -> Result.failure(UnsupportedControllerException("Unsupported firmware version: $softwareVersion. Minimum required: 262"))
            }
        }
    }
}

class UnsupportedControllerException(message: String) : Exception(message)
