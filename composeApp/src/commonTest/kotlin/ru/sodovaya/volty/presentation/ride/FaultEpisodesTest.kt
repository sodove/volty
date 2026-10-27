package ru.sodovaya.volty.presentation.ride

import kotlin.test.Test
import kotlin.test.assertEquals

class FaultEpisodesTest {
    @Test
    fun lingerOptionsMatchTheApprovedList() {
        assertEquals(listOf(0, 5, 10, 30, 60, 120, 300), LINGER_OPTIONS_SEC)
    }

    @Test
    fun everyOfferedLingerMapsToItself() {
        LINGER_OPTIONS_SEC.forEach { assertEquals(it, lingerOptionFor(it)) }
    }

    @Test
    fun zeroIsWhileActive() {
        assertEquals(0, lingerOptionFor(0))
        assertEquals(0, lingerOptionFor(-7))
    }

    @Test
    fun anUnlistedValueMapsToTheNearestOption() {
        assertEquals(30, lingerOptionFor(25))
        assertEquals(60, lingerOptionFor(90 - 31))
        assertEquals(120, lingerOptionFor(150))
        assertEquals(300, lingerOptionFor(9_999))
        assertEquals(5, lingerOptionFor(3))
    }

    @Test
    fun aTieGoesToTheShorterOption() {
        assertEquals(60, lingerOptionFor(90))
    }

    @Test
    fun lingerIsChosenByRowKindAndTheEpisodesWorstSeverity() {
        fun alert(sev: ru.sodovaya.volty.domain.usecase.AlertSeverity) = ru.sodovaya.volty.domain.alert.ActiveAlert(
            ru.sodovaya.volty.domain.usecase.AlertKind.DUTY_HIGH, 90f, sev
        )
        // three distinct values so a swapped argument cannot hide
        assertEquals(61, lingerSecFor(null, faultSec = 61, criticalSec = 31, warningSec = 11))
        assertEquals(31, lingerSecFor(alert(ru.sodovaya.volty.domain.usecase.AlertSeverity.CRITICAL), 61, 31, 11))
        assertEquals(11, lingerSecFor(alert(ru.sodovaya.volty.domain.usecase.AlertSeverity.WARNING), 61, 31, 11))
    }
}
