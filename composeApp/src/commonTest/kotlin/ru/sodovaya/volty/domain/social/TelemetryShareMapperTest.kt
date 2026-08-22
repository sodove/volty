package ru.sodovaya.volty.domain.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TelemetryShareMapperTest {
    @Test
    fun locationProfileSharesNoTelemetryFields() {
        val mapped = TelemetryShareMapper.map(
            profile = TelemetryShareProfile.LOCATION,
            earned = earnedTelemetry(),
        )

        assertFalse(mapped.speedKmh.supported)
        assertFalse(mapped.batterySocFraction.supported)
        assertFalse(mapped.cellDeltaV.supported)
        assertFalse(mapped.faults.supported)
    }

    @Test
    fun rideProfileSharesOnlyBasicRideState() {
        val mapped = TelemetryShareMapper.map(
            profile = TelemetryShareProfile.RIDE,
            earned = earnedTelemetry(),
        )

        assertTrue(mapped.speedKmh.supported)
        assertTrue(mapped.batterySocFraction.supported)
        assertTrue(mapped.powerW.supported)
        assertFalse(mapped.packVoltageV.supported)
        assertFalse(mapped.cellMinV.supported)
        assertFalse(mapped.escTempC.supported)
    }

    @Test
    fun fullProfilePreservesKnownAndUnknownCapabilities() {
        val earned = earnedTelemetry().copy(
            packVoltageV = TelemetryNumber.unknown(supported = true),
            motorTempC = TelemetryNumber.unsupported(),
        )

        val mapped = TelemetryShareMapper.map(TelemetryShareProfile.FULL, earned)

        assertTrue(mapped.packVoltageV.supported)
        assertFalse(mapped.packVoltageV.known)
        assertNull(mapped.packVoltageV.value)
        assertFalse(mapped.motorTempC.supported)
        assertFalse(mapped.motorTempC.known)
        assertEquals(0.3, mapped.cellDeltaV.value)
        assertTrue(mapped.faults.known)
    }

    @Test
    fun serializedSocialTelemetryContainsNoBleOrVehicleIdentifier() {
        val payload = Json.encodeToString(
            TelemetryShareMapper.map(TelemetryShareProfile.FULL, earnedTelemetry()),
        )

        assertFalse(payload.contains("ble", ignoreCase = true))
        assertFalse(payload.contains("vehicleId", ignoreCase = true))
        assertFalse(payload.contains("mac", ignoreCase = true))
    }

    private fun earnedTelemetry() = EarnedTelemetry(
        speedKmh = TelemetryNumber.known(21.5),
        batterySocFraction = TelemetryNumber.known(0.82),
        packVoltageV = TelemetryNumber.known(80.6),
        batteryCurrentA = TelemetryNumber.known(31.0),
        powerW = TelemetryNumber.known(2_498.6),
        escTempC = TelemetryNumber.known(42.0),
        motorTempC = TelemetryNumber.known(47.0),
        cellMinV = TelemetryNumber.known(3.91),
        cellMaxV = TelemetryNumber.known(4.01),
        cellDeltaV = TelemetryNumber.known(0.3),
        faults = TelemetryFaults.known(listOf("over-temperature")),
    )
}
