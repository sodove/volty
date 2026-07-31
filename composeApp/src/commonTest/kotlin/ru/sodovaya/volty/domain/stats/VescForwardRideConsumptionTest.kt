package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.data.bms.VescProtocol
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescTestFrames
import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `BegodeForwardRideConsumptionTest`'s twin for the OTHER decoder — the same
 * cross-layer shape, deliberately, rather than a third variant of it.
 *
 * Task 1 took the magnitude in `BegodeProtocol` and justified it with "so the
 * two protocols agree on what the shared field means". Its own review found the
 * claim was an overclaim: `VescValues` still published a **signed** speed from
 * both of its sources, so every consequence Task 1 listed stayed live for a
 * VESC. This file is the consequence-level pin for that half.
 *
 * **It is not hypothetical.** A scooter reversing reports negative eRPM, and a
 * controller whose motor direction is configured the other way round reports
 * negative eRPM *while moving forward* — the same firmware-variance case that
 * produced the Begode bug on the rider's two wheels. VESC Tool itself treats the
 * polarity as a preference (`VescInterface::speedGaugeUseNegativeValues`,
 * default true, consumed by `mobile/RtDataSetup.qml`), which is precisely why a
 * decoder cannot assume one convention.
 *
 * **What differs from the Begode case, and why it is worse rather than better.**
 * A Begode keeps no energy counters, so a nulled instant figure left the card
 * blank — visibly absent. A VESC keeps four counters, so
 * [MotionReadings.whPerKm] falls back to
 * [MotionReadings.sessionWhPerKm] and the rider is shown a **different,
 * confidently-rendered number** instead of a blank. The first test below asserts
 * both halves of that, so the failure it pins is "wrong figure", not merely
 * "missing figure".
 *
 * Nothing here is hand-built: real framed VESC packets go through the real
 * [VescProtocol], the real `ControllerData` mapping and the real
 * [MotionReadings] gate that the dashboard's consumption card reads through
 * [ru.sodovaya.volty.presentation.ride.CleanMetricMapper].
 */
class VescForwardRideConsumptionTest {

    // 78.2 V x 52.4 A = 4097.68 W; 13.056 m/s = 47.0 km/h -> 87.185 Wh/km.
    private val expectedPowerW = 78.2f * 52.4f
    private val expectedSpeedKmh = 47.0f
    private val expectedInstantWhPerKm = expectedPowerW / expectedSpeedKmh

    /**
     * A scooter whose SETUP frame signs FORWARD negative, driven for two frames
     * so the session trip counter leaves its baseline — which is what makes the
     * session fallback available and therefore makes the silent substitution
     * above reachable.
     */
    private fun ridingScooter(speedMsRaw: Int, rpm: Int): VescProtocol {
        val protocol = VescProtocol(useSetupFrame = true)
        protocol.onNotification(setupFrame(speedMsRaw = speedMsRaw, rpm = rpm, tachAbsMRaw = 1_284_600_000))
        // 1000 m further on: tripKm = 1.0, so `sessionWhPerKm` = 980 Wh / 1 km.
        protocol.onNotification(setupFrame(speedMsRaw = speedMsRaw, rpm = rpm, tachAbsMRaw = 1_285_600_000))
        return protocol
    }

    @Test
    fun aScooterRidingForwardShowsAConsumptionEvenWhenItsControllerSignsForwardNegative() {
        val motion = assertNotNull(
            ridingScooter(speedMsRaw = -13_056, rpm = -12_000).latestMotion(0),
            "precondition: two SETUP frames were decoded"
        )

        // Preconditions, so a failure below names its own cause rather than
        // leaving three candidates: the sample must have a speed and a power.
        assertEquals(SpeedSource.REPORTED, motion.speedSource, "the controller computes a ground speed")
        assertEquals(78.2f, assertNotNull(MotionReadings.inputVoltageV(motion)), 0.01f)
        assertEquals(expectedPowerW, assertNotNull(MotionReadings.powerW(motion)), 0.5f, "78.2 V x 52.4 A")

        val consumption = assertNotNull(
            MotionReadings.instantWhPerKm(motion),
            "a forward-moving scooter with a measured power has a consumption; " +
                "a negative speed nulls it here for the whole ride, silently"
        )
        assertEquals(expectedInstantWhPerKm, consumption, 0.05f, "4097.68 W over 47.0 km/h")

        // And what the dashboard actually draws is that number. Unlike a
        // Begode, this protocol HAS a fallback — so the bug does not blank the
        // card, it swaps a real 87 Wh/km for the session's 980 Wh/km without
        // saying anything.
        val session = assertNotNull(
            MotionReadings.sessionWhPerKm(motion),
            "precondition: the fallback is available, which is what makes the swap silent"
        )
        assertEquals(980.0f, session, 0.5f, "980 Wh consumed over a 1.0 km session")
        assertEquals(expectedInstantWhPerKm, assertNotNull(MotionReadings.whPerKm(motion)), 0.05f)
    }

    @Test
    fun theSameRideReadsTheSameWhicheverWayTheMotorDirectionIsConfigured() {
        // The point of taking the magnitude rather than NEGATING: the polarity
        // is a per-setup convention, not a universal one, so both configurations
        // must land on one reading. A negation passes the test above and fails
        // this one.
        val negativeForward = assertNotNull(ridingScooter(speedMsRaw = -13_056, rpm = -12_000).latestMotion(0))
        val positiveForward = assertNotNull(ridingScooter(speedMsRaw = 13_056, rpm = 12_000).latestMotion(0))

        assertEquals(
            assertNotNull(MotionReadings.instantWhPerKm(positiveForward)),
            assertNotNull(MotionReadings.instantWhPerKm(negativeForward)),
            0.001f,
            "one ride, two motor-direction conventions, one consumption"
        )
        assertEquals(positiveForward.speedKmh, negativeForward.speedKmh, 0.001f)

        // The direction is not destroyed, only kept out of the shared field:
        // `eRpm` still carries it, on the sample the session publishes. This is
        // the difference from Begode, where no other field did and Task 1 had to
        // add `signedSpeedKmh()` to keep the bit at all.
        assertEquals(-12_000f, negativeForward.eRpm, "the sign lives on eRpm")
        assertEquals(12_000f, positiveForward.eRpm)
    }

    @Test
    fun aScooterStandingStillStillHasNoInstantConsumptionToShow() {
        // The guard the fix must not trample: 0 km/h is a real reading (rpm
        // agrees, so `reportedSpeedSource` keeps it REPORTED) and dividing by it
        // is meaningless. Without this, "abs plus a dropped MIN_SPEED_KMH" would
        // read as a pass above.
        val parked = assertNotNull(ridingScooter(speedMsRaw = 0, rpm = 0).latestMotion(0))
        assertEquals(SpeedSource.REPORTED, parked.speedSource, "a stationary vehicle keeps its speed gauge")
        assertEquals(0f, parked.speedKmh, 0f)
        assertNull(
            MotionReadings.instantWhPerKm(parked),
            "a parked scooter's instant Wh/km is a division blow-up, not a number"
        )
        assertTrue(
            assertNotNull(MotionReadings.powerW(parked)) > 0f,
            "…and it is the speed that withholds it, not a missing power"
        )
    }

    /**
     * The OTHER VESC speed source. A controller that answers plain
     * `COMM_GET_VALUES` has no controller-computed ground speed, so the figure
     * is derived from eRPM — and inherits eRPM's sign unless the decoder takes
     * the magnitude there too. Two sources, one contract.
     *
     * On this path the failure is total rather than substituted: `GET_VALUES`
     * carries tachometer COUNTS, which this decoder deliberately does not report
     * as metres, so `tripKm` stays 0 and the session fallback is unavailable.
     */
    @Test
    fun aControllerWithNoSetupFrameDerivesAForwardSpeedFromANegativeRpm() {
        val wheel = MotorConfig(polePairs = 15, wheelDiameterMm = 254, gearRatio = 1f)
        val expectedDerived = assertNotNull(
            VescValues.derivedSpeedKmh(10_000f, wheel),
            "precondition: this wheel config can derive a speed at all"
        )
        val protocol = VescProtocol(useSetupFrame = false, motor = wheel)
        protocol.onNotification(valuesFrame(rpm = -10_000))
        val motion = assertNotNull(protocol.latestMotion(0))

        assertEquals(SpeedSource.DERIVED, motion.speedSource)
        assertEquals(expectedDerived, motion.speedKmh, 0.001f, "the magnitude of the derived figure")
        assertEquals(-10_000f, motion.eRpm, "and the direction is still on eRpm")

        assertNull(
            MotionReadings.sessionWhPerKm(motion),
            "precondition: no odometer on this frame, so there is no fallback to hide behind"
        )
        assertEquals(
            expectedPowerW / expectedDerived,
            assertNotNull(
                MotionReadings.whPerKm(motion),
                "a derived speed from a negative rpm must still produce a consumption"
            ),
            0.05f
        )
    }

    // --- Frames: the layout is `VescTestFrames`', the numbers are this file's ---

    /** `COMM_GET_VALUES_SETUP` (47): 78.2 V rail, 52.4 A in, 980 Wh consumed. */
    private fun setupFrame(speedMsRaw: Int, rpm: Int, tachAbsMRaw: Int): ByteArray =
        VescPacket.frame(
            VescTestFrames.setupPayload(
                rpm = rpm,
                speedMsRaw = speedMsRaw,
                tachAbsMRaw = tachAbsMRaw
            )
        )

    /** `COMM_GET_VALUES` (4): the same rail and current, no ground speed, no metres. */
    private fun valuesFrame(rpm: Int): ByteArray =
        VescPacket.frame(VescTestFrames.valuesPayload(rpm = rpm))
}
