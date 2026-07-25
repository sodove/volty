package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.Pack

/**
 * One battery reachable over TWO links of the same vehicle — the contention
 * Part C §5 exists to resolve.
 *
 * The product owner's scooter is the shape this describes: an ANT smart BMS
 * with a single BLE central slot, reachable **directly** while parked and
 * **through the head unit** while riding (the head unit takes that slot for
 * itself and re-serves the battery over its own link as a hosted VESC-BMS).
 * Both packs carry the same [Pack.aliasGroup], so
 * [ru.sodovaya.volty.domain.stats.PackAggregator] already counts them once and
 * keeps the battery visible on whichever path is online (A §3.1) — that half is
 * done and is NOT re-implemented here.
 *
 * What this adds is which link to let go of, and when: once [hostedPackIndex]
 * is *actually reporting*, [directAddress] is released so the head unit can
 * hold the BMS; when the head-unit link drops, it comes back.
 */
internal data class AliasHandoff(
    /** The [Pack.aliasGroup] both paths share. */
    val aliasGroup: String,
    /** Address of the gateway link that hosts the battery (the head unit). */
    val gatewayAddress: String,
    /**
     * Vehicle-global index of the pack the gateway hosts. THE trigger: the
     * direct link is released when a sample lands on this index, never merely
     * because [gatewayAddress] came online — see §9.4 and the report.
     */
    val hostedPackIndex: Int,
    /** Address of the direct BLE link to the same battery — what gets released. */
    val directAddress: String,
    /** Vehicle-global index of the direct path's pack slot. */
    val directPackIndex: Int
)

/**
 * Plan the alias-path handoffs of one connection from its link specs and the
 * expanded pack list they were sized from. Pure — no BLE, no repository state.
 *
 * A handoff is emitted only when ALL of the following hold, and each condition
 * is a guard against releasing something the rider still needs:
 *
 *  - the hosted pack sits on a link that is a gateway ([isGatewayLink]) — a
 *    plain second BMS link is not a head unit and must never be yielded to;
 *  - the two packs share a non-null [Pack.aliasGroup] — they are the same
 *    physical battery, so `PackAggregator` will keep one of them visible
 *    across the swap. Without the shared group, releasing the direct link
 *    would simply delete a battery from the dashboard;
 *  - they sit on DIFFERENT links — releasing the link the hosted pack itself
 *    rides would take the head unit down with it;
 *  - the direct link owns **nothing else**: no controllers, and no packs
 *    outside this alias group. `disconnectLink` tears down the whole link, so
 *    a direct link that also carried, say, a controller would lose that
 *    controller's telemetry as collateral — silently, and only while riding.
 *
 * Several alias groups may hand off independently; each yields its own entry.
 * A group with more than one direct path yields the lowest-indexed one, which
 * is the same tie-break `PackAggregator.collapseAliases` uses, so the pack the
 * aggregate would have chosen is the pack whose link is released.
 */
internal fun planAliasHandoffs(specs: List<LinkSpec>, packs: List<Pack>): List<AliasHandoff> {
    if (specs.size < 2) return emptyList()
    val aliasByIndex: Map<Int, String> = packs.mapNotNull { p -> p.aliasGroup?.let { p.index to it } }.toMap()
    if (aliasByIndex.isEmpty()) return emptyList()
    val specByPackIndex: Map<Int, LinkSpec> =
        specs.flatMap { spec -> spec.ownedPacks.map { it.globalIndex to spec } }.toMap()

    val handoffs = mutableListOf<AliasHandoff>()
    for (gateway in specs) {
        if (!gateway.isGatewayLink) continue
        for (hosted in gateway.ownedPacks) {
            val alias = aliasByIndex[hosted.globalIndex] ?: continue
            val direct = packs
                .filter { it.aliasGroup == alias && it.index != hosted.globalIndex }
                .sortedBy { it.index }
                .firstOrNull { candidate ->
                    val spec = specByPackIndex[candidate.index] ?: return@firstOrNull false
                    spec.address != gateway.address &&
                        spec.ownedControllers.isEmpty() &&
                        spec.ownedPacks.all { aliasByIndex[it.globalIndex] == alias }
                } ?: continue
            val directSpec = specByPackIndex.getValue(direct.index)
            handoffs += AliasHandoff(
                aliasGroup = alias,
                gatewayAddress = gateway.address,
                hostedPackIndex = hosted.globalIndex,
                directAddress = directSpec.address,
                directPackIndex = direct.index
            )
        }
    }
    return handoffs
}
