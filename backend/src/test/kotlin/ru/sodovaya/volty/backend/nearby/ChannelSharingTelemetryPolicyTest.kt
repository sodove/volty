package ru.sodovaya.volty.backend.nearby

import ru.sodovaya.volty.backend.*
import kotlin.test.*

class ChannelSharingTelemetryPolicyTest {
    private fun known(value: Double) = TelemetryNumberDto(true, true, value)
    private val absent = TelemetryNumberDto(false, false)

    @Test fun locationConsentNeverAcceptsTelemetryIncludingEntirelyUnknownPayload() {
        assertNull(ChannelSharingTelemetryPolicy.sanitize(ChannelSharingMode.LOCATION, null))
        assertFailsWith<IllegalArgumentException> {ChannelSharingTelemetryPolicy.sanitize(ChannelSharingMode.LOCATION, SharedTelemetryDto(profile = "LOCATION"))}
    }

    @Test fun rideIncludesOnlyEarnedRideMetricsAndCannotExposeFullOnlyFields() {
        val payload = SharedTelemetryDto(profile = "RIDE", speedKmh = known(12.0), batterySocFraction = known(0.0), powerW = known(-4200.0),
            packVoltageV = known(100.0), batteryCurrentA = known(-42.0), escTempC = known(40.0), motorTempC = known(50.0),
            cellMinV = known(3.9), cellMaxV = known(4.1), cellDeltaV = known(0.2), faults = TelemetryFaultsDto(true, true, listOf("FAULT")))
        val safe = assertNotNull(ChannelSharingTelemetryPolicy.sanitize(ChannelSharingMode.RIDE, payload))
        assertEquals(payload.speedKmh, safe.speedKmh); assertEquals(payload.batterySocFraction, safe.batterySocFraction); assertEquals(payload.powerW, safe.powerW)
        assertTrue(listOf(safe.packVoltageV, safe.batteryCurrentA, safe.escTempC, safe.motorTempC, safe.cellMinV, safe.cellMaxV, safe.cellDeltaV).all {it == absent})
        assertEquals(TelemetryFaultsDto(false, false), safe.faults)
    }

    @Test fun fullConsentStillCannotEarnUnsupportedOrUnknownNumbersFromIncoherentFixture() {
        val payload = SharedTelemetryDto(profile = "FULL", speedKmh = TelemetryNumberDto(true, false, 4200.0),
            batterySocFraction = TelemetryNumberDto(false, true, 0.8), packVoltageV = known(0.0), powerW = known(-4200.0),
            batteryCurrentA = TelemetryNumberDto(true, false, Double.NaN))
        val safe = assertNotNull(ChannelSharingTelemetryPolicy.sanitize(ChannelSharingMode.FULL, payload))
        assertEquals(TelemetryNumberDto(true, false), safe.speedKmh); assertEquals(absent, safe.batterySocFraction)
        assertEquals(TelemetryNumberDto(true, false), safe.batteryCurrentA)
        assertEquals(known(0.0), safe.packVoltageV); assertEquals(known(-4200.0), safe.powerW)
    }

    @Test fun unknownFaultsDiscardOldValuesWhileKnownEmptyFaultsRemainEarned() {
        val old = SharedTelemetryDto(profile = "FULL", faults = TelemetryFaultsDto(true, false, listOf("OLD_FAULT")))
        assertEquals(TelemetryFaultsDto(true, false), ChannelSharingTelemetryPolicy.sanitize(ChannelSharingMode.FULL, old)?.faults)
        val unsupported = old.copy(faults = TelemetryFaultsDto(false, true, listOf("UNSUPPORTED_FAULT")))
        assertEquals(TelemetryFaultsDto(false, false), ChannelSharingTelemetryPolicy.sanitize(ChannelSharingMode.FULL, unsupported)?.faults)
        val clear = old.copy(faults = TelemetryFaultsDto(true, true, emptyList()))
        assertEquals(clear.faults, ChannelSharingTelemetryPolicy.sanitize(ChannelSharingMode.FULL, clear)?.faults)
    }

    @Test fun invalidKnownValuesFaultsSchemaAndMismatchedConsentModeAreRejected() {
        val valid = SharedTelemetryDto(profile = "FULL", powerW = known(42.0))
        for (payload in listOf(valid.copy(schemaVersion = 2), valid.copy(profile = "RIDE"),
            valid.copy(powerW = TelemetryNumberDto(true, true, null)), valid.copy(powerW = known(Double.POSITIVE_INFINITY)),
            valid.copy(faults = TelemetryFaultsDto(true, true, null)), valid.copy(faults = TelemetryFaultsDto(true, true, listOf(""))),
            valid.copy(faults = TelemetryFaultsDto(true, true, listOf("x".repeat(257)))), valid.copy(faults = TelemetryFaultsDto(true, true, List(17) {"FAULT"})),
        )) assertFailsWith<IllegalArgumentException> {ChannelSharingTelemetryPolicy.sanitize(ChannelSharingMode.FULL, payload)}
        assertFailsWith<IllegalArgumentException> {ChannelSharingTelemetryPolicy.sanitize(ChannelSharingMode.RIDE, null)}
    }
}
