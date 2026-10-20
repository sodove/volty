package ru.sodovaya.volty.domain.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioDuplicateRuleTest {
    private val low = "02:11:22:33:44:55"
    private val high = "0a:11:22:33:44:55"

    @Test
    fun smallerBssidWins() {
        assertTrue(RadioDuplicateRule.shouldYield(3, high, low))
        assertFalse(RadioDuplicateRule.shouldYield(3, low, high))
    }

    @Test
    fun ruleIsAntisymmetric() {
        val a = RadioDuplicateRule.shouldYield(1, low, high)
        val b = RadioDuplicateRule.shouldYield(1, high, low)
        assertEquals(1, listOf(a, b).count { it })
        // letter case must not break the ordering
        assertEquals(a, RadioDuplicateRule.shouldYield(1, low.uppercase(), high.lowercase()))
    }

    @Test
    fun equalBssidNeverYields() {
        assertFalse(RadioDuplicateRule.shouldYield(1, low, low))
        assertFalse(RadioDuplicateRule.shouldYield(1, low, low.uppercase()))
    }
}
