package ru.sodovaya.volty.data.bms

/** Narrow read-only facts a protocol may expose after decoding a notification. */
interface ProtocolDiagnostics {
    val diagnosticHardwareCode: String?
    val diagnosticModel: String?
    val diagnosticFirmwareVersion: String?
}

data class ProtocolDiagnosticValues(
    val hardwareCode: String?,
    val model: String?,
    val firmwareVersion: String?,
)

fun ProtocolDiagnostics.diagnosticValues() = ProtocolDiagnosticValues(
    hardwareCode = diagnosticHardwareCode,
    model = diagnosticModel,
    firmwareVersion = diagnosticFirmwareVersion,
)
