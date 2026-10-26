package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.model.BleDiagnosticRecord
import ru.sodovaya.volty.domain.model.BleDiagnosticsState

private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * Synchronized in-memory ring; append never suspends or waits on consumers.
 *
 * The ring keeps up to [MAX_RECORDS] notifications - minutes of a ride at a wheel's frame rate, for
 * a dump the rider shares as a file - while the published [state] carries only the newest
 * [UI_RECORDS] plus the total count, so a long capture does not copy thousands of records into the
 * UI on every notification. An earlier revision kept just 200 records in total, which a free-spin
 * test outruns in seconds (rider, 2026-10-26). [snapshot] returns the whole ring.
 */
internal class BleDiagnosticBuffer {
    private val lock = Any()
    private val ring = ArrayDeque<BleDiagnosticRecord>()
    private val mutableState = MutableStateFlow(BleDiagnosticsState())
    val state: StateFlow<BleDiagnosticsState> = mutableState.asStateFlow()

    fun start() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(isCapturing = true)
    }

    fun stop() = synchronized(lock) {
        mutableState.value = mutableState.value.copy(isCapturing = false)
    }

    fun clear() = synchronized(lock) {
        ring.clear()
        mutableState.value = mutableState.value.copy(records = emptyList(), recordCount = 0)
    }

    /** Every retained record, oldest first. */
    fun snapshot(): List<BleDiagnosticRecord> = synchronized(lock) { ring.toList() }

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
        ring.addLast(record)
        while (ring.size > MAX_RECORDS) ring.removeFirst()
        val newest = (current.records + record).takeLast(UI_RECORDS)
        mutableState.value = current.copy(records = newest, recordCount = ring.size)
    }

    fun updateActiveLinks(links: List<ru.sodovaya.volty.domain.model.BleDiagnosticLink>) = synchronized(lock) {
        mutableState.value = mutableState.value.copy(activeLinks = links.toList())
    }

    companion object {
        /** About 15-30 minutes of one wheel link at 10-20 notifications/s; a few MB of text. */
        const val MAX_RECORDS = 20_000

        /** What the debug screen lists and copies to the clipboard. */
        const val UI_RECORDS = 200
    }
}
