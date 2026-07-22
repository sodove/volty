package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Begode without a smart BMS (likely the T4 and older wheels) sends only
 * live 0x00 and odometer 0x04 frames — 0x01/0x02/0x03 never arrive. Before
 * this feature such a wheel never produced a single pack sample and the
 * connection was silently dead.
 *
 * These tests are built from SYNTHETIC frames and the known frame layout:
 * nobody has captured a real dumb-BMS Begode yet (see the multi-pack spec,
 * "Begode без смарт-BMS"). The numbers are anchored to the ET Max capture's
 * live frames (raw 5892 = 58.92 V on the 67.2 V scale for a real ~147 V pack)
 * so the decode path is at least pinned to a real wheel's 0x00 layout.
 *
 * The synthetic pack must NEVER override a smart BMS: the fixture-driven
 * tests in [BegodeProtocolTest] stay authoritative for that path, and the
 * retirement tests here pin the hand-over.
 */
class BegodeNoBmsProtocolTest {

    // --- The synthetic pack of a live-only stream ---

    @Test
    fun aLiveOnlyStreamSynthesisesOnePackWithCurrentAndBoardTemperature() {
        val protocol = BegodeProtocol()
        // A dumb wheel's whole vocabulary: live frames and the odometer.
        protocol.onNotification(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))
        protocol.onNotification(odometerFrame())
        protocol.onNotification(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))

        val data = assertNotNull(protocol.latestData(0), "a live-only wheel must produce pack 0")
        assertTrue(data.isConnected, "the synthetic pack is a live connection")
        // Phase current, 0.01 A units: raw -350 = -3.5 A.
        assertEquals(-3.5f, data.current, 0.001f)
        // MPU temperature: raw / 340 + 36.53 = 27.5035 C for raw -3069.
        assertEquals(1, data.temperatures.size, "exactly the board temperature")
        assertEquals(27.5035f, data.temperatures[0], 0.01f)
        // The live-frame voltage is on the 67.2 V scale and this protocol has
        // no cell count to scale it with — publishing it as pack volts would
        // show ~59 V on a 168 V wheel. Zero means "caller must scale".
        assertEquals(0f, data.voltage, 0f, "unscaled voltage must not be published as pack volts")
        assertEquals(0f, data.power, 0f, "power is voltage-derived and equally unknown")
        assertTrue(data.cellVoltages.isEmpty(), "a dumb wheel reports no cells")
        // Same rationale as the smart path: a wheel streaming telemetry is not
        // cut off, and false would paint alarming red OFF badges.
        assertTrue(data.chargeEnabled)
        assertTrue(data.dischargeEnabled)

        assertNull(protocol.latestData(1), "no evidence of a second branch — no second pack")
        assertTrue(protocol.sections(0).isEmpty(), "no assemblies are known either")
    }

    @Test
    fun theUnscaledLiveVoltageIsExposedForTheCallerToScale() {
        val protocol = BegodeProtocol()
        assertNull(protocol.liveVoltageOn672ScaleV(), "nothing decoded yet")

        protocol.onNotification(liveFrame(voltageRaw = 5892))

        // Raw 5892 = 58.92 V on Begode's 67.2 V reference scale — the exact
        // value the ET Max capture reports for its real ~147 V pack.
        assertEquals(58.92f, assertNotNull(protocol.liveVoltageOn672ScaleV()), 0.005f)
    }

    @Test
    fun theScaleFormulaMatchesTheKnownWheels() {
        // nominal / 67.2 where nominal = cellCount * 4.2 V (WheelLog's
        // getScaledVoltage). 40S ET Max: x2.5 -> 58.92 V reads 147.3 V, the
        // value the capture's 0x01 frames independently report. 24S T4: x1.5.
        assertEquals(147.3f, BegodeProtocol.scaleLiveVoltage(58.92f, cellCount = 40), 0.01f)
        assertEquals(88.38f, BegodeProtocol.scaleLiveVoltage(58.92f, cellCount = 24), 0.01f)
    }

    @Test
    fun aBootZeroLiveFrameSynthesisesNothing() {
        // A wheel booting can zero-pad the live frame. A 0.00 V "pack" with a
        // fabricated 36.53 C temperature (the formula's raw-zero output) is
        // not data — no pack until the voltage field is real.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 0, currentRaw = 0, tempRaw = 0))
        assertNull(protocol.latestData(0), "boot-zero live frames are not evidence")
        assertNull(protocol.liveVoltageOn672ScaleV())
    }

    @Test
    fun eachLiveFrameMintsAFreshInstanceAndSilenceKeepsTheOld() {
        // Same identity contract PackSampleGate relies on for real branches:
        // a new decode is a new instance, no decode re-serves the cached one.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892))
        val first = protocol.latestData(0)
        assertTrue(protocol.latestData(0) === first, "silence must re-serve the same instance")

        protocol.onNotification(liveFrame(voltageRaw = 5890))
        assertTrue(protocol.latestData(0) !== first, "a new live frame must mint a fresh instance")
    }

    // --- The synthetic pack yields to a smart BMS and never returns ---

    @Test
    fun theFirstTelemetryFrameRetiresTheSyntheticPackForGood() {
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))
        assertNotNull(protocol.latestData(0), "precondition: synthetic pack active")

        // A real 0x01 frame: this wheel has a smart BMS after all.
        protocol.onNotification(
            telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741)
        )

        val data = assertNotNull(protocol.latestData(0))
        assertEquals(147.2f, data.voltage, 0.01f, "the real branch's voltage, not the synthetic 0")
        assertEquals(listOf(28f, 26f), data.temperatures, "the BMS temperatures, not the board's")
        assertNull(protocol.liveVoltageOn672ScaleV(), "no caller may scale a retired synthetic pack")

        // Later live frames must not resurrect it.
        protocol.onNotification(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))
        val after = assertNotNull(protocol.latestData(0))
        assertEquals(147.2f, after.voltage, 0.01f, "live frames no longer speak for pack 0")
        assertNull(protocol.liveVoltageOn672ScaleV())
    }

    @Test
    fun aCellFrameAloneAlsoRetiresTheSyntheticPack() {
        // 0x02/0x03 are smart-BMS frames too. A branch is only published once
        // its 0x01 telemetry lands (existing gate), so the honest state here
        // is NO pack — not a synthetic one contradicting the cells in flight.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892))
        assertNotNull(protocol.latestData(0), "precondition: synthetic pack active")

        protocol.onNotification(cellFrame(type = 0x02, packetIndex = 0, baseMv = 3710))

        assertNull(protocol.latestData(0), "cells prove a smart BMS — the synthetic pack must yield")
        assertNull(protocol.liveVoltageOn672ScaleV())

        protocol.onNotification(liveFrame(voltageRaw = 5892))
        assertNull(protocol.latestData(0), "and it must not come back on the next live frame")
    }

    @Test
    fun theRealSmartBmsCaptureEndsWithTheSyntheticPathFullyRetired() {
        // The ET Max fixture opens with a live frame BEFORE its first BMS
        // frame, so the synthetic pack exists briefly — and must be gone,
        // with both real branches standing, once the stream has played out.
        val protocol = BegodeProtocol()
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }

        assertNull(protocol.liveVoltageOn672ScaleV(), "a smart-BMS wheel must not offer a live voltage to scale")
        for (packIndex in 0..1) {
            val data = assertNotNull(protocol.latestData(packIndex))
            assertEquals(40, data.cellVoltages.size, "branch $packIndex is the real decode")
            assertTrue(data.voltage in 148.1f..148.7f, "branch $packIndex voltage is the cell sum")
        }
    }

    @Test
    fun resetRestoresTheSyntheticPath() {
        // After a reconnect the next wheel may be a different one; a protocol
        // stuck in "smart BMS seen" would leave a dumb wheel dataless again.
        val protocol = BegodeProtocol()
        protocol.onNotification(
            telemetryFrame(bmsnum = 0, packVoltageRaw = 1472, t1 = 28, t2 = 26, sectionVoltageRaw = 741)
        )
        protocol.reset()

        protocol.onNotification(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))
        val data = assertNotNull(protocol.latestData(0), "the synthetic path must work after reset")
        assertEquals(-3.5f, data.current, 0.001f)
        assertEquals(58.92f, assertNotNull(protocol.liveVoltageOn672ScaleV()), 0.005f)
    }

    // --- Synthetic frame builders (same layout as BegodeProtocolTest's) ---

    private fun frame(type: Int, subtype: Int, payload: ByteArray): ByteArray {
        require(payload.size == 16) { "payload is frame bytes 2..17" }
        return byteArrayOf(0x55, 0xAA.toByte()) + payload +
            byteArrayOf(type.toByte(), subtype.toByte(), 0x5A, 0x5A, 0x5A, 0x5A)
    }

    /**
     * Live 0x00 frame: voltage (67.2 V scale, 0.01 V units) at frame bytes
     * 2..3, phase current (0.01 A signed) at 10..11, raw MPU temperature at
     * 12..13, all BE. Byte 19 is 24 on the real wheel (meaning unknown).
     */
    private fun liveFrame(voltageRaw: Int, currentRaw: Int = 0, tempRaw: Int = 0): ByteArray {
        val p = ByteArray(16)
        p[0] = (voltageRaw shr 8).toByte(); p[1] = voltageRaw.toByte()
        p[8] = (currentRaw shr 8).toByte(); p[9] = currentRaw.toByte()
        p[10] = (tempRaw shr 8).toByte(); p[11] = tempRaw.toByte()
        return frame(0x00, 24, p)
    }

    /** Odometer 0x04 frame — a dumb wheel's only other frame type. Ignored. */
    private fun odometerFrame(): ByteArray {
        val p = ByteArray(16)
        p[0] = 0x00; p[1] = 0x82.toByte(); p[2] = 0xB2.toByte(); p[3] = 0x5D
        return frame(0x04, 24, p)
    }

    /** 0x01 telemetry frame — layout as in [BegodeProtocolTest]. */
    private fun telemetryFrame(
        bmsnum: Int,
        packVoltageRaw: Int,
        t1: Int,
        t2: Int,
        sectionVoltageRaw: Int,
        currentRaw: Int = 0
    ): ByteArray {
        val p = ByteArray(16)
        p[4] = (packVoltageRaw shr 8).toByte(); p[5] = packVoltageRaw.toByte()
        p[6] = (currentRaw shr 8).toByte(); p[7] = currentRaw.toByte()
        p[8] = (t1 shr 8).toByte(); p[9] = t1.toByte()
        p[10] = (t2 shr 8).toByte(); p[11] = t2.toByte()
        p[12] = (sectionVoltageRaw shr 8).toByte(); p[13] = sectionVoltageRaw.toByte()
        return frame(0x01, bmsnum, p)
    }

    /** Cell frame (0x02/0x03): 8 cells at frame bytes 2..17, BE millivolts. */
    private fun cellFrame(type: Int, packetIndex: Int, baseMv: Int): ByteArray {
        val p = ByteArray(16)
        for (i in 0 until 8) {
            val mv = baseMv + i
            p[i * 2] = (mv shr 8).toByte()
            p[i * 2 + 1] = mv.toByte()
        }
        return frame(type, packetIndex, p)
    }
}
