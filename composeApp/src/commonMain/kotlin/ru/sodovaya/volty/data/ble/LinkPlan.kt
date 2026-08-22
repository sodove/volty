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
enum class ProtocolKind {
    JK, JBD, ANT, DALY, BEGODE, VESC, VESC_BMS, FARDRIVER, KELLY,
    NINEBOT, NINEBOT_LEGACY, KINGSONG, INMOTION, VETERAN
}

fun BmsType.protocolKind(): ProtocolKind = when (this) {
    BmsType.JK_BMS -> ProtocolKind.JK
    BmsType.JBD_BMS -> ProtocolKind.JBD
    BmsType.ANT_BMS -> ProtocolKind.ANT
    BmsType.DALY_BMS -> ProtocolKind.DALY
    BmsType.BEGODE -> ProtocolKind.BEGODE
    BmsType.LEAPERKIM -> ProtocolKind.VETERAN
    BmsType.VESC_BMS -> ProtocolKind.VESC_BMS
}

fun ControllerType.protocolKind(): ProtocolKind = when (this) {
    ControllerType.VESC -> ProtocolKind.VESC
    ControllerType.FARDRIVER -> ProtocolKind.FARDRIVER
    ControllerType.KELLY -> ProtocolKind.KELLY
    ControllerType.BEGODE -> ProtocolKind.BEGODE
    ControllerType.NINEBOT -> ProtocolKind.NINEBOT
    ControllerType.NINEBOT_LEGACY -> ProtocolKind.NINEBOT_LEGACY
    ControllerType.KINGSONG -> ProtocolKind.KINGSONG
    ControllerType.INMOTION -> ProtocolKind.INMOTION
    ControllerType.VETERAN -> ProtocolKind.VETERAN
    ControllerType.NOSFET -> ProtocolKind.VETERAN
}

/**
 * Whether a link speaking this protocol has a CAN bus that can be enumerated
 * (`COMM_PING_CAN`, `G §3` flow 4).
 *
 * **THE single statement of it**, and it exists because two layers had their own
 * copy and disagreed: the composer offered a scan target that
 * `KableBmsRepository.discoverCanIds` then refused, so the button appeared and
 * the tap could only ever fail. Each layer had a test pinning its own half and
 * no fixture spanned both, which is exactly how a contradiction survives a
 * mutation sweep — every mutant of either side was killed by that side's own
 * test.
 *
 * `VESC` and nothing else: `VESC_BMS` is the *hosted battery's* decode kind, and
 * a link that resolves to it (`resolveLinkKind`, below) has no VESC controller
 * on it at all — `createProtocol` refuses such a link outright, so it can never
 * be online to be scanned. Every other kind is a battery protocol or a
 * controller volty cannot decode yet.
 *
 * Exhaustive with no `else`, like every other `when` over this enum: a new kind
 * has to answer.
 */
val ProtocolKind.hasCanBus: Boolean
    get() = when (this) {
        ProtocolKind.VESC -> true
        ProtocolKind.VESC_BMS, ProtocolKind.BEGODE, ProtocolKind.FARDRIVER, ProtocolKind.KELLY,
        ProtocolKind.NINEBOT, ProtocolKind.NINEBOT_LEGACY, ProtocolKind.KINGSONG,
        ProtocolKind.INMOTION, ProtocolKind.VETERAN,
        ProtocolKind.JK, ProtocolKind.JBD, ProtocolKind.ANT, ProtocolKind.DALY -> false
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
    ProtocolKind.VESC, ProtocolKind.FARDRIVER, ProtocolKind.KELLY,
    ProtocolKind.NINEBOT, ProtocolKind.NINEBOT_LEGACY, ProtocolKind.KINGSONG,
    ProtocolKind.INMOTION ->
        error("$this is a controller kind — no BMS protocol (built in a later part)")
    ProtocolKind.VETERAN -> BmsType.LEAPERKIM
}

/**
 * One source a link owns: its vehicle-global index (within its own kind — packs
 * and controllers are numbered separately), the CAN id it is forwarded under
 * (null for a direct BLE source or a HOSTED one the gateway answers itself —
 * e.g. `BMS_GET_VALUES`), and, when it decodes differently from the link's own
 * [LinkSpec.protocolKind], the [ProtocolKind] that actually decodes it (C §6).
 *
 * [kind] is left null for the ordinary case — a direct source whose own kind
 * IS the link's kind, exactly like every source before Part C — so an
 * `OwnedSource(index)` built the old way still equals a freshly planned one.
 * It is populated for a CAN-forwarded source (even when its own kind happens
 * to match the link's, e.g. a uBox controller behind its own VESC gateway) and
 * for a hosted source whose kind genuinely differs from the link's (the
 * gateway's own hosted VESC-BMS battery behind a VESC link) — the two cases
 * C's gateway multiplexer needs to tell apart from a plain pass-through.
 */
data class OwnedSource(val globalIndex: Int, val canId: Int? = null, val kind: ProtocolKind? = null)

/**
 * One BLE link: a distinct address, the [ProtocolKind] the LINK itself speaks
 * (its wire protocol — what a session actually dials in over BLE), and the
 * vehicle-global pack / controller sources it is responsible for, in local
 * order. A session speaks local indices (0-based within its own protocol);
 * [globalPackIndex] / [globalControllerIndex] map them to the vehicle's global
 * indices the orchestrator is keyed by.
 *
 * Since Part C a link's owned sources may be decoded differently from the
 * link's own [protocolKind] — see [OwnedSource.kind]: a gateway link still
 * speaks ONE protocol over the air, but a CAN-forwarded controller or a hosted
 * battery behind it can carry its own decode kind.
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

/**
 * Whether this link is a **gateway** — one BLE address fronting several
 * sources — and therefore needs the multiplexer
 * ([ru.sodovaya.volty.data.bms.VescGatewayProtocol]) rather than the plain
 * single-source protocol. Three independent triggers, each of which the single
 * protocol cannot serve:
 *
 *  - **any CAN-forwarded source** — reaching it at all means wrapping the
 *    request in `COMM_FORWARD_CAN`, which only the multiplexer does;
 *  - **a hosted battery** (a pack whose own kind is [ProtocolKind.VESC_BMS] on
 *    a link that speaks something else) — it answers `COMM_BMS_GET_VALUES`, a
 *    command the single-controller protocol never sends;
 *  - **more than one controller** on one address — nothing else can even
 *    address the second one.
 *
 * Read off the SPEC, and deliberately blind to how many PACKS a link owns
 * beyond their tags: a plain single VESC link owns one controller and, once
 * `planLinkPacks` has given it a derived battery slot, one untagged pack too.
 * Counting sources naively would flip every existing single-VESC vehicle onto
 * the gateway — and, worse, would answer differently before and after that
 * expansion, so the protocol used to SIZE the pack list and the protocol the
 * session actually speaks could disagree. Every trigger above is invariant
 * across the expansion (`KableBmsRepository.effectiveLinkSpecs` preserves each
 * planned source's `canId`/`kind`, and synthesised slots carry neither).
 */
val LinkSpec.isGatewayLink: Boolean
    get() = (ownedPacks + ownedControllers).any { it.canId != null } ||
        ownedPacks.any { it.kind == ProtocolKind.VESC_BMS } ||
        ownedControllers.size > 1

/** Battery-only overload — any caller that has no controllers compiles unchanged. */
fun planLinks(packs: List<Pack>): List<LinkSpec> = planLinks(packs, emptyList())

/**
 * Resolve the ONE wire protocol a link speaks from the distinct [ProtocolKind]s
 * observed among its owned sources. A single BLE link still speaks exactly one
 * protocol (unchanged since Part A) — with one sanctioned exception, added by
 * Part C §6: a hosted VESC-BMS battery (`BMS_GET_VALUES`, answered by the
 * gateway itself — never forwarded) shares its VESC controllers' link without
 * being a conflict, because it is fundamentally the SAME wire protocol
 * (VESC's own binary framing), just a different command/decode once hosted.
 * That pairing resolves to [ProtocolKind.VESC] — the link's own kind — while
 * the battery keeps its true kind on its own [OwnedSource.kind] (C §6).
 *
 * Every other combination of distinct kinds — including two CAN-forwarded
 * controllers of genuinely different kinds — is still rejected: lifting the
 * `canId == null` restriction only adds this one pairing, it does not open the
 * door to arbitrary mixed kinds at one address.
 */
private fun resolveLinkKind(address: String, kinds: Set<ProtocolKind>): ProtocolKind = when {
    kinds.size == 1 -> kinds.first()
    kinds == setOf(ProtocolKind.VESC, ProtocolKind.VESC_BMS) -> ProtocolKind.VESC
    else -> throw IllegalArgumentException(
        "Address $address resolves to conflicting protocol kinds $kinds"
    )
}

/** A source's own kind, its vehicle-global index and its CAN id, before the link's own kind is known. */
private data class RawSource(val index: Int, val canId: Int?, val kind: ProtocolKind)

/**
 * Group a vehicle's packs AND controllers into links by distinct address. A
 * Begode's two packs and its motherboard share one address and form one link;
 * two independent BMS at two addresses form two links. Link order follows each
 * address's first appearance; each link's owned sources are ascending by index.
 *
 * Since Part C, a source may be CAN-forwarded (`canId != null`, relayed via
 * `FORWARD_CAN`) or hosted (`canId == null`, answered by the gateway itself,
 * e.g. `BMS_GET_VALUES`) — see [OwnedSource] and C §6. [resolveLinkKind] still
 * throws if the address's sources resolve to more than one [ProtocolKind],
 * except the one sanctioned VESC/VESC_BMS pairing — a single BLE link speaks
 * exactly one wire protocol, CAN forwarding does not change that. Also throws
 * if two sources at the same address claim the same CAN id — two nodes cannot
 * physically share one id behind one gateway. Pure — no BLE, no ordering
 * assumption on the input.
 */
fun planLinks(packs: List<Pack>, controllers: List<Controller>): List<LinkSpec> {
    data class Acc(
        val packs: MutableList<RawSource> = mutableListOf(),
        val controllers: MutableList<RawSource> = mutableListOf(),
        val kinds: MutableSet<ProtocolKind> = linkedSetOf()
    )
    val byAddress = LinkedHashMap<String, Acc>()
    for (p in packs.sortedBy { it.index }) {
        val acc = byAddress.getOrPut(p.bmsAddress) { Acc() }
        val kind = p.bmsType.protocolKind()
        acc.packs += RawSource(p.index, p.canId, kind)
        acc.kinds += kind
    }
    for (c in controllers.sortedBy { it.index }) {
        val acc = byAddress.getOrPut(c.address) { Acc() }
        val kind = c.controllerType.protocolKind()
        acc.controllers += RawSource(c.index, c.canId, kind)
        acc.kinds += kind
    }
    return byAddress.map { (address, acc) ->
        val resolvedKind = resolveLinkKind(address, acc.kinds)
        val allCanIds = (acc.packs + acc.controllers).mapNotNull { it.canId }
        val duplicates = allCanIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "Address $address assigns duplicate CAN id(s) $duplicates to more than one source"
        }
        fun RawSource.toOwned() = OwnedSource(
            globalIndex = index,
            canId = canId,
            // Only tag when it actually carries information beyond the link's
            // own kind: a CAN-forwarded source (canId != null) or a source
            // whose own kind differs from the resolved link kind (the hosted
            // VESC_BMS battery). A plain direct source that matches the link
            // stays untagged, so every pre-Part-C plan is byte-for-byte the
            // same OwnedSource it always was.
            kind = if (canId != null || kind != resolvedKind) kind else null
        )
        LinkSpec(
            address = address,
            protocolKind = resolvedKind,
            ownedPacks = acc.packs.map { it.toOwned() },
            ownedControllers = acc.controllers.map { it.toOwned() }
        )
    }
}
