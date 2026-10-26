package ru.sodovaya.volty.presentation.vehicle.info

import ru.sodovaya.volty.data.bms.BegodeDumpFixture
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.BegodeT4Fixture
import ru.sodovaya.volty.data.bms.DalyBmsProtocol
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
}
