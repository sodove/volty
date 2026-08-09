package ru.sodovaya.volty.presentation.vehicle

import ru.sodovaya.volty.data.ble.ProtocolKind
import ru.sodovaya.volty.data.ble.isGatewayLink
import ru.sodovaya.volty.data.ble.planLinks
import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.data.bms.vesc.VescSetupConfig
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.MotorConfigProvenance
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /**
     * Creation projects the whole draft, rather than rebuilding just its first
     * pack. Dropping either source would turn two separately dialled devices
     * back into the old single-BMS vehicle at Save.
     */
    @Test
    fun `a new vehicle keeps every source from a two-link draft`() {
        val draft = VehicleDraft()
            .addController(ControllerType.VESC, "CT:01", "Controller")
            .addPack(BmsType.ANT_BMS, "PK:02", "Battery")

        val vehicle = newVehicleFromDraft(
            id = "new",
            name = "Bike",
            iconKey = "ebike",
            draft = draft,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = createdAt
        )

        assertEquals(listOf("PK:02"), vehicle.packs.map { it.bmsAddress })
        assertEquals(listOf("CT:01"), vehicle.controllers.map { it.address })
        assertEquals(listOf("PK:02", "CT:01"), planLinks(vehicle.packs, vehicle.controllers).map { it.address })
    }

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
     * The dumb-wheel case Task 3 exists for: no smart BMS ever confirms a
     * count, so the rider's own typed value is the ONLY way this pack gets
     * one — and it must win over whatever `origin` still holds, unlike every
     * OTHER pack field the composer does not edit.
     */
    @Test
    fun `an edited cell count overrides the stored one`() {
        val loaded = vehicle(
            packs = listOf(pack(0, "Wheel", BmsType.BEGODE, "WH:01", cellCount = null)),
            controllers = listOf(controller(0, "C", ControllerType.BEGODE, "WH:01"))
        )
        val d = draftOf(loaded).let {
            it.updatePack(it.packs.single().key) { p -> p.copy(cellCount = 24, cellCountEdited = true) }
        }

        assertEquals(24, d.toPacks().single().cellCount)
    }

    /**
     * The complement of the test above, stated directly on `toPack` rather
     * than through a whole-vehicle re-anchor: touching a DIFFERENT field on
     * the same pack must not turn on [PackDraft.cellCountEdited] by itself —
     * `mutateDraft`'s `packsEdited` is form-wide and would otherwise be the
     * only signal, which is exactly the granularity `reanchoredTo`'s KDoc
     * warns is too coarse for a field-level guarantee.
     */
    @Test
    fun `an unedited cell count still comes off origin after another field changes`() {
        val origin = pack(0, "Wheel", BmsType.BEGODE, "WH:01", cellCount = 40)
        val d = PackDraft(key = "p0", label = "Wheel", bmsType = BmsType.BEGODE, address = "WH:01", origin = origin)
            .copy(label = "Renamed")

        assertEquals(false, d.cellCountEdited, "label alone must not mark the cell count touched")
        assertEquals(40, d.toPack(0).cellCount, "and the stored count must still come through")
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
    fun `a FarDriver controller is decodable and is not reported`() {
        val d = VehicleDraft().addController(ControllerType.FARDRIVER, "FD:01")
        assertEquals(emptyList(), validate(d))
    }

    @Test
    fun `a decodable controller type is not reported`() {
        val d = VehicleDraft()
            .addController(ControllerType.VESC, "VE:01")
            .addController(ControllerType.KELLY, "KL:01")
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
            // A FarDriver is a normal decodable controller and still has to
            // PLAN like every other controller-only draft.
            "FarDriver controller" to VehicleDraft().addController(ControllerType.FARDRIVER, "FD:01"),
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

    // -----------------------------------------------------------------------
    // G2 Task 3: what the screen needs in order to be a renderer
    // -----------------------------------------------------------------------

    /**
     * **Every issue kind, and the rows it must appear on.** Enumerated rather
     * than sampled: the three per-address kinds carry a BLE address instead of
     * a key, so each has to re-walk the draft, and a card printing only the
     * issues that carry its own key would leave the two blocking address issues
     * with nowhere to appear but a banner naming a MAC.
     *
     * The head-unit fixture is deliberately the one shape where every kind is
     * reachable at once and where "which row?" has more than one candidate —
     * three sources on one link, so an implementation that answered "all of
     * them" for [ComposerIssue.DuplicateCanId] fails on the innocent third.
     */
    @Test
    fun `each issue names the rows that must show it`() {
        val gateway = draftOf(
            vehicle(
                packs = listOf(pack(0, "Hosted", BmsType.VESC_BMS, "HU:01", canId = 41)),
                controllers = listOf(
                    controller(0, "uBox L", ControllerType.VESC, "HU:01", canId = 41),
                    controller(1, "uBox R", ControllerType.VESC, "HU:01", canId = 42)
                )
            )
        )
        // p0 and c0 share id 41; c1 is on the same link and innocent.
        assertEquals(
            listOf("c0", "p0"),
            ComposerIssue.DuplicateCanId("HU:01", 41).affectedKeys(gateway),
            "only the rows actually holding the duplicated id"
        )
        assertEquals(
            listOf("c0", "c1", "p0"),
            ComposerIssue.ConflictingKinds("HU:01", emptySet()).affectedKeys(gateway),
            "a link that cannot resolve implicates everything on it"
        )
        assertEquals(
            listOf("c0", "c1", "p0"),
            ComposerIssue.UnroutableGateway("HU:01", ProtocolKind.VESC).affectedKeys(gateway),
            "so does a link nothing can multiplex"
        )
        assertEquals(listOf("c1"), ComposerIssue.BlankAddress("c1").affectedKeys(gateway))
        assertEquals(
            listOf("c0"),
            ComposerIssue.NoControllerDecoder("c0", ControllerType.FARDRIVER).affectedKeys(gateway)
        )
        assertEquals(listOf("p0"), ComposerIssue.HostlessVescBms("p0").affectedKeys(gateway))
        assertEquals(
            listOf("c0", "c1"),
            ComposerIssue.AmbiguousGatewaySource("HU:01", listOf("c0", "c1")).affectedKeys(gateway)
        )
        // An address no source sits at names nobody rather than throwing — the
        // screen asks for a key it has, and a stale issue must not crash it.
        assertEquals(emptyList(), ComposerIssue.ConflictingKinds("GONE", emptySet()).affectedKeys(gateway))
    }

    /**
     * The index the cards are actually rendered from: a source with nothing
     * wrong is absent, and a source with two problems keeps both, in
     * [validate]'s order.
     */
    @Test
    fun `issues are indexed by the source card that must show them`() {
        val d = VehicleDraft()
            .addController(ControllerType.FARDRIVER, "") // no decoder AND no link
            .addPack(BmsType.ANT_BMS, "AN:01") // nothing wrong
        val index = issuesBySource(d, validate(d))

        assertEquals(setOf("c-new-0"), index.keys, "the clean pack must not appear at all")
        assertEquals(
            listOf(ComposerIssue.BlankAddress("c-new-0")),
            index.getValue("c-new-0")
        )
    }

    /**
     * The one-tap "same link as" chips. Controllers lead because a gateway is a
     * controller; a link is listed once however many sources share it; and a
     * blank address is not a link a rider can mean — it is the absence of one,
     * which [ComposerIssue.BlankAddress] already says.
     */
    @Test
    fun `the link list names each link once, controllers first`() {
        // The DIRECT pack is listed first on the vehicle, so "controllers
        // first" and "packs first" genuinely disagree here. With the hosted
        // pack first they would not: it shares the controllers' link, and
        // `distinct()` would hide the difference — which is how a weaker
        // fixture makes an ordering claim unfalsifiable.
        val d = draftOf(
            vehicle(
                packs = listOf(
                    pack(0, "Direct", BmsType.ANT_BMS, "AN:01"),
                    pack(1, "Hosted", BmsType.VESC_BMS, "HU:01")
                ),
                controllers = listOf(
                    controller(0, "uBox L", ControllerType.VESC, "HU:01"),
                    controller(1, "uBox R", ControllerType.VESC, "HU:01")
                )
            )
        )
        assertEquals(listOf("HU:01", "AN:01"), d.linkAddresses)
        assertEquals(
            emptyList(),
            VehicleDraft().addController(ControllerType.VESC, "").linkAddresses,
            "a source with no link offers none"
        )
    }

    // -----------------------------------------------------------------------
    // Motor geometry, per controller (G2 Task 3)
    // -----------------------------------------------------------------------

    /**
     * The rule the deleted flat Motor card carried: a cleared box is not a
     * zero, it is "unset", and it resolves to [MotorConfig]'s own default only
     * on the way out.
     *
     * Every field is exercised because each has a different default and a
     * different type — a `?: 0` would satisfy `wheelDiameterMm` alone.
     */
    @Test
    fun `a blank motor field resolves to MotorConfig's default`() {
        assertEquals(MotorConfig(), MotorDraft(null, null, null).resolve())
        assertEquals(
            MotorConfig(polePairs = 7, wheelDiameterMm = MotorConfig().wheelDiameterMm, gearRatio = 2f),
            MotorDraft(polePairs = 7, wheelDiameterMm = null, gearRatio = 2f).resolve()
        )
        // Round trip: a stored geometry survives being loaded into the draft.
        val stored = MotorConfig(polePairs = 9, wheelDiameterMm = 400, gearRatio = 2.5f)
        assertEquals(stored, MotorDraft.of(stored).resolve())
        // And an untouched draft IS the default, so a freshly added controller
        // shows the geometry it will be saved with rather than three empty
        // boxes.
        assertEquals(MotorDraft.of(MotorConfig()), MotorDraft())
    }

    // -----------------------------------------------------------------------
    // A CAN-discovered controller inherits the gateway's wheel (Part I Task 6)
    // -----------------------------------------------------------------------

    /** A gateway whose wheel a rider has already measured, plus one slave on its bus. */
    private val measuredWheel = MotorConfig(polePairs = 21, wheelDiameterMm = 500, gearRatio = 3.5f)

    /**
     * **The defect, in one assertion.** A CAN-discovered controller used to be
     * created with a bare `MotorConfig()` — `wheelDiameterMm = 0`, which
     * `VescValues.derivedSpeedKmh` refuses to derive a speed from — even though
     * the rider had already told us the wheel on the gateway sitting on the same
     * link.
     *
     * The value asserted is [measuredWheel], not merely "not the default": a
     * diameter is the field that matters, but a fix that copied only the
     * diameter and left the pole pairs at 15 would produce a speed off by a
     * factor of 1.4 and pass a `wheelDiameterMm != 0` assertion. All three
     * fields, and all three differ from `MotorConfig()`'s own.
     */
    @Test
    fun `a CAN-discovered controller inherits the gateway's motor config`() {
        val d = VehicleDraft()
            .addController(ControllerType.VESC, "HU:01", "Head unit", motor = MotorDraft.of(measuredWheel))
            .addCanController(ControllerType.VESC, "HU:01", "CAN 10", canId = 10)

        assertEquals(MotorDraft.of(measuredWheel), d.controllers[1].motor)
        assertEquals(measuredWheel, d.toControllers()[1].motor, "…and it survives the save")
        assertEquals(10, d.controllers[1].canId, "the id the add already carried is untouched")
        // The point of all of it, asserted through the decoder that consumes it:
        // the eRPM fallback is now OPEN for this slave. Stated on the saved
        // MotorConfig, because that is the object VescValues is actually handed.
        assertNotNull(
            VescValues.derivedSpeedKmh(3000f, d.toControllers()[1].motor),
            "the eRPM->speed fallback is available to the slave now"
        )
    }

    /**
     * The negative half, and the reason this is not "copy anything you find":
     * an add by hand must NOT inherit. A rider adding a controller by typing an
     * address has not said it shares a wheel with anything — only a CAN scan of
     * a specific gateway says that — and silently prefilling a diameter they
     * never measured is the confident-wrong-speed failure this task's other half
     * exists to prevent.
     */
    @Test
    fun `a hand-added controller on the same address inherits nothing`() {
        val d = VehicleDraft()
            .addController(ControllerType.VESC, "HU:01", "Head unit", motor = MotorDraft.of(measuredWheel))
            .addController(ControllerType.VESC, "HU:01", "Typed by hand")

        assertEquals(MotorDraft(), d.controllers[1].motor)
        assertEquals(0, d.toControllers()[1].motor.wheelDiameterMm, "unset, so the speed reads as unknown")
    }

    @Test
    fun `a controller answer replaces inherited geometry but not a rider edit`() {
        val gatewayMotor = MotorConfig(polePairs = 21, wheelDiameterMm = 500, gearRatio = 3.5f)
        val controllerMotor = MotorConfig(polePairs = 15, wheelDiameterMm = 600, gearRatio = 1f)
        val config = VescSetupConfig(
            maxErpm = 100_000f,
            maxWattsOut = 10_000f,
            maxInputCurrentA = 60f,
            motorPoles = 30,
            gearRatio = 1f,
            wheelDiameterM = .6f
        )
        val inherited = VehicleDraft()
            .addController(ControllerType.VESC, "HU:01", "Head", motor = MotorDraft.of(gatewayMotor))
            .addCanController(ControllerType.VESC, "HU:01", "Slave", canId = 10)
        val measured = inherited.applyControllerSetup(inherited.controllers[1].key, config)
        assertEquals(MotorDraft.of(controllerMotor), measured.controllers[1].motor)
        assertEquals(MotorConfigProvenance.CONTROLLER, measured.controllers[1].motorProvenance)

        val rider = inherited.updateController(inherited.controllers[1].key) {
            it.copy(motor = MotorDraft.of(MotorConfig(wheelDiameterMm = 700)), motorProvenance = MotorConfigProvenance.RIDER)
        }
        val preserved = rider.applyControllerSetup(rider.controllers[1].key, config)
        assertEquals(700, preserved.controllers[1].motor.resolve().wheelDiameterMm)
        assertEquals(MotorConfigProvenance.RIDER, preserved.controllers[1].motorProvenance)
    }

    @Test
    fun `controller geometry answer distinguishes unconfigured and mismatched rider values`() {
        val base = VehicleDraft().addController(ControllerType.VESC, "VESC", "Bike")
        val key = base.controllers.single().key
        val unconfigured = base.applyControllerSetup(
            key,
            VescSetupConfig(100f, 100f, 10f, 30, 1f, 0f)
        )
        assertTrue(validate(unconfigured).any { it is ComposerIssue.ControllerGeometryUnconfigured })

        val typed = base.updateController(key) {
            it.copy(
                motor = MotorDraft.of(MotorConfig(wheelDiameterMm = 700)),
                motorProvenance = MotorConfigProvenance.RIDER
            )
        }.applyControllerSetup(
            key,
            VescSetupConfig(100f, 100f, 10f, 30, 1f, .6f)
        )
        val mismatch = validate(typed).filterIsInstance<ComposerIssue.ControllerGeometryMismatch>().single()
        assertEquals(600, mismatch.controllerDiameterMm)
        assertEquals(700, mismatch.enteredDiameterMm)

        val agreeing = base.updateController(key) {
            it.copy(
                motor = MotorDraft.of(MotorConfig(wheelDiameterMm = 600)),
                motorProvenance = MotorConfigProvenance.RIDER
            )
        }.applyControllerSetup(
            key,
            VescSetupConfig(100f, 100f, 10f, 30, 1f, .6f)
        )
        assertTrue(validate(agreeing).none { it is ComposerIssue.ControllerGeometryMismatch })
    }

    /**
     * Which controller on the link IS the gateway. The null [ControllerDraft.canId]
     * is the definition (`C §6`: its requests go out unwrapped); the lowest id is
     * the fallback for a draft assembled out of discovery before the head unit
     * itself was added, and it is order-independent so a re-scan cannot change
     * the answer.
     */
    @Test
    fun `the gateway is the null-id controller on that link, else the lowest id`() {
        val other = MotorConfig(polePairs = 7, wheelDiameterMm = 200, gearRatio = 2f)

        // The null id wins even when it was added last and carries the higher id's
        // neighbours on either side — position must not be what decides.
        val withHeadUnit = VehicleDraft()
            .addController(ControllerType.VESC, "HU:01", "Slave", canId = 10, motor = MotorDraft.of(other))
            .addController(ControllerType.VESC, "HU:01", "Head unit", motor = MotorDraft.of(measuredWheel))
        assertEquals(MotorDraft.of(measuredWheel), withHeadUnit.gatewayMotorAt("HU:01"))

        // No null id anywhere: the lowest CAN id, regardless of add order.
        val slavesOnly = VehicleDraft()
            .addController(ControllerType.VESC, "HU:01", "Slave 11", canId = 11, motor = MotorDraft.of(other))
            .addController(ControllerType.VESC, "HU:01", "Slave 3", canId = 3, motor = MotorDraft.of(measuredWheel))
        assertEquals(MotorDraft.of(measuredWheel), slavesOnly.gatewayMotorAt("HU:01"))

        // A controller on a DIFFERENT link is not this link's gateway, however
        // null its id: a vehicle with two BLE links has two gateways.
        val twoLinks = VehicleDraft()
            .addController(ControllerType.VESC, "AN:01", "Other link", motor = MotorDraft.of(measuredWheel))
            .addController(ControllerType.VESC, "HU:01", "This link", motor = MotorDraft.of(other))
        assertEquals(MotorDraft.of(other), twoLinks.gatewayMotorAt("HU:01"))

        // Nothing on the link at all: the bare default. Inventing a diameter is
        // worse than admitting there is not one.
        assertEquals(MotorDraft(), VehicleDraft().gatewayMotorAt("HU:01"))
    }

    /**
     * Inheritance is a **snapshot**, not a live link. A rider who later corrects
     * the gateway's wheel must not have a second controller silently change
     * under them, and a slave with a genuinely different wheel must stay one
     * edit away rather than being unrepresentable.
     */
    @Test
    fun `the inherited motor config is a snapshot and stays independently editable`() {
        val d = VehicleDraft()
            .addController(ControllerType.VESC, "HU:01", "Head unit", motor = MotorDraft.of(measuredWheel))
            .addCanController(ControllerType.VESC, "HU:01", "CAN 10", canId = 10)

        val gatewayKey = d.controllers[0].key
        val corrected = d.updateController(gatewayKey) { it.copy(motor = it.motor.copy(wheelDiameterMm = 660)) }
        assertEquals(660, corrected.controllers[0].motor.wheelDiameterMm, "precondition: the gateway moved")
        assertEquals(500, corrected.controllers[1].motor.wheelDiameterMm, "the slave did not follow it")

        val slaveKey = d.controllers[1].key
        val rewheeled = d.updateController(slaveKey) { it.copy(motor = it.motor.copy(wheelDiameterMm = 300)) }
        assertEquals(300, rewheeled.controllers[1].motor.wheelDiameterMm, "the slave is editable on its own")
        assertEquals(500, rewheeled.controllers[0].motor.wheelDiameterMm, "and the gateway did not follow it")
    }

    // -----------------------------------------------------------------------
    // "+ Wheel" as a single add (G §3 flow 3, G2 Task 5)
    // -----------------------------------------------------------------------

    /**
     * **The point of the add**, in the terms Task 3's review corrected: not the
     * tap it saves, but that the two sources land on ONE link — a claim only a
     * rider can make, and the only place it can be stored.
     *
     * Asserted through the real `planLinks`, not by comparing two addresses:
     * "one link" is a `planLinks` outcome, and comparing strings would pass for
     * a shape the connection layer still split in two.
     */
    @Test
    fun `a wheel add puts both sources on one link`() {
        val d = VehicleDraft().addWheel(ControllerType.BEGODE, BmsType.BEGODE, "WH:01")
        val links = planLinks(d.toPacks(), d.toControllers())
        assertEquals(1, links.size, "a wheel is one device, so one link")
        val link = links.single()
        assertEquals("WH:01", link.address)
        assertEquals(ProtocolKind.BEGODE, link.protocolKind)
        assertEquals(1, link.ownedControllers.size)
        assertEquals(1, link.ownedPacks.size)
    }

    /**
     * The contrast that makes the previous test mean something: the two blank
     * adds the screen offers beside it produce two sources the connection layer
     * groups SEPARATELY, because it groups by address and nothing told it they
     * are one device.
     */
    @Test
    fun `two separate adds at two addresses are two links`() {
        val d = VehicleDraft()
            .addController(ControllerType.BEGODE, "WH:01")
            .addPack(BmsType.BEGODE, "WH:02")
        assertEquals(2, planLinks(d.toPacks(), d.toControllers()).size)
    }

    @Test
    fun `a wheel add gives its two halves distinct keys`() {
        val d = VehicleDraft().addWheel(ControllerType.BEGODE, BmsType.BEGODE, "WH:01")
        assertEquals(1, d.controllers.size)
        assertEquals(1, d.packs.size)
        assertTrue(
            d.controllers.single().key != d.packs.single().key,
            "a shared key would make the screen address the wrong row"
        )
        // Both halves keep the rider-visible name they were added with.
        val named = VehicleDraft().addWheel(ControllerType.BEGODE, BmsType.BEGODE, "WH:01", "Monster")
        assertEquals("Monster", named.controllers.single().label)
        assertEquals("Monster", named.packs.single().label)
    }

    @Test
    fun `device-is-both completes an existing direct controller instead of adding it twice`() {
        val existing = VehicleDraft()
            .addController(ControllerType.BEGODE, "WH:01", "Monster")

        val completed = existing.addDeviceAsBoth(
            controllerType = ControllerType.BEGODE,
            bmsType = BmsType.BEGODE,
            address = "WH:01",
            label = "Monster"
        )

        val link = planLinks(completed.toPacks(), completed.toControllers()).single()
        assertEquals(1, link.ownedControllers.size)
        assertEquals(1, link.ownedPacks.size)
        assertEquals("WH:01", link.address)
    }

    /**
     * `G §6` through the wheel: the pack the same add created is what turns the
     * controller's derived battery off, and the rule — not a constant — is what
     * says so, so removing the pack turns it back on.
     *
     * (`wheelVehicle` hard-codes `providesDerivedBattery = false`; a composed
     * wheel gets the same answer from [VehicleDraft.derivedBatteryDefault]
     * while the pack exists, and a better one afterwards.)
     */
    @Test
    fun `a composed wheel derives no battery while its own pack is there`() {
        val d = VehicleDraft().addWheel(ControllerType.BEGODE, BmsType.BEGODE, "WH:01")
        val c = d.controllers.single()
        assertFalse(d.resolvedDerivedBattery(c))
        assertFalse(d.toControllers().single().providesDerivedBattery)

        val withoutPack = d.removePack(d.packs.single().key)
        assertTrue(
            withoutPack.resolvedDerivedBattery(withoutPack.controllers.single()),
            "the rule, not a constant: with the pack gone the controller is the only battery source"
        )
    }

    /** A wheel add is a valid draft — it must not raise an issue of its own. */
    @Test
    fun `a wheel add is well formed`() {
        val d = VehicleDraft().addWheel(ControllerType.BEGODE, BmsType.BEGODE, "WH:01")
        assertEquals(emptyList(), validate(d))
    }

    // -----------------------------------------------------------------------
    // The CAN id an added source carries (G2 Task 5)
    // -----------------------------------------------------------------------

    /**
     * The trap Task 2 built [ComposerIssue.AmbiguousGatewaySource] for: on a
     * gateway link `canId == null` means "the head unit itself", and
     * `VescGatewayProtocol.frameFor` sends such a request UNWRAPPED — so two of
     * them are byte-identical and `MotionAggregator` sums the same decode twice.
     *
     * Discovery therefore adds every node WITH its id, which the two adds must
     * be able to carry — this pins that they do, and that the resulting
     * two-uBox shape validates clean.
     */
    @Test
    fun `adds carry a can id, and two identified uBoxes behind one gateway are clean`() {
        val d = VehicleDraft()
            .addController(ControllerType.VESC, "HU:01", "Head unit")
            .addController(ControllerType.VESC, "HU:01", "Controller 2", canId = 10)
            .addController(ControllerType.VESC, "HU:01", "Controller 3", canId = 11)
            .addPack(BmsType.VESC_BMS, "HU:01", "Hosted battery")
        assertEquals(listOf(null, 10, 11), d.controllers.map { it.canId })
        assertEquals(listOf(null), d.packs.map { it.canId })
        assertEquals(emptyList(), validate(d), "one gateway, one head unit, two identified slaves")
        // And the real planner agrees it is one gateway link.
        val link = planLinks(d.toPacks(), d.toControllers()).single()
        assertTrue(link.isGatewayLink)
    }

    /**
     * The same shape with the ids dropped — which is what an add that ignored
     * `canId` would produce — is BLOCKING. Without this the test above would
     * pass just as well against an `addController` that threw the id away.
     */
    @Test
    fun `the same two slaves without ids are the blocking gateway shape`() {
        val d = VehicleDraft()
            .addController(ControllerType.VESC, "HU:01", "Head unit")
            .addController(ControllerType.VESC, "HU:01", "Controller 2")
            .addController(ControllerType.VESC, "HU:01", "Controller 3")
        val issues = validate(d)
        assertTrue(
            issues.any { it is ComposerIssue.AmbiguousGatewaySource && it.blocking },
            "three controllers all claiming to be the head unit must be refused, got $issues"
        )
    }

    /** A hosted battery and the head unit are one of each, which is legal. */
    @Test
    fun `a null-id pack beside a null-id controller is fine, a second pack is not`() {
        val ok = VehicleDraft()
            .addController(ControllerType.VESC, "HU:01", canId = 10)
            .addPack(BmsType.VESC_BMS, "HU:01", "Hosted battery")
        assertEquals(emptyList(), validate(ok))

        val doubled = ok.addPack(BmsType.VESC_BMS, "HU:01", "Hosted battery again")
        assertTrue(doubled.let(::validate).any { it is ComposerIssue.AmbiguousGatewaySource })
    }
}
