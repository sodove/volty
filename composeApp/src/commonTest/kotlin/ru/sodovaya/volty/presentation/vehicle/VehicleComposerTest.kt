package ru.sodovaya.volty.presentation.vehicle

import ru.sodovaya.volty.data.ble.ProtocolKind
import ru.sodovaya.volty.data.ble.isGatewayLink
import ru.sodovaya.volty.data.ble.planLinks
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The composer's model and its rules (Part G2 Task 2). Everything here is pure:
 * Compose is not unit-testable in this repo, so the draft, the validation and
 * the derived-battery rule are the layer that carries the decisions and this is
 * where they are pinned.
 */
@OptIn(ExperimentalTime::class)
class VehicleComposerTest {

    private val createdAt = Instant.fromEpochSeconds(1_700_000_000)

    private fun vehicle(packs: List<Pack>, controllers: List<Controller>) = Vehicle(
        id = "v1",
        name = "V",
        iconKey = "generic",
        packs = packs,
        controllers = controllers,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = createdAt
    )

    /** The vehicle a draft would save as, so the real `planLinks` can be run over it. */
    private fun VehicleDraft.asVehicle() = vehicle(toPacks(), toControllers())

    private fun pack(
        index: Int = 0,
        label: String = "P",
        type: BmsType = BmsType.JK_BMS,
        address: String = "PK:01",
        cellCount: Int? = null,
        canId: Int? = null,
        aliasGroup: String? = null
    ) = Pack(index, label, type, address, cellCount, canId, aliasGroup)

    private fun controller(
        index: Int = 0,
        label: String = "C",
        type: ControllerType = ControllerType.VESC,
        address: String = "CT:01",
        canId: Int? = null,
        motor: MotorConfig = MotorConfig(),
        derived: Boolean = false
    ) = Controller(index, label, type, address, canId, motor, derived)

    // -----------------------------------------------------------------------
    // Round trip — the draft must not be a lossy view of the vehicle
    // -----------------------------------------------------------------------

    /**
     * Every [Pack] and [Controller] field at a non-default value, because a
     * field left at its default is carried and dropped by identical-looking
     * code — the same reason `existingVehicle()` in `VehicleEditComponentTest`
     * is built that way.
     */
    @Test
    fun `a loaded vehicle round-trips through the draft unchanged`() {
        val v = vehicle(
            packs = listOf(
                pack(0, "Front", BmsType.ANT_BMS, "HU:01", cellCount = 20, canId = 11, aliasGroup = "g1"),
                pack(1, "Rear", BmsType.ANT_BMS, "HU:01", cellCount = 21, canId = 12, aliasGroup = "g2")
            ),
            controllers = listOf(
                controller(0, "Left", ControllerType.VESC, "VE:01", canId = 41, motor = MotorConfig(7, 200, 2.5f))
            )
        )
        val d = draftOf(v)

        assertEquals(v.packs, d.toPacks())
        assertEquals(v.controllers, d.toControllers())
        // Row identity: two packs of one vehicle must not answer to the same
        // key, or an edit aimed at one lands on both.
        assertEquals(2, d.packs.map { it.key }.toSet().size)
    }

    @Test
    fun `a controller-only vehicle round-trips, derived battery included`() {
        val v = vehicle(
            packs = emptyList(),
            controllers = listOf(controller(0, "Main", ControllerType.VESC, "VE:01", derived = true))
        )
        assertEquals(emptyList(), draftOf(v).toPacks())
        assertEquals(v.controllers, draftOf(v).toControllers())
    }

    /**
     * `origin` is the load-time snapshot, and the stored row moves underneath
     * it: `maybePersistCellCount` upserts a cell count the moment the pack's
     * first frames arrive, which is while this form is on screen. Re-anchoring
     * picks that up for the fields the composer does not edit, without
     * disturbing the ones it does.
     */
    @Test
    fun `re-anchoring picks up a field written to the stored row while the form was open`() {
        // TWO packs, each learning a different count: matching them back by
        // list position instead of by stored index would hand pack 0 pack 1's
        // telemetry, which is worse than the staleness this fixes.
        val loaded = vehicle(
            packs = listOf(
                pack(0, "P0", BmsType.ANT_BMS, "AN:01", cellCount = null, aliasGroup = null),
                pack(1, "P1", BmsType.ANT_BMS, "AN:02", cellCount = null, aliasGroup = null)
            ),
            controllers = listOf(controller(0, "C", ControllerType.VESC, "VE:01"))
        )
        val d = draftOf(loaded).let { it.updatePack(it.packs.first().key) { p -> p.copy(label = "Renamed") } }
        val freshController = loaded.controllers.single().copy(motor = MotorConfig(9, 400, 2f))
        val fresh = loaded.copy(
            packs = listOf(
                loaded.packs[0].copy(cellCount = 20, aliasGroup = "g"),
                loaded.packs[1].copy(cellCount = 7, aliasGroup = "other")
            ),
            controllers = listOf(freshController)
        )

        assertEquals(null, d.toPacks().first().cellCount, "the load-time snapshot knew nothing")
        val out = d.reanchoredTo(fresh).toPacks()
        assertEquals(20, out[0].cellCount)
        assertEquals("g", out[0].aliasGroup)
        assertEquals(7, out[1].cellCount, "and each pack gets its OWN row back, matched by stored index")
        assertEquals("Renamed", out[0].label, "and the rider's own edit still wins")
        // Both halves are re-anchored. A `Controller` has no field the draft
        // leaves unmodelled today, so this is only observable on `origin`
        // itself — which is precisely the thing a future field would ride on.
        assertEquals(freshController, d.reanchoredTo(fresh).controllers.single().origin)
    }

    /**
     * The draft renumbers from its own order rather than carrying stored
     * indices, which is what makes reorder mean anything — and is also a
     * repair: `VehicleConnection` matches a source by index and
     * `List<Pack>.expandedTo` numbers synthesised slots after the highest
     * existing one, so a gap leaves a slot permanently unreachable.
     */
    @Test
    fun `saving normalises non-contiguous stored indices`() {
        val v = vehicle(
            packs = listOf(pack(3, "A", address = "PK:01"), pack(7, "B", address = "PK:02")),
            controllers = listOf(controller(5, "C", address = "CT:01"))
        )
        assertEquals(listOf(0, 1), draftOf(v).toPacks().map { it.index })
        assertEquals(listOf(0), draftOf(v).toControllers().map { it.index })
    }

    // -----------------------------------------------------------------------
    // N sources: add / remove / reorder, and the one that must never be reached
    // -----------------------------------------------------------------------

    @Test
    fun `adding sources appends them with positional default labels`() {
        val d = VehicleDraft()
            .addController(ControllerType.VESC, "VE:01")
            .addController(ControllerType.VESC, "VE:02")
            .addPack(BmsType.ANT_BMS, "AN:01")

        assertEquals(listOf("Controller 1", "Controller 2"), d.controllers.map { it.label })
        assertEquals(listOf("Pack 1"), d.packs.map { it.label })
        assertEquals("VE:02", d.controllers[1].address)
        assertEquals(BmsType.ANT_BMS, d.packs[0].bmsType)
        // Distinct keys, so a screen row can address exactly one source.
        assertEquals(3, (d.packs.map { it.key } + d.controllers.map { it.key }).toSet().size)
    }

    @Test
    fun `an explicit label wins over the positional default`() {
        val d = VehicleDraft().addController(ControllerType.VESC, "VE:01", "Задний")
        assertEquals("Задний", d.controllers.single().label)
    }

    /**
     * **The exception the UI must never be able to reach.** `Vehicle`'s own
     * `init` requires a source; removing the last one is refused here rather
     * than thrown and caught, and [VehicleDraft.canRemoveSource] is the same
     * fact for the screen to disable the control with.
     */
    @Test
    fun `the last source cannot be removed`() {
        val lastPack = draftOf(vehicle(listOf(pack()), emptyList()))
        assertFalse(lastPack.canRemoveSource)
        assertEquals(lastPack, lastPack.removePack(lastPack.packs.single().key))

        val lastController = draftOf(vehicle(emptyList(), listOf(controller())))
        assertFalse(lastController.canRemoveSource)
        assertEquals(lastController, lastController.removeController(lastController.controllers.single().key))
    }

    /**
     * The other half: "at least one source" is a count over BOTH lists, so the
     * only pack of a vehicle that also has a controller may go — and what is
     * left still builds a [Vehicle].
     */
    @Test
    fun `the only pack may go when a controller remains`() {
        val d = draftOf(vehicle(listOf(pack()), listOf(controller())))
        assertTrue(d.canRemoveSource)

        val trimmed = d.removePack(d.packs.single().key)
        assertEquals(emptyList(), trimmed.packs)
        assertEquals(1, trimmed.toControllers().size)
        // Constructing this must not throw — that is the whole point.
        assertEquals(1, trimmed.asVehicle().controllers.size)
    }

    @Test
    fun `removing an unknown key changes nothing`() {
        val d = draftOf(vehicle(listOf(pack(0), pack(1, address = "PK:02")), emptyList()))
        assertEquals(d, d.removePack("nope"))
        assertEquals(d, d.removeController("nope"))
    }

    @Test
    fun `reordering renumbers the saved indices`() {
        val d = draftOf(
            vehicle(
                packs = listOf(pack(0, "A", address = "PK:01"), pack(1, "B", address = "PK:02")),
                controllers = listOf(
                    controller(0, "Front", address = "CT:01"),
                    controller(1, "Rear", address = "CT:02")
                )
            )
        )
        val moved = d.movePack(0, 1).moveController(1, 0)

        assertEquals(listOf("B", "A"), moved.toPacks().map { it.label })
        assertEquals(listOf(0, 1), moved.toPacks().map { it.index })
        assertEquals(listOf("Rear", "Front"), moved.toControllers().map { it.label })
        assertEquals(listOf(0, 1), moved.toControllers().map { it.index })
    }

    @Test
    fun `an out-of-range or same-slot move is a no-op`() {
        val d = draftOf(vehicle(listOf(pack(0), pack(1, address = "PK:02")), emptyList()))
        assertEquals(d, d.movePack(0, 0))
        assertEquals(d, d.movePack(0, 5))
        assertEquals(d, d.movePack(-1, 0))
        assertEquals(d, d.moveController(0, 1))
    }

    // -----------------------------------------------------------------------
    // The derived-battery rule (G §6) and the override collision
    // -----------------------------------------------------------------------

    @Test
    fun `a lone controller derives a battery, and a BMS turns it off`() {
        val lone = draftOf(vehicle(emptyList(), listOf(controller(derived = true))))
        assertEquals(true, lone.toControllers().single().providesDerivedBattery)

        val withBms = lone.addPack(BmsType.JK_BMS, "PK:01")
        assertEquals(
            false,
            withBms.toControllers().single().providesDerivedBattery,
            "adding a real battery source must turn the derived one off"
        )
    }

    @Test
    fun `removing the BMS turns the derived battery back on`() {
        val d = draftOf(vehicle(listOf(pack()), listOf(controller(derived = false))))
        assertEquals(false, d.toControllers().single().providesDerivedBattery)

        val trimmed = d.removePack(d.packs.single().key)
        assertEquals(true, trimmed.toControllers().single().providesDerivedBattery)
    }

    /**
     * **The collision, direction 1.** The rider switches a lone controller's
     * derived battery OFF while the rule says ON. Adding a BMS and removing it
     * again runs the recompute twice; neither may hand their choice back.
     */
    @Test
    fun `a recompute does not overwrite an explicit OFF`() {
        val d = draftOf(vehicle(emptyList(), listOf(controller(derived = true))))
        val key = d.controllers.single().key
        val chosen = d.updateController(key) { it.copy(derivedBattery = DerivedBatteryChoice.OFF) }
        assertEquals(false, chosen.toControllers().single().providesDerivedBattery)

        val withBms = chosen.addPack(BmsType.JK_BMS, "PK:01")
        assertEquals(false, withBms.toControllers().single().providesDerivedBattery)

        val backToLone = withBms.removePack(withBms.packs.single().key)
        assertEquals(
            false,
            backToLone.toControllers().single().providesDerivedBattery,
            "the rule says ON here; the rider said OFF, and the rider's word stands"
        )
    }

    /**
     * **The collision, direction 2** — the case a boolean cannot express at
     * all: the rider wants a derived battery *alongside* a BMS (their BMS
     * covers one branch of two). The rule says OFF while the pack is there, so
     * only an override that outlives the recompute keeps it.
     */
    @Test
    fun `a recompute does not overwrite an explicit ON`() {
        val d = draftOf(vehicle(listOf(pack()), listOf(controller(derived = false))))
        val key = d.controllers.single().key
        val chosen = d.updateController(key) { it.copy(derivedBattery = DerivedBatteryChoice.ON) }
        assertEquals(true, chosen.toControllers().single().providesDerivedBattery)

        val trimmed = chosen.removePack(chosen.packs.single().key)
        assertEquals(true, trimmed.toControllers().single().providesDerivedBattery)

        val readded = trimmed.addPack(BmsType.JK_BMS, "PK:02")
        assertEquals(
            true,
            readded.toControllers().single().providesDerivedBattery,
            "the rule says OFF here; the rider said ON"
        )
    }

    /**
     * How an override survives being persisted: the stored `Boolean` is
     * compared against the rule's answer **for the source set it was stored
     * with**, and disagreement is the proof of an override. This is the whole
     * mechanism, so it is pinned directly rather than only through a round
     * trip.
     */
    @Test
    fun `a stored value that disagrees with the rule reloads as an explicit choice`() {
        // Stored ON beside a real pack: the rule says OFF, so this is an override.
        assertEquals(
            DerivedBatteryChoice.ON,
            derivedBatteryChoiceFor(controller(derived = true), default = false)
        )
        // Stored OFF with no pack at all: the rule says ON, so this is an override.
        assertEquals(
            DerivedBatteryChoice.OFF,
            derivedBatteryChoiceFor(controller(derived = false), default = true)
        )
        // Agreement is indistinguishable from never having been touched.
        assertEquals(
            DerivedBatteryChoice.AUTO,
            derivedBatteryChoiceFor(controller(derived = true), default = true)
        )
        assertEquals(
            DerivedBatteryChoice.AUTO,
            derivedBatteryChoiceFor(controller(derived = false), default = false)
        )
    }

    /**
     * The seeding above, exercised end to end: a vehicle SAVED with an
     * override reloads with it intact and still resists the recompute. Without
     * the reload step an override would only live as long as the screen.
     */
    @Test
    fun `an override survives a save and reload`() {
        val saved = vehicle(
            packs = listOf(pack()),
            // Disagrees with the rule for this source set (a pack is present).
            controllers = listOf(controller(derived = true))
        )
        val reloaded = draftOf(saved)
        assertEquals(DerivedBatteryChoice.ON, reloaded.controllers.single().derivedBattery)

        val trimmed = reloaded.removePack(reloaded.packs.single().key)
        val recomposed = trimmed.addPack(BmsType.ANT_BMS, "AN:01")
        assertEquals(true, recomposed.toControllers().single().providesDerivedBattery)
    }

    /** The AUTO half of the same reload: an untouched controller keeps following the rule. */
    @Test
    fun `a stored value that agrees with the rule keeps following it after reload`() {
        val saved = vehicle(emptyList(), listOf(controller(derived = true)))
        val reloaded = draftOf(saved)
        assertEquals(DerivedBatteryChoice.AUTO, reloaded.controllers.single().derivedBattery)
        assertEquals(
            false,
            reloaded.addPack(BmsType.JK_BMS, "PK:01").toControllers().single().providesDerivedBattery
        )
    }

    /** The rule itself, stated once: it is the presence of ANY pack, vehicle-wide. */
    @Test
    fun `the rule is vehicle-wide, not per link`() {
        // A pack on a completely different link still covers the controller —
        // archetype 4 in 01-linking (EUC plus an enthusiast-added BMS).
        val d = draftOf(
            vehicle(
                packs = listOf(pack(0, address = "PK:99")),
                controllers = listOf(controller(0, address = "CT:01"))
            )
        )
        assertFalse(d.derivedBatteryDefault)
        assertEquals(false, d.toControllers().single().providesDerivedBattery)
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Test
    fun `a well-formed draft has nothing to report`() {
        val d = draftOf(
            vehicle(
                packs = listOf(pack(0, address = "AN:01", type = BmsType.ANT_BMS)),
                controllers = listOf(controller(0, address = "VE:01"))
            )
        )
        assertEquals(emptyList(), validate(d))
        assertTrue(d.canRemoveSource)
    }

    @Test
    fun `two protocol kinds at one address are blocked`() {
        val d = draftOf(
            vehicle(
                packs = listOf(pack(0, address = "SHARED", type = BmsType.JK_BMS)),
                controllers = listOf(controller(0, address = "SHARED", type = ControllerType.VESC))
            )
        )
        assertEquals(
            listOf(ComposerIssue.ConflictingKinds("SHARED", setOf(ProtocolKind.JK, ProtocolKind.VESC))),
            validate(d)
        )
        assertTrue(validate(d).single().blocking)
    }

    @Test
    fun `two controllers of different types at one address are blocked`() {
        val d = draftOf(
            vehicle(
                packs = emptyList(),
                controllers = listOf(
                    controller(0, address = "SHARED", type = ControllerType.VESC),
                    controller(1, address = "SHARED", type = ControllerType.BEGODE)
                )
            )
        )
        assertEquals(
            listOf(ComposerIssue.ConflictingKinds("SHARED", setOf(ProtocolKind.VESC, ProtocolKind.BEGODE))),
            validate(d)
        )
    }

    @Test
    fun `two battery types at one address are blocked`() {
        val d = draftOf(
            vehicle(
                packs = listOf(
                    pack(0, address = "SHARED", type = BmsType.JK_BMS),
                    pack(1, address = "SHARED", type = BmsType.ANT_BMS)
                ),
                controllers = emptyList()
            )
        )
        assertEquals(
            listOf(ComposerIssue.ConflictingKinds("SHARED", setOf(ProtocolKind.JK, ProtocolKind.ANT))),
            validate(d)
        )
    }

    /**
     * The one sanctioned pairing (`C §6`): a head unit hosting its own
     * VESC-BMS battery beside the VESC controllers it forwards to. Reporting
     * this would make the product owner's own scooter unsavable.
     */
    @Test
    fun `a hosted VESC-BMS beside a VESC controller is not a conflict`() {
        val d = draftOf(
            vehicle(
                packs = listOf(pack(0, address = "HU:01", type = BmsType.VESC_BMS)),
                controllers = listOf(controller(0, address = "HU:01", type = ControllerType.VESC, canId = 41))
            )
        )
        assertEquals(emptyList(), validate(d))
    }

    /** A wheel is one device: its controller and its branches share one kind. */
    @Test
    fun `a Begode wheel's controller and packs at one address are not a conflict`() {
        val d = draftOf(
            vehicle(
                packs = listOf(
                    pack(0, address = "WH:01", type = BmsType.BEGODE),
                    pack(1, address = "WH:01", type = BmsType.BEGODE)
                ),
                controllers = listOf(controller(0, address = "WH:01", type = ControllerType.BEGODE))
            )
        )
        assertEquals(emptyList(), validate(d))
    }

    @Test
    fun `two sources claiming one CAN id behind one gateway are blocked`() {
        val d = draftOf(
            vehicle(
                packs = listOf(pack(0, address = "HU:01", type = BmsType.VESC_BMS, canId = 41)),
                controllers = listOf(controller(0, address = "HU:01", type = ControllerType.VESC, canId = 41))
            )
        )
        assertEquals(listOf(ComposerIssue.DuplicateCanId("HU:01", 41)), validate(d))
        assertTrue(validate(d).single().blocking)
    }

    @Test
    fun `the same CAN id at two different addresses is fine`() {
        val d = draftOf(
            vehicle(
                packs = emptyList(),
                controllers = listOf(
                    controller(0, address = "HU:01", canId = 41),
                    controller(1, address = "HU:02", canId = 41)
                )
            )
        )
        assertEquals(emptyList(), validate(d))
    }

    @Test
    fun `a source with no address is reported`() {
        val c = VehicleDraft().addController(ControllerType.VESC, "")
        assertEquals(listOf(ComposerIssue.BlankAddress(c.controllers.single().key)), validate(c))
        assertFalse(validate(c).single().blocking)

        // Both halves: a pack with no address is just as undiallable, and the
        // two are separate loops in `validate`.
        val p = VehicleDraft().addPack(BmsType.JK_BMS, "")
        assertEquals(listOf(ComposerIssue.BlankAddress(p.packs.single().key)), validate(p))
    }

    @Test
    fun `a controller volty cannot decode yet is reported but not blocked`() {
        val d = VehicleDraft()
            .addController(ControllerType.FARDRIVER, "FD:01")
            .addController(ControllerType.KELLY, "KL:01")
        assertEquals(
            listOf(
                ComposerIssue.NoControllerDecoder(d.controllers[0].key, ControllerType.FARDRIVER),
                ComposerIssue.NoControllerDecoder(d.controllers[1].key, ControllerType.KELLY)
            ),
            validate(d)
        )
        assertTrue(validate(d).none { it.blocking }, "a missing decoder is Parts E/H's job, not a config error")
    }

    @Test
    fun `a decodable controller type is not reported`() {
        val d = VehicleDraft()
            .addController(ControllerType.VESC, "VE:01")
            .addController(ControllerType.BEGODE, "WH:01")
        assertEquals(emptyList(), validate(d))
    }

    /**
     * A VESC-BMS is only readable as a gateway's HOSTED battery. On its own the
     * link resolves to VESC_BMS and `KableBmsRepository.createProtocol` throws
     * "VESC BMS protocol decode is not implemented yet".
     */
    @Test
    fun `a VESC-BMS pack with no VESC host is reported`() {
        val d = VehicleDraft().addPack(BmsType.VESC_BMS, "VB:01")
        assertEquals(listOf(ComposerIssue.HostlessVescBms(d.packs.single().key)), validate(d))
    }

    /**
     * `VescGatewayProtocol` is the only multiplexer there is: every other arm
     * of `controllerMotionProtocol` ignores the link spec, so CAN ids and
     * second controllers on a non-VESC link are silently never addressed.
     * `ControllerProtocols.kt` names this limitation and asks whoever first
     * lets a rider add a second controller to refuse the pairing.
     */
    @Test
    fun `a gateway shape on a link with no multiplexer is reported`() {
        val twoWheels = draftOf(
            vehicle(
                packs = emptyList(),
                controllers = listOf(
                    // Distinct CAN ids, so this is ONLY about the missing
                    // multiplexer — the ambiguous-source case is its own test.
                    controller(0, address = "WH:01", type = ControllerType.BEGODE, canId = 1),
                    controller(1, address = "WH:01", type = ControllerType.BEGODE, canId = 2)
                )
            )
        )
        assertEquals(
            listOf(ComposerIssue.UnroutableGateway("WH:01", ProtocolKind.BEGODE)),
            validate(twoWheels)
        )

        val forwardedBms = draftOf(
            vehicle(
                packs = listOf(pack(0, address = "JK:01", type = BmsType.JK_BMS, canId = 7)),
                controllers = emptyList()
            )
        )
        assertEquals(
            listOf(ComposerIssue.UnroutableGateway("JK:01", ProtocolKind.JK)),
            validate(forwardedBms)
        )
    }

    /**
     * **The product owner's own topology entered naively, and the worst
     * outcome this task exists to stop: it saves clean, connects, and lies.**
     *
     * Two uBoxes behind the head unit with no CAN ids. `planLinks` accepts it
     * — its duplicate check runs over `mapNotNull { it.canId }`, so nulls are
     * dropped — and plans ONE gateway link owning two controllers whose
     * `canId` is null. `VescGatewayProtocol.frameFor` sends an unwrapped
     * request for a null id, so both `GET_VALUES` frames are byte-identical,
     * the head unit answers the same thing twice, and `MotionAggregator` SUMS
     * `motorCurrentA` / `batteryCurrentA` / `powerW` across controllers: the
     * dashboard reads about double, with nothing thrown and nothing logged.
     */
    @Test
    fun `two controllers on one gateway link both claiming to be the gateway are blocked`() {
        val d = VehicleDraft()
            .addController(ControllerType.VESC, "HU:01")
            .addController(ControllerType.VESC, "HU:01")

        assertEquals(
            listOf(ComposerIssue.AmbiguousGatewaySource("HU:01", d.controllers.map { it.key })),
            validate(d)
        )
        assertTrue(validate(d).single().blocking)

        // The shape this is about, read off the real planner: one gateway link,
        // two owned controllers, both `canId == null`. planLinks does not
        // refuse it, which is exactly why the composer must.
        val v = d.asVehicle()
        val link = planLinks(v.packs, v.controllers).single()
        assertTrue(link.isGatewayLink)
        assertEquals(listOf(null, null), link.ownedControllers.map { it.canId })
    }

    /** Same contradiction on the battery half: two batteries hosted by one head unit. */
    @Test
    fun `two hosted packs on one gateway link are blocked`() {
        val d = draftOf(
            vehicle(
                packs = listOf(
                    pack(0, address = "HU:01", type = BmsType.VESC_BMS),
                    pack(1, address = "HU:01", type = BmsType.VESC_BMS)
                ),
                // No CAN id anywhere: what makes this a gateway link at all is
                // the hosted VESC_BMS pack, `isGatewayLink`'s third trigger.
                controllers = listOf(controller(0, address = "HU:01"))
            )
        )
        assertEquals(
            listOf(ComposerIssue.AmbiguousGatewaySource("HU:01", listOf("p0", "p1"))),
            validate(d)
        )
        val v = d.asVehicle()
        assertTrue(planLinks(v.packs, v.controllers).single().isGatewayLink)
    }

    /**
     * The half that must NOT be reported: a controller-gateway answers
     * `GET_VALUES` for itself and `BMS_GET_VALUES` for its hosted battery —
     * different questions, so one null-id source of each kind is not
     * ambiguous. Counting them together would refuse `01-linking §3`
     * archetype 1b.
     */
    @Test
    fun `one direct controller beside one hosted pack is fine`() {
        val d = draftOf(
            vehicle(
                packs = listOf(pack(0, address = "HU:01", type = BmsType.VESC_BMS)),
                controllers = listOf(
                    controller(0, address = "HU:01"),
                    controller(1, address = "HU:01", canId = 42)
                )
            )
        )
        assertEquals(emptyList(), validate(d))
    }

    /** The shape that DOES have a multiplexer must not be reported. */
    @Test
    fun `a VESC gateway with two CAN controllers and a hosted battery is clean`() {
        // The product owner's scooter: one head unit, two uBoxes on CAN, the
        // ANT battery hosted behind it (01-linking §3, archetype 1).
        val d = draftOf(
            vehicle(
                packs = listOf(pack(0, "ANT", BmsType.VESC_BMS, "HU:01")),
                controllers = listOf(
                    controller(0, "uBox L", ControllerType.VESC, "HU:01", canId = 41),
                    controller(1, "uBox R", ControllerType.VESC, "HU:01", canId = 42)
                )
            )
        )
        assertEquals(emptyList(), validate(d))
    }

    @Test
    fun `every issue at once is reported, and the blocking ones are the two planLinks throws on`() {
        val d = VehicleDraft()
            .addController(ControllerType.FARDRIVER, "")
            .addPack(BmsType.JK_BMS, "SHARED")
            .addController(ControllerType.VESC, "SHARED")
        val issues = validate(d)

        assertEquals(
            setOf(
                ComposerIssue.BlankAddress(d.controllers[0].key),
                ComposerIssue.NoControllerDecoder(d.controllers[0].key, ControllerType.FARDRIVER),
                ComposerIssue.ConflictingKinds("SHARED", setOf(ProtocolKind.JK, ProtocolKind.VESC))
            ),
            issues.toSet()
        )
        assertEquals(
            listOf(ComposerIssue.ConflictingKinds("SHARED", setOf(ProtocolKind.JK, ProtocolKind.VESC))),
            issues.filter { it.blocking }
        )
    }

    // -----------------------------------------------------------------------
    // The anti-drift check: this file re-states planLinks' rules rather than
    // catching its exception, so the two must be shown to agree.
    // -----------------------------------------------------------------------

    /**
     * `validate` deliberately re-states `resolveLinkKind` and the duplicate-CAN
     * `require` instead of calling `planLinks` inside a `try` — catching the
     * exception the composer exists to prevent is the failure mode, not the
     * fix. That leaves two copies of one rule, so this test runs the REAL
     * `planLinks` over both sides of the line.
     *
     * If it fails, `planLinks` changed and [validate] has not: fix [validate],
     * do not relax this.
     */
    @Test
    fun `validate agrees with the real planLinks in both directions`() {
        val clean: List<Pair<String, VehicleDraft>> = listOf(
            "one controller" to VehicleDraft().addController(ControllerType.VESC, "VE:01"),
            "controller plus BMS" to VehicleDraft()
                .addController(ControllerType.VESC, "VE:01")
                .addPack(BmsType.JK_BMS, "JK:01"),
            "wheel" to draftOf(
                vehicle(
                    listOf(
                        pack(0, address = "WH:01", type = BmsType.BEGODE),
                        pack(1, address = "WH:01", type = BmsType.BEGODE)
                    ),
                    listOf(controller(0, address = "WH:01", type = ControllerType.BEGODE))
                )
            ),
            "head unit, two uBoxes and a hosted battery" to draftOf(
                vehicle(
                    listOf(pack(0, address = "HU:01", type = BmsType.VESC_BMS)),
                    listOf(
                        controller(0, address = "HU:01", canId = 41),
                        controller(1, address = "HU:01", canId = 42)
                    )
                )
            ),
            // Advisory-only drafts still have to PLAN — that is exactly why
            // they are advisory rather than blocking.
            "undecodable controller" to VehicleDraft().addController(ControllerType.FARDRIVER, "FD:01"),
            "hostless VESC-BMS" to VehicleDraft().addPack(BmsType.VESC_BMS, "VB:01"),
            "unroutable gateway" to draftOf(
                vehicle(
                    emptyList(),
                    listOf(
                        controller(0, address = "WH:01", type = ControllerType.BEGODE, canId = 1),
                        controller(1, address = "WH:01", type = ControllerType.BEGODE, canId = 2)
                    )
                )
            ),
            "blank address" to VehicleDraft().addController(ControllerType.VESC, "")
        )
        for ((name, d) in clean) {
            assertTrue(validate(d).none { it.blocking }, "$name must not be blocked")
            val v = d.asVehicle()
            // The assertion IS that this does not throw.
            assertTrue(planLinks(v.packs, v.controllers).isNotEmpty(), "$name must plan")
        }

        val rejected: List<Pair<String, VehicleDraft>> = listOf(
            "pack and controller of different kinds at one address" to VehicleDraft()
                .addPack(BmsType.JK_BMS, "SHARED")
                .addController(ControllerType.VESC, "SHARED"),
            "two controller kinds at one address" to VehicleDraft()
                .addController(ControllerType.VESC, "SHARED")
                .addController(ControllerType.BEGODE, "SHARED"),
            "two battery kinds at one address" to VehicleDraft()
                .addPack(BmsType.JK_BMS, "SHARED")
                .addPack(BmsType.ANT_BMS, "SHARED"),
            "two blank-address sources of different kinds" to VehicleDraft()
                .addPack(BmsType.JK_BMS, "")
                .addController(ControllerType.VESC, ""),
            "duplicate CAN id across a pack and a controller" to draftOf(
                vehicle(
                    listOf(pack(0, address = "HU:01", type = BmsType.VESC_BMS, canId = 41)),
                    listOf(controller(0, address = "HU:01", canId = 41))
                )
            ),
            "duplicate CAN id across two controllers" to draftOf(
                vehicle(
                    emptyList(),
                    listOf(
                        controller(0, address = "HU:01", canId = 41),
                        controller(1, address = "HU:01", canId = 41)
                    )
                )
            )
        )
        for ((name, d) in rejected) {
            assertTrue(validate(d).any { it.blocking }, "$name must be blocked")
            val v = d.asVehicle()
            assertFailsWith<IllegalArgumentException>("$name must be what planLinks refuses") {
                planLinks(v.packs, v.controllers)
            }
        }

        // The composer is STRICTLY stricter than `planLinks`, and this is the
        // list of where — each entry a contradiction `planLinks` structurally
        // cannot see, with the reason. Kept here rather than in its own test so
        // that "blocked but plans fine" stays an enumerated, argued set instead
        // of a silent gap in the agreement above.
        val beyondPlanLinks: List<Pair<String, VehicleDraft>> = listOf(
            // Its duplicate-CAN check is `mapNotNull { it.canId }`
            // (LinkPlan.kt:189) — deliberately, because before Part C every
            // source's id was null and "null" could not yet mean "the gateway
            // itself".
            "two controllers claiming to be the gateway itself" to VehicleDraft()
                .addController(ControllerType.VESC, "HU:01")
                .addController(ControllerType.VESC, "HU:01"),
            "two packs hosted by the same gateway" to draftOf(
                vehicle(
                    listOf(
                        pack(0, address = "HU:01", type = BmsType.VESC_BMS),
                        pack(1, address = "HU:01", type = BmsType.VESC_BMS)
                    ),
                    listOf(controller(0, address = "HU:01"))
                )
            )
        )
        for ((name, d) in beyondPlanLinks) {
            assertTrue(validate(d).any { it.blocking }, "$name must be blocked")
            val v = d.asVehicle()
            assertTrue(
                planLinks(v.packs, v.controllers).isNotEmpty(),
                "$name is blocked by the composer alone — planLinks accepts it"
            )
        }
    }
}
