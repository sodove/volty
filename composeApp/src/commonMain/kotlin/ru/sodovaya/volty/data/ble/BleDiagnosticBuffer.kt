package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.model.BleDiagnosticRecord
import ru.sodovaya.volty.domain.model.BleDiagnosticsState

private const val HEX_DIGITS = "0123456789ABCDEF"

/** Small synchronized in-memory ring; append never suspends or waits on consumers. */
internal class BleDiagnosticBuffer {
    private val lock = Any()
    private val mutableState = MutableStateFlow(BleDiagnosticsState())
    val state: StateFlow<BleDiagnosticsState> = mutableState.asStateFlow()

    fun start() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(isCapturing = true)
    }

    fun stop() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(isCapturing = false)
    }

    fun clear() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(records = emptyList())
    }

    /** Returns before reading or copying [bytes] when capture is off. */
    fun appendNotification(
        timestampEpochMillis: Long,
        address: String,
        name: String?,
        protocol: String,
        notifyUuid: String,
        bytes: ByteArray,
    ) = synchronized(lock) {
        val current = mutableState.value
        if (!current.isCapturing) return@synchronized
        val hex = buildString(bytes.size * 3 - if (bytes.isEmpty()) 0 else 1) {
            bytes.forEachIndexed { index, byte ->
                if (index > 0) append(' ')
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
            }
        }
        val record = BleDiagnosticRecord(
            timestampEpochMillis = timestampEpochMillis,
            deviceAddress = address,
            deviceName = name,
            protocol = protocol,
            notifyUuid = notifyUuid,
            payloadLength = bytes.size,
            payloadHex = hex,
        )
        val updated = (current.records + record).takeLast(MAX_RECORDS)
        mutableState.value = current.copy(records = updated)
    }

    fun updateActiveLinks(links: List<ru.sodovaya.volty.domain.model.BleDiagnosticLink>) = synchronized(lock) {
        mutableState.value = mutableState.value.copy(activeLinks = links.toList())
    }

    companion object {
        const val MAX_RECORDS = 200
    }
}
