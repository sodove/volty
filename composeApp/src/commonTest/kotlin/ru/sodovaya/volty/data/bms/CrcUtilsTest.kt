package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals

class CrcUtilsTest {

    @Test
    fun `checksumSum returns lower byte of sum`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
        // sum = 0x105 -> & 0xFF = 0x05
        assertEquals(0x05, checksumSum(data))
    }

    @Test
    fun `checksumSum respects start and end range`() {
        val data = byteArrayOf(0x10, 0x01, 0x02, 0x10)
        // bytes 1..2 inclusive = 0x01 + 0x02 = 0x03
        assertEquals(0x03, checksumSum(data, start = 1, end = 3))
    }

    @Test
    fun `crc16Modbus matches known vector`() {
        // Known test vector for "123456789" ASCII = 0x4B37
        val data = "123456789".encodeToByteArray()
        assertEquals(0x4B37, crc16Modbus(data))
    }

    @Test
    fun `u16LE little-endian`() {
        // 0x34, 0x12 -> 0x1234 = 4660
        assertEquals(0x1234, byteArrayOf(0x34, 0x12).u16LE(0))
    }

    @Test
    fun `i16LE negative`() {
        // 0xFF, 0xFF -> -1
        assertEquals(-1, byteArrayOf(0xFF.toByte(), 0xFF.toByte()).i16LE(0))
    }

    @Test
    fun `u16BE big-endian`() {
        assertEquals(0x1234, byteArrayOf(0x12, 0x34).u16BE(0))
    }

    @Test
    fun `u32LE four bytes little-endian`() {
        // 0x78,0x56,0x34,0x12 -> 0x12345678
        val data = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        assertEquals(0x12345678L, data.u32LE(0))
    }
}
