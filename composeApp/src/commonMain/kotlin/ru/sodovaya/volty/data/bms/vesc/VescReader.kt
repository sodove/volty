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

    /**
     * VESC's `float32_auto` (`buffer_get_float32_auto`, `vesc_core/src/buffer.c:182`,
     * mirrored by VESC Tool's `VByteArray::vbPopFrontDouble32Auto`): four bytes
     * carrying a *bit-packed* float, **not** a scaled integer. Used where the
     * magnitude is unbounded and no single divisor would do — e.g. the BMS
     * lifetime charge/discharge totals.
     *
     * The layout is the firmware's own: bit 31 sign, bits 30..23 a biased
     * exponent, bits 22..0 the significand of `sig = sig_i / 2^24 + 0.5`, so
     * the value is `±sig × 2^(e - 126)`.
     *
     * That happens to be bit-identical to IEEE-754 binary32 for every value the
     * serialiser can produce (`(sig_i/2^24 + 0.5)·2^(e-126) == 1.f·2^(e-127)`),
     * but this is deliberately **not** implemented as `Float.fromBits`: the two
     * disagree on words the encoder never emits.
     * - `e == 0` with a non-zero significand reads as `sig × 2^-126` here
     *   (≈5.88e-39 for word `0x00000001`) and as an IEEE subnormal there
     *   (2^-149, ≈1.4e-45) — six orders apart.
     * - `e == 255` overflows either way, since `sig × 2^129 ≥ 2^128 > Float.MAX`,
     *   so this returns ±Infinity; `Float.fromBits` returns Infinity only when
     *   the significand is zero and NaN otherwise. The divergence there is
     *   Infinity-vs-NaN, not finite-vs-infinite.
     *
     * Following the firmware keeps a corrupt word decoding the way every other
     * VESC client decodes it.
     */
    fun f32auto(): Float {
        val bits = i32()
        var e = (bits ushr 23) and 0xFF
        val sigI = bits and 0x7FFFFF
        var sig = 0f
        if (e != 0 || sigI != 0) {
            sig = sigI / (8388608f * 2f) + 0.5f
            e -= 126
        }
        if (bits < 0) sig = -sig                  // bit 31 is the sign
        // ldexpf(sig, e). `e` spans -126..129 here, so 2^e is built exactly
        // from a Double's own exponent field (bias 1023, never subnormal) and
        // the product is exact; the single rounding happens in toFloat(),
        // exactly as ldexpf would — including flushing to a Float subnormal.
        return (sig.toDouble() * Double.fromBits((e + DOUBLE_EXP_BIAS).toLong() shl 52)).toFloat()
    }

    /**
     * A NUL-terminated byte string, as written by the firmware's
     * `strcpy(buf + ind, status); ind += strlen(status) + 1` and read back by
     * VESC Tool's `vbPopFrontString`. The terminator is consumed; a run of
     * bytes with no terminator (corruption — the firmware always writes one)
     * is taken to the end of the payload rather than failing.
     */
    fun zstring(): String {
        val start = i
        while (i < p.size && p[i].toInt() != 0) i++
        val s = p.decodeToString(start, i)
        if (i < p.size) i++                       // consume the terminator
        return s
    }

    private companion object {
        /** IEEE-754 binary64 exponent bias, used to build an exact 2^e. */
        const val DOUBLE_EXP_BIAS = 1023
    }
}
