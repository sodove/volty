package ru.sodovaya.volty.data.bms.vesc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `COMM_BMS_GET_VALUES` (96) decoder tests.
 *
 * Every frame here is assembled **by hand from the field table** in
 * `docs/superpowers/specs/2026-07-24-vehicle-platform/C-multi-controller.md` §4
 * (re-read against the gateway serialiser `vesc_core/src/buffer.c` +
 * `vesc_express/src/bms.c:298-360` and VESC Tool `commands.cpp:709-772`):
 * the raw integers below are the numbers that go on the wire, and the expected
 * physical values were derived on paper from the documented scale. Nothing is
 * round-tripped through an encoder of our own, so a decoder that agrees with
 * these numbers agrees with the wire format, not with itself.
 */
class VescBmsValuesTest {

    // ---------------------------------------------------------------------
    // Byte offsets into [frame] for cells = 2 / sensors = 1, counted by hand
    // from the field table. Used to truncate the full frame at exactly the
    // boundaries the wire format allows.
    //
    //  0        opcode (96)
    //  1..24    v_tot, v_charge, i_in, i_in_ic, ah_cnt, wh_cnt   (6 x i32)
    //  25       cell count (2)
    //  26..29   2 x v_cell (i16)
    //  30..31   2 x is_balancing (u8)
    //  32       sensor count (1)
    //  33..34   1 x temp (i16)
    //  35..42   temp_ic, temp_hum, humidity, temp_cells_highest  (4 x i16)
    //  43..44   soc (i16)
    //  45..46   soh (i16)
    //  47       can_id (u8)
    //  48..63   4 x float32_auto totals
    //  64..65   pressure (i16)
    //  66       data_version (u8)
    //  67..     status, NUL-terminated
    // ---------------------------------------------------------------------
    private val endOfMandatory = 43
    private val endOfSoc = 45
    private val endOfCanId = 48
    private val endOfTotals = 64
    private val endOfPressure = 66
    private val endOfDataVersion = 67

    /**
     * A frame in the pinned field order. Defaults describe a healthy 2-cell /
     * 1-sensor pack mid-ride; every parameter is the **raw wire integer**, so
     * the scale conversion stays the decoder's job and the test's arithmetic.
     */
    private fun frame(
        vTotRaw: Int = 75_500_000,          // /1e6 = 75.5 V
        vChargeRaw: Int = 0,                // /1e6 = 0.0 V  (ANT zeroes this)
        iInRaw: Int = -12_500_000,          // /1e6 = -12.5 A (discharging)
        iInIcRaw: Int = -12_400_000,        // /1e6 = -12.4 A
        ahCntRaw: Int = 18_200,             // /1e3 = 18.2 Ah
        whCntRaw: Int = 1_374_100,          // /1e3 = 1374.1 Wh
        cellsRaw: List<Int> = listOf(4_012, 3_998),  // /1e3 = 4.012 V, 3.998 V
        balRaw: List<Int> = listOf(0, 1),            // cell 1 is balancing
        tempsRaw: List<Int> = listOf(2_350),         // /1e2 = 23.5 C
        tempIcRaw: Int = 3_120,             // /1e2 = 31.2 C
        tempHumRaw: Int = 2_880,            // /1e2 = 28.8 C
        humidityRaw: Int = 0,               // /1e2 = 0.0 %  (ANT never fills it)
        tempMaxCellRaw: Int = 2_410,        // /1e2 = 24.1 C
        socRaw: Int = 875,                  // /1e3 = 0.875 -> 87.5 %
        sohRaw: Int = 990,                  // /1e3 = 0.990
        canIdRaw: Int = 10,
        // float32_auto words, each derived by hand below in
        // [float32_auto_words_decode_to_their_hand_derived_values].
        totalWords: List<Long> = listOf(
            0x4148_0000L,   // ah_cnt_chg_total = 12.5 Ah
            0x4479_C000L,   // wh_cnt_chg_total = 999.0 Wh
            0x41C8_0000L,   // ah_cnt_dis_total = 25.0 Ah
            0x44FA_0000L    // wh_cnt_dis_total = 2000.0 Wh
        ),
        pressureRaw: Int = 1_234,           // scale 1e-1 -> raw x 10 = 12340.0
        dataVersionRaw: Int = 2,
        status: String = "OK",
        opcode: Int = VescBmsValues.OPCODE_BMS_GET_VALUES,
        cellCount: Int = cellsRaw.size,
        sensorCount: Int = tempsRaw.size
    ): ByteArray {
        val o = mutableListOf<Byte>()
        fun u8(v: Int) { o += (v and 0xFF).toByte() }
        fun i16(v: Int) { u8(v shr 8); u8(v) }
        fun i32(v: Int) { u8(v shr 24); u8(v shr 16); u8(v shr 8); u8(v) }
        fun u32(v: Long) {
            u8((v shr 24).toInt()); u8((v shr 16).toInt()); u8((v shr 8).toInt()); u8(v.toInt())
        }

        u8(opcode)
        i32(vTotRaw); i32(vChargeRaw); i32(iInRaw); i32(iInIcRaw); i32(ahCntRaw); i32(whCntRaw)
        u8(cellCount)
        cellsRaw.forEach { i16(it) }
        balRaw.forEach { u8(it) }
        u8(sensorCount)
        tempsRaw.forEach { i16(it) }
        i16(tempIcRaw); i16(tempHumRaw); i16(humidityRaw); i16(tempMaxCellRaw)
        i16(socRaw); i16(sohRaw)
        u8(canIdRaw)
        totalWords.forEach { u32(it) }
        i16(pressureRaw)
        u8(dataVersionRaw)
        status.forEach { u8(it.code) }
        u8(0)                               // status terminator, always on the wire
        return o.toByteArray()
    }

    /** The four bytes of one `float32_auto` word, for the primitive's own tests. */
    private fun word(v: Long): ByteArray = byteArrayOf(
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()
    )

    // =====================================================================
    // Frame shape and offsets
    // =====================================================================

    /**
     * Pins the hand-counted offsets used by every truncation test below. If the
     * builder and the offset table ever disagree, the truncation tests would
     * silently cut at the wrong place and still "pass"; this makes that a
     * failure instead.
     *
     * **This test never calls the decoder** — it is evidence about the test
     * fixture, not about `VescBmsValues`, and should not be counted as decoder
     * coverage. It earns its place by protecting the tests that *are*.
     */
    @Test fun hand_counted_frame_layout_matches_the_field_table() {
        assertEquals(70, frame().size, "1 + 24 + 1 + 4 + 2 + 1 + 2 + 8 + 4 + 1 + 16 + 2 + 1 + 3")
        assertEquals(10, frame()[47].toInt(), "can_id must sit at offset 47")
        assertEquals(2, frame()[66].toInt(), "data_version must sit at offset 66")
    }

    // =====================================================================
    // Full frame
    // =====================================================================

    @Test fun full_frame_decodes_every_field_in_the_pinned_order() {
        val f = assertNotNull(VescBmsValues.decode(frame()))
        assertEquals(75.5f, f.vTot, 0.001f)
        assertEquals(0f, f.vCharge, 0.001f)
        assertEquals(-12.5f, f.iIn, 0.001f)
        assertEquals(-12.4f, f.iInIc, 0.001f)
        assertEquals(18.2f, f.ahCnt, 0.001f)
        assertEquals(1374.1f, f.whCnt, 0.01f)
        assertEquals(2, f.cellVoltages.size)
        assertEquals(4.012f, f.cellVoltages[0], 0.0005f)
        assertEquals(3.998f, f.cellVoltages[1], 0.0005f)
        assertEquals(listOf(false, true), f.balancing)
        assertEquals(1, f.temperatures.size)
        assertEquals(23.5f, f.temperatures[0], 0.001f)
        assertEquals(31.2f, f.tempIcC, 0.001f)
        assertEquals(28.8f, f.tempHumSensorC, 0.001f)
        assertEquals(0f, f.humidityPercent, 0.001f)
        assertEquals(24.1f, f.tempCellsHighestC, 0.001f)
        assertEquals(0.875f, assertNotNull(f.soc), 0.001f)
        assertEquals(0.990f, assertNotNull(f.soh), 0.001f)
        assertEquals(10, f.canId)
        assertFalse(f.noBmsDataYet)
        val t = assertNotNull(f.totals)
        assertEquals(12.5f, t.ahChargedTotal, 0.001f)
        assertEquals(999.0f, t.whChargedTotal, 0.01f)
        assertEquals(25.0f, t.ahDischargedTotal, 0.001f)
        assertEquals(2000.0f, t.whDischargedTotal, 0.01f)
        assertEquals(12340.0f, assertNotNull(f.pressure), 0.01f)
        assertEquals(2, f.dataVersion)
        assertEquals("OK", f.status)
    }

    /**
     * The counts are wire-driven, not hardcoded: a 16-cell / 3-sensor pack must
     * produce 16 cell voltages and 3 temperatures, and every later field must
     * still land correctly — which only happens if the cursor advanced by
     * `3 x cells` and `2 x sensors`, not by the 2/1 of the default frame.
     */
    @Test fun cell_and_sensor_counts_are_read_from_the_wire_not_assumed() {
        val cells = List(16) { 3_500 + it }          // 3.500 .. 3.515 V
        val bal = List(16) { if (it == 7) 1 else 0 }
        val temps = listOf(2_000, 2_100, 2_200)      // 20.0, 21.0, 22.0 C
        val f = assertNotNull(
            VescBmsValues.decode(frame(cellsRaw = cells, balRaw = bal, tempsRaw = temps))
        )
        assertEquals(16, f.cellVoltages.size)
        assertEquals(3.500f, f.cellVoltages.first(), 0.0005f)
        assertEquals(3.515f, f.cellVoltages.last(), 0.0005f)
        assertEquals(listOf(7), f.balancing.indices.filter { f.balancing[it] })
        assertEquals(listOf(20.0f, 21.0f, 22.0f), f.temperatures)
        // Everything after the variable-length block must still be intact.
        assertEquals(10, f.canId)
        assertEquals(12340.0f, assertNotNull(f.pressure), 0.01f)
        assertEquals("OK", f.status)
    }

    // =====================================================================
    // Trap 1: pressure uses scale 1e-1, so decoding MULTIPLIES by ten
    // =====================================================================

    /**
     * `buffer_append_float16(buf, pressure, 1e-1, &ind)` — the only d16 in the
     * frame whose scale is below 1. Decoding is `raw / 1e-1` = `raw x 10`.
     *
     * Raw 1234 must decode to **12340.0**. The natural mistake (treating 1e-1
     * like every other scale and dividing by ten, or multiplying by 0.1) yields
     * 123.4 — two orders of magnitude out and nowhere near the tolerance here.
     */
    @Test fun pressure_multiplies_by_ten_because_its_scale_is_below_one() {
        val f = assertNotNull(VescBmsValues.decode(frame(pressureRaw = 1_234)))
        assertEquals(12340.0f, assertNotNull(f.pressure), 0.01f)
    }

    /**
     * A second, differently-shaped pressure so the assertion above cannot be
     * satisfied by a constant: raw 5 -> 50.0, and a backwards implementation
     * would produce 0.5.
     */
    @Test fun pressure_scale_holds_for_a_small_raw_value_too() {
        val f = assertNotNull(VescBmsValues.decode(frame(pressureRaw = 5)))
        assertEquals(50.0f, assertNotNull(f.pressure), 0.001f)
    }

    // =====================================================================
    // Trap 2: can_id is an int truncated to a byte, initialised to -1
    // =====================================================================

    /**
     * `bms.c:53` initialises `can_id` to `-1`, and `ant_bms.c:901-907` memsets
     * the struct back to that on disconnect. Truncated to one byte it arrives
     * as 0xFF. 255 is not a CAN id — it means the gateway is holding no BMS
     * data at all, so the rest of the frame is the zeroed struct, not a reading.
     */
    @Test fun can_id_ff_is_the_no_data_sentinel_not_a_can_id() {
        val f = assertNotNull(VescBmsValues.decode(frame(canIdRaw = 0xFF)))
        assertNull(f.canId, "0xFF is the -1 sentinel, never a real CAN id")
        assertTrue(f.noBmsDataYet)
    }

    /** 254 is the highest id the gateway will ever probe, and it is real. */
    @Test fun can_id_254_is_a_real_unsigned_id() {
        val f = assertNotNull(VescBmsValues.decode(frame(canIdRaw = 254)))
        assertEquals(254, f.canId)
        assertFalse(f.noBmsDataYet)
    }

    /** Id 0 is a real id, and must not be confused with "absent". */
    @Test fun can_id_zero_is_a_real_id() {
        val f = assertNotNull(VescBmsValues.decode(frame(canIdRaw = 0)))
        assertEquals(0, f.canId)
        assertFalse(f.noBmsDataYet)
    }

    // =====================================================================
    // Trap 3: the tail is gated on remaining bytes; totals are all-or-nothing
    // =====================================================================

    @Test fun frame_truncated_after_can_id_reports_the_entire_tail_absent() {
        val f = assertNotNull(VescBmsValues.decode(frame().copyOf(endOfCanId)))
        assertEquals(10, f.canId)
        assertNull(f.totals)
        assertNull(f.pressure)
        assertNull(f.dataVersion)
        assertNull(f.status)
    }

    @Test fun frame_truncated_before_pressure_still_carries_the_totals() {
        val f = assertNotNull(VescBmsValues.decode(frame().copyOf(endOfTotals)))
        val t = assertNotNull(f.totals)
        assertEquals(12.5f, t.ahChargedTotal, 0.001f)
        assertEquals(999.0f, t.whChargedTotal, 0.01f)
        assertEquals(25.0f, t.ahDischargedTotal, 0.001f)
        assertEquals(2000.0f, t.whDischargedTotal, 0.01f)
        assertNull(f.pressure)
        assertNull(f.dataVersion)
        assertNull(f.status)
    }

    /**
     * Fifteen of the sixteen totals bytes present. The block is all-or-nothing,
     * so `totals` is absent — **and the leftover 15 bytes must not be re-read
     * as the next field**. A decoder that falls through to the `>= 2` pressure
     * gate (as VESC Tool's literal code does) would report
     * `0x4148 x 10 = 167,120` as a pressure reading.
     *
     * Safe in general, not just here: the tail is positional with no presence
     * bitmap, so it is append-only and a conforming frame can only be
     * prefix-truncated — anything carrying `pressure` necessarily carries all
     * 16 totals bytes before it. Stopping can therefore never drop a field a
     * conforming firmware could send.
     */
    @Test fun a_partial_totals_block_yields_no_totals_and_no_pressure() {
        val f = assertNotNull(VescBmsValues.decode(frame().copyOf(endOfCanId + 15)))
        assertNull(f.totals)
        assertNull(f.pressure, "leftover totals bytes are not a pressure field")
        assertNull(f.dataVersion)
        assertNull(f.status)
    }

    @Test fun frame_truncated_before_data_version_keeps_totals_and_pressure() {
        val f = assertNotNull(VescBmsValues.decode(frame().copyOf(endOfPressure)))
        assertNotNull(f.totals)
        assertEquals(12340.0f, assertNotNull(f.pressure), 0.01f)
        assertNull(f.dataVersion)
        assertNull(f.status)
    }

    @Test fun frame_truncated_before_the_status_string_keeps_data_version() {
        val f = assertNotNull(VescBmsValues.decode(frame().copyOf(endOfDataVersion)))
        assertEquals(2, f.dataVersion)
        assertNull(f.status)
    }

    /**
     * VESC Tool gates `soc`, `soh` and `can_id` on remaining size too
     * (`commands.cpp:738-748`), so a frame that stops after
     * `temp_cells_highest` is still valid — it simply carries no SoC.
     */
    @Test fun frame_truncated_before_soc_has_no_soc_soh_or_can_id() {
        val f = assertNotNull(VescBmsValues.decode(frame().copyOf(endOfMandatory)))
        assertEquals(24.1f, f.tempCellsHighestC, 0.001f)
        assertNull(f.soc)
        assertNull(f.soh)
        assertNull(f.canId)
        assertFalse(f.noBmsDataYet, "the can_id byte was absent, not 0xFF")
        assertNull(f.totals)
    }

    /**
     * One byte short of a whole `can_id`: the dangling half of `soh` must not
     * be consumed as a CAN id.
     */
    @Test fun a_dangling_byte_after_soc_is_not_read_as_soh_or_can_id() {
        val f = assertNotNull(VescBmsValues.decode(frame().copyOf(endOfSoc + 1)))
        assertEquals(0.875f, assertNotNull(f.soc), 0.001f)
        assertNull(f.soh, "one byte cannot be a d16")
        assertNull(f.canId, "the leftover soh byte is not a can_id")
    }

    // =====================================================================
    // THE requirement: absent vs measured zero (spec §10.5)
    // =====================================================================

    /**
     * An ANT battery behind the gateway (`ant_bms.c:653-689`) leaves `v_charge`,
     * humidity, pressure, status, all four totals and `data_version` at zero,
     * yet the gateway's serialiser (`bms.c:298-360`) emits them unconditionally.
     * So the tail is **present and zero** here — byte-identical to a genuine
     * measurement of zero, and *not* the same thing as the truncated frames
     * above where the same fields are absent.
     */
    @Test fun ant_shaped_frame_reports_its_zero_tail_as_present_not_absent() {
        val ant = frame(
            vChargeRaw = 0,
            humidityRaw = 0,
            totalWords = listOf(0L, 0L, 0L, 0L),
            pressureRaw = 0,
            dataVersionRaw = 0,
            status = ""
        )
        val f = assertNotNull(VescBmsValues.decode(ant))

        val t = assertNotNull(f.totals, "the totals were on the wire, they are not absent")
        assertEquals(0f, t.ahChargedTotal)
        assertEquals(0f, t.whChargedTotal)
        assertEquals(0f, t.ahDischargedTotal)
        assertEquals(0f, t.whDischargedTotal)
        assertEquals(0f, assertNotNull(f.pressure), "pressure was on the wire, reading 0")
        assertEquals(0, f.dataVersion)
        assertEquals("", assertNotNull(f.status), "an empty status is present, not absent")
    }

    /**
     * The discriminating pair. The ANT frame and the truncated frame carry the
     * *same values* for the tail (all zero / nothing) and differ only in
     * presence. A decoder that defaults the tail to `0.0` passes the ANT test
     * and fails this one; a decoder that nulls anything that decodes to zero
     * passes this one and fails the ANT test.
     */
    @Test fun absent_tail_and_present_zero_tail_are_distinguishable() {
        val presentZero = assertNotNull(
            VescBmsValues.decode(
                frame(totalWords = listOf(0L, 0L, 0L, 0L), pressureRaw = 0, dataVersionRaw = 0)
            )
        )
        val absent = assertNotNull(VescBmsValues.decode(frame().copyOf(endOfCanId)))

        assertNotNull(presentZero.totals)
        assertNull(absent.totals)
        assertNotNull(presentZero.pressure)
        assertNull(absent.pressure)
        assertNotNull(presentZero.dataVersion)
        assertNull(absent.dataVersion)
    }

    /**
     * `v_charge` and `humidity` sit in the **mandatory** part of the frame, so
     * the wire carries no presence bit for them: an ANT battery's explicit zero
     * (`ant_bms.c:657` sets `v_charge = 0.0f`) is byte-identical to a real
     * BMS reporting a genuine 0. The decoder therefore reports the raw value
     * and offers the documented "exactly zero means nothing is reporting"
     * reading separately, so a screen has something safe to bind to.
     *
     * These accessors' `null` means *"the value says nothing is reporting"*,
     * **not** *"the field was absent"* — the opposite of every nullable field
     * on the frame, which is why they are not named `…OrNull`.
     */
    @Test fun mandatory_zero_fields_expose_both_the_raw_value_and_a_safe_reading() {
        val f = assertNotNull(VescBmsValues.decode(frame(vChargeRaw = 0, humidityRaw = 0)))
        assertEquals(0f, f.vCharge)
        assertEquals(0f, f.humidityPercent)
        assertNull(f.vChargeIfReporting, "0 V charger voltage must not surface as a reading")
        assertNull(f.humidityIfSensed, "0 % humidity must not surface as a reading")
    }

    /** A BMS that really does report a charger and a humidity sensor. */
    @Test fun non_zero_charger_voltage_and_humidity_do_surface() {
        val f = assertNotNull(
            VescBmsValues.decode(frame(vChargeRaw = 84_000_000, humidityRaw = 4_150))
        )
        assertEquals(84.0f, assertNotNull(f.vChargeIfReporting), 0.001f)
        assertEquals(41.5f, assertNotNull(f.humidityIfSensed), 0.001f)
    }

    // =====================================================================
    // float32_auto
    // =====================================================================

    /**
     * `buffer_get_float32_auto` (`vesc_core/src/buffer.c:182`, mirrored by
     * VESC Tool's `VByteArray::vbPopFrontDouble32Auto`):
     *
     *   e = (w >> 23) & 0xFF ; sig_i = w & 0x7FFFFF ; neg = w bit 31
     *   sig = sig_i / 2^24 + 0.5 ; value = ldexp(+/-sig, e - 126)
     *
     * Each word below was derived by hand with that formula:
     *  - 0x41480000: e=130, sig_i=0x480000=4194304*1.125 -> sig=0.28125+0.5
     *                =0.78125, exp=4  -> 0.78125 * 16   = 12.5
     *  - 0x4479C000: e=136, sig_i=0x79C000=7979008 -> sig=0.9755859375,
     *                exp=10 -> * 1024 = 999.0
     *  - 0x41C80000: e=131, sig=0.78125, exp=5 -> * 32 = 25.0
     *  - 0x44FA0000: e=137, sig_i=0x7A0000=7995392 -> sig=0.9765625,
     *                exp=11 -> * 2048 = 2000.0
     *
     * A fixed-divisor reading (the trap: `d32(1e3)`) would report 0x41480000 —
     * 1,095,237,632 as a plain int — as 1,095,237.6 instead of 12.5.
     */
    @Test fun float32_auto_words_decode_to_their_hand_derived_values() {
        assertEquals(12.5f, VescReader(word(0x4148_0000L)).f32auto(), 0.0001f)
        assertEquals(999.0f, VescReader(word(0x4479_C000L)).f32auto(), 0.001f)
        assertEquals(25.0f, VescReader(word(0x41C8_0000L)).f32auto(), 0.0001f)
        assertEquals(2000.0f, VescReader(word(0x44FA_0000L)).f32auto(), 0.001f)
    }

    /** Sign lives in bit 31 and does not disturb the exponent field. */
    @Test fun float32_auto_decodes_a_negative_word() {
        assertEquals(-12.5f, VescReader(word(0xC148_0000L)).f32auto(), 0.0001f)
    }

    /** `e == 0 && sig_i == 0` is the encoder's flush-to-zero, not 2^-126. */
    @Test fun float32_auto_all_zero_word_is_exactly_zero() {
        assertEquals(0f, VescReader(word(0L)).f32auto())
    }

    /**
     * `float32_auto` coincides with IEEE-754 binary32 for every value the
     * serialiser can emit, but it is **not** a bit reinterpretation: with
     * `e == 0` and a non-zero significand the firmware still takes the
     * `sig_i / 2^24 + 0.5` branch, giving `sig * 2^-126` (here 2^-127, about
     * 5.88e-39) where `Float.fromBits` would give the subnormal 2^-149
     * (1.4e-45). Pins the algorithm rather than the shortcut.
     */
    @Test fun float32_auto_is_the_firmware_algorithm_not_a_bit_reinterpretation() {
        val v = VescReader(word(0x0000_0001L)).f32auto()
        assertEquals(5.877472e-39f, v, 1e-44f)
        assertNotEquals(Float.fromBits(1), v)
    }

    /** Four words are consumed in order; the cursor must advance 16 bytes. */
    @Test fun float32_auto_advances_the_cursor_by_four_bytes() {
        val r = VescReader(word(0x4148_0000L) + word(0x41C8_0000L))
        assertEquals(12.5f, r.f32auto(), 0.0001f)
        assertEquals(25.0f, r.f32auto(), 0.0001f)
        assertEquals(0, r.remaining())
    }

    // =====================================================================
    // Opcode / malformed frames
    // =====================================================================

    @Test fun empty_payload_is_null() {
        assertNull(VescBmsValues.decode(byteArrayOf()))
    }

    /**
     * A well-formed frame of the right length but the wrong opcode — e.g. a
     * misrouted `COMM_GET_VALUES_SETUP` reply — must be rejected, not decoded
     * into plausible-looking battery numbers.
     */
    @Test fun wrong_opcode_is_null() {
        assertNull(VescBmsValues.decode(frame(opcode = 47)))
    }

    @Test fun frame_truncated_inside_the_mandatory_scalars_is_null() {
        assertNull(VescBmsValues.decode(frame().copyOf(20)))
    }

    @Test fun frame_truncated_inside_the_cell_block_is_null() {
        assertNull(VescBmsValues.decode(frame().copyOf(28)))
    }

    @Test fun frame_truncated_between_temp_ic_and_temp_cells_highest_is_null() {
        assertNull(VescBmsValues.decode(frame().copyOf(40)))
    }

    /**
     * A cell count larger than the frame can hold is corruption, not a short
     * read: it must yield null rather than throwing out of the BLE callback.
     */
    @Test fun a_cell_count_the_frame_cannot_satisfy_is_null() {
        assertNull(VescBmsValues.decode(frame(cellCount = 200)))
    }

    @Test fun a_sensor_count_the_frame_cannot_satisfy_is_null() {
        assertNull(VescBmsValues.decode(frame(sensorCount = 200)))
    }

    /** Zero cells and zero sensors is a legal, if useless, frame. */
    @Test fun a_frame_with_no_cells_and_no_sensors_decodes() {
        val f = assertNotNull(
            VescBmsValues.decode(frame(cellsRaw = emptyList(), balRaw = emptyList(), tempsRaw = emptyList()))
        )
        assertTrue(f.cellVoltages.isEmpty())
        assertTrue(f.balancing.isEmpty())
        assertTrue(f.temperatures.isEmpty())
        assertEquals(10, f.canId)
        assertEquals("OK", f.status)
    }

    // =====================================================================
    // BmsData mapping
    // =====================================================================

    @Test fun bms_data_mapping_carries_voltage_soc_cells_and_temperatures() {
        val d = assertNotNull(VescBmsValues.decode(frame())).toBmsData()
        assertEquals(75.5f, d.voltage, 0.001f)
        assertEquals(87.5f, d.soc, 0.01f, "soc is a 0..1 fraction on the wire, percent in BmsData")
        assertTrue(d.socKnown, "a VESC BMS coulomb-counts; its SoC is never a guess")
        assertEquals(2, d.cellVoltages.size)
        assertEquals(4.012f, d.cellVoltages[0], 0.0005f)
        assertEquals(listOf(23.5f), d.temperatures)
        assertTrue(d.isConnected)
    }

    @Test fun nyxdash_remaining_ah_and_soc_expose_battery_capacity() {
        // nyxdash writes ANT's remaining Ah into ah_cnt and carries the same
        // pack's SoC in the VESC BMS frame.  18.2 Ah at 87.5% means a 20.8 Ah
        // designed pack; this is the capacity the dashboard currently hides.
        val d = assertNotNull(VescBmsValues.decode(frame())).toBmsData()

        assertEquals(18.2f, d.charge, 0.001f)
        assertEquals(20.8f, d.capacity, 0.01f)
    }

    /**
     * `temp_ic` is the BMS chip's own die temperature and `temp_cells_highest`
     * is a derived maximum of temperatures already in the list — neither is a
     * pack thermistor, so neither may join `temperatures` and trip an
     * over-temperature alert. Same rule VescProtocol applies to the ESC
     * temperature (`VescProtocol.kt:115-118`).
     */
    @Test fun bms_data_temperatures_exclude_the_ic_die_and_the_derived_maximum() {
        val d = assertNotNull(
            VescBmsValues.decode(frame(tempsRaw = listOf(2_350), tempIcRaw = 9_900, tempMaxCellRaw = 8_800))
        ).toBmsData()
        assertEquals(listOf(23.5f), d.temperatures)
    }

    /**
     * Sign: `i_in` is **discharge-positive** and `BmsData` is charge-positive,
     * so the frame's current is NEGATED on the way in.
     *
     * These two tests asserted the opposite until the 2026-08-01 field test.
     * The old rationale cited `ant_bms.c:658,685` for "+ = charging", but
     * `:658` is `val->i_in = current` — a verbatim copy of ANT's own reading,
     * whose convention is "+ = discharge", which is precisely why
     * `AntBmsProtocol` negates it. The citation proved the opposite of what it
     * was quoted for, and the rider's charger settled it: a physically charging
     * pack was displayed as discharging.
     *
     * The assertions are named for the physical state, not the wire value, so
     * that a future reader cannot satisfy them by flipping a sign in the test.
     */
    @Test fun a_charging_pack_reads_as_charging() {
        // Discharge-positive wire: a CHARGING pack sends a NEGATIVE i_in.
        val d = assertNotNull(VescBmsValues.decode(frame(iInRaw = -2_500_000))).toBmsData()
        assertEquals(2.5f, d.current, 0.001f)
        assertTrue(d.current > 0f, "BmsData is charge-positive; i_in is not")
        assertTrue(d.power > 0f, "power must not disagree with the sign of its own current")
    }

    @Test fun a_discharging_pack_reads_as_discharging() {
        val d = assertNotNull(VescBmsValues.decode(frame(iInRaw = 12_500_000))).toBmsData()
        assertEquals(-12.5f, d.current, 0.001f)
        assertTrue(d.current < 0f, "a pack under load is discharging")
        assertEquals(75.5f * -12.5f, d.power, 0.5f)
    }

    /** A frame that stops before `soc` must report the SoC as unknown, not 0 %. */
    @Test fun a_frame_without_soc_maps_to_soc_unknown() {
        val d = assertNotNull(VescBmsValues.decode(frame().copyOf(endOfMandatory))).toBmsData()
        assertFalse(d.socKnown)
        assertEquals(0f, d.soc)
        assertEquals(18.2f, d.charge, 0.001f)
        assertEquals(0f, d.capacity, 0.001f)
    }

    /** A genuinely flat pack reports 0 % WITH `socKnown = true`. */
    @Test fun a_flat_pack_reports_zero_percent_as_a_known_soc() {
        val d = assertNotNull(VescBmsValues.decode(frame(socRaw = 0))).toBmsData()
        assertTrue(d.socKnown)
        assertEquals(0f, d.soc)
        assertEquals(18.2f, d.charge, 0.001f)
        assertEquals(0f, d.capacity, 0.001f)
    }

}
