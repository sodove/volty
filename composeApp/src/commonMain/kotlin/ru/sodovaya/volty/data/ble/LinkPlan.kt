package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack

/**
 * One BLE link: a distinct address, the BMS type behind it, and the vehicle's
 * global pack indices it is responsible for, in local order. A session speaks
 * local indices (0-based within its own protocol); [globalIndex] maps them to
 * the vehicle's pack indices the orchestrator is keyed by.
 */
data class LinkSpec(
    val address: String,
    val bmsType: BmsType,
    val ownedIndices: List<Int>
) {
    fun globalIndex(local: Int): Int = ownedIndices[local]
}

/**
 * Group a vehicle's packs into links by distinct address. A Begode's two
 * packs share one address and form one link owning [0, 1]; a group of two
 * independent BMS at two addresses forms two links owning [0] and [1]. Link
 * order follows each address's first appearance; each link's owned indices
 * are ascending. Pure — no BLE, no ordering assumption on the input.
 */
fun planLinks(packs: List<Pack>): List<LinkSpec> {
    val byAddress = LinkedHashMap<String, MutableList<Pack>>()
    for (p in packs) byAddress.getOrPut(p.bmsAddress) { mutableListOf() }.add(p)
    return byAddress.map { (address, group) ->
        val sorted = group.sortedBy { it.index }
        LinkSpec(
            address = address,
            bmsType = sorted.first().bmsType,
            ownedIndices = sorted.map { it.index }
        )
    }
}
