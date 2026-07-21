package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests against a REAL capture from a Begode ET Max (see [BegodeDumpFixture]).
 * Ground truth at capture time: 40S battery as two parallel branches of
 * 2 x 20S, ~147.2 V pack voltage per the 0x01 frames, each branch's 40 cells
 * at 3.70..3.72 V summing to ~148.4 V, section voltages 74.1 / 74.2 V,
 * temperatures 25..28 C, wheel stationary so branch currents are 0.
 */
class BegodeProtocolTest {

    private fun protocolFedWithFixture(): BegodeProtocol {
        val protocol = BegodeProtocol()
        // Feed one notification at a time, exactly as the BLE layer would:
        // at MTU 23 every 24-byte frame straddles two notifications.
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        return protocol
    }

    // --- The no-write rule ---

    @Test
    fun neverWritesToTheWheel() {
        // FFE1 is Begode's COMMAND channel: writes set light, pedal mode,
        // tiltback. A write could reconfigure a wheel under its rider, so the
        // protocol must never emit a single command. Empty lists here are a
        // safety requirement, not a stub.
        val protocol = BegodeProtocol()
        assertTrue(protocol.handshakeCommands().isEmpty(), "handshake must not write to FFE1")
        assertTrue(protocol.pollCommands().isEmpty(), "polling must not write to FFE1")
    }

    @Test
    fun reportsTwoPacks() {
        assertEquals(2, BegodeProtocol().packCount)
    }

    @Test
    fun usesBegodeSerialUuids() {
        val uuids = BegodeProtocol().uuids
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", uuids.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", uuids.notifyCharUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", uuids.writeCharUuid)
    }

    // --- Decoding the real capture ---

    @Test
    fun decodesBothBranchesFromRealCapture() {
        val protocol = protocolFedWithFixture()

        for (packIndex in 0..1) {
            val data = assertNotNull(
                protocol.latestData(packIndex),
                "branch $packIndex must produce data"
            )
            assertTrue(data.isConnected, "branch $packIndex must be connected")

            // Pack voltage from frame 0x01 bytes 6..7 (0.1 V): 0x05C0 = 147.2 V
            // in the final frames of this capture.
            assertTrue(
                data.voltage in 147.0f..147.4f,
                "branch $packIndex pack voltage ${data.voltage} should be ~147.2 V"
            )

            // Wheel was stationary: branch current is exactly 0 in every frame.
            assertEquals(0f, data.current, 0.01f, "branch $packIndex current")
            assertEquals(0f, data.power, 1f, "branch $packIndex power")

            // 40 cells per branch (2 x 20S in series) — the branch's own cells
            // only, never both branches merged (which would be 80).
            assertEquals(40, data.cellVoltages.size, "branch $packIndex cell count")
            data.cellVoltages.forEachIndexed { i, v ->
                assertTrue(
                    v in 3.70f..3.72f,
                    "branch $packIndex cell $i = $v V outside 3.70..3.72"
                )
            }

            // Each branch's cells sum to ~148.4 V (two 20S sections in series).
            val sum = data.cellVoltages.sum()
            assertTrue(
                sum in 148.1f..148.7f,
                "branch $packIndex cell sum $sum should be ~148.4 V"
            )

            // Two temperatures per section, two sections per branch. The
            // capture's ground truth is 25..28 C (t1 = 28 C, t2 = 25..26 C on
            // every bmsnum), so the band pins the decode to the known values —
            // a decoder wrong by even a few degrees must fail here.
            assertEquals(4, data.temperatures.size, "branch $packIndex temp count")
            data.temperatures.forEach { t ->
                assertTrue(t in 25f..28f, "branch $packIndex temp $t outside 25..28")
            }
        }
    }

    @Test
    fun branchesReportTheirOwnCells() {
        val protocol = protocolFedWithFixture()
        val cells0 = assertNotNull(protocol.latestData(0)).cellVoltages
        val cells1 = assertNotNull(protocol.latestData(1)).cellVoltages
        // The branches are physically distinct packs: identical 40-value lists
        // would mean one branch's cells leaked into the other.
        assertTrue(cells0 != cells1, "branch cell lists must be independent")
    }

    @Test
    fun chunkBoundariesDoNotMatter() {
        val perNotification = protocolFedWithFixture()

        // Same bytes, one giant chunk: the parser must not depend on where
        // the BLE layer happened to cut the stream.
        val oneChunk = BegodeProtocol()
        val allBytes = BegodeDumpFixture.chunks()
            .fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        oneChunk.onNotification(allBytes)

        for (packIndex in 0..1) {
            val a = assertNotNull(perNotification.latestData(packIndex))
            val b = assertNotNull(oneChunk.latestData(packIndex))
            assertSameDecodedData(a, b, "pack $packIndex")
        }
    }

    @Test
    fun garbagePrefixDoesNotPreventDecoding() {
        val clean = protocolFedWithFixture()

        val dirty = BegodeProtocol()
        // Garbage containing a deceptive 55 AA that is NOT a frame start: the
        // parser must resync one byte at a time until the real stream begins.
        dirty.onNotification(byteArrayOf(0x00, 0x55, 0xAA.toByte(), 0x13, 0x37, 0x5A, 0x5A))
        BegodeDumpFixture.chunks().forEach { dirty.onNotification(it) }

        for (packIndex in 0..1) {
            val a = assertNotNull(clean.latestData(packIndex))
            val b = assertNotNull(dirty.latestData(packIndex), "pack $packIndex lost after garbage")
            assertSameDecodedData(a, b, "pack $packIndex")
        }
    }

    // --- Boot-time zero telemetry (the wheel zero-pads 0x01 frames at start) ---

    @Test
    fun bootZeroFramesNeverPublishZeroTemperatures() {
        // The capture opens with ~5 s of zero-padded 0x01 frames: 13 all-zero
        // telemetry frames through notification 78, and under a naive decoder
        // the stale 0 C for branch 0 / section 0 survives in published data
        // until the first genuine bmsnum-0 frame completes at notification 98.
        // Walking the WHOLE fixture notification by notification covers that
        // opening window as a prefix (any prefix >= 99 would catch it) and
        // additionally proves zeros never reappear mid-stream.
        val protocol = BegodeProtocol()
        BegodeDumpFixture.chunks().forEachIndexed { i, chunk ->
            protocol.onNotification(chunk)
            for (packIndex in 0..1) {
                protocol.latestData(packIndex)?.temperatures?.forEach { t ->
                    assertTrue(
                        t != 0f,
                        "branch $packIndex published 0 C after notification $i"
                    )
                }
            }
        }
        // Not vacuous: all four real temperatures must have arrived by the end.
        for (packIndex in 0..1) {
            assertEquals(
                4,
                assertNotNull(protocol.latestData(packIndex)).temperatures.size,
                "branch $packIndex temp count after full fixture"
            )
        }
    }

    @Test
    fun bootPlaceholderKeepsPreviousTemperatures() {
        val protocol = BegodeProtocol()
        // A real reading first...
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))
        // ...then a zero-padded boot-style frame for the same section.
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1473, t1 = 0, t2 = 0, sectionVoltageRaw = 0))
        val data = assertNotNull(protocol.latestData(0))
        // Pack voltage is real even in boot frames and must update; the
        // zero-padded temperatures must not clobber the known ones.
        assertEquals(147.3f, data.voltage, 0.01f)
        assertEquals(listOf(28f, 26f), data.temperatures)
    }

    @Test
    fun genuineZeroCelsiusReadingIsAccepted() {
        // A wheel really can sit at 0 C in winter. Unlike a boot placeholder,
        // a genuine cold frame carries a live section voltage (a 20S section
        // never reads 0.0 V), so it must be published as-is.
        val protocol = BegodeProtocol()
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 0, t2 = 0, sectionVoltageRaw = 741))
        val data = assertNotNull(protocol.latestData(0))
        assertEquals(listOf(0f, 0f), data.temperatures, "0 C with live section voltage is real data")
    }

    // --- Cell positions must survive a missing middle packet ---

    @Test
    fun missingMiddleCellPacketDoesNotShiftPositions() {
        val protocol = BegodeProtocol()
        // Telemetry first: a branch publishes nothing before its 0x01 frame.
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741))

        // Each cell gets a value unique to its physical position.
        fun mvOf(cell: Int) = 3000 + (cell / 8) * 100 + (cell % 8)

        // Packets arrive out of order and packet 2 (cells 16..23) is missing.
        for (packet in intArrayOf(3, 0, 4, 1)) {
            protocol.onNotification(cellFrame(type = 0x02, packetIndex = packet, baseMv = 3000 + packet * 100))
        }

        val partial = assertNotNull(protocol.latestData(0)).cellVoltages
        // Only the contiguous run 0..15 may be exposed: a map compacted around
        // the gap would show physical cell 24 at list index 16, and the
        // dashboard renders this list positionally.
        assertEquals(16, partial.size, "list must stop at the first gap")
        partial.forEachIndexed { i, v ->
            assertEquals(mvOf(i) / 1000f, v, 1e-4f, "cell $i misplaced — positions shifted")
        }

        // Once the missing packet lands, the full list appears, each cell in place.
        protocol.onNotification(cellFrame(type = 0x02, packetIndex = 2, baseMv = 3000 + 2 * 100))
        val full = assertNotNull(protocol.latestData(0)).cellVoltages
        assertEquals(40, full.size, "full list once every packet arrived")
        full.forEachIndexed { i, v ->
            assertEquals(mvOf(i) / 1000f, v, 1e-4f, "cell $i after gap filled")
        }
    }

    @Test
    fun noDataBeforeAnyBmsFrame() {
        val protocol = BegodeProtocol()
        assertNull(protocol.latestData(0))
        assertNull(protocol.latestData(1))
    }

    @Test
    fun resetClearsState() {
        val protocol = protocolFedWithFixture()
        assertNotNull(protocol.latestData(0))
        protocol.reset()
        assertNull(protocol.latestData(0))
        assertNull(protocol.latestData(1))

        // And the protocol decodes again from scratch after a reset.
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        val data = assertNotNull(protocol.latestData(0))
        assertEquals(40, data.cellVoltages.size)
    }

    // --- Synthetic frame builders (24 bytes: 55 AA + 16 payload + type + subtype + 5A x4) ---

    private fun frame(type: Int, subtype: Int, payload: ByteArray): ByteArray {
        require(payload.size == 16) { "payload is frame bytes 2..17" }
        return byteArrayOf(0x55, 0xAA.toByte()) + payload +
            byteArrayOf(type.toByte(), subtype.toByte(), 0x5A, 0x5A, 0x5A, 0x5A)
    }

    /** 0x01 telemetry frame: pack voltage at 6..7, temps at 10..13, section voltage at 14..15 (all BE). */
    private fun telemetryFrame(bmsnum: Int, packVoltageRaw: Int, t1: Int, t2: Int, sectionVoltageRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[4] = (packVoltageRaw shr 8).toByte(); p[5] = packVoltageRaw.toByte()
        // Bytes 6..7 of the payload (frame 8..9) are the branch current: 0.
        p[8] = (t1 shr 8).toByte(); p[9] = t1.toByte()
        p[10] = (t2 shr 8).toByte(); p[11] = t2.toByte()
        p[12] = (sectionVoltageRaw shr 8).toByte(); p[13] = sectionVoltageRaw.toByte()
        return frame(0x01, bmsnum, p)
    }

    /** Cell frame (0x02/0x03): 8 cells at frame bytes 2..17, BE millivolts baseMv..baseMv+7. */
    private fun cellFrame(type: Int, packetIndex: Int, baseMv: Int): ByteArray {
        val p = ByteArray(16)
        for (i in 0 until 8) {
            val mv = baseMv + i
            p[i * 2] = (mv shr 8).toByte()
            p[i * 2 + 1] = mv.toByte()
        }
        return frame(type, packetIndex, p)
    }

    private fun assertSameDecodedData(a: BmsData, b: BmsData, label: String) {
        assertEquals(a.voltage, b.voltage, 0.001f, "$label voltage")
        assertEquals(a.current, b.current, 0.001f, "$label current")
        assertEquals(a.cellVoltages, b.cellVoltages, "$label cells")
        assertEquals(a.temperatures, b.temperatures, "$label temps")
    }
}
