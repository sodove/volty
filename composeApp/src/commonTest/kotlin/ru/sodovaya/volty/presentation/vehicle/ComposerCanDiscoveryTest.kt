package ru.sodovaya.volty.presentation.vehicle

import ru.sodovaya.volty.data.ble.hasCanBus
import ru.sodovaya.volty.data.ble.planLinks
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
     * A hosted `VESC_BMS` pack **on its own** is not a scan target, and this is
     * the review's Important 1.
     *
     * It looks like one — "hosted" means there is a gateway — but `planLinks`'
     * `resolveLinkKind` resolves a `{VESC_BMS}`-only address to
     * `ProtocolKind.VESC_BMS`, which `KableBmsRepository.discoverCanIds`
     * refuses. Offering it meant a button that appeared and could only ever
     * fail. A hosted battery that really is behind a gateway shares its address
     * with a VESC controller, and that is what makes it a target.
     */
    @Test
    fun `a hosted VESC BMS is not a scan target on its own`() {
        val d = draft(packs = listOf(pack(address = head)))
        assertNull(canScanTarget(d, setOf(head)))
    }

    @Test
    fun `a hosted VESC BMS beside its gateway controller still scans, through the controller`() {
        val d = draft(controllers = listOf(controller(address = head)), packs = listOf(pack(address = head)))
        assertEquals(head, canScanTarget(d, setOf(head)))
    }

    @Test
    fun `a live plain BMS is not a scan target`() {
        val d = draft(packs = listOf(pack(address = head, type = BmsType.ANT_BMS)))
        assertNull(canScanTarget(d, setOf(head)))
    }

    // ------------------------------------------------------------------
    // The two layers must agree — and each had only its own half pinned
    // ------------------------------------------------------------------

    /**
     * **The cross-layer invariant, over an exhaustive source set.**
     *
     * `canScanTarget` decides whether to OFFER a scan; `discoverCanIds` decides
     * whether to PERFORM one, and its criterion is the *planned link's* protocol
     * kind. Those are two different things — a draft holds sources, `planLinks`
     * resolves them into a link — and when each was stated separately they
     * disagreed for one shape (a lone hosted `VESC_BMS`): the button appeared
     * and the tap always failed.
     *
     * A 63-mutant sweep could not see it, because both halves were pinned by
     * their own layer's tests and **no fixture spanned the two**. Every mutant of
     * either side was killed by that side. This is the fixture that spans them,
     * and it is written as an implication over every controller type × every
     * battery type × three link arrangements rather than over one example —
     * an agreement argued from one shape is not an agreement.
     */
    @Test
    fun `a scan target's link is always one the repository will scan`() {
        var offered = 0
        for (ct in ControllerType.entries) {
            for (bt in BmsType.entries) {
                // Same link, two links, and each source alone: the arrangements
                // that change what `resolveLinkKind` sees.
                val arrangements = listOf(
                    draft(controllers = listOf(controller(type = ct)), packs = listOf(pack(type = bt))),
                    draft(
                        controllers = listOf(controller(type = ct)),
                        packs = listOf(pack(address = "OTHER", type = bt))
                    ),
                    draft(controllers = listOf(controller(type = ct))),
                    draft(packs = listOf(pack(type = bt)))
                )
                for (d in arrangements) {
                    // A draft the composer itself refuses is not a shape
                    // `planLinks` is ever handed — `validate`'s whole contract
                    // is that a non-blocking draft survives it. Two different
                    // kinds at one address is exactly such a refusal.
                    if (validate(d).any { it.blocking }) continue
                    val live = setOf(head, "OTHER")
                    val target = canScanTarget(d, live) ?: continue
                    offered++
                    // The link the connection layer would actually build for
                    // that address — the real planner, not a restatement.
                    val link = planLinks(d.toPacks(), d.toControllers()).single { it.address == target }
                    assertTrue(
                        link.protocolKind.hasCanBus,
                        "offered a scan on $target, whose link resolves to ${link.protocolKind} — " +
                            "discoverCanIds would refuse it (controller $ct, pack $bt)"
                    )
                }
            }
        }
        // Non-vacuity, and EXACT rather than a floor: the implication above is
        // trivially true if nothing is ever offered, and a `canScanTarget` that
        // quietly narrowed would still pass a `>=` written loosely enough.
        //
        // Only a VESC controller yields a target, so the arithmetic is:
        //   · same link, VESC controller + pack — 1, because every battery kind
        //     but VESC_BMS is a BLOCKING conflict at one address (`resolveLinkKind`
        //     sanctions {VESC, VESC_BMS} and nothing else);
        //   · two links — one per battery kind, none of them conflicting;
        //   · controller alone — the same draft once per battery kind of the loop;
        //   · pack alone — none: no controller, no target.
        assertEquals(
            1 + 2 * BmsType.entries.size,
            offered,
            "the set of shapes that get a scan offered changed — re-derive it rather than relaxing this"
        )
    }
}
