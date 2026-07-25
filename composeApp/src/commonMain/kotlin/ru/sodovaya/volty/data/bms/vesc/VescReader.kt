package ru.sodovaya.volty.data.bms.vesc

/**
 * Big-endian cursor over a VESC payload. Every read is length-checked: a short
 * frame yields null from the decoder rather than an exception, because a
 * truncated notification is an ordinary BLE event, not a programming error.
 */
class VescReader(private val p: ByteArray, private var i: Int = 0) {
    fun remaining(): Int = p.size - i
    fun has(n: Int): Boolean = remaining() >= n

    fun u8(): Int { val v = p[i].toInt() and 0xFF; i += 1; return v }
    fun i8(): Int { val v = p[i].toInt(); i += 1; return v }
    fun i16(): Int { val v = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF); i += 2
                     return if (v >= 0x8000) v - 0x10000 else v }
    fun i32(): Int { val v = ((p[i].toInt() and 0xFF) shl 24) or ((p[i + 1].toInt() and 0xFF) shl 16) or
                             ((p[i + 2].toInt() and 0xFF) shl 8) or (p[i + 3].toInt() and 0xFF); i += 4; return v }
    fun d16(scale: Float): Float = i16() / scale
    fun d32(scale: Float): Float = i32() / scale
}
