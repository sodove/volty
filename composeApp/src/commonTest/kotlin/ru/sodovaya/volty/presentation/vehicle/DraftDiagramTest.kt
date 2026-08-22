package ru.sodovaya.volty.presentation.vehicle

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.data.ble.ProtocolKind
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType

class DraftDiagramTest {

    @Test
    fun `CAN sources are children of the one direct gateway instead of BLE-link peers`() {
        val draft = VehicleDraft(
            controllers = listOf(
                controller("gateway", "Head unit", "AA", canId = null),
                controller("motor", "uBox", "AA", canId = 21)
            ),
            packs = listOf(pack("battery", "ANT bridge", "AA", canId = 47))
        )

        val link = draftDiagram(draft).children.single()
        val gateway = link.children.single { "gateway" in it.sourceKeys }

        assertEquals(setOf("motor", "battery"), gateway.children.flatMap { it.sourceKeys }.toSet())
        assertEquals(listOf("gateway"), link.children.flatMap { it.sourceKeys })
        assertTrue(gateway.children.all { it.canId != null })
    }

    @Test
    fun `a Begode wheel is one source node carrying both roles`() {
        val draft = VehicleDraft(
            controllers = listOf(
                controller("controller", "ET Max", "WHEEL", ControllerType.BEGODE)
            ),
            packs = listOf(pack("pack", "ET Max", "WHEEL", bmsType = BmsType.BEGODE))
        )

        val link = draftDiagram(draft).children.single()
        val wheel = link.children.single()

        assertEquals(DiagramNodeKind.BOTH, wheel.kind)
        assertEquals(setOf("controller", "pack"), wheel.sourceKeys.toSet())
        assertEquals(ControllerType.BEGODE, wheel.controllerType)
        assertEquals(BmsType.BEGODE, wheel.bmsType)
        assertEquals("WHEEL", wheel.address)
    }

    @Test
    fun `a Leaperkim wheel with two branches is one source node carrying both roles`() {
        val draft = VehicleDraft(
            controllers = listOf(
                controller("controller", "Veteran", "WHEEL", ControllerType.VETERAN)
            ),
            packs = listOf(
                pack("pack-a", "Veteran 1", "WHEEL", bmsType = BmsType.LEAPERKIM),
                pack("pack-b", "Veteran 2", "WHEEL", bmsType = BmsType.LEAPERKIM)
            )
        )

        val wheel = draftDiagram(draft).children.single().children.single()

        assertEquals(DiagramNodeKind.BOTH, wheel.kind)
        assertEquals(setOf("controller", "pack-a", "pack-b"), wheel.sourceKeys.toSet())
        assertEquals(ControllerType.VETERAN, wheel.controllerType)
        assertEquals(BmsType.LEAPERKIM, wheel.bmsType)
    }

    @Test
    fun `one Begode controller and two matching pack rows remain three visible sources`() {
        val draft = VehicleDraft(
            controllers = listOf(
                controller("controller", "ET Max", "WHEEL", ControllerType.BEGODE)
            ),
            packs = listOf(
                pack("pack-a", "Branch A", "WHEEL", bmsType = BmsType.BEGODE),
                pack("pack-b", "Branch B", "WHEEL", bmsType = BmsType.BEGODE)
            )
        )

        val sources = draftDiagram(draft).children.single().children

        assertEquals(
            listOf(DiagramNodeKind.CONTROLLER, DiagramNodeKind.BATTERY, DiagramNodeKind.BATTERY),
            sources.map { it.kind }
        )
        assertEquals(
            listOf(setOf("controller"), setOf("pack-a"), setOf("pack-b")),
            sources.map { it.sourceKeys.toSet() }
        )
        assertTrue(sources.none { it.kind == DiagramNodeKind.BOTH })
    }

    @Test
    fun `two BLE addresses are sibling links and every source exposes role type and address`() {
        val draft = VehicleDraft(
            controllers = listOf(controller("controller", "VESC", "CTRL")),
            packs = listOf(pack("pack", "ANT", "BMS", bmsType = BmsType.ANT_BMS))
        )

        val root = draftDiagram(draft)
        val links = root.children

        assertEquals(DiagramNodeKind.PHONE, root.kind)
        assertEquals(listOf("CTRL", "BMS"), links.map { it.address })
        assertTrue(links.all { it.kind == DiagramNodeKind.BLE_LINK })
        assertEquals(DiagramNodeKind.CONTROLLER, links[0].children.single().kind)
        assertEquals(ControllerType.VESC, links[0].children.single().controllerType)
        assertEquals("CTRL", links[0].children.single().address)
        assertEquals(DiagramNodeKind.BATTERY, links[1].children.single().kind)
        assertEquals(BmsType.ANT_BMS, links[1].children.single().bmsType)
        assertEquals("BMS", links[1].children.single().address)
    }

    @Test
    fun `a controller-derived battery is visible as its own battery source on that link`() {
        val draft = VehicleDraft(
            controllers = listOf(
                ControllerDraft(
                    key = "controller",
                    label = "VESC",
                    controllerType = ControllerType.VESC,
                    address = "CTRL",
                    canId = 21,
                    derivedBattery = DerivedBatteryChoice.ON
                )
            )
        )

        val sources = draftDiagram(draft).children.single().children

        assertEquals(listOf(DiagramNodeKind.CONTROLLER, DiagramNodeKind.DERIVED_BATTERY), sources.map { it.kind })
        assertEquals("CTRL", sources[1].address)
        assertEquals(21, sources[1].canId)
    }

    @Test
    fun `every ComposerIssue variant is attached to exactly its affected source nodes`() {
        val draft = VehicleDraft(
            controllers = listOf(
                controller("direct", "Gateway", "AA"),
                controller("can-a", "CAN A", "AA", canId = 21),
                controller("can-b", "CAN B", "AA", canId = 21),
                controller("blank", "Unsupported", "", controllerType = ControllerType.FARDRIVER)
            ),
            packs = listOf(
                pack("hosted", "Hosted", "AA"),
                pack("can-pack", "CAN pack", "AA", canId = 21),
                pack("other", "Other", "BB", bmsType = BmsType.ANT_BMS)
            )
        )
        val issues = listOf(
            ComposerIssue.ConflictingKinds("AA", setOf(ProtocolKind.VESC, ProtocolKind.ANT)),
            ComposerIssue.DuplicateCanId("AA", 21),
            ComposerIssue.AmbiguousGatewaySource("AA", listOf("direct")),
            ComposerIssue.BlankAddress("blank"),
            ComposerIssue.NoControllerDecoder("blank", ControllerType.FARDRIVER),
            ComposerIssue.HostlessVescBms("other"),
            ComposerIssue.UnroutableGateway("AA", ProtocolKind.VESC),
            ComposerIssue.DuplicatePack("hosted", "other"),
            ComposerIssue.PhantomGatewayController("direct")
        )

        val nodes = draftDiagram(draft, issues).descendants()

        issues.forEach { issue ->
            val expectedKeys = issue.affectedKeys(draft).toSet()
            val attachedNodes = nodes.filter { issue in it.issues }
            assertTrue(attachedNodes.isNotEmpty(), "$issue was not attached to any node")
            assertEquals(
                expectedKeys,
                attachedNodes.flatMap { it.sourceKeys }.toSet(),
                "$issue was attached to the wrong source node"
            )
            expectedKeys.forEach { key ->
                val node = assertNotNull(nodes.singleOrNull { key in it.sourceKeys }, "$key appears once")
                assertContains(node.issues, issue)
            }
        }
    }

    @Test
    fun `an incompatible duplicate address remains two visibly repeated links`() {
        val draft = VehicleDraft(
            controllers = listOf(controller("controller", "VESC", "DUPLICATE")),
            packs = listOf(pack("pack", "ANT", "DUPLICATE", bmsType = BmsType.ANT_BMS))
        )

        val links = draftDiagram(draft).children

        assertEquals(listOf("DUPLICATE", "DUPLICATE"), links.map { it.address })
        assertEquals(listOf(setOf("controller"), setOf("pack")), links.map { it.sourceKeysDeep() })
        val conflict = validate(draft).single { it is ComposerIssue.ConflictingKinds }
        assertTrue(links.all { link -> link.descendants().any { conflict in it.issues } })
    }

    @Test
    fun `blank Begode controller and pack rows remain separate instead of inferring a wheel`() {
        val draft = VehicleDraft(
            controllers = listOf(
                controller("controller", "Incomplete controller", "", ControllerType.BEGODE)
            ),
            packs = listOf(
                pack("pack", "Incomplete pack", "", bmsType = BmsType.BEGODE)
            )
        )

        val links = draftDiagram(draft).children

        assertEquals(2, links.size)
        assertEquals(listOf("", ""), links.map { it.address })
        assertEquals(listOf(setOf("controller"), setOf("pack")), links.map { it.sourceKeysDeep() })
        assertTrue(links.flatMap { it.descendants() }.all { node ->
            node.sourceKeys.isEmpty() || node.issues.any { it is ComposerIssue.BlankAddress }
        })
    }

    private fun controller(
        key: String,
        label: String,
        address: String,
        controllerType: ControllerType = ControllerType.VESC,
        canId: Int? = null
    ) = ControllerDraft(
        key = key,
        label = label,
        controllerType = controllerType,
        address = address,
        canId = canId,
        derivedBattery = DerivedBatteryChoice.OFF
    )

    private fun pack(
        key: String,
        label: String,
        address: String,
        canId: Int? = null,
        bmsType: BmsType = BmsType.VESC_BMS
    ) = PackDraft(
        key = key,
        label = label,
        bmsType = bmsType,
        address = address,
        canId = canId
    )

    private fun DiagramNode.descendants(): List<DiagramNode> =
        listOf(this) + children.flatMap { it.descendants() }

    private fun DiagramNode.sourceKeysDeep(): Set<String> =
        descendants().flatMap { it.sourceKeys }.toSet()
}
