package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.util.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class RideDistanceMapperTest {
    @Test
    fun `distance strip distinguishes a missing controller counter from a genuine zero`() {
        val knownZero = ControllerData(odometerKm = 0f, tripKm = 0f, hasDistance = true)
        val unavailable = knownZero.copy(hasDistance = false)

        assertEquals("0.0 km", RideDistanceMapper.odometerValue(knownZero, UnitSystem.METRIC))
        assertEquals("0.0 km", RideDistanceMapper.tripValue(knownZero, UnitSystem.METRIC))
        assertEquals(UNKNOWN_READOUT, RideDistanceMapper.odometerValue(unavailable, UnitSystem.METRIC))
        assertEquals(UNKNOWN_READOUT, RideDistanceMapper.tripValue(unavailable, UnitSystem.METRIC))
    }
}
