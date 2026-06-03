package ru.sodovaya.volty.data.bms

/** Simple byte-sum checksum (JK BMS, Daly BMS). */
fun checksumSum(data: ByteArray, start: Int = 0, end: Int = data.size): Int {
    var sum = 0
    for (i in start until end) {
        sum += data[i].toInt() and 0xFF
    }
    return sum and 0xFF
}

/** CRC-16/MODBUS (ANT BMS). Reflected polynomial 0xA001. */
fun crc16Modbus(data: ByteArray, start: Int = 0, end: Int = data.size): Int {
    var crc = 0xFFFF
    for (i in start until end) {
        crc = crc xor (data[i].toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
        }
    }
    return crc and 0xFFFF
}

fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

fun ByteArray.u16LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8)

fun ByteArray.i16LE(offset: Int): Int {
    val v = u16LE(offset)
    return if (v >= 0x8000) v - 0x10000 else v
}

fun ByteArray.u32LE(offset: Int): Long =
    (this[offset].toInt() and 0xFF).toLong() or
            ((this[offset + 1].toInt() and 0xFF).toLong() shl 8) or
            ((this[offset + 2].toInt() and 0xFF).toLong() shl 16) or
            ((this[offset + 3].toInt() and 0xFF).toLong() shl 24)

fun ByteArray.i32LE(offset: Int): Int = u32LE(offset).toInt()

fun ByteArray.u16BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or
            (this[offset + 1].toInt() and 0xFF)

fun ByteArray.i16BE(offset: Int): Int {
    val v = u16BE(offset)
    return if (v >= 0x8000) v - 0x10000 else v
}

fun ByteArray.u32BE(offset: Int): Long =
    ((this[offset].toInt() and 0xFF).toLong() shl 24) or
            ((this[offset + 1].toInt() and 0xFF).toLong() shl 16) or
            ((this[offset + 2].toInt() and 0xFF).toLong() shl 8) or
            (this[offset + 3].toInt() and 0xFF).toLong()

/**
 * Read 8 bytes starting at [offset] as an unsigned 64-bit little-endian value.
 * Used by the Daly BLE protocol for its 64-bit alarm bitmap. We build the Long
 * byte-by-byte to avoid sign-extension surprises when promoting Int → Long.
 */
fun ByteArray.u64LE(offset: Int): Long {
    var v = 0L
    for (i in 0 until 8) {
        v = v or ((this[offset + i].toInt() and 0xFF).toLong() shl (i * 8))
    }
    return v
}
