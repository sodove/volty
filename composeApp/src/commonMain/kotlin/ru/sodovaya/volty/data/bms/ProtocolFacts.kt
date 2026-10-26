package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.DeviceFact

/**
 * Optional on a protocol: read-only facts it has decoded so far, read after each decoded
 * notification exactly as [ProtocolDiagnostics] is. An empty list means "nothing known yet" -
 * a fact is listed only once the wire has actually said it.
 */
interface ProtocolFacts {
    fun deviceFacts(): List<DeviceFact>
}

/** The facts [protocol] reports, or none when it does not implement [ProtocolFacts]. */
fun deviceFactsOf(protocol: Any?): List<DeviceFact> = (protocol as? ProtocolFacts)?.deviceFacts().orEmpty()

/**
 * [value] with exactly [decimals] fraction digits, rounded half away from zero and independent of
 * any locale ("8565.341", "-0.5"). A fact's value is a machine string the screen formats from
 * resources, so it must never depend on the device's decimal separator.
 */
internal fun factNumber(value: Float, decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = kotlin.math.round(kotlin.math.abs(value.toDouble()) * scale).toLong()
    val negative = value < 0f && scaled != 0L
    val whole = scaled / scale
    val text = if (decimals == 0) whole.toString() else {
        whole.toString() + "." + (scaled % scale).toString().padStart(decimals, '0')
    }
    return if (negative) "-$text" else text
}
