package ru.sodovaya.volty.presentation.vehicle.info

import ru.sodovaya.volty.data.bms.BegodeDumpFixture
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.BegodeT4Fixture
import ru.sodovaya.volty.data.bms.DalyBmsProtocol
import ru.sodovaya.volty.data.bms.FarDriverProtocol
import ru.sodovaya.volty.data.bms.InMotionProtocol
import ru.sodovaya.volty.data.bms.KellyProtocol
import ru.sodovaya.volty.data.bms.KingSongProtocol
import ru.sodovaya.volty.data.bms.NinebotLegacyProtocol
import ru.sodovaya.volty.data.bms.NinebotProtocol
import ru.sodovaya.volty.data.bms.VeteranProtocol
import ru.sodovaya.volty.data.bms.JkBmsProtocol
import ru.sodovaya.volty.data.bms.SmartBmsFactsCache
import ru.sodovaya.volty.data.bms.checksumSum
import ru.sodovaya.volty.domain.model.DeviceFactKey
import ru.sodovaya.volty.domain.model.DeviceFact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every unit symbol a protocol's facts can carry must map to an [InfoUnit]: an unmapped one renders
 * as a bare number. The facts come from the real Begode captures plus one synthetic odometer frame
 * (the captures carry no tiltback); VESC facts carry no unit at all.
 */
class FactUnitCoverageTest {
    private fun tiltbackFrame(): ByteArray {
        val p = ByteArray(16)
        p[3] = 1; p[9] = 40 // odometer 1 m, tiltback raw 40 (< 100 means set)
        return byteArrayOf(0x55, 0xAA.toByte()) + p + byteArrayOf(0x04, 24, 0x5A, 0x5A, 0x5A, 0x5A)
    }

    private fun emitted(): List<DeviceFact> {
        val etMax = BegodeProtocol().also { p -> BegodeDumpFixture.chunks().forEach { p.onNotification(it) } }
        val t4 = BegodeProtocol().also { p -> BegodeT4Fixture.notifications.forEach { p.onNotification(it) } }
        val tilt = BegodeProtocol().also { p ->
            BegodeDumpFixture.chunks().forEach { p.onNotification(it) }
            p.onNotification(tiltbackFrame())
        }
        return etMax.deviceFacts() + t4.deviceFacts() + tilt.deviceFacts()
    }

    @Test fun everyEmittedUnitSymbolMapsToAnInfoUnit() {
        val symbols = emitted().mapNotNull { it.unit }.toSet()
        // Precondition: the drivers really reach each symbol, or this test could pass vacuously.
        assertEquals(setOf("km", "km/h", "°C", "%", "V", "A"), symbols)
        for (s in symbols) assertNotNull(factUnit(s), "unit symbol '$s' has no InfoUnit")
        assertTrue(factUnit(null) == null)
    }

    /**
     * The smart-BMS facts (ANT, JK, JBD, Daly) are text and counts: none carries a unit symbol, so
     * none can render as a bare number for want of a mapping. Daly and JK are driven through their
     * real decoders (minimal frames: no capture exists for either); the shared builder behind all
     * four is driven with every input set, which covers ANT and JBD too.
     */
    @Test fun smartBmsFactsCarryNoUnitSymbol() {
        val daly = DalyBmsProtocol().also { p ->
            val f = ByteArray(13)
            f[0] = 0xA5.toByte(); f[1] = 1; f[2] = 0x94.toByte(); f[3] = 8; f[4] = 16; f[5] = 3
            f[12] = checksumSum(f, 0, 12).toByte()
            p.onNotification(f)
        }
        val jk = JkBmsProtocol().also { p ->
            val f = ByteArray(300)
            f[0] = 0x55; f[1] = 0xAA.toByte(); f[2] = 0xEB.toByte(); f[3] = 0x90.toByte(); f[4] = 0x03
            "11.A".encodeToByteArray().copyInto(f, 22)
            "11.26".encodeToByteArray().copyInto(f, 30)
            f[299] = checksumSum(f, 0, 299).toByte()
            p.onNotification(f)
        }
        val all = SmartBmsFactsCache().facts("fw", "hw", 4, 2)
        val facts = daly.deviceFacts() + jk.deviceFacts() + all
        // Precondition: the drivers really emitted every smart-BMS key.
        assertEquals(
            setOf(DeviceFactKey.FIRMWARE, DeviceFactKey.HARDWARE_VERSION, DeviceFactKey.BMS_DECLARED_CELLS, DeviceFactKey.BMS_DECLARED_TEMP_SENSORS),
            facts.map { it.key }.toSet(),
        )
        assertEquals(emptyList(), facts.mapNotNull { it.unit })
    }

    /**
     * The controller facts: Kelly emits none; FarDriver emits the phase C current (A) and the VCU
     * charge (%). Driven with minimal synthetic register frames (CRC-16 as the controller's new frames
     * use it), since FarDriver has no capture; both symbols must map to an [InfoUnit].
     */
    @Test fun controllerFactsCarryMappedUnitSymbols() {
        fun newFrame(id: Int, fill: (ByteArray) -> Unit): ByteArray {
            val body = ByteArray(12).also(fill)
            val head = byteArrayOf(0xAA.toByte(), (0x80 or id).toByte()) + body
            var crc = 0x7F3C
            for (b in head) {
                crc = crc xor (b.toInt() and 0xFF)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
            }
            return head + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
        }
        val farDriver = FarDriverProtocol().also { p ->
            p.onNotification(newFrame(2) { it[8] = 0x04 }) // phase C raw 1024 (frame bytes 9..11, big-endian)
            p.onNotification(newFrame(53) { it[3] = 80 }) // VCU charge byte 80
        }
        val facts = farDriver.deviceFacts() + KellyProtocol().deviceFacts()
        // Precondition: the driver really emitted both keys, or the symbols below could be vacuous.
        assertEquals(setOf(DeviceFactKey.PHASE_C_CURRENT_A, DeviceFactKey.VCU_CHARGE_PERCENT), facts.map { it.key }.toSet())
        val symbols = facts.mapNotNull { it.unit }.toSet()
        assertEquals(setOf("A", "%"), symbols)
        for (s in symbols) assertNotNull(factUnit(s), "unit symbol '$s' has no InfoUnit")
    }

    /** One CRC-32 (IEEE, as the Veteran frame uses it) trailer appended to [len] payload bytes. */
    private fun withCrc32(frame: ByteArray, len: Int) {
        var crc = 0xFFFF_FFFFL
        for (i in 0 until len) {
            crc = crc xor (frame[i].toLong() and 0xFF)
            repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1 }
        }
        crc = crc.inv() and 0xFFFF_FFFFL
        for (k in 0 until 4) frame[len + k] = (crc ushr (24 - 8 * k)).toByte()
    }

    private fun inMotionFrame(data: ByteArray): ByteArray {
        val body = byteArrayOf(0x82.toByte()) + data
        val payload = byteArrayOf(0x11, body.size.toByte()) + body
        return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + payload +
            byteArrayOf(payload.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }.toByte())
    }

    /**
     * The wheel facts (Veteran declared cells, InMotion identity) are text and counts: none carries a
     * unit symbol. KingSong and both Ninebots emit nothing at all (their KDoc says why). Veteran is
     * driven with one minimal synthetic page-1 frame, InMotion with a model, a serial and a firmware
     * page; the empty protocols are driven through their own tests.
     */
    @Test fun wheelFactsCarryNoUnitSymbol() {
        val veteran = VeteranProtocol().also { p ->
            val f = ByteArray(87)
            f[0] = 0xDC.toByte(); f[1] = 0x5A; f[2] = 0x5C; f[3] = 83
            // version 5001 -> major 5, a smart-BMS wheel; page 1 declares 36 series cells
            f[28] = (5001 ushr 8).toByte(); f[29] = 5001.toByte()
            f[46] = 1; f[52] = 36
            withCrc32(f, 83)
            p.onNotification(f)
        }
        val inMotion = InMotionProtocol().also { p ->
            p.onNotification(inMotionFrame(byteArrayOf(1, 2, 6, 1)))
            p.onNotification(inMotionFrame(byteArrayOf(2) + "SN0123456789ABCD".encodeToByteArray() + byteArrayOf(0)))
            p.onNotification(inMotionFrame(ByteArray(24).apply { this[0] = 6; this[5] = 1 }))
        }
        val facts = veteran.deviceFacts() + inMotion.deviceFacts()
        // Precondition: the drivers really emitted every wheel key, or this could pass vacuously.
        assertEquals(
            setOf(DeviceFactKey.BMS_DECLARED_CELLS, DeviceFactKey.MODEL_NAME, DeviceFactKey.SERIAL_NUMBER, DeviceFactKey.FIRMWARE),
            facts.map { it.key }.toSet(),
        )
        assertEquals(emptyList(), facts.mapNotNull { it.unit })
        listOf(KingSongProtocol().deviceFacts(), NinebotProtocol().deviceFacts(), NinebotLegacyProtocol().deviceFacts())
            .forEach { assertEquals(emptyList(), it) }
    }
}
