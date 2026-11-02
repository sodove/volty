package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryObservation
import ru.sodovaya.volty.domain.model.TelemetryOrigin

class VeteranLegacyReassemblyTest {

    @Test
    fun `lost legacy tail does not publish its partial bytes as telemetry`() {
        val protocol = VeteranProtocol()
        val observed = capture(protocol)
        val next = legacyFrame(voltageRaw = 12_525, speedRaw = 100)

        // The old frame's 24-byte prefix is from the audited upstream legacy example.
        // A following speed high byte of zero lets the current shape check accept a
        // fabricated 36-byte frame, so this fixture catches the old framing behavior.
        protocol.onNotification(UPSTREAM_LEGACY_FRAME.copyOfRange(0, 24) + next)

        assertEquals(listOf(10.0), controllerSpeeds(observed),
            "only the recovered next frame may be published; the old prefix is not a sample")
    }

    @Test
    fun `split nested header and length wait for the complete recovered legacy frame`() {
        val protocol = VeteranProtocol()
        val observed = capture(protocol)
        val oldPrefix = UPSTREAM_LEGACY_FRAME.copyOfRange(0, 24)
        val next = legacyFrame(voltageRaw = 12_525, speedRaw = 100)

        protocol.onNotification(oldPrefix)
        protocol.onNotification(next.copyOfRange(0, 1)) // DC
        protocol.onNotification(next.copyOfRange(1, 3)) // 5A 5C
        protocol.onNotification(next.copyOfRange(3, 4)) // 20 (legacy length)
        protocol.onNotification(next.copyOfRange(4, 12)) // enough bytes to fool the old 36-byte parser

        assertEquals(emptyList(), controllerSpeeds(observed),
            "a split nested header must abandon the torn prefix before it becomes a full-sized candidate")

        protocol.onNotification(next.copyOfRange(12, next.size))

        assertEquals(listOf(10.0), controllerSpeeds(observed))
    }

    @Test
    fun `consecutive lost legacy tails recover the last complete frame`() {
        val protocol = VeteranProtocol()
        val observed = capture(protocol)
        val first = legacyFrame(voltageRaw = 10_000, speedRaw = 70)
        val second = legacyFrame(voltageRaw = 11_000, speedRaw = 80)
        val recovered = legacyFrame(voltageRaw = 12_525, speedRaw = 100)

        protocol.onNotification(first.copyOfRange(0, 24))
        protocol.onNotification(second.copyOfRange(0, 24))
        protocol.onNotification(recovered)

        assertEquals(listOf(10.0), controllerSpeeds(observed),
            "neither torn prefix may become a telemetry publication")
    }

    @Test
    fun `two complete adjacent legacy frames are both retained`() {
        val protocol = VeteranProtocol()
        val observed = capture(protocol)
        val first = legacyFrame(voltageRaw = 12_000, speedRaw = 100)
        val second = legacyFrame(voltageRaw = 12_525, speedRaw = 200)

        protocol.onNotification(first + second)

        assertEquals(listOf(10.0, 20.0), controllerSpeeds(observed))
    }

    @Test
    fun `header-like bytes in a CRC frame payload remain part of that frame`() {
        val protocol = VeteranProtocol()
        val observed = capture(protocol)
        val frame = crcFrame(voltageRaw = 12_525, speedRaw = 150).apply {
            this[24] = 0xDC.toByte()
            this[25] = 0x5A
            this[26] = 0x5C
            this[27] = 0x20
            appendCrc(this, length = 71)
        }

        protocol.onNotification(frame)

        assertEquals(listOf(15.0), controllerSpeeds(observed),
            "the nested-header recovery rule applies to legacy framing only")
    }

    @Test
    fun `reset discards a pending legacy prefix before the next connection`() {
        val protocol = VeteranProtocol()
        val observed = capture(protocol)
        val next = legacyFrame(voltageRaw = 12_525, speedRaw = 100)

        protocol.onNotification(UPSTREAM_LEGACY_FRAME.copyOfRange(0, 24))
        protocol.reset()
        protocol.onNotification(next)

        assertEquals(listOf(10.0), controllerSpeeds(observed))
    }

    private fun capture(protocol: VeteranProtocol): MutableList<TelemetryObservation> =
        mutableListOf<TelemetryObservation>().also { protocol.telemetryObserver = it::add }

    private fun controllerSpeeds(observed: List<TelemetryObservation>): List<Double?> =
        observed.asSequence()
            .filter { it.origin == TelemetryOrigin.CONTROLLER }
            .map { it.readings.single { reading -> reading.metric == TelemetryMetric.SPEED }.value }
            .toList()

    private fun legacyFrame(
        voltageRaw: Int,
        speedRaw: Int,
        versionRaw: Int = 5_001,
    ): ByteArray = frame(length = 32, voltageRaw = voltageRaw, speedRaw = speedRaw, versionRaw = versionRaw)

    private fun crcFrame(voltageRaw: Int, speedRaw: Int): ByteArray =
        frame(length = 71, voltageRaw = voltageRaw, speedRaw = speedRaw, versionRaw = 5_001)

    private fun frame(length: Int, voltageRaw: Int, speedRaw: Int, versionRaw: Int): ByteArray =
        ByteArray(length + 4).apply {
            this[0] = 0xDC.toByte()
            this[1] = 0x5A
            this[2] = 0x5C
            this[3] = length.toByte()
            putBe(4, voltageRaw)
            putBe(6, speedRaw)
            putBe(16, 100)
            putBe(18, 2_500)
            putBe(22, 0)
            putBe(24, 0)
            putBe(26, 0)
            this[28] = (versionRaw ushr 8).toByte()
            this[29] = versionRaw.toByte()
            this[30] = (versionRaw ushr 16).toByte()
            if (length >= 32) putBe(34, 6_000)
            if (length > 38) appendCrc(this, length)
        }

    private fun ByteArray.putBe(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun appendCrc(frame: ByteArray, length: Int) {
        var crc = 0xFFFF_FFFFL
        for (i in 0 until length) {
            crc = crc xor (frame[i].toInt() and 0xFF).toLong()
            repeat(8) {
                crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1
            }
        }
        crc = crc.inv() and 0xFFFF_FFFFL
        frame[length] = (crc ushr 24).toByte()
        frame[length + 1] = (crc ushr 16).toByte()
        frame[length + 2] = (crc ushr 8).toByte()
        frame[length + 3] = crc.toByte()
    }

    private companion object {
        // Upstream Leaperkim legacy example: 36-byte, length-0x20, no-CRC frame.
        // On a checksum-less protocol, payload bytes equal to DC 5A 5C 20 are
        // inherently ambiguous; recovery recognizes that exact nested legacy
        // marker before byte 36 and does not claim every payload collision is avoidable.
        val UPSTREAM_LEGACY_FRAME =
            "DC5A5C20242F00005D60000142AD008B00320E940E1000000AF0024604530002005D00D1".hexBytes()

        fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
