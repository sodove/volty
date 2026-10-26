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
