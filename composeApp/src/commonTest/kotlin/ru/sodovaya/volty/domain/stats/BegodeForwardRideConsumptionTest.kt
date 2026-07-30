package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The test that was missing when the first hardware ride found an inverted
 * speed** (field report `2026-07-30-first-hardware-test`, S1 and S2).
 *
 * Every other speed assertion in this repo stops at the decoder, and the only
 * real Begode capture reads `00 00` in bytes 4..5 across all 38 of its live
 * frames — 13 seconds of a wheel standing still. So every non-zero speed
 * assertion in the suite is synthetic, and a synthetic assertion written from
 * the decoder's own convention cannot notice that the convention is wrong: the
 * old reverse test asserted −18.828 km/h and passed happily on a wheel that was
 * riding forward.
 *
 * What no test covered is the *consequence*: whether a wheel MOVING FORWARD
 * produces a usable reading at the far end of the pipeline. It did not, and the
 * failure was silent — `RideMetrics.MIN_SPEED_KMH` nulls consumption below
 * 0.5 km/h, and every negative number is below it, so the rider saw no
 * consumption for the whole ride (report S2) with nothing logged and no error.
 * The same negative would also freeze the session speed peak and leave the
 * SPEED alarm unfireable.
 *
 * This drives REAL Begode frames through the real decoder, the real
 * [ControllerData][ru.sodovaya.volty.domain.model.ControllerData] mapping and the
 * real [MotionReadings] gate that the dashboard's consumption card reads
 * ([ru.sodovaya.volty.presentation.ride.CleanMetricMapper]). Nothing here is
 * hand-built, which is the point: the suite's mixed fixtures assemble
 * `ControllerData` by hand and therefore test the producers' habits rather than
 * the contract.
 */
class BegodeForwardRideConsumptionTest {

    /**
     * A 40S ET Max riding forward at 36 km/h and drawing 5 A from the pack —
     * with the sign the rider's two wheels actually put on "forward".
     *
     * The cell count is what makes a voltage, and therefore a power, available
     * at all (see `BegodeProtocol.inputVoltageOrNull`): 58.92 V on Begode's
     * 67.2 V reference x 2.5 = 147.3 V rail, x 5 A = 736.5 W, over 36 km/h =
     * 20.458 Wh/km.
     */
    private fun forwardRidingWheel(speedRaw: Int): BegodeProtocol {
        val protocol = BegodeProtocol(cellCount = 40)
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = speedRaw, currentRaw = -1500))
        protocol.onNotification(motionFrame(batteryCurrentRaw = 500, motorTempRaw = 41, dutyRaw = 63))
        return protocol
    }

    @Test
    fun aWheelRidingForwardShowsAConsumptionEvenWhenItsFirmwareSignsForwardNegative() {
        // speedRaw = -1000 is FORWARD on the rider's ET Max and EXN (S1).
        val motion = assertNotNull(
            forwardRidingWheel(speedRaw = -1000).latestMotion(0),
            "precondition: two motion-bearing frames were decoded"
        )

        // Preconditions, so a failure below names its own cause rather than
        // leaving three candidates: the sample must have a speed and a power.
        assertEquals(SpeedSource.REPORTED, motion.speedSource, "a wheel measures ground speed")
        assertEquals(147.3f, assertNotNull(MotionReadings.inputVoltageV(motion)), 0.01f, "40S x 2.5")
        assertEquals(736.5f, assertNotNull(MotionReadings.powerW(motion)), 0.1f, "147.3 V x 5 A")

        val consumption = assertNotNull(
            MotionReadings.instantWhPerKm(motion),
            "a forward-moving wheel with a measured power has a consumption; " +
                "a negative speed nulls it here for the whole ride, silently"
        )
        assertEquals(20.458f, consumption, 0.01f, "736.5 W over 36 km/h")

        // And the gauge figure the dashboard actually draws is that number — not
        // the session fallback, which a Begode can never supply (it keeps no
        // energy counters), so an absent instant figure means an absent card.
        assertEquals(20.458f, assertNotNull(MotionReadings.whPerKm(motion)), 0.01f)
        assertNull(
            MotionReadings.sessionWhPerKm(motion),
            "there is no fallback to hide behind: a Begode reports no energy counters"
        )
    }

    @Test
    fun theSameRideReadsTheSameWhicheverSignTheFirmwarePutsOnForward() {
        // The point of taking the magnitude rather than negating: the polarity is
        // NOT universal (WheelLog exposes it as a per-wheel preference for
        // exactly this reason), so both firmwares must land on one reading.
        // A negation would pass the test above and fail this one.
        val negativeForward = assertNotNull(forwardRidingWheel(speedRaw = -1000).latestMotion(0))
        val positiveForward = assertNotNull(forwardRidingWheel(speedRaw = 1000).latestMotion(0))

        assertEquals(
            assertNotNull(MotionReadings.instantWhPerKm(positiveForward)),
            assertNotNull(MotionReadings.instantWhPerKm(negativeForward)),
            0.001f,
            "one ride, two firmware conventions, one consumption"
        )
        assertEquals(positiveForward.speedKmh, negativeForward.speedKmh, 0.001f)
    }

    @Test
    fun aWheelStandingStillStillHasNoConsumptionToShow() {
        // The guard the fix must not trample: 0 km/h is a real reading and
        // dividing by it is meaningless, so the absence here is correct. Without
        // this, `abs` plus a dropped MIN_SPEED_KMH would read as a pass above.
        val parked = assertNotNull(forwardRidingWheel(speedRaw = 0).latestMotion(0))
        assertEquals(0f, parked.speedKmh, 0f)
        assertNull(
            MotionReadings.instantWhPerKm(parked),
            "a parked wheel's Wh/km is a division blow-up, not a number"
        )
        assertTrue(
            assertNotNull(MotionReadings.powerW(parked)) > 0f,
            "…and it is the speed that withholds it, not a missing power"
        )
    }

    // --- Frame builders: the same layout BegodeMotionProtocolTest documents ---

    private fun frame(type: Int, subtype: Int, payload: ByteArray): ByteArray {
        require(payload.size == 16) { "payload is frame bytes 2..17" }
        return byteArrayOf(0x55, 0xAA.toByte()) + payload +
            byteArrayOf(type.toByte(), subtype.toByte(), 0x5A, 0x5A, 0x5A, 0x5A)
    }

    /** Live 0x00 frame: voltage at 2..3, speed at 4..5, phase current at 10..11. */
    private fun liveFrame(voltageRaw: Int, speedRaw: Int, currentRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[0] = (voltageRaw shr 8).toByte(); p[1] = voltageRaw.toByte()
        p[2] = (speedRaw shr 8).toByte(); p[3] = speedRaw.toByte()
        p[8] = (currentRaw shr 8).toByte(); p[9] = currentRaw.toByte()
        return frame(0x00, 24, p)
    }

    /** Motion 0x07 frame: battery current at 2..3, motor temp at 6..7, duty at 8..9. */
    private fun motionFrame(batteryCurrentRaw: Int, motorTempRaw: Int, dutyRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[0] = (batteryCurrentRaw shr 8).toByte(); p[1] = batteryCurrentRaw.toByte()
        p[2] = 0x00; p[3] = 0x01 // the constant the real wheel sends here
        p[4] = (motorTempRaw shr 8).toByte(); p[5] = motorTempRaw.toByte()
        p[6] = (dutyRaw shr 8).toByte(); p[7] = dutyRaw.toByte()
        return frame(0x07, 24, p)
    }
}
