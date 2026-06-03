package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData

abstract class BmsProtocol {

    abstract val uuids: BmsUuids

    /** Commands sent once after connecting. */
    abstract fun handshakeCommands(): List<ByteArray>

    /** Commands sent each poll cycle. Empty list = streaming protocol. */
    abstract fun pollCommands(): List<ByteArray>

    /** Delay between poll cycles (ms). Ignored when [pollCommands] is empty. */
    open val pollIntervalMs: Long = 1000L

    /** Feed an incoming BLE notification chunk. */
    abstract fun onNotification(data: ByteArray)

    /** Latest fully-parsed BMS data, or null if nothing has been parsed yet. */
    abstract fun latestData(): BmsData?

    /** Reset internal buffers and parser state. */
    abstract fun reset()
}
