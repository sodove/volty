package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BmsTypeDetectorTest {

    private val SHARED_FFE0_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private val JBD_SERVICE = "0000ff00-0000-1000-8000-00805f9b34fb"
    private val DALY_SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb"

    @Test
    fun `detects JK by name prefix`() {
        assertEquals(BmsType.JK_BMS, BmsTypeDetector.detect(name = "JK_B2A24S20P", serviceUuids = emptyList()))
        assertEquals(BmsType.JK_BMS, BmsTypeDetector.detect(name = "JK-XYZ", serviceUuids = emptyList()))
    }

    @Test
    fun `detects JBD by name prefix`() {
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(name = "xiaoxiang-001", serviceUuids = emptyList()))
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(name = "JBD-1", serviceUuids = emptyList()))
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(name = "SP04S001", serviceUuids = emptyList()))
    }

    @Test
    fun `detects ANT by name prefix`() {
        assertEquals(BmsType.ANT_BMS, BmsTypeDetector.detect(name = "ANT-201", serviceUuids = emptyList()))
    }

    @Test
    fun `detects Daly by name prefix`() {
        assertEquals(BmsType.DALY_BMS, BmsTypeDetector.detect(name = "DL-32E-24S", serviceUuids = emptyList()))
        assertEquals(BmsType.DALY_BMS, BmsTypeDetector.detect(name = "Daly-xxx", serviceUuids = emptyList()))
    }

    @Test
    fun `detects JBD by unique service UUID when name does not match`() {
        // Stock BMS on the Syccyba Goliath advertises a "DB…" name that no
        // prefix matches — fall back to the JBD-unique 0xFF00 service UUID.
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(name = "DB12345", serviceUuids = listOf(JBD_SERVICE)))
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(name = null, serviceUuids = listOf(JBD_SERVICE)))
    }

    @Test
    fun `does not detect a BMS from the shared FFE0 service UUID alone`() {
        // 0xFFE0 is shared by JK, ANT, and wheel peripherals; the service alone
        // cannot establish which device type the rider has found.
        assertNull(BmsTypeDetector.detect(name = "BMS123", serviceUuids = listOf(SHARED_FFE0_SERVICE)))
        assertNull(BmsTypeDetector.detect(name = null, serviceUuids = listOf(SHARED_FFE0_SERVICE)))
    }

    @Test
    fun `does not fall back on the shared Daly service UUID`() {
        // 0xFFF0 is widely shared (DJI cameras, headphones) — name must match
        // for Daly, the UUID alone is not enough.
        assertNull(BmsTypeDetector.detect(name = null, serviceUuids = listOf(DALY_SERVICE)))
        assertNull(BmsTypeDetector.detect(name = "OsmoSodovaya", serviceUuids = listOf(DALY_SERVICE)))
    }

    @Test
    fun `name detection works regardless of service UUIDs`() {
        assertEquals(BmsType.JK_BMS, BmsTypeDetector.detect(name = "JK-001", serviceUuids = listOf(SHARED_FFE0_SERVICE)))
        assertEquals(BmsType.ANT_BMS, BmsTypeDetector.detect(name = "ANT-001", serviceUuids = listOf(SHARED_FFE0_SERVICE)))
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(BmsTypeDetector.detect(name = "Whatever", serviceUuids = listOf("0000abcd-0000-1000-8000-00805f9b34fb")))
        assertNull(BmsTypeDetector.detect(name = null, serviceUuids = emptyList()))
    }
}
