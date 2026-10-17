package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.history.HistoryTelemetrySource
import ru.sodovaya.volty.data.history.historySourceKindForTelemetry
import ru.sodovaya.volty.domain.history.HistorySource
import ru.sodovaya.volty.domain.model.*

/** The same immutable decoder configuration lookup used by native construction. */
internal fun nativeLinkController(spec: LinkSpec, vehicle: Vehicle?): Controller? =
    spec.ownedControllers.firstOrNull()?.globalIndex?.let { index -> vehicle?.controllers?.firstOrNull { it.index == index } }

internal fun nativeLinkCellCount(spec: LinkSpec, vehicle: Vehicle?): Int? =
    spec.ownedPacks.firstOrNull()?.globalIndex?.let { index -> vehicle?.packs?.firstOrNull { it.index == index }?.cellCount }

/** Managed selection only: caller must first validate the complete physical plan.
 * One selected direct alias path is safe; this cannot authorize concurrent paths.
 */
internal fun nativeHistorySourcesForSelectedLink(spec: LinkSpec, vehicle: Vehicle?): Map<HistoryTelemetrySource, HistorySource> =
    nativeHistorySourcesForLink(spec, vehicle, singlePathAliases = true)

/**
 * Direct, non-aliased native sources and explicit forwarded VESC controllers.
 * Gateway BMS cache sources carry typed absence only; numeric freshness is not
 * certified. Derived gateway packs and physical aliases still need qualification.
 * Native branch/role identity is independent of UI labels and global slot order.
 */
internal fun nativeHistorySourcesForLink(spec: LinkSpec, vehicle: Vehicle?): Map<HistoryTelemetrySource, HistorySource> =
    nativeHistorySourcesForLink(spec, vehicle, singlePathAliases = false)

private fun nativeHistorySourcesForLink(spec: LinkSpec, vehicle: Vehicle?, singlePathAliases: Boolean): Map<HistoryTelemetrySource, HistorySource> {
    val profile = requireNotNull(vehicle) { "Recording needs an owned vehicle" }
    require(spec.address.isNotBlank())
    require(profile.packs.map { it.index }.distinct().size == profile.packs.size)
    require(profile.controllers.map { it.index }.distinct().size == profile.controllers.size)
    require(spec.ownedPacks.map { it.globalIndex }.distinct().size == spec.ownedPacks.size)
    require(spec.ownedControllers.map { it.globalIndex }.distinct().size == spec.ownedControllers.size)
    if (spec.isGatewayLink) return nativeHistoryForwardedControllers(spec, profile)
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
    when (spec.protocolKind) {
        ProtocolKind.JK, ProtocolKind.JBD, ProtocolKind.ANT, ProtocolKind.DALY -> {
            require(spec.ownedPacks.size == 1 && packs.single() != null && controllers.isEmpty())
            add(TelemetryOrigin.BATTERY, 0, checkNotNull(packs.single()).label)
        }
        ProtocolKind.BEGODE -> {
            require(spec.ownedPacks.size == 2 && controllers.size <= 1)
            require(packs.any { it != null } || controllers.isNotEmpty())
            for (branch in 0..1) {
                val label = packs[branch]?.label ?: "${profile.name} · ${branch + 1}"
                add(TelemetryOrigin.BATTERY, branch, label)
                for (assembly in 0..1) add(TelemetryOrigin.BATTERY, branch, "$label · ${assembly + 1}", TelemetryRole.ASSEMBLY, assembly)
            }
            add(TelemetryOrigin.CONTROLLER, 0, nativeLinkController(spec, profile)?.label ?: profile.name)
        }
        ProtocolKind.VESC -> {
            require(controllers.size == 1 && packs.all { it == null })
            val controller = checkNotNull(controllers.single())
            val derived = controller.providesDerivedBattery || profile.packs.isEmpty()
            require(spec.ownedPacks.size == if (derived) 1 else 0)
            add(TelemetryOrigin.CONTROLLER, 0, controller.label)
            add(TelemetryOrigin.CONTROLLER, 0, "${controller.label} · CAN", TelemetryRole.REPORTED_CAN)
            if (derived) add(TelemetryOrigin.BATTERY, 0, controller.label, TelemetryRole.DERIVED)
        }
        ProtocolKind.VESC_BMS, ProtocolKind.FARDRIVER, ProtocolKind.KELLY,
        ProtocolKind.NINEBOT, ProtocolKind.NINEBOT_LEGACY, ProtocolKind.KINGSONG,
        ProtocolKind.INMOTION, ProtocolKind.VETERAN -> throw IllegalArgumentException("Native recording producer is not qualified")
    }
    return result.toMap()
}

/** Explicit forwarded controllers plus optional physical BMS cache sources.
 * Local controller targets without a known native CAN ID remain unqualified.
 * Cache identities certify only the decoder's typed absence policy, never numeric
 * freshness, and cannot borrow a direct battery's identity or a virtual slot.
 */
private fun nativeHistoryForwardedControllers(spec: LinkSpec, profile: Vehicle): Map<HistoryTelemetrySource, HistorySource> {
    require(spec.protocolKind == ProtocolKind.VESC && spec.ownedControllers.isNotEmpty())
    // Match native construction, including its first-controller/profile fallback.
    // A real owned BMS slot suppresses that fallback even when the flag is ON.
    val deriveBattery = nativeLinkController(spec, profile)?.providesDerivedBattery == true || profile.packs.isEmpty()
    val packs = vescGatewayPacks(spec, deriveBattery)
    require(packs.none { it.derived }) { "Derived gateway recording is not qualified" }
    require(spec.ownedControllers.all { it.canId != null && it.canId in 0..254 &&
        (it.kind == null || it.kind == ProtocolKind.VESC) })
    require(spec.ownedControllers.map { it.canId }.distinct().size == spec.ownedControllers.size)
    val result = linkedMapOf<HistoryTelemetrySource, HistorySource>()
    spec.ownedControllers.forEachIndexed { local, owned ->
        val controller = requireNotNull(profile.controllers.singleOrNull { it.index == owned.globalIndex })
        require(controller.controllerType == ControllerType.VESC && controller.canId == owned.canId &&
            controller.address.equals(spec.address, ignoreCase = true))
        val motor = controller.motor
        val calibration = encodeIdentity(listOf(motor.polePairs.toString(), motor.wheelDiameterMm.toString(),
            motor.gearRatio.toRawBits().toString()))
        for (role in listOf(TelemetryRole.CONTRIBUTOR, TelemetryRole.REPORTED_CAN)) {
            val key = HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, local, role)
            // The local decoder array can reorder while the endpoint stays the same.
            val id = "native-gateway-history-v1:" + encodeIdentity(listOf(spec.address.uppercase(),
                spec.protocolKind.name, owned.canId.toString(), role.name,
                if (role == TelemetryRole.CONTRIBUTOR) calibration else ""))
            result[key] = HistorySource(id, historySourceKindForTelemetry(key.origin, role),
                if (role == TelemetryRole.REPORTED_CAN) "${controller.label} · CAN" else controller.label)
        }
    }
    // Reuse native construction's slot order, after excluding actual virtual slots.
    require(packs.map { it.canId }.distinct().size == packs.size)
    packs.forEachIndexed { local, endpoint ->
        val owned = spec.ownedPacks[local]
        require(owned.kind == ProtocolKind.VESC_BMS && (endpoint.canId == null || endpoint.canId in 0..254))
        val pack = requireNotNull(profile.packs.singleOrNull { it.index == endpoint.globalIndex })
        require(pack.bmsType == BmsType.VESC_BMS && pack.canId == endpoint.canId &&
            pack.bmsAddress.equals(spec.address, ignoreCase = true) && pack.aliasGroup == null)
        val key = HistoryTelemetrySource(TelemetryOrigin.BATTERY, local)
        // Explicit policy version prevents a future numeric producer from
        // silently continuing the cache-absence series under the same source ID.
        val id = "native-gateway-cache-unknown-v1:" + encodeIdentity(listOf(spec.address.uppercase(),
            spec.protocolKind.name, endpoint.canId?.toString() ?: "host"))
        result[key] = HistorySource(id, historySourceKindForTelemetry(key.origin, key.role), pack.label)
    }
    return result.toMap()
}

/** Length prefixes prevent address/configuration text from merging identity fields. */
private fun encodeIdentity(fields: List<String>): String = fields.joinToString("") { "${it.length}:$it" }
