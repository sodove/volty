package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.SectionState

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

    /**
     * Physically replaceable assemblies ("sections") inside pack [packIndex],
     * each with the voltage / temperature evidence the protocol decoded for
     * it. Empty — the default — when the protocol has no such breakdown,
     * which is every single-assembly BMS volty talks to directly.
     *
     * Contract for implementers: [SectionState.cellRange] may only be filled
     * from evidence about the frame layout that was actually verified, never
     * from arithmetic over the received cell list — `groupPackCells` refuses
     * inferred boundaries, and a fabricated range would mislabel every cell
     * of the later assemblies. No verified range is reported as null, and no
     * verified breakdown at all as an empty list; both degrade downstream to
     * a flat cell list, which is the honest rendering.
     */
    open fun sections(packIndex: Int): List<SectionState> = emptyList()

    /** Reset internal buffers and parser state. */
    abstract fun reset()
}
