package ru.sodovaya.volty.data.bms.vesc

/**
 * CRC-16/CCITT (XMODEM): poly 0x1021, init 0x0000, no reflection, no final xor.
 * This is the CRC the VESC packet layer puts over the payload — distinct from
 * the CRC-16/MODBUS the ANT BMS uses (see CrcUtils), hence a separate object.
 */
object VescCrc {
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }
}
