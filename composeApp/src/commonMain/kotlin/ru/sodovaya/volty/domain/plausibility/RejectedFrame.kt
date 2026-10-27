package ru.sodovaya.volty.domain.plausibility

/**
 * Which plausibility rule held a frame back. One entry per rule in the plan; later tasks append (R13 pack voltage,
 * the fault-bit debounce, ...). [token] is what a protocol puts in a device fact (stable too). The name is exported in the BLE dump, so it is stable: never rename a constant.
 */
enum class PlausibilityRule(val token: String) {
    /** Begode 0x04 lifetime odometer contradicted by the live frame's power-on counter (or by the counter gate). */
    R1_ODOMETER_WITNESS("odometer_witness"),

    /** Begode 0x01 pack voltage contradicted by the scaled live (0x00) voltage once the branch ratio is certified. */
    R13_PACK_VOLTAGE_WITNESS("pack_voltage_witness"),

    /** A session trip's distance counter (VESC, Veteran, KingSong, Ninebot legacy, InMotion) stepped backwards and was held by the counter gate. */
    TRIP_COUNTER_STEP("trip_counter_step"),
}

/**
 * REJECTED: a frame held back. REBASED: a counter accepted a new baseline. WITNESS_DISTRUSTED: a witness that had
 * proven itself disagreed with a coherent signal for several frames in a row, so its rule stopped judging for the
 * rest of the connection (a note, not a rejection; the frame carried is the one that tipped it over).
 */
enum class LedgerKind { REJECTED, REBASED, WITNESS_DISTRUSTED }

/**
 * One entry of a protocol's rejection ledger: a frame the decoder refused to publish ([LedgerKind.REJECTED]) or the
 * point where a counter accepted a new baseline after confirmation ([LedgerKind.REBASED]).
 *
 * The frames carry no timestamps, so [atFrameIndex] counts framed notifications since the connection began, and
 * [atEpochMillis] is the wall clock at the moment the protocol RECORDED the entry (an injected clock, `Clock.System` by
 * default; a Begode uses the same clock as its PWM-alarm onset). An earlier revision of this comment said "no clock here;
 * the repository stamps wall-clock time when it exports the ring". Nothing ever did, and a ring exported later cannot
 * recover when a frame arrived, so the stamp is taken where the ledger records (final review M4). For a late decision
 * (an odometer frame judged one frame after it arrived) the time is that of the decision, a frame or so after
 * arrival. Null only for entries built without a clock.
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
    val atEpochMillis: Long? = null,
)
