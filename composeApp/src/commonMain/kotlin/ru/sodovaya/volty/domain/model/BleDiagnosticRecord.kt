package ru.sodovaya.volty.domain.model


/** One incoming BLE notification retained for an explicitly started capture. */
data class BleDiagnosticRecord(
    val timestampEpochMillis: Long,
    val deviceAddress: String,
    val deviceName: String?,
    val protocol: String,
    val notifyUuid: String,
    val payloadLength: Int,
    val payloadHex: String,
)

data class BleDiagnosticsState(
    val isCapturing: Boolean = false,
    val records: List<BleDiagnosticRecord> = emptyList(),
    val activeLinks: List<BleDiagnosticLink> = emptyList(),
)

/** Read-only identity/version facts for one currently active BLE link. */
data class BleDiagnosticLink(
    val deviceAddress: String,
    val deviceName: String?,
    val protocol: String,
    /** Configured source identity, such as VETERAN or NOSFET; null if unconfigured. */
    val controllerIdentity: String?,
    val hardwareCode: String?,
    val model: String?,
    val firmwareVersion: String?,
    val batteries: List<BleDiagnosticBatterySample> = emptyList(),
    val controllers: List<BleDiagnosticControllerSample> = emptyList(),
)

/** Latest decoded battery sample attributed to one pack on this BLE link. */
data class BleDiagnosticBatterySample(
    val localPackIndex: Int,
    val globalPackIndex: Int?,
    val packIdentity: String?,
    val data: BmsData,
)

/** Latest decoded controller sample attributed to one controller on this BLE link. */
data class BleDiagnosticControllerSample(
    val localControllerIndex: Int,
    val globalControllerIndex: Int?,
    val controllerIdentity: String?,
    val data: ControllerData,
)
