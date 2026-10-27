package ru.sodovaya.volty.domain.plausibility

/**
 * Which plausibility rule held a frame back. One entry per rule in the plan; later tasks append (R13 pack voltage,
 * the fault-bit debounce, ...). The name is exported in the BLE dump, so it is stable: never rename a constant.
 */
enum class PlausibilityRule {
    /** Begode 0x04 lifetime odometer contradicted by the live frame's power-on counter (or by the counter gate). */
    R1_ODOMETER_WITNESS,
}

enum class LedgerKind { REJECTED, REBASED }

/**
 * One entry of a protocol's rejection ledger: a frame the decoder refused to publish ([LedgerKind.REJECTED]) or the
 * point where a counter accepted a new baseline after confirmation ([LedgerKind.REBASED]).
 *
 * No clock here: a decoder has none and the frames carry no timestamps. [atFrameIndex] counts framed notifications
 * since the connection began; the repository stamps wall-clock time when it exports the ring.
 *
 * [reference] is what the frame was judged against and [witness] what contradicted it, both as short human text
 * ("2816164 m", "+7 m"); either is null when the rule has no such thing.
 */
data class RejectedFrame(
    val atFrameIndex: Long,
    val frameType: Int,
    val rule: PlausibilityRule,
    val rawHex: String,
    val reference: String?,
    val witness: String?,
    val kind: LedgerKind = LedgerKind.REJECTED,
)
