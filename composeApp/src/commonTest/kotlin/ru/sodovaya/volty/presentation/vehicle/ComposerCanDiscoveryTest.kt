package ru.sodovaya.volty.presentation.vehicle

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * `ComposerCanDiscovery.kt` — the pure half of `G §3` flow 4 (G2 Task 5).
 *
 * `PING_CAN` answers with raw ids and nothing else, so everything a rider sees
 * is derived here, and every rule below is one a Compose test could not reach.
 */
@OptIn(ExperimentalTime::class)
class ComposerCanDiscoveryTest {

    private val head = "HEAD:UNIT"

    private fun draft(
        controllers: List<ControllerDraft> = emptyList(),
        packs: List<PackDraft> = emptyList()
    ) = VehicleDraft(packs = packs, controllers = controllers)

    private fun controller(
        key: String = "c0",
        address: String = head,
        canId: Int? = null,
        type: ControllerType = ControllerType.VESC
    ) = ControllerDraft(key = key, label = key, controllerType = type, address = address, canId = canId)

    private fun pack(
        key: String = "p0",
        address: String = head,
        canId: Int? = null,
        type: BmsType = BmsType.VESC_BMS
    ) = PackDraft(key = key, label = key, bmsType = type, address = address, canId = canId)

    // ------------------------------------------------------------------
    // Candidates
    // ------------------------------------------------------------------

    /**
     * The friendly numbering of `G §9.1`: the head unit is "1", so the first
     * node found is "2". Derived from the SORTED id list rather than from the
     * draft, so a row does not renumber between two taps.
     */
    @Test
    fun `nodes are sorted, de-duplicated and numbered from two`() {
        val out = canCandidates(draft(), head, listOf(24, 10, 24, 11))
        val nodes = out.filter { it.kind == CanCandidateKind.NODE }
        assertEquals(listOf(10, 11, 24), nodes.map { it.canId })
        assertEquals(listOf(2, 3, 4), nodes.map { it.ordinal })
    }

    @Test
    fun `a node already in the draft comes back already added`() {
        val d = draft(controllers = listOf(controller(canId = 10)))
        val out = canCandidates(d, head, listOf(10, 11))
        assertTrue(out.first { it.canId == 10 }.alreadyAdded)
        assertTrue(!out.first { it.canId == 11 }.alreadyAdded)
    }

    /** A node taken as a BATTERY is taken just as much as one taken as a controller. */
    @Test
    fun `a node taken by a pack is already added too`() {
        val d = draft(packs = listOf(pack(canId = 11)))
        val out = canCandidates(d, head, listOf(10, 11))
        assertTrue(out.first { it.canId == 11 }.alreadyAdded)
    }

    /** A node on a DIFFERENT link is a different device and must still be offered. */
    @Test
    fun `a matching can id on another link does not take this one`() {
        val d = draft(controllers = listOf(controller(address = "OTHER", canId = 10)))
        val out = canCandidates(d, head, listOf(10))
        assertTrue(!out.first { it.canId == 10 }.alreadyAdded)
    }

    @Test
    fun `the hosted battery is offered last and carries no can id`() {
        val out = canCandidates(draft(), head, listOf(10, 11))
        val last = out.last()
        assertEquals(CanCandidateKind.HOSTED_BATTERY, last.kind)
        assertNull(last.canId)
        assertNull(last.ordinal)
    }

    /**
     * The [ComposerIssue.AmbiguousGatewaySource] guard, at the one place this
     * task could walk into it: the hosted battery is the ONLY candidate whose
     * added source carries `canId == null`, and a second one on the same link
     * is the blocking shape (two "the head unit itself"s, whose requests are
     * byte-identical and whose currents `MotionAggregator` sums).
     */
    @Test
    fun `the hosted battery is already added once the link has a null-id pack`() {
        val d = draft(packs = listOf(pack(canId = null)))
        val hosted = canCandidates(d, head, listOf(10)).last()
        assertEquals(CanCandidateKind.HOSTED_BATTERY, hosted.kind)
        assertTrue(hosted.alreadyAdded)
    }

    /** A CAN-forwarded pack is not the hosted one — it has an id. */
    @Test
    fun `a can-forwarded pack does not take the hosted slot`() {
        val d = draft(packs = listOf(pack(canId = 10)))
        assertTrue(!canCandidates(d, head, listOf(10)).last().alreadyAdded)
    }

    /**
     * An empty answer is a real answer — the gateway replied and its bus is
     * empty — and its hosted battery is still offerable. The screen tells that
     * apart from "not scanned" by looking at the scan state, never at the list.
     */
    @Test
    fun `an empty bus still offers the hosted battery`() {
        val out = canCandidates(draft(), head, emptyList())
        assertEquals(1, out.size)
        assertEquals(CanCandidateKind.HOSTED_BATTERY, out.single().kind)
    }

    @Test
    fun `labels name the friendly thing, per half`() {
        val node = CanCandidate(canId = 10, kind = CanCandidateKind.NODE, ordinal = 2, alreadyAdded = false)
        val hosted = CanCandidate(canId = null, kind = CanCandidateKind.HOSTED_BATTERY, ordinal = null, alreadyAdded = false)
        assertEquals("Controller 2", canCandidateLabel(node, asBattery = false))
        assertEquals("Battery CAN 10", canCandidateLabel(node, asBattery = true))
        assertEquals("Hosted battery", canCandidateLabel(hosted, asBattery = true))
        // The hosted battery is a battery whichever way it is asked for — the
        // screen never offers it as a controller.
        assertEquals("Hosted battery", canCandidateLabel(hosted, asBattery = false))
    }

    // ------------------------------------------------------------------
    // Whether a scan is possible at all
    // ------------------------------------------------------------------

    private fun vehicle(
        controllers: List<Controller> = emptyList(),
        packs: List<Pack> = emptyList()
    ) = Vehicle(
        id = "v1",
        name = "Scooter",
        iconKey = "scooter",
        packs = packs,
        controllers = controllers,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochMilliseconds(0)
    )

    @Test
    fun `live addresses are empty unless connected`() {
        val v = vehicle(controllers = listOf(Controller(0, "c", ControllerType.VESC, head)))
        assertEquals(emptySet(), liveLinkAddresses(v, ConnectionState.Idle))
        assertEquals(emptySet(), liveLinkAddresses(v, ConnectionState.Connecting(v)))
        // Reconnecting deliberately does NOT count: a scan needs a live link,
        // and offering the button mid-reconnect produces a failure the rider
        // can do nothing about.
        assertEquals(emptySet(), liveLinkAddresses(v, ConnectionState.Reconnecting(1, "drop")))
        assertEquals(emptySet(), liveLinkAddresses(null, ConnectionState.Connected(null)))
    }

    @Test
    fun `live addresses cover every source of the vehicle`() {
        val v = vehicle(
            controllers = listOf(Controller(0, "c", ControllerType.VESC, head)),
            packs = listOf(Pack(0, "p", BmsType.ANT_BMS, "ANT:1"))
        )
        assertEquals(setOf(head, "ANT:1"), liveLinkAddresses(v, ConnectionState.Connected(v)))
    }

    @Test
    fun `a blank address is not a live link`() {
        val v = vehicle(controllers = listOf(Controller(0, "c", ControllerType.VESC, "")))
        assertEquals(emptySet(), liveLinkAddresses(v, ConnectionState.Connected(v)))
    }

    @Test
    fun `the scan target is a live VESC controller`() {
        val d = draft(controllers = listOf(controller(address = head)))
        assertEquals(head, canScanTarget(d, setOf(head)))
    }

    @Test
    fun `there is no scan target when nothing is live`() {
        val d = draft(controllers = listOf(controller(address = head)))
        assertNull(canScanTarget(d, emptySet()))
    }

    /** A Begode has no CAN bus, and `PING_CAN` is a VESC command. */
    @Test
    fun `a live non-VESC controller is not a scan target`() {
        val d = draft(controllers = listOf(controller(type = ControllerType.BEGODE)))
        assertNull(canScanTarget(d, setOf(head)))
    }

    /** Controllers first — a gateway is a controller. */
    @Test
    fun `a controller wins over a pack on another live link`() {
        val d = draft(
            controllers = listOf(controller(address = head)),
            packs = listOf(pack(address = "OTHER"))
        )
        assertEquals(head, canScanTarget(d, setOf(head, "OTHER")))
    }

    /**
     * A vehicle whose only VESC-speaking source is a hosted VESC-BMS still has
     * a gateway behind it — that is the whole meaning of "hosted" — so the scan
     * is offered rather than refused for want of a controller row.
     */
    @Test
    fun `a live hosted VESC BMS is a scan target on its own`() {
        val d = draft(packs = listOf(pack(address = head)))
        assertEquals(head, canScanTarget(d, setOf(head)))
    }

    @Test
    fun `a live plain BMS is not a scan target`() {
        val d = draft(packs = listOf(pack(address = head, type = BmsType.ANT_BMS)))
        assertNull(canScanTarget(d, setOf(head)))
    }
}
