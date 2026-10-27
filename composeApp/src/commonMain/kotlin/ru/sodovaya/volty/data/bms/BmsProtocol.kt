package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.TelemetryObservation
import ru.sodovaya.volty.domain.model.TelemetryOrigin
import ru.sodovaya.volty.domain.model.TelemetryReading
import ru.sodovaya.volty.domain.model.TelemetryRole
import ru.sodovaya.volty.domain.plausibility.LedgerKind
import ru.sodovaya.volty.domain.plausibility.PlausibilityRule
import ru.sodovaya.volty.domain.plausibility.RejectedFrame

abstract class BmsProtocol {
    /** Fresh accepted decoder output. Consumer failure must not tear down the live BLE stream. */
    var telemetryObserver: ((TelemetryObservation) -> Unit)? = null
    var telemetryObserverFailures: Long = 0
        private set

    /** Publish the same decoded snapshot to live consumers and its fresh fields to observers. */
    protected fun publishControllerUpdate(data: ControllerData, fields: Set<TelemetryMetric>, localIndex: Int = 0): ControllerData {
        if (fields.isNotEmpty()) observeTelemetry(TelemetryOrigin.CONTROLLER, localIndex) { freshControllerReadings(data, fields) }
        return data
    }

    protected fun publishBatteryUpdate(data: BmsData, fields: Set<TelemetryMetric>, localIndex: Int = 0,
        role: TelemetryRole = TelemetryRole.CONTRIBUTOR): BmsData {
        if (fields.isNotEmpty()) observeTelemetry(TelemetryOrigin.BATTERY, localIndex, role) { freshBatteryReadings(data, fields) }
        return data
    }

    protected fun observeTelemetry(origin: TelemetryOrigin, localIndex: Int,
        role: TelemetryRole = TelemetryRole.CONTRIBUTOR, assemblyIndex: Int? = null,
        readings: () -> List<TelemetryReading>) {
        val observer = telemetryObserver ?: return
        try {
            observer(TelemetryObservation(origin, localIndex, readings(), role, assemblyIndex))
        } catch (error: Exception) {
            // Keep decode/alerts alive and make a failed recorder callback observable without logging payloads.
            if (telemetryObserverFailures < Long.MAX_VALUE) telemetryObserverFailures++
        }
    }

    // --- Rejection ledger, shared by every protocol ---

    /** Framed notifications seen on this connection; the ledger's clock (frames carry no timestamps). */
    @Volatile private var ledgerFrameIndex: Long = 0L
    @Volatile private var rejectedCount: Long = 0L
    @Volatile private var rebases: Long = 0L
    @Volatile private var ring: List<RejectedFrame> = emptyList()

    /** Frames a plausibility rule refused to publish on this connection. Zeroed by [resetLedger]. */
    val rejectedFrameCount: Long get() = rejectedCount

    /** Counter re-baselines accepted after confirmation on this connection. A storm of them on a healthy device is a false positive. */
    val rebaseCount: Long get() = rebases

    /**
     * The last 32 rejections and re-baselines, oldest first. Unlike the counters it SURVIVES [resetLedger]:
     * it is evidence for a later bug report, not connection state. Safe to read from another thread.
     */
    fun rejectedFrames(): List<RejectedFrame> = ring

    /** Call once per framed notification, before decoding it; entries are stamped with this index. */
    protected fun noteLedgerFrame() { ledgerFrameIndex++ }

    /** The current frame's ledger index, for a decision that will be reported later. */
    protected val currentLedgerFrame: Long get() = ledgerFrameIndex

    /** Record a frame that a rule held back. [atFrameIndex] defaults to now; pass the arrival index for a late decision. */
    protected fun recordRejection(frameType: Int, rule: PlausibilityRule, raw: ByteArray, reference: String?, witness: String?,
        atFrameIndex: Long = ledgerFrameIndex) {
        rejectedCount++
        push(RejectedFrame(atFrameIndex, frameType, rule, raw.toLedgerHex(), reference, witness, LedgerKind.REJECTED))
    }

    /** Record a confirmed counter re-baseline (the frame that became the new baseline). */
    protected fun recordRebase(frameType: Int, rule: PlausibilityRule, raw: ByteArray, reference: String?, witness: String?,
        atFrameIndex: Long = ledgerFrameIndex) {
        rebases++
        push(RejectedFrame(atFrameIndex, frameType, rule, raw.toLedgerHex(), reference, witness, LedgerKind.REBASED))
    }

    /** Zero the per-connection counters. Subclasses call it from [reset]; the ring stays. */
    protected fun resetLedger() {
        ledgerFrameIndex = 0L
        rejectedCount = 0L
        rebases = 0L
    }

    private fun push(entry: RejectedFrame) { ring = (ring + entry).takeLast(LEDGER_CAPACITY) }

    private fun ByteArray.toLedgerHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

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

    private companion object { const val LEDGER_CAPACITY = 32 }
}
