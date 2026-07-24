package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack

/**
 * The protocol spoken over one BLE link, independent of whether the source is a
 * battery pack or a controller. A Begode wheel multiplexes both a smart-BMS and
 * a motherboard stream over ONE address, so pack sources and controller sources
 * can resolve to the same kind — hence a single unified enum rather than two.
 */
enum class ProtocolKind { JK, JBD, ANT, DALY, BEGODE, VESC, VESC_BMS, FARDRIVER, KELLY }

fun BmsType.protocolKind(): ProtocolKind = when (this) {
    BmsType.JK_BMS -> ProtocolKind.JK
    BmsType.JBD_BMS -> ProtocolKind.JBD
    BmsType.ANT_BMS -> ProtocolKind.ANT
    BmsType.DALY_BMS -> ProtocolKind.DALY
    BmsType.BEGODE -> ProtocolKind.BEGODE
    BmsType.VESC_BMS -> ProtocolKind.VESC_BMS
}

fun ControllerType.protocolKind(): ProtocolKind = when (this) {
    ControllerType.VESC -> ProtocolKind.VESC
    ControllerType.FARDRIVER -> ProtocolKind.FARDRIVER
    ControllerType.KELLY -> ProtocolKind.KELLY
    ControllerType.BEGODE -> ProtocolKind.BEGODE
}

/**
 * Bridge back to a [BmsType] for the battery half of a link. [KableBmsRepository]
 * builds its decode protocol from a [BmsType]; a link keyed by [ProtocolKind]
 * hands it back the matching value here. Controller-only kinds have no BMS
 * protocol and are built in a later part — the `error(...)` branch is
 * unreachable in Part A because the repository only ever plans battery packs
 * (`planLinks(stored)`), which never yields a controller kind.
 */
fun ProtocolKind.toBmsType(): BmsType = when (this) {
    ProtocolKind.JK -> BmsType.JK_BMS
    ProtocolKind.JBD -> BmsType.JBD_BMS
    ProtocolKind.ANT -> BmsType.ANT_BMS
    ProtocolKind.DALY -> BmsType.DALY_BMS
    ProtocolKind.BEGODE -> BmsType.BEGODE
    ProtocolKind.VESC_BMS -> BmsType.VESC_BMS
    ProtocolKind.VESC, ProtocolKind.FARDRIVER, ProtocolKind.KELLY ->
        error("$this is a controller kind — no BMS protocol (built in a later part)")
}

/**
 * One source a link owns: its vehicle-global index (within its own kind — packs
 * and controllers are numbered separately) and, in a later part, the CAN id it
 * is forwarded under. [canId] is always null in Part A — direct BLE only.
 */
data class OwnedSource(val globalIndex: Int, val canId: Int? = null)

/**
 * One BLE link: a distinct address, the [ProtocolKind] spoken behind it, and
 * the vehicle-global pack / controller sources it is responsible for, in local
 * order. A session speaks local indices (0-based within its own protocol);
 * [globalPackIndex] / [globalControllerIndex] map them to the vehicle's global
 * indices the orchestrator is keyed by.
 */
data class LinkSpec(
    val address: String,
    val protocolKind: ProtocolKind,
    val ownedPacks: List<OwnedSource> = emptyList(),
    val ownedControllers: List<OwnedSource> = emptyList()
) {
    fun globalPackIndex(local: Int): Int = ownedPacks[local].globalIndex
    fun globalControllerIndex(local: Int): Int = ownedControllers[local].globalIndex
}

/** Battery-only overload — any caller that has no controllers compiles unchanged. */
fun planLinks(packs: List<Pack>): List<LinkSpec> = planLinks(packs, emptyList())

/**
 * Group a vehicle's packs AND controllers into links by distinct address. A
 * Begode's two packs and its motherboard share one address and form one link;
 * two independent BMS at two addresses form two links. Link order follows each
 * address's first appearance; each link's owned sources are ascending by index.
 *
 * Requires every source to be direct (canId == null): CAN-forwarded sources are
 * Part C. Throws if the direct sources at one address resolve to more than one
 * [ProtocolKind] — a single BLE link speaks exactly one protocol. Pure — no
 * BLE, no ordering assumption on the input.
 */
fun planLinks(packs: List<Pack>, controllers: List<Controller>): List<LinkSpec> {
    require(packs.all { it.canId == null } && controllers.all { it.canId == null }) {
        "CAN-forwarded sources (canId != null) are not supported until Part C"
    }
    data class Acc(
        val packs: MutableList<OwnedSource> = mutableListOf(),
        val controllers: MutableList<OwnedSource> = mutableListOf(),
        val kinds: MutableSet<ProtocolKind> = linkedSetOf()
    )
    val byAddress = LinkedHashMap<String, Acc>()
    for (p in packs.sortedBy { it.index }) {
        val acc = byAddress.getOrPut(p.bmsAddress) { Acc() }
        acc.packs += OwnedSource(p.index, p.canId)
        acc.kinds += p.bmsType.protocolKind()
    }
    for (c in controllers.sortedBy { it.index }) {
        val acc = byAddress.getOrPut(c.address) { Acc() }
        acc.controllers += OwnedSource(c.index, c.canId)
        acc.kinds += c.controllerType.protocolKind()
    }
    return byAddress.map { (address, acc) ->
        require(acc.kinds.size == 1) {
            "Address $address resolves to conflicting protocol kinds ${acc.kinds}"
        }
        LinkSpec(address, acc.kinds.first(), acc.packs.toList(), acc.controllers.toList())
    }
}
