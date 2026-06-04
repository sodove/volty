package ru.sodovaya.volty.util

import kotlin.math.abs
import kotlin.math.round

/**
 * KMP-safe fixed-point formatting (no JVM `String.format`).
 *
 * Formats [v] with exactly [decimals] fraction digits and handles negative
 * values correctly: -6.1 renders as "-6.1", never "-6.-1". A value that rounds
 * to zero never carries a minus sign (e.g. -0.04 at 1 decimal -> "0.0").
 *
 * Rounding follows [kotlin.math.round] (round-half-to-even / banker's rounding).
 * We scale on the absolute value, so rounding is symmetric about zero: a value
 * and its negation round to the same magnitude (e.g. 12.345f and -12.345f at
 * 2 decimals both yield ".35", since the float 12.345f is slightly above the tie).
 */
fun formatFixed(v: Float, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10L }
    val negative = v < 0f
    val absScaled = round(abs(v.toDouble()) * factor).toLong()
    val intPart = absScaled / factor
    val fracPart = absScaled % factor
    val sign = if (negative && (intPart != 0L || fracPart != 0L)) "-" else ""
    if (decimals == 0) return "$sign$intPart"
    return "$sign$intPart.${fracPart.toString().padStart(decimals, '0')}"
}

/** Like [formatFixed] but always prefixes a sign ("+1.2" / "-1.2"; "+0.0" for zero). */
fun formatSigned(v: Float, decimals: Int): String =
    (if (v >= 0f) "+" else "") + formatFixed(v, decimals)
