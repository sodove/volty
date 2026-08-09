package ru.sodovaya.volty.data.controller.kelly

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParameterCodecTest {
    @Test
    fun readParam_readsUnsignedWordsBigEndian() {
        assertEquals(
            "265",
            ParameterCodec.readParam(intArrayOf(1, 9), 0, ParamSize.WORD, 1, ParamType.UNSIGNED)
        )
    }

    @Test
    fun readParam_readsAnIndividualBit() {
        assertEquals(
            "1",
            ParameterCodec.readParam(intArrayOf(0b10000000), 0, ParamSize.BIT, 7, ParamType.UNSIGNED)
        )
    }

    @Test
    fun readParam_preservesUnsignedHexAndAsciiFormats() {
        assertEquals(
            "0aff0109",
            ParameterCodec.readParam(intArrayOf(0x0A, 0xFF, 0x01, 0x09), 0, ParamSize.WORD, 3, ParamType.HEX)
        )
        assertEquals(
            "KBLS",
            ParameterCodec.readParam("KBLS".map(Char::code).toIntArray(), 0, ParamSize.WORD, 3, ParamType.ASCII)
        )
    }

    @Test
    fun writeParam_retainsExternalCodecBehaviorForCalibrationBuffers() {
        val data = IntArray(2)

        assertTrue(ParameterCodec.writeParam(data, 0, ParamSize.WORD, 1, ParamType.UNSIGNED, "258"))
        assertEquals(1, data[0])
        assertEquals(2, data[1])
        assertFalse(ParameterCodec.writeParam(data, 0, ParamSize.BIT, 0, ParamType.UNSIGNED, "2"))
    }
}
