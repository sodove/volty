package ru.sodovaya.volty.presentation.common.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsChoiceStyleTest {
    @Test
    fun threeShortRealLabelsStaySegmented() {
        // Voice output / microphone source call sites, ru.
        assertEquals(ChoiceStyle.SEGMENTED, choiceStyleFor(listOf("Авто", "Наушники", "Динамик")))
        assertEquals(ChoiceStyle.SEGMENTED, choiceStyleFor(listOf("Авто", "Телефон", "Гарнитура")))
        // Theme, ru.
        assertEquals(ChoiceStyle.SEGMENTED, choiceStyleFor(listOf("Система", "Светлая", "Тёмная")))
    }

    @Test
    fun twoOptionsGetAWiderLabelBudget() {
        assertEquals(ChoiceStyle.SEGMENTED, choiceStyleFor(listOf("Метрические", "Имперские")))
        assertEquals(ChoiceStyle.SEGMENTED, choiceStyleFor(listOf("a".repeat(14), "b")))
        assertEquals(ChoiceStyle.DROPDOWN, choiceStyleFor(listOf("a".repeat(15), "b")))
    }

    @Test
    fun threeOptionsAllowTenCharactersExactly() {
        assertEquals(ChoiceStyle.SEGMENTED, choiceStyleFor(listOf("a".repeat(10), "b", "c")))
        assertEquals(ChoiceStyle.DROPDOWN, choiceStyleFor(listOf("a".repeat(11), "b", "c")))
    }

    @Test
    fun longRealAlertLabelsBecomeADropdown() {
        assertEquals(
            ChoiceStyle.DROPDOWN,
            choiceStyleFor(listOf("Приглушать музыку", "Пищать поверх музыки")),
        )
        assertEquals(
            ChoiceStyle.DROPDOWN,
            choiceStyleFor(listOf("Как подсказка навигатора", "Как будильник")),
        )
    }

    @Test
    fun moreThanThreeOptionsAreADropdownEvenWhenShort() {
        assertEquals(ChoiceStyle.DROPDOWN, choiceStyleFor(listOf("A", "B", "C", "D")))
    }

    @Test
    fun noOptionsIsADropdownNotADivisionByZero() {
        assertEquals(ChoiceStyle.DROPDOWN, choiceStyleFor(emptyList()))
    }
}
