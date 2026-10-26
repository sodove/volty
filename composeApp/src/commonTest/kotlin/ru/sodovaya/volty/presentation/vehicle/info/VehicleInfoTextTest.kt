package ru.sodovaya.volty.presentation.vehicle.info

import ru.sodovaya.volty.domain.model.DeviceFactKey
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Exhaustiveness is the compiler's job (every `stringRes` is a `when` with no `else`). What a
 * compiler cannot see is two values sharing one string by a copy-paste slip, so each enum must map
 * injectively. That is checked here; whether a string reads well on the screen is a device matter.
 */
class VehicleInfoTextTest {
    private fun <T> assertInjective(what: String, values: List<T>, key: (T) -> String) {
        val keys = values.map(key)
        assertEquals(values.size, keys.toSet().size, "two $what values share one string resource: $keys")
    }

    @Test
    fun every_enum_maps_to_its_own_string() {
        assertInjective("section", InfoSectionKind.entries) { it.stringRes().key }
        assertInjective("label", InfoLabel.entries) { it.stringRes().key }
        assertInjective("absence", InfoAbsence.entries) { it.stringRes().key }
        assertInjective("choice", InfoChoice.entries) { it.stringRes().key }
        assertInjective("device fact", DeviceFactKey.entries) { it.stringRes().key }
    }

    @Test
    fun the_three_converted_units_follow_the_unit_system_and_the_rest_do_not() {
        for (unit in listOf(InfoUnit.SPEED, InfoUnit.DISTANCE, InfoUnit.CONSUMPTION)) {
            assertNotEquals(unit.stringRes(UnitSystem.METRIC).key, unit.stringRes(UnitSystem.IMPERIAL).key, "$unit")
        }
        for (unit in InfoUnit.entries - setOf(InfoUnit.SPEED, InfoUnit.DISTANCE, InfoUnit.CONSUMPTION)) {
            assertEquals(unit.stringRes(UnitSystem.METRIC).key, unit.stringRes(UnitSystem.IMPERIAL).key, "$unit")
        }
    }
}
