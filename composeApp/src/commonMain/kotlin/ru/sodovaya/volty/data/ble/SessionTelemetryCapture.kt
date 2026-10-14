package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.domain.model.TelemetryObservation

internal data class TelemetryReceiptTime(val epochMillis: Long, val elapsedMillis: Long) {
    init { require(elapsedMillis >= 0) }
}
internal data class TelemetryReceipt(val sessionId: String, val notificationOrdinal: Long, val time: TelemetryReceiptTime)
internal data class ReceivedTelemetry(val receipt: TelemetryReceipt, val observation: TelemetryObservation)

/** One incoming collector and one fresh protocol per connection attempt; no source attribution or I/O here. */
internal class SessionTelemetryCapture(
    private val protocol: BmsProtocol,
    private val sessionId: String,
    private val readTime: () -> TelemetryReceiptTime,
    private val ownsSession: () -> Boolean,
    private val onObservation: (ReceivedTelemetry) -> Unit,
    private val onUnreceiptedObservation: (TelemetryObservation) -> Unit = {},
    initialNotificationOrdinal: Long = 0,
) {
    init { require(sessionId.isNotBlank()); require(initialNotificationOrdinal >= 0) }
    @Volatile private var closed = false
    private var decoding = false
    private var ordinal = initialNotificationOrdinal
    var receiptFailures: Long = 0
        private set
    fun requireProtocol(candidate: BmsProtocol) { require(candidate === protocol) }
    fun close() { closed = true }
    private fun active() = !closed && ownsSession()
    fun processNotification(data: ByteArray, beforeDecode: () -> Unit = {}): Boolean {
        if (!active()) return false
        check(!decoding) { "A protocol has one incoming collector; receipt capture cannot be reentered" }
        decoding = true
        try {
            val receipt = try {
                val hasOrdinal = ordinal < Long.MAX_VALUE
                if (hasOrdinal) ordinal++
                // Refresh per-notification context even after ordinal exhaustion;
                // decoded loss must never reuse a previous recording owner.
                val time = readTime()
                check(hasOrdinal)
                TelemetryReceipt(sessionId, ordinal, time)
            } catch (_: Exception) {
                if (receiptFailures < Long.MAX_VALUE) receiptFailures++
                null
            }
            if (!active()) return false
            val previous = protocol.telemetryObserver
            var scopeOpen = true
            lateinit var observer: (TelemetryObservation) -> Unit
            observer = { observation ->
                if (scopeOpen && active() && protocol.telemetryObserver === observer) {
                    if (receipt != null) onObservation(ReceivedTelemetry(receipt, observation))
                    else onUnreceiptedObservation(observation)
                }
            }
            // Even a failed clock gets a scope: suppress unstamped history while keeping the live decoder alive.
            protocol.telemetryObserver = observer
            try {
                beforeDecode()
                if (!active()) return false
                protocol.onNotification(data)
                return true
            } finally {
                scopeOpen = false
                // A callback may have installed another observer; restoration must not erase that replacement.
                if (protocol.telemetryObserver === observer) protocol.telemetryObserver = previous
            }
        } finally {
            decoding = false
        }
    }
}
