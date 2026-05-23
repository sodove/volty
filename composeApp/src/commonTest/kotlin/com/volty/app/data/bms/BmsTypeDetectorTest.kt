package com.volty.app.data.bms

import com.volty.app.domain.model.BmsType
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
    fun `JBD detected by unique service UUID when name is missing`() {
        assertEquals(BmsType.JBD_BMS, BmsTypeDetector.detect(name = null, serviceUuids = listOf(JBD_SERVICE)))
    }

    @Test
    fun `Daly detected by unique service UUID when name is missing`() {
        assertEquals(BmsType.DALY_BMS, BmsTypeDetector.detect(name = null, serviceUuids = listOf(DALY_SERVICE)))
    }

    @Test
    fun `JK_OR_ANT service uuid returns null when name is missing`() {
        // ffe0 is shared by JK and ANT - cannot disambiguate from UUID alone
        assertNull(BmsTypeDetector.detect(name = null, serviceUuids = listOf(JK_OR_ANT_SERVICE)))
    }

    @Test
    fun `name prefix wins over service uuid for shared ffe0`() {
        assertEquals(BmsType.JK_BMS, BmsTypeDetector.detect(name = "JK-001", serviceUuids = listOf(JK_OR_ANT_SERVICE)))
        assertEquals(BmsType.ANT_BMS, BmsTypeDetector.detect(name = "ANT-001", serviceUuids = listOf(JK_OR_ANT_SERVICE)))
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(BmsTypeDetector.detect(name = "Whatever", serviceUuids = listOf("0000abcd-0000-1000-8000-00805f9b34fb")))
        assertNull(BmsTypeDetector.detect(name = null, serviceUuids = emptyList()))
    }
}
