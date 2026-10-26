package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.BleDiagnosticLink
import ru.sodovaya.volty.domain.model.DeviceFact
import ru.sodovaya.volty.domain.model.DeviceFactKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolFactsTest {
    private class Reporting : ProtocolFacts {
        override fun deviceFacts() = listOf(
            DeviceFact(DeviceFactKey.ODOMETER_KM, "1234.5", "km"),
            DeviceFact(DeviceFactKey.BRANCH_VOLTAGE_V, "84.1", "V", index = 1),
        )
    }

    @Test
    fun aProtocolThatImplementsFactsHasThemRead() {
        val facts = deviceFactsOf(Reporting())
        assertEquals(2, facts.size)
        assertEquals(DeviceFactKey.ODOMETER_KM, facts[0].key)
        assertNull(facts[0].index)
        assertEquals(1, facts[1].index)
    }

    @Test
    fun anyOtherProtocolHasNone() {
        assertTrue(deviceFactsOf(Any()).isEmpty())
        assertTrue(deviceFactsOf(null).isEmpty())
    }

    @Test
    fun aLinkWithNothingToSayDefaultsToEmptyAndUnmeasured() {
        val link = BleDiagnosticLink("AA", null, "BEGODE", null, null, null, null)
        assertTrue(link.facts.isEmpty())
        assertNull(link.framesPerSecond)
        assertEquals(0, link.reconnects)
    }
}
