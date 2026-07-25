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

    /**
     * Pins the untagged half of Task 3's rule directly at the `planLinks`
     * level (not just through `KableBmsRepositoryVescTest`, which only
     * exercises this for controllers — its `effectiveLinkSpecs` rebuilds
     * `ownedPacks` from scratch and would hide a pack-side regression here).
     * A direct pack whose own kind IS the link's kind must come out with
     * `kind == null`, so every pre-Part-C `OwnedSource(index)` still equals a
     * freshly planned one.
     */
    @Test
    fun ordinary_direct_pack_source_leaves_kind_untagged() {
        val links = planLinks(listOf(pack(0, "AA", BmsType.ANT_BMS)))
        assertEquals(
            LinkSpec(address = "AA", protocolKind = ProtocolKind.ANT, ownedPacks = listOf(OwnedSource(0))),
            links.single()
        )
    }

    /** Controller-side sibling of [ordinary_direct_pack_source_leaves_kind_untagged]. */
    @Test
    fun ordinary_direct_controller_source_leaves_kind_untagged() {
        val links = planLinks(emptyList(), listOf(Controller(0, "ESC", ControllerType.VESC, "AA")))
        assertEquals(
            LinkSpec(address = "AA", protocolKind = ProtocolKind.VESC, ownedControllers = listOf(OwnedSource(0))),
            links.single()
        )
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

    /**
     * The message is asserted, not just the type: [planLinks] raises
     * `IllegalArgumentException` from TWO places — the kind conflict here and
     * the duplicate-CAN-id guard below — so a throw for the wrong reason would
     * satisfy the type alone and leave the guard under test unexercised.
     */
    @Test
    fun conflicting_direct_kinds_at_one_address_throw() {
        val e = assertFailsWith<IllegalArgumentException> {
            planLinks(
                packs = listOf(Pack(0, "p", BmsType.ANT_BMS, "SAME")),
                controllers = listOf(Controller(0, "c", ControllerType.VESC, "SAME"))
            )
        }
        assertTrue(
            e.message.orEmpty().contains("resolves to conflicting protocol kinds"),
            "must fail on the KIND conflict, not on some other guard: ${e.message}"
        )
    }

    // --- Task 3 (Part C): CAN-forwarded controllers + hosted battery ---

    /**
     * Replaces `can_forwarded_source_is_rejected_in_part_A` (Part A/B): Part C
     * lifts the `canId == null` assertion, so a single CAN-forwarded controller
     * must now plan cleanly instead of throwing. Deliberately replaced, not
     * deleted — see task-3-report.md.
     */
    @Test
    fun can_forwarded_controller_is_accepted_in_part_C() {
        val links = planLinks(emptyList(), listOf(Controller(0, "c", ControllerType.VESC, "GW", canId = 41)))
        assertEquals(1, links.size)
        assertEquals(ProtocolKind.VESC, links[0].protocolKind)
        assertEquals(listOf(OwnedSource(0, canId = 41, kind = ProtocolKind.VESC)), links[0].ownedControllers)
    }

    /**
     * The product owner's actual scooter, the shape the whole of Part C exists
     * for: one head-unit link owning three sources — two uBox controllers at
     * distinct CAN ids, and the head unit's own hosted battery (VESC_BMS,
     * answered directly — never forwarded, hence no canId). One [LinkSpec],
     * link kind VESC (the gateway's own wire protocol), each owned source
     * tagged with its OWN decode kind.
     */
    @Test
    fun scooter_two_can_controllers_plus_hosted_bms_is_one_gateway_link() {
        val links = planLinks(
            packs = listOf(Pack(index = 2, label = "Battery", bmsType = BmsType.VESC_BMS, bmsAddress = "GW")),
            controllers = listOf(
                Controller(0, "Front uBox", ControllerType.VESC, "GW", canId = 41),
                Controller(1, "Rear uBox", ControllerType.VESC, "GW", canId = 42)
            )
        )
        assertEquals(1, links.size, "one BLE address must still be one link")
        val gw = links[0]
        assertEquals("GW", gw.address)
        assertEquals(ProtocolKind.VESC, gw.protocolKind, "the link speaks the gateway's own (VESC) wire protocol")

        assertEquals(
            listOf(
                OwnedSource(0, canId = 41, kind = ProtocolKind.VESC),
                OwnedSource(1, canId = 42, kind = ProtocolKind.VESC)
            ),
            gw.ownedControllers
        )
        assertEquals(
            listOf(OwnedSource(2, canId = null, kind = ProtocolKind.VESC_BMS)),
            gw.ownedPacks,
            "the hosted battery has no canId — the gateway answers BMS_GET_VALUES itself"
        )
    }

    /**
     * The rule Task 3 must NOT weaken: a single BLE link still speaks exactly
     * one LINK protocol, even once its owned sources may be CAN-forwarded.
     * Two CAN-forwarded controllers of genuinely different, non-hosted kinds
     * at one gateway address is still a conflict — lifting the canId
     * restriction only ever ADDS the one sanctioned VESC/VESC_BMS pairing
     * (§6), it does not open the gate to arbitrary mixed kinds.
     */
    @Test
    fun conflicting_kinds_among_can_forwarded_controllers_still_throw() {
        val e = assertFailsWith<IllegalArgumentException> {
            planLinks(
                emptyList(),
                listOf(
                    Controller(0, "a", ControllerType.VESC, "GW", canId = 1),
                    Controller(1, "b", ControllerType.FARDRIVER, "GW", canId = 2)
                )
            )
        }
        // The distinct CAN ids here make the duplicate-id guard inapplicable,
        // so without pinning the message this passes on a throw from it.
        assertTrue(
            e.message.orEmpty().contains("resolves to conflicting protocol kinds"),
            "must fail on the KIND conflict, not on some other guard: ${e.message}"
        )
    }

    /** Two nodes cannot physically share one CAN id behind the same gateway. */
    @Test
    fun duplicate_can_id_at_one_gateway_address_throws() {
        val e = assertFailsWith<IllegalArgumentException> {
            planLinks(
                emptyList(),
                listOf(
                    Controller(0, "a", ControllerType.VESC, "GW", canId = 5),
                    Controller(1, "b", ControllerType.VESC, "GW", canId = 5)
                )
            )
        }
        // Both controllers are VESC, so the kind guard cannot fire here — but
        // asserting the message is what makes that a fact of the test rather
        // than of the fixture someone edits later.
        assertTrue(
            e.message.orEmpty().contains("duplicate CAN id(s) [5]"),
            "must name the duplicated id, not merely refuse: ${e.message}"
        )
    }
}
