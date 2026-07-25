package ru.sodovaya.volty.data.bms.vesc

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType

/**
 * Lifetime charge/discharge counters. They arrive as one **all-or-nothing**
 * 16-byte block of four `float32_auto` words, so either all four are on the
 * wire or none are — which is why they live in their own type rather than as
 * four independently-nullable fields.
 *
 * A gateway hosting an ANT battery leaves every one of them at zero
 * (spec §10.5): present, and zero, and meaningless. See [VescBmsFrame.totals].
 */
data class VescBmsTotals(
    val ahChargedTotal: Float,
    val whChargedTotal: Float,
    val ahDischargedTotal: Float,
    val whDischargedTotal: Float
)

/**
 * One decoded `COMM_BMS_GET_VALUES` reply.
 *
 * ### Absent is not zero
 * The frame has a fixed head and an **optional tail**, and the tail is gated on
 * how many bytes are left rather than on any presence flag. Every tail field is
 * therefore nullable and `null` means *"these bytes were not on the wire"* —
 * never *"the BMS measured zero"*. That distinction is the whole point of this
 * type: a gateway fronting an ANT battery emits the tail **and fills it with
 * zeros**, so `totals == null` and `totals != null && ahChargedTotal == 0f` are
 * genuinely different states and a consumer must treat them differently.
 *
 * ### Where the wire cannot help
 * [vCharge] and [humidityPercent] sit in the *mandatory* head, so a frame
 * always carries them and there is no presence bit to read. An ANT battery
 * zeroes both (`ant_bms.c:657` sets `v_charge = 0.0f`; nothing ever writes
 * `hum`), which is byte-identical to a BMS genuinely reporting 0. **This
 * decoder cannot distinguish those two cases and does not pretend to** — it
 * reports the raw value and offers [vChargeOrNull] / [humidityPercentOrNull]
 * for callers that need a value safe to display.
 *
 * Pinned against the gateway serialiser (`vesc_express/src/bms.c:298-360`) and
 * VESC Tool's parser (`commands.cpp:709-772`); see
 * `docs/superpowers/specs/2026-07-24-vehicle-platform/C-multi-controller.md`
 * §4, §10.4 and §10.5.
 */
data class VescBmsFrame(
    /** Pack voltage, V. */
    val vTot: Float,
    /**
     * Charger voltage, V. **Zero does not mean "no charger" reliably** — an ANT
     * battery behind the gateway hardcodes it to 0. Prefer [vChargeOrNull].
     */
    val vCharge: Float,
    /** Pack current, A. **Positive means charging** (spec §10.4). */
    val iIn: Float,
    /** Pack current as measured by the BMS IC, A. Same sign convention as [iIn]. */
    val iInIc: Float,
    /**
     * The BMS amp-hour counter, Ah. **Meaning is firmware-dependent**: a stock
     * VESC BMS reports a cumulative, resettable coulomb count, whereas the
     * user's head unit overwrites it with the pack's *remaining* capacity
     * (`ant_bms.c:663`, `val->ah_cnt = remaining_ah`). Deliberately **not**
     * mapped into [BmsData.charge] by [toBmsData] for that reason.
     */
    val ahCnt: Float,
    /** The BMS watt-hour counter, Wh. Same firmware-dependent meaning as [ahCnt]. */
    val whCnt: Float,
    /** Per-cell voltages, V, in wire order. Length is the frame's own cell count. */
    val cellVoltages: List<Float>,
    /** Per-cell balancing flags, parallel to [cellVoltages]. */
    val balancing: List<Boolean>,
    /** Per-sensor pack temperatures, °C, in wire order. */
    val temperatures: List<Float>,
    /** The BMS IC's own die temperature, °C. Not a pack thermistor. */
    val tempIcC: Float,
    /** Temperature at the humidity sensor, °C. */
    val tempHumSensorC: Float,
    /**
     * Relative humidity, %. **Zero does not mean "dry"** — it is what a BMS
     * with no humidity sensor, and every ANT battery, reports. See
     * [humidityPercentOrNull].
     */
    val humidityPercent: Float,
    /** Highest cell temperature, °C — derived by the BMS from [temperatures]. */
    val tempCellsHighestC: Float,
    /** State of charge as a 0..1 fraction, or null if the frame stopped short. */
    val soc: Float?,
    /** State of health as a 0..1 fraction, or null if the frame stopped short. */
    val soh: Float?,
    /**
     * The BMS's CAN id, or null when there is none to report — either the byte
     * was not on the wire, or it was the firmware's 0xFF sentinel (see
     * [noBmsDataYet]). 255 is never a real id.
     */
    val canId: Int?,
    /**
     * True when the `can_id` byte was present and read 0xFF. `bms.c:53`
     * initialises the field to `-1` and `ant_bms.c:901-907` memsets the struct
     * back to that when the battery disconnects, so this flags **the whole
     * frame as the zeroed default rather than a reading** — every other field
     * in it is 0. A consumer should drop such a sample, not display it.
     */
    val noBmsDataYet: Boolean,
    /** Lifetime counters, or null when the 16-byte block was not on the wire. */
    val totals: VescBmsTotals?,
    /**
     * Pressure, in the BMS's own units, or null when not on the wire. Decoded
     * with scale `1e-1`, i.e. `raw × 10` — the only field in the frame whose
     * scale is below one. Zero on every ANT-backed gateway.
     */
    val pressure: Float?,
    /** BMS data-format version, or null when not on the wire. Zero on ANT. */
    val dataVersion: Int?,
    /**
     * BMS status string, or null when not on the wire. An ANT-backed gateway
     * sends the block but leaves it empty, so `""` (present, empty) and `null`
     * (absent) are different answers.
     */
    val status: String?
) {
    /**
     * [vCharge] unless it is exactly zero. Zero is what an ANT battery, and any
     * BMS with no charger attached, reports — so it is never a charger voltage
     * worth showing. This *also* swallows the (physically uninteresting) case
     * of a charger genuinely sitting at 0.000000 V; that trade is deliberate,
     * because the alternative is presenting "0.0 V" as a live measurement.
     */
    val vChargeOrNull: Float? get() = vCharge.takeIf { it != 0f }

    /**
     * [humidityPercent] unless it is exactly zero, which is what a BMS with no
     * humidity sensor — and every ANT battery — reports. Same deliberate trade
     * as [vChargeOrNull]: a true 0 % RH is not physically reachable indoors,
     * so nothing real is lost.
     */
    val humidityPercentOrNull: Float? get() = humidityPercent.takeIf { it != 0f }

    /**
     * Project the frame onto the shared battery model.
     *
     * Only what `BmsData` can carry honestly makes the trip:
     * - `voltage` = `v_tot`; `current` = `i_in` **unflipped** — both conventions
     *   are "+ = charging" (spec §10.4, `ant_bms.c:658`), unlike the *controller*
     *   current `VescProtocol.synthesiseBattery` has to negate.
     * - `soc` is a 0..1 fraction on the wire and a percentage in `BmsData`.
     *   `socKnown` follows [soc]'s presence: a VESC BMS coulomb-counts, so a
     *   SoC it reports is real — including a genuine 0 % — but a frame that
     *   stopped before the field has no SoC at all and must not read as empty.
     * - `temperatures` is the pack sensor list only. [tempIcC] is the BMS chip's
     *   die and [tempCellsHighestC] is a maximum of values already in the list;
     *   neither is a pack thermistor and neither may trip an over-temperature
     *   alert (same rule as `VescProtocol.kt:115-118` for the ESC temperature).
     * - `charge`/`capacity` are left unset: see [ahCnt] — the counter means
     *   different things on stock VESC and on the user's head unit, and a
     *   cumulative count rendered as "remaining Ah" would be badly wrong.
     * - `chargeEnabled`/`dischargeEnabled` are true because this frame carries
     *   no MOSFET state at all (`is_charge_allowed` exists in `bms_values` but
     *   is never serialised), and false would render as a fake "output off" —
     *   the same choice `VescProtocol.kt:121-122` makes.
     *
     * [balancing], [noBmsDataYet] and the whole optional tail have no home in
     * `BmsData`; a caller that needs them must read them off the frame.
     */
    fun toBmsData(): BmsData = BmsData(
        voltage = vTot,
        current = iIn,
        power = vTot * iIn,
        soc = (soc ?: 0f) * 100f,
        socKnown = soc != null,
        cellVoltages = cellVoltages,
        temperatures = temperatures,
        chargeEnabled = true,
        dischargeEnabled = true,
        isConnected = true
    )
}

/**
 * Decoder for `COMM_BMS_GET_VALUES` (96) — the battery hosted by a VESC Express
 * head unit acting as a CAN gateway.
 *
 * On the user's vehicle this is how his **ANT** pack is read while riding: the
 * head unit owns the ANT's BLE link, translates it into VESC's `bms_values`
 * (`vesc_express/src/ant_bms.c:653-689`) and answers this opcode. Volty does
 * not re-parse ANT here — that is `AntBmsProtocol`, the other alias path used
 * while parked (spec §5).
 *
 * Pure codec, matching the conventions of its siblings [VescValues] and
 * [VescCan]: it takes a bare VESC *payload* (outer framing belongs to
 * [VescPacket]), validates the opcode, and answers `null` for anything it
 * cannot read rather than throwing — a truncated or misrouted notification is
 * an ordinary BLE event.
 */
object VescBmsValues {

    const val OPCODE_BMS_GET_VALUES: Int = 96

    /** The [BmsType] a frame from this decoder belongs to. */
    val BMS_TYPE: BmsType = BmsType.VESC_BMS

    /** `can_id` is an `int` truncated to a byte and initialised to -1. */
    private const val CAN_ID_NO_DATA = 0xFF

    /**
     * Scale of the `pressure` d16 — **below one on purpose**
     * (`buffer_append_float16(buf, pressure, 1e-1, &ind)`). Decoding divides by
     * the scale, so this one field multiplies by ten while every sibling d16
     * divides. Spec §10.4, trap 1.
     */
    private const val PRESSURE_SCALE = 1e-1f

    private const val U8_BYTES = 1
    private const val D16_BYTES = 2
    private const val D32_BYTES = 4
    private const val F32AUTO_BYTES = 4

    /**
     * Bytes read after the opcode before the cell count is known:
     * v_tot + v_charge + i_in + i_in_ic + ah_cnt + wh_cnt (6 × d32) plus the
     * cell-count byte = 25.
     */
    private const val SCALARS_BYTES = 6 * D32_BYTES + U8_BYTES

    /**
     * Bytes read after the per-sensor temperatures, still mandatory:
     * temp_ic + temp_hum + humidity + temp_cells_highest = 8.
     */
    private const val CLIMATE_BYTES = 4 * D16_BYTES

    /** The lifetime counters block, present only in full: 4 × float32_auto = 16. */
    private const val TOTALS_BYTES = 4 * F32AUTO_BYTES

    /**
     * Decodes a `COMM_BMS_GET_VALUES` payload, or returns null when [payload]
     * does not begin with [OPCODE_BMS_GET_VALUES] or is too short to carry the
     * mandatory head (including a cell/sensor count larger than the frame can
     * satisfy, which is corruption rather than a short read).
     *
     * The **optional tail** is a fixed sequence of all-or-nothing blocks —
     * `soc`, `soh`, `can_id`, the 16-byte totals, `pressure`, `data_version`,
     * `status` — each taken only if it fits in what is left, exactly the gating
     * VESC Tool applies (`commands.cpp:738-770`). Reading stops at the first
     * block that does not fit: because the order is fixed, bytes too few for a
     * block cannot belong to a later one. (VESC Tool's literal code would fall
     * through and read, say, `pressure` out of a half-delivered totals block;
     * that only differs on frames no firmware emits, and silently inventing a
     * plausible reading out of leftover bytes is exactly what this decoder
     * exists to avoid.)
     */
    fun decode(payload: ByteArray): VescBmsFrame? {
        val r = VescReader(payload)
        if (!r.has(1) || r.u8() != OPCODE_BMS_GET_VALUES) return null

        if (!r.has(SCALARS_BYTES)) return null
        val vTot = r.d32(1e6f)
        val vCharge = r.d32(1e6f)
        val iIn = r.d32(1e6f)
        val iInIc = r.d32(1e6f)
        val ahCnt = r.d32(1e3f)
        val whCnt = r.d32(1e3f)

        val cells = r.u8()
        // cells × v_cell (d16) + cells × is_balancing (u8) + the sensor count.
        if (!r.has(cells * (D16_BYTES + U8_BYTES) + U8_BYTES)) return null
        val cellVoltages = List(cells) { r.d16(1e3f) }
        val balancing = List(cells) { r.u8() != 0 }

        val sensors = r.u8()
        if (!r.has(sensors * D16_BYTES + CLIMATE_BYTES)) return null
        val temperatures = List(sensors) { r.d16(1e2f) }
        val tempIcC = r.d16(1e2f)
        val tempHumSensorC = r.d16(1e2f)
        val humidityPercent = r.d16(1e2f)
        val tempCellsHighestC = r.d16(1e2f)

        // ---- optional tail: fixed order, stop at the first block that misses.
        var soc: Float? = null
        var soh: Float? = null
        var canIdRaw: Int? = null
        var totals: VescBmsTotals? = null
        var pressure: Float? = null
        var dataVersion: Int? = null
        var status: String? = null
        run {
            if (!r.has(D16_BYTES)) return@run
            soc = r.d16(1e3f)
            if (!r.has(D16_BYTES)) return@run
            soh = r.d16(1e3f)
            if (!r.has(U8_BYTES)) return@run
            canIdRaw = r.u8()
            if (!r.has(TOTALS_BYTES)) return@run
            totals = VescBmsTotals(r.f32auto(), r.f32auto(), r.f32auto(), r.f32auto())
            if (!r.has(D16_BYTES)) return@run
            pressure = r.d16(PRESSURE_SCALE)
            if (!r.has(U8_BYTES)) return@run
            dataVersion = r.u8()
            if (!r.has(U8_BYTES)) return@run
            status = r.zstring()
        }

        return VescBmsFrame(
            vTot = vTot,
            vCharge = vCharge,
            iIn = iIn,
            iInIc = iInIc,
            ahCnt = ahCnt,
            whCnt = whCnt,
            cellVoltages = cellVoltages,
            balancing = balancing,
            temperatures = temperatures,
            tempIcC = tempIcC,
            tempHumSensorC = tempHumSensorC,
            humidityPercent = humidityPercent,
            tempCellsHighestC = tempCellsHighestC,
            soc = soc,
            soh = soh,
            canId = canIdRaw?.takeIf { it != CAN_ID_NO_DATA },
            noBmsDataYet = canIdRaw == CAN_ID_NO_DATA,
            totals = totals,
            pressure = pressure,
            dataVersion = dataVersion,
            status = status
        )
    }
}
