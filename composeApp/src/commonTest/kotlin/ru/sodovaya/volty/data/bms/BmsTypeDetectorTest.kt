package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BmsType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BmsTypeDetectorTest {

    private val JK_OR_ANT_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
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
    fun `returns null when name is missing even with known service UUID`() {
        assertNull(BmsTypeDetector.detect(name = null, serviceUuids = listOf(JBD_SERVICE)))
        assertNull(BmsTypeDetector.detect(name = null, serviceUuids = listOf(DALY_SERVICE)))
    }

    @Test
    fun `returns null for unknown name even with known service UUID`() {
        // DJI cameras advertise service fff0 but their name doesn't match
        assertNull(BmsTypeDetector.detect(name = "OsmoSodovaya", serviceUuids = listOf(DALY_SERVICE)))
    }

    @Test
    fun `name detection works regardless of service UUIDs`() {
        assertEquals(BmsType.JK_BMS, BmsTypeDetector.detect(name = "JK-001", serviceUuids = listOf(JK_OR_ANT_SERVICE)))
        assertEquals(BmsType.ANT_BMS, BmsTypeDetector.detect(name = "ANT-001", serviceUuids = listOf(JK_OR_ANT_SERVICE)))
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(BmsTypeDetector.detect(name = "Whatever", serviceUuids = listOf("0000abcd-0000-1000-8000-00805f9b34fb")))
        assertNull(BmsTypeDetector.detect(name = null, serviceUuids = emptyList()))
    }
}
