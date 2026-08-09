package ru.sodovaya.volty.data.controller.kelly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KellyDefinitionsTest {
    @Test
    fun errorCodes_decodeSetBitsInProtocolOrder() {
        assertEquals(listOf("Over Volt", "Low Volt"), ErrorCodes.decode(0b110))
        assertEquals("Current Meter Err", ErrorCodes.decode(0x8000).single())
    }

    @Test
    fun controllerModel_acceptsKlsFirmware262AndNewer() {
        assertEquals(ControllerModel.KBLS_0106, ControllerModel.detect("KBLS7218", 264).getOrThrow())
        assertEquals(ControllerModel.KBLS_0109, ControllerModel.detect("KBLS7218", 265).getOrThrow())
        assertTrue(ControllerModel.detect("KBLS7218", 261).isFailure)
    }

    @Test
    fun voltageRanges_handleKnownAndSpecial80Code() {
        assertEquals(62, VoltageRanges.getMax(48))
        assertEquals(18 to 90, VoltageRanges.getVoltageRange("80", 72))
    }
}
