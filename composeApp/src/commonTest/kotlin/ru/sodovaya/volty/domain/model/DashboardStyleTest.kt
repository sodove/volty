package ru.sodovaya.volty.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardStyleTest {
    @Test
    fun the_map_hud_is_named_light() {
        assertEquals("LIGHT", DashboardStyle.entries.last().name)
    }

    @Test
    fun old_saved_vscape_name_is_read_as_light() {
        assertEquals(DashboardStyle.LIGHT, DashboardStyle.fromPersistedName("VESCAPE"))
    }
}
