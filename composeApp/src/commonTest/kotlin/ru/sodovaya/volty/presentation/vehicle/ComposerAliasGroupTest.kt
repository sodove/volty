package ru.sodovaya.volty.presentation.vehicle

import ru.sodovaya.volty.data.ble.planAliasHandoffs
import ru.sodovaya.volty.data.ble.planLinks
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Part G2 Task 4 — alias groups, the duplicate heuristic and the phantom
 * head-unit advisory. All pure, for the reason the rest of the composer is:
 * Compose is not unit-testable in this repo, so nothing a rider will act on may
 * live in a `@Composable`.
 *
 * The fixture throughout is the product owner's scooter (`01-linking §3`
 * archetype 1/1b): a head unit fronting two uBoxes on CAN and hosting a
 * VESC-BMS, plus the ANT reachable directly over its own BLE link.
 */
@OptIn(ExperimentalTime::class)
class ComposerAliasGroupTest {

    private companion object {
        const val HU = "HU:00"
        const val ANT = "AN:01"
        const val ANT2 = "AN:02"
    }

    private val createdAt = Instant.fromEpochSeconds(1_700_000_000)

    private fun vehicle(packs: List<Pack>, controllers: List<Controller>) = Vehicle(
        id = "v1",
        name = "Scooter",
        iconKey = "generic",
        packs = packs,
        controllers = controllers,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = createdAt
    )

    /**
     * The head unit as the picker creates it — a controller row with a null
     * `canId` — plus the two uBoxes it reaches by CAN, the battery it hosts, and
     * the same battery's own direct BLE link.
     */
    private fun scooter() = vehicle(
        packs = listOf(
            Pack(0, "ANT", BmsType.ANT_BMS, ANT),
            Pack(1, "Hosted", BmsType.VESC_BMS, HU, canId = null)
        ),
        controllers = listOf(
            Controller(0, "Head unit", ControllerType.VESC, HU, canId = null),
            Controller(1, "uBox 1", ControllerType.VESC, HU, canId = 41),
            Controller(2, "uBox 2", ControllerType.VESC, HU, canId = 42)
        )
    )

    private fun VehicleDraft.packKey(label: String) = packs.first { it.label == label }.key
    private fun VehicleDraft.controllerKey(label: String) = controllers.first { it.label == label }.key
    private fun VehicleDraft.aliasOf(label: String) = packs.first { it.label == label }.aliasGroup

    // -----------------------------------------------------------------------
    // 1. Explicit grouping — the deliverable
    // -----------------------------------------------------------------------

    @Test
    fun `grouping two packs gives them one shared alias group`() {
        val d = draftOf(scooter())
        val out = d.groupPacks(d.packKey("ANT"), d.packKey("Hosted"))

        val a = out.aliasOf("ANT")
        val b = out.aliasOf("Hosted")
        assertTrue(a != null, "the direct path is grouped")
        assertEquals(a, b, "and it is the SAME group as the hosted path")
    }

    /** Two adds must not collide, so the id comes from the draft's own counter. */
    @Test
    fun `a second group gets an id of its own`() {
        val d = draftOf(
            vehicle(
                packs = listOf(
                    Pack(0, "A", BmsType.ANT_BMS, ANT),
                    Pack(1, "B", BmsType.VESC_BMS, HU),
                    Pack(2, "C", BmsType.ANT_BMS, ANT2),
                    Pack(3, "D", BmsType.VESC_BMS, "HU2")
                ),
                controllers = listOf(Controller(0, "C0", ControllerType.VESC, HU))
            )
        )
        val out = d.groupPacks(d.packKey("A"), d.packKey("B"))
            .let { it.groupPacks(it.packKey("C"), it.packKey("D")) }

        assertTrue(out.aliasOf("A") != out.aliasOf("C"), "two independent pairs are two groups")
        assertEquals(out.aliasOf("A"), out.aliasOf("B"))
        assertEquals(out.aliasOf("C"), out.aliasOf("D"))
    }

    /**
     * `aliasGroup` holds ONE id, so overlapping groups are not representable —
     * joining two of them has to merge rather than silently drop a member.
     */
    @Test
    fun `grouping across two existing groups merges them into one`() {
        val d = draftOf(
            vehicle(
                packs = listOf(
                    Pack(0, "A", BmsType.ANT_BMS, ANT),
                    Pack(1, "B", BmsType.VESC_BMS, HU),
                    Pack(2, "C", BmsType.ANT_BMS, ANT2),
                    Pack(3, "D", BmsType.VESC_BMS, "HU2")
                ),
                controllers = listOf(Controller(0, "C0", ControllerType.VESC, HU))
            )
        )
        val two = d.groupPacks(d.packKey("A"), d.packKey("B"))
            .let { it.groupPacks(it.packKey("C"), it.packKey("D")) }
        val merged = two.groupPacks(two.packKey("B"), two.packKey("C"))

        assertEquals(1, merged.packs.mapNotNull { it.aliasGroup }.toSet().size, "one group, not two")
        assertEquals(4, merged.packs.count { it.aliasGroup != null }, "and nobody was dropped")
    }

    @Test
    fun `grouping a pack with itself, or with a key that is not there, changes nothing`() {
        val d = draftOf(scooter())
        assertEquals(d, d.groupPacks(d.packKey("ANT"), d.packKey("ANT")))
        assertEquals(d, d.groupPacks(d.packKey("ANT"), "no-such-key"))
    }

    /** A group of one is not a group — see [VehicleDraft.ungroupPack]. */
    @Test
    fun `ungrouping one side clears the other, because a group of one is not a group`() {
        val d = draftOf(scooter())
        val grouped = d.groupPacks(d.packKey("ANT"), d.packKey("Hosted"))
        val out = grouped.ungroupPack(grouped.packKey("ANT"))

        assertEquals(null, out.aliasOf("ANT"))
        assertEquals(null, out.aliasOf("Hosted"), "the survivor is not left claiming a path that is gone")
    }

    /** Three members: taking one out must leave the other two grouped. */
    @Test
    fun `ungrouping one of three leaves the remaining two grouped`() {
        val d = draftOf(
            vehicle(
                packs = listOf(
                    Pack(0, "A", BmsType.ANT_BMS, ANT, aliasGroup = "g"),
                    Pack(1, "B", BmsType.VESC_BMS, HU, aliasGroup = "g"),
                    Pack(2, "C", BmsType.ANT_BMS, ANT2, aliasGroup = "g")
                ),
                controllers = listOf(Controller(0, "C0", ControllerType.VESC, HU))
            )
        )
        val out = d.ungroupPack(d.packKey("A"))

        assertEquals(null, out.aliasOf("A"))
        assertEquals("g", out.aliasOf("B"))
        assertEquals("g", out.aliasOf("C"))
    }

    // -----------------------------------------------------------------------
    // 1b. …and the field is written back only once the rider has touched it
    // -----------------------------------------------------------------------

    /**
     * The rule [PackDraft.aliasEdited] exists for: an untouched grouping still
     * comes off the freshly-read row, exactly like the auto-filled cell count,
     * so a save cannot revert a value written underneath the open form.
     */
    @Test
    fun `an untouched alias group comes from the re-anchored row, not the load-time snapshot`() {
        val loaded = scooter()
        val d = draftOf(loaded).let { it.updatePack(it.packKey("ANT")) { p -> p.copy(label = "Передний") } }
        val fresh = loaded.copy(packs = loaded.packs.map { it.copy(aliasGroup = "written-underneath") })

        val out = d.reanchoredTo(fresh).toPacks()
        assertEquals("written-underneath", out[0].aliasGroup)
        assertEquals("Передний", out[0].label, "and the rider's own edit still lands")
    }

    @Test
    fun `a grouping the rider made survives a re-anchor that disagrees`() {
        val loaded = scooter()
        val d = draftOf(loaded).let { it.groupPacks(it.packKey("ANT"), it.packKey("Hosted")) }
        val fresh = loaded.copy(packs = loaded.packs.map { it.copy(aliasGroup = "written-underneath") })

        val out = d.reanchoredTo(fresh).toPacks()
        assertTrue(out[0].aliasGroup != "written-underneath", "the rider's word wins on the field they set")
        assertEquals(out[0].aliasGroup, out[1].aliasGroup)
    }

    @Test
    fun `ungrouping is written back as a null, not swallowed as untouched`() {
        val loaded = scooter().let { v ->
            v.copy(packs = v.packs.map { it.copy(aliasGroup = "stored") })
        }
        val d = draftOf(loaded)
        val out = d.ungroupPack(d.packKey("ANT")).reanchoredTo(loaded).toPacks()

        assertEquals(null, out[0].aliasGroup)
        assertEquals(null, out[1].aliasGroup)
    }

    // -----------------------------------------------------------------------
    // 2. The toggle's scope: which groups the C §5 handoff is even about
    // -----------------------------------------------------------------------

    @Test
    fun `a hosted-plus-direct group is a handoff group`() {
        val d = draftOf(scooter())
        val out = d.groupPacks(d.packKey("ANT"), d.packKey("Hosted"))
        assertEquals(1, out.handoffAliasGroups.size)
    }

    /**
     * Two direct BMS links are two batteries in parallel, whatever the rider
     * grouped: there is no gateway to yield anything to, so the toggle must not
     * appear.
     */
    @Test
    fun `two direct packs are not a handoff group even when grouped`() {
        val d = draftOf(
            vehicle(
                packs = listOf(Pack(0, "A", BmsType.ANT_BMS, ANT), Pack(1, "B", BmsType.ANT_BMS, ANT2)),
                controllers = emptyList()
            )
        )
        val out = d.groupPacks(d.packKey("A"), d.packKey("B"))
        assertEquals(emptyList(), out.handoffAliasGroups)
    }

    /** Ungrouped is not a group at all. */
    @Test
    fun `an ungrouped vehicle has no handoff group`() {
        assertEquals(emptyList(), draftOf(scooter()).handoffAliasGroups)
    }

    /**
     * **The test that spans the two layers.** The composer writes `aliasGroup`;
     * `planLinks` + `planAliasHandoffs` in `data/ble` read it — and each of them
     * is pinned only by its own tests, which is exactly how Task 5 shipped a
     * button whose two halves could never agree. So: group the pair the way a
     * rider would, project the draft the way the save does, and run the REAL
     * planners over the result.
     */
    @Test
    fun `a grouped alias pair is one the runtime plans a handoff for`() {
        val d = draftOf(scooter())
        val v = d.groupPacks(d.packKey("ANT"), d.packKey("Hosted"))
            .let { vehicle(it.toPacks(), it.toControllers()) }

        val handoffs = planAliasHandoffs(planLinks(v.packs, v.controllers), v.packs)

        assertEquals(1, handoffs.size, "the composer's grouping must reach the C §5 handoff")
        assertEquals(HU, handoffs.single().gatewayAddress)
        assertEquals(ANT, handoffs.single().directAddress, "the direct link is the one released")
    }

    /** The other direction of the same span: no grouping, no handoff. */
    @Test
    fun `the same vehicle without the grouping plans nothing`() {
        val v = draftOf(scooter()).let { vehicle(it.toPacks(), it.toControllers()) }
        assertEquals(emptyList(), planAliasHandoffs(planLinks(v.packs, v.controllers), v.packs))
    }

    // -----------------------------------------------------------------------
    // 3. The duplicate heuristic — both directions
    // -----------------------------------------------------------------------

    private fun packState(index: Int, address: String, volts: Float, cells: Int, online: Boolean = true) =
        PackState(
            pack = Pack(index, "p$index", BmsType.ANT_BMS, address),
            data = BmsData(voltage = volts, cellVoltages = List(cells) { volts / cells }),
            isOnline = online
        )

    /** [samples] pairs of (direct volts, hosted volts), oldest first. */
    private fun observed(draft: VehicleDraft, samples: List<Pair<Float, Float>>, cells: Int = 20) =
        samples.fold(ComposerTelemetry()) { t, (a, b) ->
            t.observing(
                draft,
                VehicleData(
                    packs = listOf(
                        packState(0, draft.packs[0].address, a, cells),
                        packState(1, draft.packs[1].address, b, cells)
                    )
                )
            )
        }

    /** Both paths reading one pack: identical to within the decoders' quantisation. */
    private val tracking = listOf(
        72.40f to 72.41f,
        72.38f to 72.39f,
        72.35f to 72.33f,
        72.30f to 72.31f,
        72.28f to 72.27f
    )

    @Test
    fun `a real duplicate is caught and offered as a grouping`() {
        val d = draftOf(scooter())
        val issues = validate(d, observed(d, tracking))

        val dup = issues.filterIsInstance<ComposerIssue.DuplicatePack>().single()
        assertEquals(setOf(d.packKey("ANT"), d.packKey("Hosted")), setOf(dup.keyA, dup.keyB))
        assertFalse(dup.blocking, "it is a question about hardware, not a contradiction")
    }

    /**
     * `G §9.2`'s named risk, in the shape it actually reaches this rule: the
     * product owner has TWO ANT packs, so a head unit hosting one of them sits
     * beside a direct link to the OTHER — same chemistry, same 20 cells, same
     * nominal voltage. They must not be nagged about.
     */
    @Test
    fun `two similar but distinct packs are left alone`() {
        val d = draftOf(scooter())
        // Same cell count, both near 72 V, and drifting apart the way two
        // independent packs do — 0.4 V is twice the 20S band.
        val distinct = listOf(
            72.40f to 72.00f,
            72.38f to 71.96f,
            72.35f to 71.93f,
            72.30f to 71.90f,
            72.28f to 71.85f
        )
        assertEquals(
            emptyList(),
            validate(d, observed(d, distinct)).filterIsInstance<ComposerIssue.DuplicatePack>()
        )
    }

    /**
     * The gate that carries the case telemetry cannot decide: two packs on two
     * DIRECT links tracking perfectly are two packs in parallel — the same
     * electrical node, so of course they track — and there is no gateway path
     * for them to be two paths to.
     */
    @Test
    fun `two direct packs tracking perfectly are never flagged`() {
        val d = draftOf(
            vehicle(
                packs = listOf(Pack(0, "A", BmsType.ANT_BMS, ANT), Pack(1, "B", BmsType.ANT_BMS, ANT2)),
                controllers = emptyList()
            )
        )
        val identical = List(DUPLICATE_SAMPLES) { 72.40f to 72.40f }
        assertEquals(
            emptyList(),
            validate(d, observed(d, identical)).filterIsInstance<ComposerIssue.DuplicatePack>()
        )
    }

    @Test
    fun `a different series-cell count is not the same pack`() {
        val d = draftOf(scooter())
        // 20 cells against 24, at voltages that would otherwise agree.
        val t = tracking.fold(ComposerTelemetry()) { acc, (a, b) ->
            acc.observing(
                d,
                VehicleData(
                    packs = listOf(
                        packState(0, d.packs[0].address, a, 20),
                        packState(1, d.packs[1].address, b, 24)
                    )
                )
            )
        }
        assertEquals(emptyList(), validate(d, t).filterIsInstance<ComposerIssue.DuplicatePack>())
    }

    /** A pack with no cell frames at all: online, voltage known, series count not. */
    private fun cellless(index: Int, address: String, volts: Float) = PackState(
        pack = Pack(index, "p$index", BmsType.VESC_BMS, address),
        data = BmsData(voltage = volts),
        isOnline = true
    )

    private fun foldStates(draft: VehicleDraft, states: List<List<PackState>>) =
        states.fold(ComposerTelemetry()) { acc, s -> acc.observing(draft, VehicleData(packs = s)) }

    /** Unknown on the HOSTED side. */
    @Test
    fun `an unknown cell count on the hosted side is not a match`() {
        val d = draftOf(scooter())
        val t = foldStates(
            d,
            tracking.map { (a, b) -> listOf(packState(0, ANT, a, 20), cellless(1, HU, b)) }
        )
        assertEquals(emptyList(), validate(d, t).filterIsInstance<ComposerIssue.DuplicatePack>())
    }

    /** …and on the DIRECT side, which is a different `?:` in the same rule. */
    @Test
    fun `an unknown cell count on the direct side is not a match`() {
        val d = draftOf(scooter())
        val t = foldStates(
            d,
            tracking.map { (a, b) -> listOf(cellless(0, ANT, a), packState(1, HU, b, 20)) }
        )
        assertEquals(emptyList(), validate(d, t).filterIsInstance<ComposerIssue.DuplicatePack>())
    }

    /**
     * Neither side known is not "both the same". Two packs that have never sent
     * a cell frame agree on nothing — reading their absent counts as one shared
     * value would flag every pair of them.
     */
    @Test
    fun `two packs that have never reported cells are not a match`() {
        val d = draftOf(scooter())
        val t = foldStates(
            d,
            tracking.map { (a, b) -> listOf(cellless(0, ANT, a), cellless(1, HU, b)) }
        )
        assertEquals(emptyList(), validate(d, t).filterIsInstance<ComposerIssue.DuplicatePack>())
    }

    /**
     * The count is what the pack HAS reported, not what it reported last: a
     * frame without a cell list is a frame that says nothing about the count,
     * and forgetting it there would make the fact flicker with the poll.
     */
    @Test
    fun `a pack that stops sending cell frames keeps the count it reported`() {
        val d = draftOf(scooter())
        val t = foldStates(
            d,
            listOf(listOf(packState(0, ANT, 72.4f, 20), packState(1, HU, 72.41f, 20))) +
                tracking.map { (a, b) -> listOf(cellless(0, ANT, a), cellless(1, HU, b)) }
        )
        assertEquals(20, t.packSeriesCells[d.packKey("ANT")])
        assertTrue(validate(d, t).any { it is ComposerIssue.DuplicatePack })
    }

    /** A blank link is not a path, so it cannot be the alternate of one. */
    @Test
    fun `a pack with no link is never offered as a duplicate`() {
        val d = draftOf(
            vehicle(
                packs = listOf(Pack(0, "Nowhere", BmsType.ANT_BMS, ""), Pack(1, "Hosted", BmsType.VESC_BMS, HU)),
                controllers = listOf(Controller(0, "uBox", ControllerType.VESC, HU, canId = 41))
            )
        )
        val t = foldStates(
            d,
            tracking.map { (a, b) -> listOf(packState(0, "", a, 20), packState(1, HU, b, 20)) }
        )
        assertEquals(emptyList(), validate(d, t).filterIsInstance<ComposerIssue.DuplicatePack>())
    }

    /**
     * Four is stated as a literal, not as `DUPLICATE_SAMPLES - 1`: a window
     * written in terms of the constant moves with it and could not notice the
     * constant changing, which is exactly what the sweep caught.
     */
    @Test
    fun `four agreeing samples are not enough`() {
        val d = draftOf(scooter())
        assertEquals(
            emptyList(),
            validate(d, observed(d, tracking.take(4))).filterIsInstance<ComposerIssue.DuplicatePack>()
        )
    }

    /** …and five are, which is the other half of "the window is exactly five". */
    @Test
    fun `five agreeing samples are enough`() {
        val d = draftOf(scooter())
        assertTrue(
            validate(d, observed(d, tracking.take(5))).any { it is ComposerIssue.DuplicatePack }
        )
    }

    /** Five agreeing samples with a disagreement in the middle are not tracking. */
    @Test
    fun `one sample outside the band inside the window suppresses the offer`() {
        val d = draftOf(scooter())
        val crossing = tracking.toMutableList().also { it[2] = 72.35f to 71.80f }
        assertEquals(
            emptyList(),
            validate(d, observed(d, crossing)).filterIsInstance<ComposerIssue.DuplicatePack>()
        )
    }

    /** The rider has answered; asking again is the nag `G §9.2` forbids. */
    @Test
    fun `an already grouped pair is not offered again`() {
        val d = draftOf(scooter())
        val t = observed(d, tracking)
        val grouped = d.groupPacks(d.packKey("ANT"), d.packKey("Hosted"))
        assertEquals(emptyList(), validate(grouped, t).filterIsInstance<ComposerIssue.DuplicatePack>())
    }

    /**
     * Two packs at one address are one link's business — a Begode wheel reports
     * two real branches over one BLE address, which is the most ordinary EUC
     * there is.
     */
    @Test
    fun `two branches of one wheel are not a duplicate`() {
        val d = draftOf(
            vehicle(
                packs = listOf(
                    Pack(0, "A", BmsType.BEGODE, "WH:01"),
                    Pack(1, "B", BmsType.BEGODE, "WH:01")
                ),
                controllers = listOf(Controller(0, "Wheel", ControllerType.BEGODE, "WH:01"))
            )
        )
        val identical = List(DUPLICATE_SAMPLES) { 72.40f to 72.40f }
        assertEquals(
            emptyList(),
            validate(d, observed(d, identical)).filterIsInstance<ComposerIssue.DuplicatePack>()
        )
    }

    @Test
    fun `the duplicate offer names both cards`() {
        val d = draftOf(scooter())
        val issues = validate(d, observed(d, tracking))
        val bySource = issuesBySource(d, issues)

        assertTrue(bySource[d.packKey("ANT")].orEmpty().any { it is ComposerIssue.DuplicatePack })
        assertTrue(bySource[d.packKey("Hosted")].orEmpty().any { it is ComposerIssue.DuplicatePack })
    }

    // -----------------------------------------------------------------------
    // 4. The observation window itself
    // -----------------------------------------------------------------------

    @Test
    fun `the window keeps only the last DUPLICATE_SAMPLES snapshots`() {
        val d = draftOf(scooter())
        val t = observed(d, List(DUPLICATE_SAMPLES * 3) { 70f + it to 70f + it })
        assertEquals(DUPLICATE_SAMPLES, t.packVoltagePairs.size)
        assertEquals(70f + (DUPLICATE_SAMPLES * 3 - 1), t.packVoltagePairs.last()[d.packKey("ANT")])
    }

    /**
     * A snapshot with one pack online informs no pair, and letting it into the
     * window would flush out the paired history a pair had built.
     */
    @Test
    fun `a snapshot with only one pack online does not enter the window`() {
        val d = draftOf(scooter())
        val full = observed(d, tracking)
        val after = full.observing(
            d,
            VehicleData(packs = listOf(packState(0, d.packs[0].address, 72.2f, 20)))
        )
        assertEquals(full.packVoltagePairs, after.packVoltagePairs)
        assertTrue(validate(d, after).any { it is ComposerIssue.DuplicatePack })
    }

    /** Offline packs are not samples: a stale reading is not a reading. */
    @Test
    fun `an offline pack contributes nothing`() {
        val d = draftOf(scooter())
        val t = List(DUPLICATE_SAMPLES) { it }.fold(ComposerTelemetry()) { acc, _ ->
            acc.observing(
                d,
                VehicleData(
                    packs = listOf(
                        packState(0, d.packs[0].address, 72.4f, 20),
                        packState(1, d.packs[1].address, 72.4f, 20, online = false)
                    )
                )
            )
        }
        assertEquals(emptyList(), t.packVoltagePairs)
        assertEquals(emptyList(), validate(d, t).filterIsInstance<ComposerIssue.DuplicatePack>())
    }

    /**
     * Rows are matched by STORED index, so a source the rider just added — which
     * has no stored twin — collects nothing rather than inheriting somebody
     * else's telemetry.
     *
     * The new pack is moved to the FRONT of the draft on purpose: matching by
     * list position instead of by stored index is the plausible wrong
     * implementation (it is the same mistake `reanchoredTo` exists to avoid),
     * and it is invisible while the added row sits last.
     */
    @Test
    fun `a freshly added pack collects no telemetry`() {
        val d = draftOf(scooter()).addPack(BmsType.ANT_BMS, ANT2, "New").movePack(2, 0)
        val t = ComposerTelemetry().observing(
            d,
            VehicleData(packs = listOf(packState(0, ANT, 72.4f, 20), packState(1, HU, 72.4f, 20)))
        )
        assertFalse(d.packKey("New") in t.sourcesSeen, "nothing has been learned about it yet")
        assertFalse(d.packKey("New") in t.packSeriesCells)
        assertTrue(d.packKey("ANT") in t.sourcesSeen, "and the stored rows still collect")
    }

    // -----------------------------------------------------------------------
    // 5. The phantom head-unit controller advisory
    // -----------------------------------------------------------------------

    private fun controllerState(index: Int, data: ControllerData, online: Boolean = true) = ControllerState(
        controller = Controller(index, "c$index", ControllerType.VESC, HU),
        data = data,
        isOnline = online
    )

    /** A live uBox: real rail, turning. */
    private val realEsc = ControllerData(inputVoltageV = 72.4f, eRpm = 1200f, isConnected = true)

    /** The head unit answering `GET_VALUES` with nothing at all — `G §9.3`. */
    private val zeroAnswer = ControllerData(isConnected = true)

    private fun withControllers(draft: VehicleDraft, states: List<ControllerState>) =
        ComposerTelemetry().observing(draft, VehicleData(controllers = states))

    @Test
    fun `a gateway controller that has never reported motion is advised about`() {
        val d = draftOf(scooter())
        val t = withControllers(
            d,
            listOf(
                controllerState(0, zeroAnswer),
                controllerState(1, realEsc),
                controllerState(2, realEsc)
            )
        )
        val issue = validate(d, t).filterIsInstance<ComposerIssue.PhantomGatewayController>().single()

        assertEquals(d.controllerKey("Head unit"), issue.sourceKey)
        assertFalse(issue.blocking, "a head unit that IS an ESC is a legitimate build")
    }

    /** The silent case: the head unit answers nothing and never comes online. */
    @Test
    fun `a gateway controller that answers nothing at all is advised about too`() {
        val d = draftOf(scooter())
        val t = withControllers(
            d,
            listOf(
                controllerState(0, ControllerData(), online = false),
                controllerState(1, realEsc),
                controllerState(2, realEsc)
            )
        )
        assertEquals(
            listOf(d.controllerKey("Head unit")),
            validate(d, t).filterIsInstance<ComposerIssue.PhantomGatewayController>().map { it.sourceKey }
        )
    }

    @Test
    fun `a head unit that does drive a motor is left alone`() {
        val d = draftOf(scooter())
        val t = withControllers(
            d,
            listOf(controllerState(0, realEsc), controllerState(1, realEsc), controllerState(2, realEsc))
        )
        assertEquals(
            emptyList(),
            validate(d, t).filterIsInstance<ComposerIssue.PhantomGatewayController>()
        )
    }

    /**
     * A parked scooter must not be told its head unit is a display. Silence
     * means something only once something else on that link has spoken.
     */
    @Test
    fun `nothing is advised while the link has never reported anything`() {
        val d = draftOf(scooter())
        assertEquals(
            emptyList(),
            validate(d, ComposerTelemetry()).filterIsInstance<ComposerIssue.PhantomGatewayController>()
        )
    }

    /**
     * The criterion is CAN-addressed sources on the link, not merely "a gateway
     * link": on a plain VESC board with a BMS on the same address neither source
     * is a gateway for the other, and its one controller is the motor.
     */
    @Test
    fun `a lone VESC with a hosted BMS and no CAN bus is not advised about`() {
        val d = draftOf(
            vehicle(
                packs = listOf(Pack(0, "BMS", BmsType.VESC_BMS, "VE:01")),
                controllers = listOf(Controller(0, "VESC", ControllerType.VESC, "VE:01"))
            )
        )
        val t = ComposerTelemetry().observing(
            d,
            VehicleData(
                packs = listOf(packState(0, "VE:01", 72.4f, 20)),
                controllers = listOf(
                    ControllerState(
                        controller = Controller(0, "VESC", ControllerType.VESC, "VE:01"),
                        data = zeroAnswer,
                        isOnline = true
                    )
                )
            )
        )
        assertEquals(
            emptyList(),
            validate(d, t).filterIsInstance<ComposerIssue.PhantomGatewayController>()
        )
    }

    /** A CAN slave has an id, so it is never the endpoint and never this issue. */
    @Test
    fun `a silent CAN slave is not called a display`() {
        val d = draftOf(scooter())
        val t = withControllers(
            d,
            listOf(
                controllerState(0, realEsc),
                controllerState(1, zeroAnswer),
                controllerState(2, realEsc)
            )
        )
        assertEquals(
            emptyList(),
            validate(d, t).filterIsInstance<ComposerIssue.PhantomGatewayController>()
        )
    }

    @Test
    fun `the advisory lands on the head unit's own card`() {
        val d = draftOf(scooter())
        val t = withControllers(
            d,
            listOf(controllerState(0, zeroAnswer), controllerState(1, realEsc), controllerState(2, realEsc))
        )
        val bySource = issuesBySource(d, validate(d, t))
        assertTrue(
            bySource[d.controllerKey("Head unit")].orEmpty()
                .any { it is ComposerIssue.PhantomGatewayController }
        )
        assertEquals(null, bySource[d.controllerKey("uBox 1")])
    }

    /** Neither new advisory may ever refuse a save. */
    @Test
    fun `neither hardware advisory blocks the save`() {
        val d = draftOf(scooter())
        val t = withControllers(
            d,
            listOf(controllerState(0, zeroAnswer), controllerState(1, realEsc), controllerState(2, realEsc))
        ).let { base ->
            tracking.fold(base) { acc, (a, b) ->
                acc.observing(
                    d,
                    VehicleData(
                        packs = listOf(packState(0, ANT, a, 20), packState(1, HU, b, 20))
                    )
                )
            }
        }
        val issues = validate(d, t)
        assertTrue(issues.any { it is ComposerIssue.PhantomGatewayController })
        assertTrue(issues.any { it is ComposerIssue.DuplicatePack })
        assertFalse(issues.any { it.blocking })
    }
}
