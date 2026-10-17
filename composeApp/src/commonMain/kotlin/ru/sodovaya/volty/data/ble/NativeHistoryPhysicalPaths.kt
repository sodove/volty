package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.history.HistoryTelemetrySource
import ru.sodovaya.volty.domain.history.HistorySource
import ru.sodovaya.volty.domain.model.TelemetryOrigin
import ru.sodovaya.volty.domain.model.Vehicle

/** One recording source per physical battery in a complete native decoder plan.
 * This filters observation bindings, never BLE specs or decoder local indices.
 * Prefer an earned direct producer over an unaged gateway cache. If that direct
 * link drops, its source gap remains honest while other controllers/GPS continue;
 * availability of cached bytes cannot authorize numeric failover.
 */
internal fun nativeHistoryPhysicalPaths(
    specs: List<LinkSpec>,
    vehicle: Vehicle,
    bindings: List<Map<HistoryTelemetrySource, HistorySource>>,
): List<Map<HistoryTelemetrySource, HistorySource>> {
    require(specs.size == bindings.size)
    data class Path(val link: Int, val key: HistoryTelemetrySource, val rank: Int, val address: String,
        val canId: Int, val protocol: String)
    val aliases = linkedMapOf<String, MutableList<Path>>()
    specs.forEachIndexed { link, spec ->
        spec.ownedPacks.forEachIndexed pack@ { local, owned ->
            val alias = vehicle.packs.singleOrNull { it.index == owned.globalIndex }?.aliasGroup ?: return@pack
            require(alias.isNotBlank())
            val key = HistoryTelemetrySource(TelemetryOrigin.BATTERY, local)
            require(key in bindings[link]) { "Physical alias needs a qualified battery producer" }
            val rank = when (spec.protocolKind) {
                ProtocolKind.ANT, ProtocolKind.JBD, ProtocolKind.JK, ProtocolKind.DALY -> 0
                ProtocolKind.VESC -> 1 // Already qualified cache-absence policy.
                else -> error("Physical alias producer is not qualified")
            }
            aliases.getOrPut(alias) { mutableListOf() } += Path(link, key, rank, spec.address.uppercase(),
                owned.canId ?: -1, spec.protocolKind.name)
        }
    }
    val omitted = List(specs.size) { mutableSetOf<HistoryTelemetrySource>() }
    val preference = compareBy<Path> { it.rank }.thenBy { it.address }.thenBy { it.canId }.thenBy { it.protocol }
    aliases.values.forEach { paths ->
        val chosen = paths.minWith(preference)
        paths.filter { it !== chosen }.forEach { omitted[it.link] += it.key }
    }
    return bindings.mapIndexed { link, sources -> sources.filterKeys { it !in omitted[link] } }
}
