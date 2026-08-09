package ru.sodovaya.volty.data.controller.kelly

/** Byte manipulation utilities from Kelly's ETS reference module. */
object ByteUtils {
    fun Byte.toUnsigned(): Int = toInt() and 0xFF

    fun Int.toBigEndian16(): ByteArray = byteArrayOf(
        ((this shr 8) and 0xFF).toByte(),
        (this and 0xFF).toByte()
    )

    fun IntArray.readBigEndian16(offset: Int): Int =
        (this[offset] shl 8) or (this[offset + 1] and 0xFF)

    fun Int.toLittleEndianAddress(): Pair<Byte, Byte> =
        (this and 0xFF).toByte() to ((this shr 8) and 0xFF).toByte()

    fun ByteArray.toHexString(offset: Int = 0, length: Int = size): String =
        (offset until offset + length).joinToString(",") { this[it].toUnsigned().toString(16).uppercase().padStart(2, '0') }

    fun String.hexToIntArray(): IntArray {
        if (isEmpty() || this == "ERROR") return intArrayOf(0)
        return split(",").map { it.trim().toInt(16) }.toIntArray()
    }

    fun ByteArray.toUnsignedIntArray(): IntArray = IntArray(size) { this[it].toUnsigned() }
}
