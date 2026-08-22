package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertTrue

class RussianCityLabelsTest {
    @Test
    fun far_zoom_russia_labels_include_moscow_and_yekaterinburg() {
        assertTrue(russianCityLabels.any { it.name == "Москва" })
        assertTrue(russianCityLabels.any { it.name == "Екатеринбург" })
        assertTrue(russianCityLabels.size >= 15)
    }
}
