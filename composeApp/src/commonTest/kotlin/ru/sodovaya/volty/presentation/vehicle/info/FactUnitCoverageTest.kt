package ru.sodovaya.volty.presentation.vehicle.info

import ru.sodovaya.volty.data.bms.BegodeDumpFixture
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.BegodeT4Fixture
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
}
