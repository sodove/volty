package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkPlanTest {

    private fun pack(index: Int, addr: String, type: BmsType = BmsType.ANT_BMS) =
        Pack(index = index, label = "P$index", bmsType = type, bmsAddress = addr)

    @Test
    fun beGodeTwoPacksOneAddressIsOneLink() {
        val links = planLinks(listOf(pack(0, "AA", BmsType.BEGODE), pack(1, "AA", BmsType.BEGODE)))
        assertEquals(1, links.size)
        assertEquals("AA", links[0].address)
        assertEquals(listOf(0, 1), links[0].ownedIndices)
        assertEquals(BmsType.BEGODE, links[0].bmsType)
    }

    @Test
    fun twoAntPacksTwoAddressesAreTwoLinks() {
        val links = planLinks(listOf(pack(0, "AA"), pack(1, "BB")))
        assertEquals(2, links.size)
        assertEquals(listOf(0), links[0].ownedIndices)
        assertEquals(listOf(1), links[1].ownedIndices)
    }

    @Test
    fun localToGlobalTranslatesWithinALink() {
        val links = planLinks(listOf(pack(0, "AA", BmsType.BEGODE), pack(1, "AA", BmsType.BEGODE)))
        assertEquals(0, links[0].globalIndex(0))
        assertEquals(1, links[0].globalIndex(1))
    }

    @Test
    fun mixedBegodePlusAuxSplitsCorrectly() {
        val links = planLinks(
            listOf(
                pack(0, "AA", BmsType.BEGODE),
                pack(1, "AA", BmsType.BEGODE),
                pack(2, "BB", BmsType.JBD_BMS)
            )
        )
        assertEquals(2, links.size)
        assertEquals(listOf(0, 1), links.first { it.address == "AA" }.ownedIndices)
        assertEquals(listOf(2), links.first { it.address == "BB" }.ownedIndices)
    }

    @Test
    fun ownedIndicesAreSortedByPackIndex() {
        // Packs may arrive unsorted; each link's owned indices must be ascending.
        val links = planLinks(listOf(pack(2, "AA"), pack(0, "AA"), pack(1, "AA")))
        assertEquals(listOf(0, 1, 2), links[0].ownedIndices)
    }

    @Test
    fun linkOrderFollowsFirstAppearanceOfEachAddress() {
        val links = planLinks(listOf(pack(0, "BB"), pack(1, "AA")))
        assertEquals(listOf("BB", "AA"), links.map { it.address })
    }

    @Test
    fun aSingleLinkVehicleYieldsExactlyOneLink() {
        val links = planLinks(listOf(pack(0, "AA", BmsType.JK_BMS)))
        assertEquals(1, links.size)
        assertEquals(listOf(0), links[0].ownedIndices)
    }
}
