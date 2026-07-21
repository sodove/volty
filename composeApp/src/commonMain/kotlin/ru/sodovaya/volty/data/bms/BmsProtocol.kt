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

    /**
     * How many logical packs this one link carries. One for every BMS volty
     * talks to directly; a Begode wheel multiplexes two over a single link.
     */
    open val packCount: Int get() = 1

    /** Latest fully-parsed data for [packIndex], or null if nothing parsed yet. */
    abstract fun latestData(packIndex: Int): BmsData?

    /** Convenience for the single-pack case. */
    fun latestData(): BmsData? = latestData(0)

    /** Reset internal buffers and parser state. */
    abstract fun reset()
}
