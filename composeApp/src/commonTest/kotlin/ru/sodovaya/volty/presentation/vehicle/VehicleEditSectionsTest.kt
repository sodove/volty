package ru.sodovaya.volty.presentation.vehicle

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VehicleEditSectionsTest {

    private fun controller(
        key: String = "c0",
        address: String = "WH:01",
        type: ControllerType = ControllerType.BEGODE,
        derived: DerivedBatteryChoice = DerivedBatteryChoice.ON,
        typedS: Int? = null,
        motor: MotorDraft = MotorDraft()
    ) = ControllerDraft(
        key = key, label = "", controllerType = type, address = address,
        derivedBattery = derived, derivedCellCount = typedS, motor = motor
    )

    private fun pack(key: String = "p0", address: String = "BMS:01", cells: Int? = null) =
        PackDraft(key = key, label = "", bmsType = BmsType.JK_BMS, address = address, cellCount = cells)

    @Test
    fun `battery summary names the source and marks a learned count`() {
        val draft = VehicleDraft(controllers = listOf(controller(typedS = null)))

        val learned = batterySummary(draft, Chemistry.LI_ION_NMC, learnedCellCount = 24)
        assertEquals(BatterySource.FROM_CONTROLLER, learned.source)
        assertEquals(24, learned.cellCount)
        assertEquals(true, learned.cellCountLearned)
        assertEquals(Chemistry.LI_ION_NMC, learned.chemistry)

        val typed = batterySummary(
            VehicleDraft(controllers = listOf(controller(typedS = 20))), Chemistry.LI_ION_NMC, learnedCellCount = 24
        )
        assertEquals(20, typed.cellCount, "a typed count outranks the learned one")
        assertEquals(false, typed.cellCountLearned)
    }

    @Test
    fun `battery summary without any count is honest about it`() {
        val s = batterySummary(VehicleDraft(controllers = listOf(controller())), Chemistry.LIFEPO4, learnedCellCount = null)
        assertEquals(BatterySource.FROM_CONTROLLER, s.source)
        assertNull(s.cellCount)
        assertEquals(false, s.cellCountLearned)
    }

    @Test
    fun `battery summary of an own BMS reads the pack count and falls back to the learned one`() {
        val own = batterySummary(
            VehicleDraft(packs = listOf(pack(cells = 16)), controllers = listOf(controller(address = "VESC:1", derived = DerivedBatteryChoice.AUTO))),
            Chemistry.LI_ION_NMC, learnedCellCount = null
        )
        assertEquals(BatterySource.OWN_BMS, own.source)
        assertEquals(16, own.cellCount)
        assertEquals(false, own.cellCountLearned)

        val unset = batterySummary(VehicleDraft(packs = listOf(pack(cells = null))), Chemistry.LI_ION_NMC, learnedCellCount = 24)
        assertEquals(24, unset.cellCount)
        assertEquals(true, unset.cellCountLearned)
    }

    @Test
    fun `a pack on the controller's own address is one device serving both`() {
        val s = batterySummary(
            VehicleDraft(packs = listOf(pack(address = "WH:01")), controllers = listOf(controller(address = "WH:01"))),
            Chemistry.LI_ION_NMC, learnedCellCount = null
        )
        assertEquals(BatterySource.DEVICE_IS_BOTH, s.source)
    }

    @Test
    fun `a controller that backs no battery and no packs is no battery at all`() {
        val s = batterySummary(
            VehicleDraft(controllers = listOf(controller(derived = DerivedBatteryChoice.OFF))),
            Chemistry.LI_ION_NMC, learnedCellCount = 24
        )
        assertEquals(BatterySource.NONE, s.source)
        assertNull(s.cellCount, "no battery, no count - even if the wheel once told us one")
    }

    @Test
    fun `motor summary says when the diameter is unset`() {
        val unset = motorSummary(VehicleDraft(controllers = listOf(controller(motor = MotorDraft(15, 0, 1f)))))
        assertEquals(15, unset.polePairs)
        assertNull(unset.wheelDiameterMm)

        val cleared = motorSummary(VehicleDraft(controllers = listOf(controller(motor = MotorDraft(null, null, null)))))
        assertNull(cleared.polePairs)
        assertNull(cleared.wheelDiameterMm)

        val set = motorSummary(VehicleDraft(controllers = listOf(controller(motor = MotorDraft(21, 500, 3.5f)))))
        assertEquals(500, set.wheelDiameterMm)

        val none = motorSummary(VehicleDraft())
        assertNull(none.polePairs)
        assertNull(none.wheelDiameterMm)
    }

    @Test
    fun `controller summary is the first controller plus a count of the rest`() {
        assertNull(controllerSummary(VehicleDraft()))
        val one = controllerSummary(VehicleDraft(controllers = listOf(controller(type = ControllerType.VESC, address = "AA:BB"))))!!
        assertEquals(ControllerType.VESC, one.type)
        assertEquals("AA:BB", one.address)
        assertEquals(0, one.others)

        val three = controllerSummary(
            VehicleDraft(controllers = listOf(controller("a"), controller("b", address = "X"), controller("c", address = "Y")))
        )!!
        assertEquals(2, three.others)
    }
}
