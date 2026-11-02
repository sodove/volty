package ru.sodovaya.volty.data.bms

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VeteranPowerFlowTest {

    /**
     * Retraction: sign agreement is not one Leaperkim-wide rule. The upstream engine in
     * loeuc-core commit ae10acc branches on the original frame format: exact 36-byte legacy
     * Sherman Max frames use agreement-as-drive, while CRC32 frames use agreement-as-braking.
     * See [LeaperKimProtocolEngine.kt](https://github.com/cancelledbit/loeuc-core/blob/ae10acc6e6de20b459928925531f2cbfd10bd56a/loeuc-core/src/commonMain/kotlin/pw/vasilevskiy/loeuc/shared/protocol/LeaperKimProtocolEngine.kt).
     */
    @Test
    fun legacySignAgreementMeansDrawAndDisagreementMeansBrakingInBothDirections() {
        val cases = listOf(
            FlowCase(speedRaw = 394, phaseCurrentRaw = 1614, powerSign = 1),
            FlowCase(speedRaw = -394, phaseCurrentRaw = -1614, powerSign = 1),
            FlowCase(speedRaw = 394, phaseCurrentRaw = -1614, powerSign = -1),
            FlowCase(speedRaw = -394, phaseCurrentRaw = 1614, powerSign = -1),
        )

        cases.forEach { case ->
            val protocol = VeteranProtocol()
            protocol.onNotification(legacyFrame(case.speedRaw, case.phaseCurrentRaw))

            val motion = assertNotNull(protocol.latestMotion(0))
            val expectedBatteryCurrent = case.powerSign * abs(case.phaseCurrentRaw / 10f) * 67.70f / 100f
            assertEquals(39.4f, motion.speedKmh, 0.001f)
            assertEquals(case.phaseCurrentRaw / 10f, motion.motorCurrentA, 0.001f, "raw phase-current sign")
            assertEquals(expectedBatteryCurrent, motion.batteryCurrentA, 0.01f)
            assertEquals(expectedBatteryCurrent * 91.45f, motion.powerW, 0.1f)
            assertTrue(motion.hasBatteryCurrent)
            assertTrue(motion.hasPower)

            val battery = assertNotNull(protocol.latestData())
            assertFalse(battery.hasCurrent, "the controller frame does not report BmsData current")
            assertFalse(battery.hasPower, "the controller frame does not report BmsData power")
        }
    }

    @Test
    fun crcFramesKeepModernConventionEvenWithLegacyVersionBytes() {
        val cases = listOf(
            FlowCase(speedRaw = 394, phaseCurrentRaw = 1614, powerSign = -1),
            FlowCase(speedRaw = -394, phaseCurrentRaw = -1614, powerSign = -1),
            FlowCase(speedRaw = 394, phaseCurrentRaw = -1614, powerSign = 1),
            FlowCase(speedRaw = -394, phaseCurrentRaw = 1614, powerSign = 1),
        )

        cases.forEach { case ->
            val protocol = VeteranProtocol()
            val frame = shortCrcFrame(case.speedRaw, case.phaseCurrentRaw)
            // With CRC_THRESHOLD=38, the shortest CRC frame is 43 bytes on the wire and
            // carries 39 CRC-covered bytes. A CRC frame with a 36-byte stripped body is
            // therefore unreachable; this fixture checks the shortest supported CRC shape.
            assertEquals(43, frame.size)
            assertEquals(39, frame[3].toInt() and 0xFF)
            protocol.onNotification(frame)

            val motion = assertNotNull(protocol.latestMotion(0), "valid short CRC frame must decode")
            val expectedBatteryCurrent = case.powerSign * abs(case.phaseCurrentRaw / 10f) * 67.70f / 100f
            assertEquals(case.phaseCurrentRaw / 10f, motion.motorCurrentA, 0.001f, "raw phase-current sign")
            assertEquals(expectedBatteryCurrent, motion.batteryCurrentA, 0.01f)
            assertEquals(expectedBatteryCurrent * 91.45f, motion.powerW, 0.1f)
            assertTrue(motion.hasPower)
        }
    }

    @Test
    fun zeroSpeedOrZeroPhaseCurrentRetainsPositiveDrawConvention() {
        val zeroSpeed = VeteranProtocol().apply {
            onNotification(legacyFrame(speedRaw = 0, phaseCurrentRaw = 1614))
        }
        val stationary = assertNotNull(zeroSpeed.latestMotion(0))
        assertTrue(stationary.batteryCurrentA > 0f)
        assertTrue(stationary.powerW > 0f)

        val zeroCurrent = VeteranProtocol().apply {
            onNotification(legacyFrame(speedRaw = -394, phaseCurrentRaw = 0))
        }
        val coasting = assertNotNull(zeroCurrent.latestMotion(0))
        assertEquals(0f, coasting.batteryCurrentA)
        assertEquals(0f, coasting.powerW)
    }

    @Test
    fun veteranTelemetrySetupRemainsReadOnly() {
        val protocol = VeteranProtocol()

        assertTrue(protocol.handshakeCommands().isEmpty())
        assertTrue(protocol.pollCommands().isEmpty())
        assertEquals(0L, protocol.pollIntervalMs)
    }

    private data class FlowCase(
        val speedRaw: Int,
        val phaseCurrentRaw: Int,
        val powerSign: Int,
    )

    private fun legacyFrame(speedRaw: Int, phaseCurrentRaw: Int): ByteArray =
        UPSTREAM_LEGACY_FRAME.decodeHex().apply {
            putUInt16Be(offset = 4, value = 9_145)
            putInt16Be(offset = 6, value = speedRaw)
            putInt16Be(offset = 16, value = phaseCurrentRaw)
            putUInt16Be(offset = 34, value = 6_770)
        }

    private fun shortCrcFrame(speedRaw: Int, phaseCurrentRaw: Int): ByteArray {
        val frame = legacyFrame(speedRaw, phaseCurrentRaw).copyOf(43).apply {
            this[3] = 39
        }
        frame.appendCrc(coveredLength = 39)
        return frame
    }

    private fun String.decodeHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.putUInt16Be(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.putInt16Be(offset: Int, value: Int) =
        putUInt16Be(offset, value and 0xFFFF)

    private fun ByteArray.appendCrc(coveredLength: Int) {
        var crc = 0xFFFF_FFFFL
        for (index in 0 until coveredLength) {
            crc = crc xor (this[index].toInt() and 0xFF).toLong()
            repeat(8) {
                crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1
            }
        }
        crc = crc.inv() and 0xFFFF_FFFFL
        this[coveredLength] = (crc ushr 24).toByte()
        this[coveredLength + 1] = (crc ushr 16).toByte()
        this[coveredLength + 2] = (crc ushr 8).toByte()
        this[coveredLength + 3] = crc.toByte()
    }

    private companion object {
        // Real 36-byte Sherman Max capture in loeuc-core ae10acc's
        // LeaperKimProtocolEngineTest. Voltage/speed/current/PWM are set per case above.
        const val UPSTREAM_LEGACY_FRAME =
            "DC5A5C20242F00005D60000142AD008B00320E940E1000000AF0024604530002005D00D1"
    }
}
