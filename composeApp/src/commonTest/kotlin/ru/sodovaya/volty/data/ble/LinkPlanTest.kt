package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinkPlanTest {

    private fun pack(index: Int, addr: String, type: BmsType = BmsType.ANT_BMS) =
        Pack(index = index, label = "P$index", bmsType = type, bmsAddress = addr)

    @Test
    fun beGodeTwoPacksOneAddressIsOneLink() {
        val links = planLinks(listOf(pack(0, "AA", BmsType.BEGODE), pack(1, "AA", BmsType.BEGODE)))
        assertEquals(1, links.size)
        assertEquals("AA", links[0].address)
        assertEquals(listOf(0, 1), links[0].ownedPacks.map { it.globalIndex })
        assertEquals(ProtocolKind.BEGODE, links[0].protocolKind)
    }

    @Test
    fun twoAntPacksTwoAddressesAreTwoLinks() {
        val links = planLinks(listOf(pack(0, "AA"), pack(1, "BB")))
        assertEquals(2, links.size)
        assertEquals(listOf(0), links[0].ownedPacks.map { it.globalIndex })
        assertEquals(listOf(1), links[1].ownedPacks.map { it.globalIndex })
    }

    @Test
    fun localToGlobalTranslatesWithinALink() {
        val links = planLinks(listOf(pack(0, "AA", BmsType.BEGODE), pack(1, "AA", BmsType.BEGODE)))
        assertEquals(0, links[0].globalPackIndex(0))
        assertEquals(1, links[0].globalPackIndex(1))
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
        assertEquals(listOf(0, 1), links.first { it.address == "AA" }.ownedPacks.map { it.globalIndex })
        assertEquals(listOf(2), links.first { it.address == "BB" }.ownedPacks.map { it.globalIndex })
    }

    @Test
    fun ownedIndicesAreSortedByPackIndex() {
        // Packs may arrive unsorted; each link's owned indices must be ascending.
        val links = planLinks(listOf(pack(2, "AA"), pack(0, "AA"), pack(1, "AA")))
        assertEquals(listOf(0, 1, 2), links[0].ownedPacks.map { it.globalIndex })
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
        assertEquals(listOf(0), links[0].ownedPacks.map { it.globalIndex })
    }

    // --- Task 7: packs + controllers merged by address ---

    @Test
    fun controller_only_address_makes_a_controller_link() {
        val links = planLinks(emptyList(), listOf(
            Controller(0, "ESC", ControllerType.VESC, "AA")
        ))
        assertEquals(1, links.size)
        assertEquals(ProtocolKind.VESC, links[0].protocolKind)
        assertEquals(listOf(0), links[0].ownedControllers.map { it.globalIndex })
        assertTrue(links[0].ownedPacks.isEmpty())
    }

    @Test
    fun begode_pack_and_controller_share_one_link() {
        val links = planLinks(
            packs = listOf(Pack(0, "b0", BmsType.BEGODE, "WHEEL"), Pack(1, "b1", BmsType.BEGODE, "WHEEL")),
            controllers = listOf(Controller(0, "wheel", ControllerType.BEGODE, "WHEEL"))
        )
        assertEquals(1, links.size)
        assertEquals(ProtocolKind.BEGODE, links[0].protocolKind)
        assertEquals(listOf(0, 1), links[0].ownedPacks.map { it.globalIndex })
        assertEquals(listOf(0), links[0].ownedControllers.map { it.globalIndex })
    }

    @Test
    fun scooter_two_ubox_two_ant_is_four_links() {
        val links = planLinks(
            packs = listOf(Pack(0, "a0", BmsType.ANT_BMS, "ANT0"), Pack(1, "a1", BmsType.ANT_BMS, "ANT1")),
            controllers = listOf(Controller(0, "u0", ControllerType.VESC, "UBOX0"),
                                 Controller(1, "u1", ControllerType.VESC, "UBOX1"))
        )
        assertEquals(4, links.size)
    }

    @Test
    fun conflicting_direct_kinds_at_one_address_throw() {
        assertFailsWith<IllegalArgumentException> {
            planLinks(
                packs = listOf(Pack(0, "p", BmsType.ANT_BMS, "SAME")),
                controllers = listOf(Controller(0, "c", ControllerType.VESC, "SAME"))
            )
        }
    }

    @Test
    fun can_forwarded_source_is_rejected_in_part_A() {
        assertFailsWith<IllegalArgumentException> {
            planLinks(emptyList(), listOf(Controller(0, "c", ControllerType.VESC, "GW", canId = 41)))
        }
    }
}
