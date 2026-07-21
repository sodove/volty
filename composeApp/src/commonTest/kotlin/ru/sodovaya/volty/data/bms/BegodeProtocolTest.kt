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

            // Two temperatures per section, two sections per branch.
            assertEquals(4, data.temperatures.size, "branch $packIndex temp count")
            data.temperatures.forEach { t ->
                assertTrue(t in 20f..40f, "branch $packIndex temp $t implausible")
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

    private fun assertSameDecodedData(a: BmsData, b: BmsData, label: String) {
        assertEquals(a.voltage, b.voltage, 0.001f, "$label voltage")
        assertEquals(a.current, b.current, 0.001f, "$label current")
        assertEquals(a.cellVoltages, b.cellVoltages, "$label cells")
        assertEquals(a.temperatures, b.temperatures, "$label temps")
    }
}
