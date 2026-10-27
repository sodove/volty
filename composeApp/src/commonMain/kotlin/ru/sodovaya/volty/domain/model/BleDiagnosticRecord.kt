package ru.sodovaya.volty.domain.model

import ru.sodovaya.volty.domain.plausibility.RejectedFrame


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
    /** The newest records only (see BleDiagnosticBuffer.UI_RECORDS); [recordCount] counts the whole capture. */
    val records: List<BleDiagnosticRecord> = emptyList(),
    val recordCount: Int = records.size,
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
    /** Protocol-specific facts decoded so far; empty when the protocol reports none. */
    val facts: List<DeviceFact> = emptyList(),
    /** Notifications per second over the last ~5 s; null until two have arrived in that window. */
    val framesPerSecond: Float? = null,
    /** Reconnect episodes of this link since the vehicle connected. */
    val reconnects: Int = 0,
    /**
     * When [framesPerSecond] and [facts] were last refreshed (epoch millis), or null if never. They update only
     * on a notification, so a stalled-but-ONLINE link keeps its last values: a reader must treat stats older
     * than a few seconds as unknown rather than current.
     */
    val statsAtEpochMillis: Long? = null,
    /**
     * The protocol's always-on rejection ring (newest 32, oldest first): frames a plausibility rule held back and
     * counter re-baselines, with the raw bytes. Independent of manual capture; it exists so the next torn read is measured.
     */
    val rejectedFrames: List<RejectedFrame> = emptyList(),
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
