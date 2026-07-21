package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData

/**
 * Begode / Gotway electric unicycle protocol.
 *
 * BLE: "serial over BLE" — service 0xFFE0, single characteristic 0xFFE1.
 *
 * The wheel streams unprompted; there is no handshake and no polling.
 * FFE1 is also Begode's COMMAND channel (light, pedal mode, tiltback), so
 * this protocol NEVER writes to it — a stray write could reconfigure a wheel
 * under its rider. Empty command lists are the requirement, not an oversight.
 *
 * Frame format (24 bytes): `55 AA` header, 18 payload bytes, frame type at
 * byte 18, `5A 5A 5A 5A` tail at bytes 20..23. There is no checksum; the tail
 * is the only integrity check, and `55 AA` legitimately occurs inside payload,
 * so a failed tail advances the scan by ONE byte, not a whole frame. At MTU 23
 * every 24-byte frame straddles two notifications — the accumulator is load-
 * bearing, not defensive.
 *
 * Frame types (byte 18):
 *   0x00 — live motherboard frame (scaled voltage, phase current, MPU temp)
 *   0x01 — smart-BMS telemetry; byte 19 is bmsnum 0..3 (branch = bmsnum shr 1,
 *          section within the branch = bmsnum and 1)
 *   0x02 / 0x03 — cell voltages of branch 0 / branch 1, 8 cells per frame,
 *          packet index at byte 19
 *   0x04 — total distance (ignored: BmsData has no odometer field)
 *   0x07 — undocumented, ignored (see the multi-pack design spec)
 *
 * The battery is two parallel branches, each two sections in series
 * (2S2P of assemblies); this protocol reports each branch as one pack.
 *
 * Based on: WheelLog GotwayAdapter (GPL-3.0, layout only) and a real capture
 * from a Begode ET Max — see docs/superpowers/specs/2026-07-21-multi-pack-bms-design.md
 * and BegodeDumpFixture.
 */
class BegodeProtocol : BmsProtocol() {

    override val uuids = BmsUuids(
        serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
        notifyCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
        writeCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb"
    )

    /** Never write to FFE1 — it is the wheel's command channel. */
    override fun handshakeCommands(): List<ByteArray> = emptyList()

    /** Never write to FFE1 — the wheel streams on its own. */
    override fun pollCommands(): List<ByteArray> = emptyList()

    override val pollIntervalMs: Long = 0L // Not used — streaming

    /** Two parallel battery branches multiplexed over one BLE link. */
    override val packCount: Int get() = 2

    private val buffer = ByteArrayAccumulator()
    private val branches = Array(2) { BranchState() }

    // Wheel-level telemetry from the live 0x00 frame. Not exposed through
    // BmsData yet: the raw voltage is on Begode's 67.2 V scale and needs a
    // nominal-voltage multiplier this protocol does not know (see the design
    // spec, "Масштабирование напряжения в кадре 0x00") — using it as a pack
    // voltage here would show ~59 V on a 168 V wheel. Pack voltage comes from
    // the 0x01 frame instead. Kept for the upcoming aggregation task.
    private var liveVoltageRaw: Int = 0
    private var phaseCurrentA: Float = 0f
    private var boardTempC: Float = 0f

    /** Per-branch decode state, assembled from 0x01 and 0x02/0x03 frames. */
    private class BranchState {
        /** True once at least one 0x01 frame for this branch was seen. */
        var sawTelemetry = false
        /** Whole-pack voltage as reported in the branch's 0x01 frames (V). */
        var packVoltageV = 0f
        /** Branch current (A), positive = charging. */
        var currentA = 0f
        /** Two temperatures per section, indexed [section * 2 + sensor]. */
        val sectionTemps = arrayOfNulls<Float>(4)
        /** Per-section voltage (V) — parsed for the pack-materialisation task. */
        val sectionVoltageV = FloatArray(2)
        /** Cell number -> voltage (V). Filled 8 cells per 0x02/0x03 frame. */
        val cells = mutableMapOf<Int, Float>()
        var lastData: BmsData? = null

        fun reset() {
            sawTelemetry = false
            packVoltageV = 0f
            currentA = 0f
            sectionTemps.fill(null)
            sectionVoltageV.fill(0f)
            cells.clear()
            lastData = null
        }
    }

    override fun onNotification(data: ByteArray) {
        buffer.append(data)
        tryParseAll()
    }

    override fun latestData(packIndex: Int): BmsData? =
        branches.getOrNull(packIndex)?.lastData

    override fun reset() {
        buffer.reset()
        branches.forEach { it.reset() }
        liveVoltageRaw = 0
        phaseCurrentA = 0f
        boardTempC = 0f
    }

    // --- Protocol implementation ---

    private fun tryParseAll() {
        while (true) {
            val buf = buffer.toByteArray()

            val startIdx = findHeader(buf)
            if (startIdx < 0) {
                // No header — keep the last byte in case a 55 AA pair is split
                // across notifications.
                if (buf.size > 1) buffer.trimLeading(buf.size - 1)
                return
            }
            if (startIdx > 0) buffer.trimLeading(startIdx)

            val current = buffer.toByteArray()
            if (current.size < FRAME_SIZE) return // Need more data

            if (!hasTail(current)) {
                // 55 AA occurs inside payload too; a bad tail means this was a
                // false header. Advance ONE byte — skipping a whole frame here
                // would eat the real header that follows.
                buffer.trimLeading(1)
                continue
            }

            parseFrame(current)
            buffer.trimLeading(FRAME_SIZE)
        }
    }

    private fun findHeader(data: ByteArray): Int {
        for (i in 0..data.size - 2) {
            if ((data[i].toInt() and 0xFF) == 0x55 &&
                (data[i + 1].toInt() and 0xFF) == 0xAA
            ) return i
        }
        return -1
    }

    private fun hasTail(frame: ByteArray): Boolean {
        for (i in TAIL_OFFSET until FRAME_SIZE) {
            if ((frame[i].toInt() and 0xFF) != 0x5A) return false
        }
        return true
    }

    private fun parseFrame(frame: ByteArray) {
        when (frame.u8(18)) {
            0x00 -> parseLiveFrame(frame)
            0x01 -> parseBmsTelemetry(frame)
            0x02 -> parseCells(frame, branch = 0)
            0x03 -> parseCells(frame, branch = 1)
            // 0x04: total distance (u32 BE at 2..5, metres). BmsData has no
            // odometer field, so it is dropped for now.
            // 0x07: undocumented; WheelLog does not decode it either. Ignored
            // deliberately — see the design spec.
        }
    }

    /**
     * Live motherboard frame. Bytes 2..3 BE: voltage on the 67.2 V scale (NOT
     * pack volts — see [liveVoltageRaw]); bytes 10..11 signed BE: phase current
     * in 0.01 A; bytes 12..13 signed BE: raw MPU6050 die temperature, converted
     * with WheelLog's `raw / 340 + 36.53` formula. Speed/trip/PWM are ignored
     * in this task.
     */
    private fun parseLiveFrame(frame: ByteArray) {
        liveVoltageRaw = frame.u16BE(2)
        phaseCurrentA = frame.i16BE(10) * 0.01f
        boardTempC = frame.i16BE(12) / 340f + 36.53f
    }

    /**
     * Smart-BMS telemetry (0x01). Byte 19 is bmsnum 0..3: bit 1 selects the
     * branch, bit 0 the section within it — confirmed against the ET Max
     * capture (semiVoltage 74.1 V at even bmsnum, 74.2 V at odd; both branches
     * report the same 147.2 V pack voltage because they are parallel).
     */
    private fun parseBmsTelemetry(frame: ByteArray) {
        val bmsnum = frame.u8(19)
        if (bmsnum > 3) return
        val branch = branches[bmsnum shr 1]
        val section = bmsnum and 1

        branch.sawTelemetry = true
        branch.packVoltageV = frame.u16BE(6) * 0.1f
        branch.currentA = frame.i16BE(8) * 0.1f

        // Two temperature sensors per section, degrees Celsius directly.
        val t1 = frame.i16BE(10)
        val t2 = frame.i16BE(12)
        val sectionVoltageRaw = frame.i16BE(14)

        // A booting BMS zero-pads the telemetry payload: both temperatures AND
        // the section voltage read 0 while the pack voltage is already real
        // (the fixture opens with ~5 s of such frames). A genuine cold reading
        // cannot look like this — a live 20S section never sits at 0.0 V — so
        // the discriminator is the all-zero payload, not the temperature value:
        // 0 C with a non-zero section voltage is accepted as real winter data.
        // On a boot frame keep the previous known values (or none) instead of
        // publishing zeros that would feed the dashboard and alert thresholds.
        val isBootPlaceholder = t1 == 0 && t2 == 0 && sectionVoltageRaw == 0
        if (!isBootPlaceholder) {
            // The sanity range guards against garbage spikes.
            if (t1 in TEMP_SANITY) branch.sectionTemps[section * 2] = t1.toFloat()
            if (t2 in TEMP_SANITY) branch.sectionTemps[section * 2 + 1] = t2.toFloat()
            branch.sectionVoltageV[section] = sectionVoltageRaw * 0.1f
        }

        rebuild(branch)
    }

    /**
     * Cell voltages (0x02 = branch 0, 0x03 = branch 1). Byte 19 is the packet
     * index; each frame carries 8 cells at bytes 2..17, big-endian millivolts.
     * Cell number = packetIndex * 8 + i.
     */
    private fun parseCells(frame: ByteArray, branch: Int) {
        val state = branches[branch]
        val packetIndex = frame.u8(19)
        for (i in 0 until 8) {
            val mv = frame.u16BE(2 + i * 2)
            // Zero slots (BMS still booting, or fewer cells than the packet
            // grid) are skipped rather than stored.
            if (mv in 1..5000) {
                state.cells[packetIndex * 8 + i] = mv / 1000f
            }
        }
        rebuild(state)
    }

    /**
     * Rebuild the branch's [BmsData] from accumulated state. Gated on the 0x01
     * frame: without it there is no voltage, and a wheel without a smart BMS
     * never sends one — such a wheel intentionally yields null here (the
     * fallback design is still an open question, see the spec).
     */
    private fun rebuild(branch: BranchState) {
        if (!branch.sawTelemetry) return
        val voltage = branch.packVoltageV
        val current = branch.currentA
        branch.lastData = BmsData(
            voltage = voltage,
            current = current,
            power = voltage * current,
            cellVoltages = contiguousCells(branch.cells),
            temperatures = branch.sectionTemps.filterNotNull(),
            isConnected = true
        )
    }

    /**
     * Cells from physical cell 0 up to the first gap, so a list index always
     * equals the physical cell number. Cell packets arrive out of order and a
     * missing middle packet must not compact the map around the gap — that
     * would show physical cell 32 at list index 16, and the dashboard renders
     * this list positionally. A shorter but honest list beats a full but
     * shuffled one; the tail appears as soon as the missing packet lands.
     */
    private fun contiguousCells(cells: Map<Int, Float>): List<Float> {
        val run = ArrayList<Float>(cells.size)
        while (true) {
            run.add(cells[run.size] ?: return run)
        }
    }

    companion object {
        private const val FRAME_SIZE = 24
        private const val TAIL_OFFSET = 20

        /** Plausible battery temperature range, degrees Celsius. */
        private val TEMP_SANITY = -39..150
    }
}
