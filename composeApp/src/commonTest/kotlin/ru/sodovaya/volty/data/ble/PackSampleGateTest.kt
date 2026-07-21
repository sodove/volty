package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate decides whether a pack genuinely produced a new sample.
 *
 * [ConnectionSession] asks every protocol for every pack's `latestData` on
 * every notification, and protocols cache their last decode — so without the
 * gate a Begode branch that fell out of circuit keeps getting re-submitted
 * with its frozen data at the notification rate, its `lastSeenAt` keeps
 * refreshing, and [VehicleConnection]'s staleness check can never fire.
 *
 * The discriminator is instance IDENTITY, not equality: every protocol
 * replaces its cached `BmsData` with a fresh instance on every successful
 * decode (see the identity-contract test in `BegodeProtocolTest`), while a
 * silent pack keeps returning the exact same instance. Structural equality
 * would be wrong here — a parked vehicle can decode identical values for
 * seconds on end, and those decodes are real liveness.
 */
class PackSampleGateTest {

    @Test
    fun aFreshInstancePasses() {
        val gate = PackSampleGate(packCount = 2)
        assertTrue(gate.advance(0, BmsData(voltage = 100f)))
    }

    @Test
    fun theSameInstanceIsSuppressed() {
        val gate = PackSampleGate(packCount = 2)
        val sample = BmsData(voltage = 100f)
        assertTrue(gate.advance(0, sample))
        assertFalse(gate.advance(0, sample), "cached re-read must not count as a sample")
        assertFalse(gate.advance(0, sample))
    }

    @Test
    fun aNewInstanceAfterSuppressionPassesAgain() {
        val gate = PackSampleGate(packCount = 2)
        val first = BmsData(voltage = 100f)
        assertTrue(gate.advance(0, first))
        assertFalse(gate.advance(0, first))
        assertTrue(gate.advance(0, BmsData(voltage = 100.1f)))
    }

    @Test
    fun structurallyEqualButDistinctInstancesBothPass() {
        // A parked vehicle decodes identical values for seconds — each decode
        // is a fresh instance and real proof of life.
        val gate = PackSampleGate(packCount = 1)
        val a = BmsData(voltage = 100f)
        val b = a.copy()
        assertTrue(gate.advance(0, a))
        assertTrue(gate.advance(0, b), "identity, not equality, must decide")
    }

    @Test
    fun packsAreTrackedIndependently() {
        val gate = PackSampleGate(packCount = 2)
        val branch0 = BmsData(voltage = 74.1f)
        val branch1 = BmsData(voltage = 74.2f)
        assertTrue(gate.advance(0, branch0))
        assertTrue(gate.advance(1, branch1))
        // Branch 1 goes quiet: its cached instance is suppressed while branch
        // 0 keeps advancing.
        assertTrue(gate.advance(0, BmsData(voltage = 74.0f)))
        assertFalse(gate.advance(1, branch1))
    }
}
