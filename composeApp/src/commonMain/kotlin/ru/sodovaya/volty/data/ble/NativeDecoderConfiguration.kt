package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.*
import ru.sodovaya.volty.domain.model.*

/** The same immutable decoder configuration lookup used by native construction. */
internal fun nativeLinkController(spec: LinkSpec, vehicle: Vehicle?): Controller? =
    spec.ownedControllers.firstOrNull()?.globalIndex?.let { index -> vehicle?.controllers?.firstOrNull { it.index == index } }

internal fun nativeLinkCellCount(spec: LinkSpec, vehicle: Vehicle?): Int? =
    spec.ownedPacks.firstOrNull()?.globalIndex?.let { index -> vehicle?.packs?.firstOrNull { it.index == index }?.cellCount }

/** Resolve once without constructing a decoder just to discover its topology.
 * The no-packs fallback is deliberately OR, not elvis: a controller-only saved
 * vehicle still needs its virtual pack even when its explicit flag is false.
 * Geometry and cell count belong to this link's owned slots, never global slot 0.
 */
internal fun nativeDecoderPlan(spec: LinkSpec, vehicle: Vehicle?): DecoderProtocolPlan {
    val controller = nativeLinkController(spec, vehicle)
    return controllerProtocolPlan(
        kind = spec.protocolKind,
        deriveBattery = controller?.providesDerivedBattery == true || vehicle?.packs.isNullOrEmpty(),
        motor = controller?.motor ?: MotorConfig(),
        link = spec,
        motorFor = { index -> vehicle?.controllers?.firstOrNull { it.index == index }?.motor ?: MotorConfig() },
        cellCount = nativeLinkCellCount(spec, vehicle),
    ) ?: batteryDecoderPlan(spec.protocolKind.toBmsType())
}

/** The same fallback factory is used by native links and standalone guest BMSs. */
internal fun batteryDecoderPlan(type: BmsType): DecoderProtocolPlan {
    val source = listOf(DecoderSource(TelemetryOrigin.BATTERY, 0))
    return when (type) {
        BmsType.JK_BMS -> DecoderProtocolPlan(1, 0, source) { JkBmsProtocol() }
        BmsType.JBD_BMS -> DecoderProtocolPlan(1, 0, source) { JbdBmsProtocol() }
        BmsType.ANT_BMS -> DecoderProtocolPlan(1, 0, source) { AntBmsProtocol() }
        BmsType.DALY_BMS -> DecoderProtocolPlan(1, 0, source) { DalyBmsProtocol() }
        BmsType.BEGODE -> checkNotNull(controllerProtocolPlan(ProtocolKind.BEGODE, false, MotorConfig()))
        BmsType.LEAPERKIM -> checkNotNull(controllerProtocolPlan(ProtocolKind.VETERAN, false, MotorConfig()))
        // Retraction of the former history-only gate: an absent decoder, unlike
        // missing numeric coverage, is a construction error. Keep the original
        // factory failure while metadata can describe its GPS-only limitation.
        BmsType.VESC_BMS -> DecoderProtocolPlan(1, 0, emptyList()) {
            error("VESC BMS protocol decode is not implemented yet")
        }
    }
}
