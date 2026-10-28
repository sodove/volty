package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.history.HistoryTelemetrySource
import ru.sodovaya.volty.data.history.historySourceKindForTelemetry
import ru.sodovaya.volty.domain.history.HistorySource
import ru.sodovaya.volty.domain.model.*

/** Managed selection only: caller must first validate the complete physical plan.
 * One selected path per alias is safe; this cannot authorize concurrent paths.
 * Gateway aliases retain their separate receipt-snapshot producer policy.
 */
internal fun nativeHistorySourcesForSelectedLink(spec: LinkSpec, vehicle: Vehicle?): Map<HistoryTelemetrySource, HistorySource> =
    nativeHistorySourcesForLink(spec, vehicle, singlePathAliases = true)

/**
 * Direct, non-aliased native sources and explicit forwarded VESC controllers.
 * Gateway BMS responses record their reported fields at receipt time. They do
 * not claim a sensor acquisition timestamp. Computed gateway packs retain their
 * own derived source; physical aliases select one recording path.
 * Native branch/role identity is independent of UI labels and global slot order.
 */
internal fun nativeHistorySourcesForLink(spec: LinkSpec, vehicle: Vehicle?): Map<HistoryTelemetrySource, HistorySource> =
    nativeHistorySourcesForLink(spec, vehicle, singlePathAliases = false)

private fun nativeHistorySourcesForLink(spec: LinkSpec, vehicle: Vehicle?, singlePathAliases: Boolean): Map<HistoryTelemetrySource, HistorySource> {
    val profile = requireNotNull(vehicle) { "Recording needs a physical connection identity" }
    require(spec.address.isNotBlank())
    require(profile.packs.map { it.index }.distinct().size == profile.packs.size)
    require(profile.controllers.map { it.index }.distinct().size == profile.controllers.size)
    require(spec.ownedPacks.map { it.globalIndex }.distinct().size == spec.ownedPacks.size)
    require(spec.ownedControllers.map { it.globalIndex }.distinct().size == spec.ownedControllers.size)
    if (spec.isGatewayLink) return nativeHistoryForwardedControllers(spec, profile, singlePathAliases)
    require((spec.ownedPacks + spec.ownedControllers).all { it.canId == null && (it.kind == null || it.kind == spec.protocolKind) })
    val packs = spec.ownedPacks.map { owned -> profile.packs.singleOrNull { it.index == owned.globalIndex } }
    val controllers = spec.ownedControllers.map { owned -> profile.controllers.singleOrNull { it.index == owned.globalIndex } }
    packs.filterNotNull().forEach {
        require(it.canId == null && it.bmsAddress.equals(spec.address, ignoreCase = true) && it.bmsType.protocolKind() == spec.protocolKind)
        if (it.aliasGroup != null) {
            require(singlePathAliases && it.aliasGroup.isNotBlank() && spec.ownedPacks.size == 1 &&
                spec.protocolKind in setOf(ProtocolKind.ANT, ProtocolKind.JBD, ProtocolKind.JK, ProtocolKind.DALY)) {
                "Aliased recording needs qualified single-path admission"
            }
        }
    }
    controllers.forEach {
        require(it != null && it.canId == null && it.address.equals(spec.address, ignoreCase = true) && it.controllerType.protocolKind() == spec.protocolKind)
    }
    val motor = nativeLinkController(spec, profile)?.motor ?: MotorConfig()
    val calibration = encodeIdentity(listOf(motor.polePairs.toString(), motor.wheelDiameterMm.toString(),
        motor.gearRatio.toRawBits().toString(),
        if (spec.protocolKind == ProtocolKind.BEGODE) nativeLinkCellCount(spec, profile)?.toString().orEmpty() else ""))
    val result = linkedMapOf<HistoryTelemetrySource, HistorySource>()
    val physicalAlias = packs.singleOrNull()?.aliasGroup
    fun add(origin: TelemetryOrigin, local: Int, label: String,
        role: TelemetryRole = TelemetryRole.CONTRIBUTOR, assembly: Int? = null) {
        val key = HistoryTelemetrySource(origin, local, role, assembly)
        // Preserve producer policy in the identity: changing protocol cannot
        // silently reinterpret a continuous series of the same physical pack.
        val id = if (physicalAlias != null) "native-alias-history-v1:" +
            encodeIdentity(listOf(profile.id, physicalAlias, spec.protocolKind.name))
        else "native-history-v1:" + encodeIdentity(listOf(spec.address.uppercase(), spec.protocolKind.name,
            origin.name, local.toString(), role.name, assembly?.toString().orEmpty(),
            if ((origin == TelemetryOrigin.CONTROLLER && role == TelemetryRole.CONTRIBUTOR) || role == TelemetryRole.DERIVED) calibration else ""))
        result[key] = HistorySource(id, historySourceKindForTelemetry(origin, role), label)
    }
    // Retraction: this used to repeat a per-protocol history allowlist. Source
    // addresses now come from the exact plan used to construct the live decoder.
    val configuration = nativeDecoderPlan(spec, profile)
    require(spec.ownedPacks.size == configuration.packCount)
    require(controllers.size <= configuration.controllerCount)
    require(controllers.isNotEmpty() || packs.any { it != null })
    configuration.sources.forEach { source ->
        val label = when (source.origin) {
            TelemetryOrigin.CONTROLLER -> controllers.getOrNull(source.localIndex)?.label ?: profile.name
            TelemetryOrigin.BATTERY -> packs.getOrNull(source.localIndex)?.label
                ?: nativeLinkController(spec, profile)?.label ?: "${profile.name} · ${source.localIndex + 1}"
        }
        add(source.origin, source.localIndex, label, source.role, source.assemblyIndex)
    }
    return result.toMap()
}

/** Local/forwarded controllers plus optional physical BMS response sources.
 * A native controller is identified by the BLE endpoint; a forwarded one also
 * includes its CAN target. BMS identities cannot borrow another battery's ID.
 */
private fun nativeHistoryForwardedControllers(spec: LinkSpec, profile: Vehicle, singlePathAliases: Boolean): Map<HistoryTelemetrySource, HistorySource> {
    require(spec.protocolKind == ProtocolKind.VESC)
    val configuration = nativeDecoderPlan(spec, profile)
    require(spec.ownedPacks.size == configuration.packCount)
    require(spec.ownedControllers.size == configuration.controllerCount)
    require(spec.ownedControllers.all { (it.canId == null || it.canId in 0..254) &&
        (it.kind == null || it.kind == ProtocolKind.VESC) })
    require(spec.ownedControllers.map { it.canId }.distinct().size == spec.ownedControllers.size)
    val controllers = spec.ownedControllers.map { owned ->
        val controller = requireNotNull(profile.controllers.singleOrNull { it.index == owned.globalIndex })
        require(controller.controllerType == ControllerType.VESC && controller.canId == owned.canId &&
            controller.address.equals(spec.address, ignoreCase = true))
        controller
    }
    fun calibration(controller: Controller): String = controller.motor.let { motor ->
        encodeIdentity(listOf(motor.polePairs.toString(), motor.wheelDiameterMm.toString(),
            motor.gearRatio.toRawBits().toString()))
    }
    val virtual = configuration.sources.filter { it.origin == TelemetryOrigin.BATTERY && it.role == TelemetryRole.DERIVED }
    require(virtual.size <= 1)
    val physical = configuration.sources.filter { it.origin == TelemetryOrigin.BATTERY && it.role == TelemetryRole.CONTRIBUTOR }
    require(physical.map { spec.ownedPacks[it.localIndex].canId }.distinct().size == physical.size)
    val result = linkedMapOf<HistoryTelemetrySource, HistorySource>()
    configuration.sources.forEach { source ->
        val key = HistoryTelemetrySource(source.origin, source.localIndex, source.role, source.assemblyIndex)
        val (id, label) = when (source.origin) {
            TelemetryOrigin.CONTROLLER -> {
                val owned = spec.ownedControllers[source.localIndex]
                val controller = controllers[source.localIndex]
                val identity = "native-gateway-history-v1:" + encodeIdentity(listOf(spec.address.uppercase(),
                    spec.protocolKind.name, owned.canId?.toString() ?: "local", source.role.name,
                    if (source.role == TelemetryRole.CONTRIBUTOR) calibration(controller) else ""))
                identity to if (source.role == TelemetryRole.REPORTED_CAN) "${controller.label} · CAN" else controller.label
            }
            TelemetryOrigin.BATTERY -> {
                val owned = spec.ownedPacks[source.localIndex]
                if (source.role == TelemetryRole.DERIVED) {
                    require(owned.kind == null && owned.canId == null)
                    require(profile.packs.none { it.index == owned.globalIndex })
                    require(controllers.isNotEmpty())
                    // Identity follows contributing endpoints and calibration,
                    // never presentation order or slot labels.
                    val contributors = spec.ownedControllers.mapIndexed { index, endpoint ->
                        encodeIdentity(listOf(endpoint.canId?.toString() ?: "local", calibration(controllers[index])))
                    }.sorted()
                    ("native-gateway-derived-v1:" + encodeIdentity(listOf(spec.address.uppercase(),
                        spec.protocolKind.name) + contributors)) to profile.name
                } else {
                    require(owned.kind == ProtocolKind.VESC_BMS && (owned.canId == null || owned.canId in 0..254))
                    val pack = requireNotNull(profile.packs.singleOrNull { it.index == owned.globalIndex })
                    require(pack.bmsType == BmsType.VESC_BMS && pack.canId == owned.canId &&
                        pack.bmsAddress.equals(spec.address, ignoreCase = true))
                    val alias = pack.aliasGroup
                    require(alias == null || (singlePathAliases && alias.isNotBlank())) {
                        "Aliased recording needs qualified single-path admission"
                    }
                    // Retraction: cache-unknown erased received values. Receipt
                    // snapshots have a separate producer-policy identity.
                    val identity = if (alias != null) "native-alias-gateway-received-v1:" + encodeIdentity(listOf(profile.id, alias))
                    else "native-gateway-received-v1:" + encodeIdentity(listOf(spec.address.uppercase(),
                        spec.protocolKind.name, owned.canId?.toString() ?: "host"))
                    identity to pack.label
                }
            }
        }
        result[key] = HistorySource(id, historySourceKindForTelemetry(source.origin, source.role), label)
    }
    return result.toMap()
}

/** Length prefixes prevent address/configuration text from merging identity fields. */
private fun encodeIdentity(fields: List<String>): String = fields.joinToString("") { "${it.length}:$it" }

/**
 * The CAN id inside a gateway controller's source identity (`native-gateway-history-v1:`, fields
 * address, protocol, CAN id or `local`, role, calibration — see the CONTROLLER branch above), or
 * null for any other identity. Used only to name export columns like logui's `V117`.
 */
internal fun historySourceCanId(sourceId: String): Int? {
    val prefix = "native-gateway-history-v1:"
    if (!sourceId.startsWith(prefix)) return null
    val fields = mutableListOf<String>()
    var at = prefix.length
    while (at < sourceId.length && fields.size < 3) {
        val colon = sourceId.indexOf(':', at).takeIf { it > at } ?: return null
        val length = sourceId.substring(at, colon).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        if (colon + 1 + length > sourceId.length) return null
        fields += sourceId.substring(colon + 1, colon + 1 + length)
        at = colon + 1 + length
    }
    return fields.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..254 }
}
