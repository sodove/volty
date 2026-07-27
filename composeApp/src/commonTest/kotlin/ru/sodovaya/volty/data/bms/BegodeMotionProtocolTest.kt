package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.alert.AlarmDefaults
import ru.sodovaya.volty.domain.alert.AlertAvailability
import ru.sodovaya.volty.domain.alert.AlertUnavailableReason
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.armedRules
import ru.sodovaya.volty.domain.alert.availabilityFor
import ru.sodovaya.volty.domain.alert.reportsDuty
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.stats.DutyBands
import ru.sodovaya.volty.domain.stats.DutyLevel
import ru.sodovaya.volty.presentation.alerts.alertDraftsFor
import ru.sodovaya.volty.presentation.alerts.isEditable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Motion decode of the Begode live 0x00, odometer 0x04 and motion 0x07 frames.
 *
 * **What the real capture can and cannot prove.** [BegodeDumpFixture] is 13
 * seconds of an ET Max standing still, which turns out to leave far more
 * exercisable than a first reading suggested — the 0x07 frame this protocol
 * used to drop carries live values even at a standstill:
 *
 * - **battery current is real data** — it varies frame to frame across the
 *   capture (raw −9..111, i.e. +0.09..−1.11 A) and is a different quantity
 *   from the phase current the battery path already decodes;
 * - **motor temperature is real data** — 20 °C against a 27.5 °C mainboard,
 *   so this wheel does have a motor thermistor;
 * - **duty is real data** — 2 % of hardware PWM, which is what a wheel
 *   spends holding itself upright;
 * - **the odometer is real**, verified only as far as plausibility goes
 *   (8 565 km, against 2.99 million km for a word-swapped read);
 * - **the trip reads 0 m**, which is consistent with a wheel that has not
 *   moved but pins the offset rather than the unit;
 * - **speed is the one field that stays unexercised.** Bytes 4..5 are `00 00`
 *   in all 38 live frames, so every fixture-based speed assertion below
 *   asserts ZERO. Offset and signedness are pinned with SYNTHETIC frames, the
 *   scale against WheelLog's documented conversion — see `SPEED_KMH_PER_UNIT`.
 *
 * Tests are named `theRealCapture…` or `synthetic…` accordingly.
 *
 * The last section covers the [MotionSource] surface — the same readings
 * mapped onto a [ru.sodovaya.volty.domain.model.ControllerData], where three
 * of them have to survive a type whose floats are not nullable: the two
 * temperatures (sentinel, never 0 — spec D §7.1), the duty of a wheel that has
 * not reported one yet, and a voltage whose scale is configuration rather than
 * frame data.
 */
@OptIn(ExperimentalTime::class)
class BegodeMotionProtocolTest {

    private fun protocolFedWithFixture(): BegodeProtocol {
        val protocol = BegodeProtocol()
        // One notification at a time, exactly as the BLE layer delivers them:
        // at MTU 23 every 24-byte frame straddles two notifications.
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        return protocol
    }

    // --- The real capture: the 0x00 and 0x04 frames ---

    @Test
    fun theRealCaptureDecodesAStationaryWheel() {
        // REAL DATA, and honestly weak: this asserts that the speed field
        // reads zero, which is true of the capture and would also be true of a
        // decoder pointed at any other zero bytes of the frame. It does kill a
        // decoder pointed at a NON-zero one (voltage at 2..3 would give 212.1,
        // the unknown word at 6..7 would give 2.196), which is what makes it
        // worth keeping.
        val protocol = protocolFedWithFixture()
        assertEquals(
            0f,
            assertNotNull(protocol.speedKmh(), "a decoded live frame must yield a speed"),
            0f,
            "the wheel is stationary throughout the capture"
        )
    }

    @Test
    fun theRealCaptureTripIsZeroBecauseTheWheelNeverMoved() {
        // REAL DATA. Bytes 8..9 are `00 00` in every live frame — the wheel
        // was switched on and stood there. The neighbouring word at 6..7 is
        // `00 3d` = 61 in every frame and is NOT the trip: read as one 32-bit
        // counter at 6..9 it would make this wheel's trip 3 998 km, on a wheel
        // whose lifetime odometer is 8 565 km and whose trip resets at
        // power-on. So this assertion is what stops that misreading, even
        // though its expected value is zero.
        val protocol = protocolFedWithFixture()
        assertEquals(
            0L,
            assertNotNull(protocol.powerOnDistanceMeters(), "the capture carries live frames"),
            "trip of a wheel that never moved (bytes 6..7 hold 61, which is not the trip)"
        )
    }

    @Test
    fun theRealCaptureOdometerIsTheWheelsLifetimeMileage() {
        // REAL DATA. The 0x04 frame's bytes 2..5 are `00 82 b2 5d` in every
        // occurrence: 8 565 341 m. A word-swapped read of the same bytes gives
        // 2 992 439 426 m — 2.99 million km, impossible — which is the extent
        // of the confirmation available without the wheel's own display.
        val protocol = protocolFedWithFixture()
        assertEquals(
            8_565_341L,
            assertNotNull(protocol.odometerMeters(), "the capture carries 0x04 frames")
        )
    }

    // --- The real capture: the 0x07 frame this protocol used to drop ---

    @Test
    fun theRealCaptureReportsTheWheelsHardwareDuty() {
        // REAL DATA — and the field an earlier reading of this protocol
        // declared underivable. 2 % is what an ET Max spends holding itself
        // upright; the capture's 38 motion frames read 2 % in 37 of them and
        // 1 % in one.
        val protocol = protocolFedWithFixture()
        assertEquals(
            2f,
            assertNotNull(protocol.dutyPercent(), "the capture proves this wheel reports duty"),
            0f
        )
    }

    @Test
    fun theRealCaptureReportsAMotorTemperature() {
        // REAL DATA. 20 °C, against the 27.5 °C the mainboard sensor reports
        // in the same seconds — so this is a second, physically distinct
        // sensor and not a copy of the board temperature.
        val protocol = protocolFedWithFixture()
        assertEquals(
            20f,
            assertNotNull(protocol.motorTempC(), "the ET Max has a motor thermistor"),
            0f
        )
    }

    @Test
    fun theRealCaptureBatteryCurrentIsLiveAndNotThePhaseCurrent() {
        // REAL DATA. The capture's last motion frame carries raw 67, negated
        // to −0.67 A. The value moves frame to frame across the capture (raw
        // −9..111), so this is a live measurement, not a constant.
        val protocol = protocolFedWithFixture()
        val battery = assertNotNull(protocol.batteryCurrentA(), "the capture carries 0x07 frames")
        // The phase current of the same seconds is −3.00 A: a decoder that
        // published one as the other would be out by a factor of four and
        // still look plausible.
        assertEquals(
            -0.67f, battery, 1e-4f,
            "battery current of the capture's last motion frame, not the −3.00 A phase current"
        )
    }

    @Test
    fun aSmartBmsWheelStillReportsItsMotion() {
        // The ET Max has a smart BMS, and the synthetic no-BMS pack is retired
        // the moment its first 0x01 frame lands. Motion must NOT be retired
        // with it — a wheel with a smart BMS still moves.
        val protocol = protocolFedWithFixture()
        assertNull(protocol.liveVoltageOn672ScaleV(), "precondition: the synthetic pack is retired")
        assertNotNull(protocol.speedKmh(), "speed must survive the smart-BMS hand-over")
        assertNotNull(protocol.powerOnDistanceMeters(), "trip must survive the smart-BMS hand-over")
        assertNotNull(protocol.dutyPercent(), "duty must survive the smart-BMS hand-over")
    }

    // --- Synthetic frames, where the capture is silent ---

    @Test
    fun syntheticSpeedComesFromBytesFourAndFiveAtWheelLogsScale() {
        // SYNTHETIC — the capture is stationary and cannot exercise this.
        // Raw 1000 (cm/s) is 36.00 km/h under WheelLog's `raw * 3.6 / 100`.
        // The two scales that were proposed and rejected would give 10.00
        // (hundredths of km/h) and 360.00 (tenths), so this value separates
        // all three.
        val protocol = BegodeProtocol()
        protocol.onNotification(
            liveFrame(
                voltageRaw = 5892,
                speedRaw = 1000,
                unknown6 = 61,
                tripMeters = 4321,
                currentRaw = -330,
                tempRaw = -3066,
                fallbackPwmRaw = 169
            )
        )
        assertEquals(36.0f, assertNotNull(protocol.speedKmh()), 1e-3f)
        // ...while the battery decode of the same frame is untouched.
        assertEquals(58.92f, assertNotNull(protocol.liveVoltageOn672ScaleV()), 0.005f)
        assertEquals(-3.30f, assertNotNull(protocol.latestData(0)).current, 1e-4f)
    }

    @Test
    fun syntheticReverseMotionReadsNegativeNotTwoThousandKmh() {
        // SYNTHETIC. The field is signed: an unsigned read turns a wheel
        // rolling gently backwards into 2340 km/h, which every alarm threshold
        // downstream would treat as a catastrophe.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = -523))
        assertEquals(
            -18.828f, assertNotNull(protocol.speedKmh()), 1e-4f,
            "an unsigned read of the same bytes would report 2340.468 km/h"
        )
    }

    @Test
    fun syntheticTripComesFromBytesEightAndNineOnly() {
        // SYNTHETIC, and stricter than the capture: the unknown word at 6..7
        // and the trip at 8..9 carry different values here, so every candidate
        // reading lands somewhere different —
        //   bytes 8..9 (correct)        -> 1234
        //   bytes 6..7                  -> 61
        //   32-bit big-endian at 6..9   -> 4 000 978
        //   32-bit word-swapped at 6..9 -> 80 871 485
        val protocol = BegodeProtocol()
        protocol.onNotification(
            liveFrame(voltageRaw = 5892, unknown6 = 61, tripMeters = 1234)
        )
        assertEquals(1234L, assertNotNull(protocol.powerOnDistanceMeters()))
    }

    @Test
    fun syntheticTripIsUnsignedSoALongRideDoesNotGoNegative() {
        // SYNTHETIC. The field is 16 bits and unsigned; read as signed, a trip
        // past 32.8 km would report as a negative distance.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, tripMeters = 65535))
        assertEquals(65535L, assertNotNull(protocol.powerOnDistanceMeters()))
    }

    @Test
    fun syntheticOdometerReadsThePlainBigEndianWord() {
        // SYNTHETIC counterpart of the capture's constant value: an odometer
        // built from four distinct bytes, so a word swap or a byte swap lands
        // somewhere else entirely.
        val protocol = BegodeProtocol()
        protocol.onNotification(odometerFrame(byteArrayOf(0x01, 0x02, 0x03, 0x04)))
        assertEquals(0x01020304L, assertNotNull(protocol.odometerMeters()))
    }

    @Test
    fun syntheticMotionFrameFieldsComeFromTheirOwnOffsets() {
        // SYNTHETIC. Every field of the 0x07 frame carries a distinct value,
        // so a decoder reading a neighbouring word fails on all three.
        val protocol = BegodeProtocol()
        protocol.onNotification(motionFrame(batteryCurrentRaw = 250, motorTempRaw = 41, dutyRaw = 63))
        assertEquals(-2.50f, assertNotNull(protocol.batteryCurrentA()), 1e-4f)
        assertEquals(41f, assertNotNull(protocol.motorTempC()), 0f)
        assertEquals(63f, assertNotNull(protocol.dutyPercent()), 0f)
    }

    @Test
    fun syntheticRegeneratingWheelReportsAPositiveBatteryCurrent() {
        // SYNTHETIC. Braking pushes current back into the pack: the raw field
        // goes negative and the negation makes the reported value positive. A
        // decoder that dropped the negation would have both signs backwards
        // and would show a wheel charging while it drains.
        val protocol = BegodeProtocol()
        protocol.onNotification(motionFrame(batteryCurrentRaw = -1500, motorTempRaw = 30, dutyRaw = 55))
        assertEquals(15.0f, assertNotNull(protocol.batteryCurrentA()), 1e-4f)
    }

    @Test
    fun syntheticMotionSurvivesTheSmartBmsHandOver() {
        // SYNTHETIC and deterministic: the fixture proves the same thing, but
        // only as a side effect of its ordering. Retiring the synthetic pack
        // must not retire the wheel's motion.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 1000))
        protocol.onNotification(motionFrame(batteryCurrentRaw = 250, motorTempRaw = 41, dutyRaw = 63))
        protocol.onNotification(odometerFrame(byteArrayOf(0x00, 0x82.toByte(), 0xB2.toByte(), 0x5D)))
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472))

        assertNull(protocol.liveVoltageOn672ScaleV(), "precondition: the synthetic pack is retired")
        assertEquals(36.0f, assertNotNull(protocol.speedKmh()), 1e-3f)
        assertEquals(8_565_341L, assertNotNull(protocol.odometerMeters()))
        assertEquals(63f, assertNotNull(protocol.dutyPercent()), 0f)
    }

    // --- Duty: real or absent, never a placeholder (spec §7.2) ---

    @Test
    fun aWheelWhoseDutyFieldIsAlwaysZeroReportsNoDutyAtAll() {
        // SYNTHETIC. A zero-filled duty field cannot be told apart from a
        // firmware that does not fill the field in, and the ШИМ alarm folds
        // duty with maxOf — armed against a constant zero it can never fire.
        // WheelLog draws the same line with its truePWM latch.
        val protocol = BegodeProtocol()
        repeat(5) {
            protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 0))
        }
        assertNotNull(protocol.batteryCurrentA(), "precondition: the frames were decoded")
        assertNull(protocol.dutyPercent(), "an always-zero duty field is not a duty measurement")

        // One non-zero reading proves the field is real...
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 3))
        assertEquals(3f, assertNotNull(protocol.dutyPercent()), 0f)

        // ...and from then on a genuine zero IS published: the wheel has
        // stopped pushing, and that is a measurement, not a silence.
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 0))
        assertEquals(0f, assertNotNull(protocol.dutyPercent(), "the latch holds once proved"), 0f)
    }

    @Test
    fun theControllerSampleCarriesTheLatchOnHasDutyBecauseZeroCannotSayIt() {
        // Task 2 recorded that the latch was INVISIBLE on the controller
        // sample: `dutyPercent() ?: 0f` and the raw value are the same number
        // at every instant, so nothing downstream could tell "0 % duty" from
        // "this firmware never fills the field in" — and Part F would show the
        // ШИМ alarm armed and unable to fire. `hasDuty` is that statement,
        // and Part D Task 4 added it because Task 4 is what makes a Begode
        // vehicle able to carry a controller at all.
        val protocol = BegodeProtocol()
        repeat(5) {
            protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 0))
        }
        val unproven = assertNotNull(protocol.latestMotion(0), "precondition: the frames were decoded")
        assertEquals(0f, unproven.dutyPercent, 0f, "0 is all the field itself can say")
        assertFalse(unproven.hasDuty, "…so the sample has to say the rest")

        // One non-zero reading closes the latch, and the flag follows.
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 3))
        val proven = assertNotNull(protocol.latestMotion(0))
        assertEquals(3f, proven.dutyPercent, 0f)
        assertTrue(proven.hasDuty)

        // …and a later genuine zero stays MEASURED: the wheel stopped pushing.
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 0))
        val stopped = assertNotNull(protocol.latestMotion(0))
        assertEquals(0f, stopped.dutyPercent, 0f)
        assertTrue(stopped.hasDuty, "the latch holds once proved — this zero IS a reading")
    }

    @Test
    fun aResetReopensTheDutyLatchOnTheControllerSampleToo() {
        // Per-wheel evidence: a reconnect may face a different wheel, and the
        // previous one's proof must not arm this one's alarm.
        val protocol = protocolFedWithFixture()
        assertTrue(assertNotNull(protocol.latestMotion(0)).hasDuty, "precondition: the ET Max proved it")

        protocol.reset()
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 0))
        assertFalse(
            assertNotNull(protocol.latestMotion(0)).hasDuty,
            "the truePWM latch must not survive a reset"
        )
    }

    @Test
    fun theLiveFramesFallbackPwmIsNeverPublishedAsDuty() {
        // SYNTHETIC, modelled on the real capture: the ET Max's live frames
        // carry 169 in the bytes WheelLog uses as a FALLBACK hardware PWM,
        // which would read as a constant 16.9 % duty on a wheel that never
        // moved. A wheel that sends no 0x07 frame must report NO duty rather
        // than that number.
        val protocol = BegodeProtocol()
        repeat(3) {
            protocol.onNotification(liveFrame(voltageRaw = 5892, fallbackPwmRaw = 169))
        }
        assertNotNull(protocol.speedKmh(), "precondition: the live frames were decoded")
        assertNull(protocol.dutyPercent(), "the live frame's fallback PWM is not this wheel's duty")
    }

    // --- The battery path must not notice any of this ---

    @Test
    fun theMotionFrameNeverLeaksIntoTheBatterySample() {
        // The synthetic no-BMS pack must keep reporting the PHASE current and
        // the MAINBOARD temperature. The 0x07 frame carries a battery current
        // and a motor temperature for the same instant, and both are wrong
        // answers for a battery sample: -0.67 A instead of -3.50 A, 20 °C
        // instead of 27.5 °C, each of them plausible enough to go unnoticed.
        //
        // The frames INTERLEAVE the way the wheel really sends them — live,
        // motion, live — because the pack sample is only rebuilt on a live
        // frame. Asserting after the motion frame alone would pass even if the
        // motion decode overwrote both fields, since the sample in hand was
        // built before the corruption; only the NEXT live frame publishes it.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))
        protocol.onNotification(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))

        val data = assertNotNull(protocol.latestData(0))
        assertEquals(-3.5f, data.current, 1e-3f, "the pack sample carries the phase current")
        assertEquals(1, data.temperatures.size, "the motor temperature is not a pack temperature")
        assertEquals(27.5035f, data.temperatures[0], 0.01f, "the board temperature")
        // And the motion side is unharmed by the same interleaving.
        assertEquals(-0.67f, assertNotNull(protocol.batteryCurrentA()), 1e-4f)
        assertEquals(20f, assertNotNull(protocol.motorTempC()), 0f)
    }

    // --- Absence is absence, never zero ---

    @Test
    fun nothingDecodedMeansNoReading() {
        // Spec §7.1: a decoder that reports 0 for a field it has never seen
        // claims a measurement. 0 km/h, 0 m, 0 A and 0 % are all perfectly
        // ordinary real values, so none of them can double as "unknown".
        val protocol = BegodeProtocol()
        assertNull(protocol.speedKmh())
        assertNull(protocol.powerOnDistanceMeters())
        assertNull(protocol.odometerMeters())
        assertNull(protocol.batteryCurrentA())
        assertNull(protocol.motorTempC())
        assertNull(protocol.dutyPercent())
    }

    @Test
    fun theThreeFrameTypesAreIndependentReadings() {
        // SYNTHETIC. They arrive in different frames and a wheel can send one
        // without the others; a single "saw something" flag would make each
        // vouch for the others.
        val liveOnly = BegodeProtocol()
        liveOnly.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 100))
        assertNotNull(liveOnly.speedKmh())
        assertNotNull(liveOnly.powerOnDistanceMeters())
        assertNull(liveOnly.odometerMeters(), "no 0x04 frame arrived")
        assertNull(liveOnly.batteryCurrentA(), "no 0x07 frame arrived")

        val odometerOnly = BegodeProtocol()
        odometerOnly.onNotification(odometerFrame(byteArrayOf(0x00, 0x82.toByte(), 0xB2.toByte(), 0x5D)))
        assertNotNull(odometerOnly.odometerMeters())
        assertNull(odometerOnly.speedKmh(), "no live frame arrived")
        assertNull(odometerOnly.motorTempC(), "no 0x07 frame arrived")

        val motionOnly = BegodeProtocol()
        motionOnly.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))
        assertNotNull(motionOnly.batteryCurrentA())
        assertNotNull(motionOnly.motorTempC())
        assertNotNull(motionOnly.dutyPercent())
        assertNull(motionOnly.speedKmh(), "no live frame arrived")
        assertNull(motionOnly.odometerMeters(), "no 0x04 frame arrived")
    }

    @Test
    fun aBootZeroLiveFrameIsNotAMotionReading() {
        // SYNTHETIC. The wheel zero-pads its live frame while booting, and the
        // existing decode already refuses to synthesise a pack from such a
        // frame (BegodeNoBmsProtocolTest). Motion takes the same gate: the
        // speed field here says 36 km/h, and publishing it would be publishing
        // the contents of a placeholder.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 0, speedRaw = 1000, tripMeters = 4321))
        assertNull(protocol.speedKmh(), "a boot placeholder is not a speed measurement")
        assertNull(protocol.powerOnDistanceMeters(), "a boot placeholder is not a trip measurement")

        // ...and a genuine frame right after it is decoded normally.
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 1000))
        assertEquals(36.0f, assertNotNull(protocol.speedKmh()), 1e-3f)
    }

    @Test
    fun resetClearsMotion() {
        // A reconnect may face a different wheel; its trip, mileage and — most
        // of all — its proof that it reports duty must not be inherited.
        val protocol = protocolFedWithFixture()
        assertNotNull(protocol.speedKmh(), "precondition: the fixture decoded motion")
        assertNotNull(protocol.powerOnDistanceMeters())
        assertNotNull(protocol.odometerMeters())
        assertNotNull(protocol.dutyPercent())
        assertNotNull(protocol.batteryCurrentA())
        assertNotNull(protocol.motorTempC())

        protocol.reset()
        assertNull(protocol.speedKmh())
        assertNull(protocol.powerOnDistanceMeters())
        assertNull(protocol.odometerMeters())
        assertNull(protocol.batteryCurrentA())
        assertNull(protocol.motorTempC())
        assertNull(protocol.dutyPercent())

        // The duty latch specifically: a wheel that reports only zeros after
        // the reset must not inherit the ET Max's proof that duty is real.
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 0))
        assertNull(protocol.dutyPercent(), "the truePWM latch must not survive a reset")

        // And the protocol decodes motion again from scratch afterwards.
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        assertEquals(0L, assertNotNull(protocol.powerOnDistanceMeters()))
        assertEquals(8_565_341L, assertNotNull(protocol.odometerMeters()))
        assertEquals(2f, assertNotNull(protocol.dutyPercent()), 0f)
    }

    // --- MotionSource: the wheel published as a controller ---

    @Test
    fun aWheelIsExactlyOneController() {
        val protocol = protocolFedWithFixture()
        assertEquals(1, protocol.controllerCount, "one motor on one board")
        assertNotNull(protocol.latestMotion(0), "the capture decoded motion")
        assertNull(protocol.latestMotion(1), "there is no second controller")
        assertNull(protocol.latestMotion(-1), "nor a controller before the first")
    }

    @Test
    fun theRealCaptureControllerSampleCarriesTheWheelsMotion() {
        // REAL DATA throughout, except speedKmh — see the class KDoc. Every
        // number here is the LAST value of its own frame type in the capture:
        // live frame `55aa 1700 0000 003d 0000 fed4 f4a4 …` and motion frame
        // `55aa 0043 0001 0014 0002 …`.
        val protocol = protocolFedWithFixture()
        val m = assertNotNull(protocol.latestMotion(0))

        assertEquals(0f, m.speedKmh, 0f, "the wheel is stationary throughout the capture")
        assertEquals(
            SpeedSource.REPORTED, m.speedSource,
            "a wheel measures GROUND speed itself — no MotorConfig, no derivation"
        )
        assertEquals(2f, m.dutyPercent, 0f, "hardware PWM of a wheel holding itself upright")
        assertEquals(20f, m.motorTempC, 0f, "the 0x07 motor thermistor")
        assertTrue(m.hasMotorTemp, "this wheel plainly reports a motor temperature")
        assertEquals(27.977f, m.escTempC, 0.01f, "the mainboard MPU of the same seconds")
        assertTrue(m.hasEscTemp, "…and that is a real reading, not the -50 C sentinel")
        assertEquals(8565.341f, m.odometerKm, 0.001f, "8 565 341 m of lifetime mileage")
        assertEquals(0f, m.tripKm, 0f, "a wheel that never moved")
        assertTrue(m.isConnected, "a decoded sample is by definition a connected one")
    }

    @Test
    fun theRealCaptureControllerSampleReportsBothCurrentsDrawnPositive() {
        // REAL DATA, and the one place a sign error would be invisible: the
        // capture's battery current (-0.67 A) and phase current (-3.00 A) are
        // both NEGATIVE in this file's battery convention, "+ = charging".
        // ControllerData uses VESC's opposite one — positive while drawing
        // from the pack, see VescProtocol.synthesiseBattery, which negates the
        // other way — so both must come out POSITIVE for a discharging wheel.
        val protocol = protocolFedWithFixture()
        val m = assertNotNull(protocol.latestMotion(0))
        assertEquals(
            0.67f, m.batteryCurrentA, 1e-4f,
            "an un-negated map would show a parked wheel CHARGING at 0.67 A"
        )
        assertEquals(
            3.00f, m.motorCurrentA, 1e-4f,
            "the live frame's PHASE current, negated the same way — and not the battery current"
        )
    }

    @Test
    fun theWheelsMotorTemperatureIsAbsentAsASentinelAndNeverAsZero() {
        // Spec D §7.1. A wheel that has sent no 0x07 frame has reported no
        // motor temperature; 0 C would be a plausible winter reading, and
        // hasMotorTemp would then be the only thing standing between a rider
        // and a MOTOR_TEMP alarm armed on a constant.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, tempRaw = -3069))
        val m = assertNotNull(protocol.latestMotion(0), "a live frame alone is motion")
        assertFalse(m.hasMotorTemp, "no 0x07 frame arrived, so no motor thermistor has spoken")
        assertTrue(
            m.motorTempC < -50f,
            "absent must sit below the -50 C sentinel, not at 0 (was ${m.motorTempC})"
        )
        // …and the board sensor of the same sample IS real.
        assertTrue(m.hasEscTemp)
        assertEquals(27.5035f, m.escTempC, 0.01f)
    }

    @Test
    fun theBoardTemperatureIsAbsentAsASentinelAndNeverAsZero() {
        // The mirror case, and the one that matters most: hasEscTemp is
        // COMPUTED as escTempC > -50f, so a 0f default would claim a sensor
        // that has not reported and arm ESC_TEMP against a constant zero.
        val protocol = BegodeProtocol()
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))
        val m = assertNotNull(protocol.latestMotion(0), "a 0x07 frame alone is motion")
        assertFalse(m.hasEscTemp, "no live frame arrived, so the mainboard sensor has not spoken")
        assertTrue(
            m.escTempC < -50f,
            "absent must sit below the -50 C sentinel, not at 0 (was ${m.escTempC})"
        )
        // The motor sensor of the same sample HAS spoken, and the two are
        // independent: one absent reading must not suppress the other.
        assertTrue(m.hasMotorTemp)
        assertEquals(20f, m.motorTempC, 0f)
    }

    @Test
    fun aWheelThatHasNotReportedASpeedSaysSoRatherThanReportingZero() {
        // A 0x07-only wheel has told us its duty and its currents but nothing
        // about its speed. SpeedSource.NONE + 0f is how ControllerData says
        // that — the same shape VescValues.decodeValues uses for an
        // underivable speed — and it is what keeps Part F's SPEED alert from
        // arming on a fabricated standstill.
        val protocol = BegodeProtocol()
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))
        val m = assertNotNull(protocol.latestMotion(0))
        assertEquals(SpeedSource.NONE, m.speedSource)
        assertFalse(m.speedKnown)
        assertEquals(0f, m.speedKmh, 0f)

        // The first live frame settles it.
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 1000))
        val moving = assertNotNull(protocol.latestMotion(0))
        assertEquals(SpeedSource.REPORTED, moving.speedSource)
        assertEquals(36.0f, moving.speedKmh, 1e-3f)
    }

    @Test
    fun theNotYetKnownDutyIsPublishedAsZeroAndNeverAsTheLiveFramesFallback() {
        // SYNTHETIC, modelled on the capture. Before the hardware PWM field
        // has been seen non-zero the wheel has not told us its duty — but
        // ControllerData has no nullable duty and no hasDuty flag, so the
        // window is published as 0: alarm-neutral (duty only escalates
        // upwards) and renderable, where a negative sentinel would put "-1 %"
        // on the safety dial of a wheel that is simply still booting.
        //
        // What this test actually pins is the thing that CAN go wrong: the
        // live frame's fallback PWM field reads 169 in every frame of the ET
        // Max capture, and adopting it — as WheelLog does when no 0x07 has
        // arrived — would publish a confident 16.9 % duty on a wheel that
        // never moved. That is the fabrication spec D §7.2 forbids.
        val protocol = BegodeProtocol()
        repeat(3) { protocol.onNotification(liveFrame(voltageRaw = 5892, fallbackPwmRaw = 169)) }
        val booting = assertNotNull(protocol.latestMotion(0))
        assertEquals(
            0f, booting.dutyPercent, 0f,
            "not the 16.9 % the live frame's fallback PWM field would give"
        )

        // And once the wheel reports one, it is published verbatim.
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 63))
        assertEquals(63f, assertNotNull(protocol.latestMotion(0)).dutyPercent, 0f)
    }

    @Test
    fun aWheelWithNoKnownCellCountPublishesNoVoltageRatherThanTheRawScale() {
        // The live frame reports voltage against Begode's 67.2 V reference —
        // 58.92 V for a pack that really sits at ~147 V. With no cell count
        // there is no honest scale, and an unscaled number would read as a
        // catastrophically flat 168 V wheel on the dashboard AND poison the
        // power reading built from it.
        val protocol = BegodeProtocol(cellCount = null)
        protocol.onNotification(liveFrame(voltageRaw = 5892, currentRaw = -350))
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))
        val m = assertNotNull(protocol.latestMotion(0))
        assertEquals(0f, m.inputVoltageV, 0f, "0 means UNKNOWN here, and 58.92 would be a lie")
        assertEquals(0f, m.powerW, 0f, "power is voltage-derived and exactly as unknown")
        // Everything the wheel DID report is unaffected by the missing scale.
        assertEquals(0.67f, m.batteryCurrentA, 1e-4f)
        assertEquals(2f, m.dutyPercent, 0f)
    }

    @Test
    fun aWheelWhoseCellCountIsKnownPublishesRealPackVolts() {
        // REAL DATA for the raw reading (the capture's last live frame holds
        // 5888 = 58.88 V on the 67.2 V scale) and configuration for the scale:
        // 40S x 4.2 V = 168 V, i.e. exactly the x2.5 WheelLog makes its riders
        // pick off a list. 58.88 x 2.5 = 147.20 V, against 148.4 V of summed
        // cells in the same capture.
        val protocol = BegodeProtocol(cellCount = 40)
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        val m = assertNotNull(protocol.latestMotion(0))
        assertEquals(147.20f, m.inputVoltageV, 0.01f)
        // Power follows: 147.20 V x 0.67 A drawn.
        assertEquals(98.62f, m.powerW, 0.05f)
        // AND it survives the smart-BMS hand-over. liveVoltageOn672ScaleV()
        // goes null there — that gate exists to stop the synthetic PACK
        // overriding real branches — but the wheel's rail voltage is still the
        // wheel's rail voltage, so the controller keeps reporting it.
        assertNull(protocol.liveVoltageOn672ScaleV(), "precondition: the synthetic pack is retired")
    }

    @Test
    fun aWheelWithANonsensicalCellCountPublishesNoVoltage() {
        // A profile can carry 0 (the "unset" a stored vehicle uses); scaling by
        // it would report a wheel at 0.00 V while it rides. Zero alone cannot
        // pin the guard — 0 cells x 4.2 V / 67.2 is 0 whether the guard runs or
        // not — so a negative is asserted too, which is the only reading that
        // tells the guard apart from its absence.
        val unset = BegodeProtocol(cellCount = 0)
        unset.onNotification(liveFrame(voltageRaw = 5892))
        assertEquals(0f, assertNotNull(unset.latestMotion(0)).inputVoltageV, 0f)

        val nonsense = BegodeProtocol(cellCount = -40)
        nonsense.onNotification(liveFrame(voltageRaw = 5892))
        assertEquals(
            0f, assertNotNull(nonsense.latestMotion(0)).inputVoltageV, 0f,
            "an unguarded scale would report -147.30 V"
        )
    }

    @Test
    fun theMotionFrameNeverLeaksIntoTheControllerSampleEither() {
        // The twin of theMotionFrameNeverLeaksIntoTheBatterySample, and it
        // covers what that one CANNOT: parseLiveFrame reassigns phaseCurrentA
        // and boardTempC unconditionally, so a motion-frame write to either
        // used to be a dead store no test could observe. The controller sample
        // is now rebuilt by the 0x07 frame ITSELF, reading both fields at that
        // exact moment — so the leak became observable here first.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, currentRaw = -350, tempRaw = -3069))
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))

        val m = assertNotNull(protocol.latestMotion(0))
        assertEquals(
            27.5035f, m.escTempC, 0.01f,
            "the mainboard reading, not the 20 C the motion frame carries"
        )
        assertEquals(
            3.50f, m.motorCurrentA, 1e-3f,
            "the phase current, not the 0.67 A battery current of the motion frame"
        )
        // …and the two values the motion frame really does own.
        assertEquals(20f, m.motorTempC, 0f)
        assertEquals(0.67f, m.batteryCurrentA, 1e-4f)
    }

    @Test
    fun nothingDecodedMeansNoControllerSampleAtAll() {
        // MotionSource's own absence encoding — and the only one that is not a
        // compromise, because latestMotion IS nullable. A boot placeholder is
        // not a sample either: its speed, trip and temperature are artefacts
        // of a zero-padded frame.
        val fresh = BegodeProtocol()
        assertNull(fresh.latestMotion(0))

        val booting = BegodeProtocol()
        booting.onNotification(liveFrame(voltageRaw = 0, speedRaw = 1000, tripMeters = 4321))
        assertNull(booting.latestMotion(0), "a boot placeholder is not a motion sample")

        booting.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 1000))
        assertNotNull(booting.latestMotion(0), "…and a genuine frame right after it is")
    }

    @Test
    fun aZeroPaddedLiveFrameMidStreamCannotOverwriteARealReading() {
        // The case the boot gate alone does NOT cover, and the one that would
        // have shipped: `sawLiveMotion` latches on the FIRST genuine frame, so
        // a rebuild gated on the latch rather than on THIS frame's voltage
        // would happily republish a later zero-padded frame's contents.
        //
        // Such a frame is reachable two ways: a placeholder emitted after a
        // reconnect, and — this format having no checksum, only a `5A5A5A5A`
        // tail — a garbled frame whose voltage word came through as zero.
        // parseLiveFrame assigns phaseCurrentA and boardTempC unconditionally,
        // so the artefacts are already in the fields; only the gate stops them
        // reaching a sample. 0/340 + 36.53 = 36.53 C, which is a plausible
        // board temperature and would quietly mask a genuinely hot one.
        val protocol = BegodeProtocol()
        // A hot board: raw 5000 -> 36.53 + 14.7 = 51.24 C.
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 1000, currentRaw = -350, tempRaw = 5000))
        val hot = assertNotNull(protocol.latestMotion(0))
        assertEquals(51.23f, hot.escTempC, 0.01f, "precondition: a real, hot board reading")

        protocol.onNotification(liveFrame(voltageRaw = 0, speedRaw = 0, currentRaw = 0, tempRaw = 0))

        val after = assertNotNull(protocol.latestMotion(0))
        assertSame(hot, after, "a zero-padded frame is not a decode and must mint no sample")
        assertEquals(
            51.23f, after.escTempC, 0.01f,
            "36.53 C is the temperature formula's raw-zero artefact, not a cooling board"
        )
        assertEquals(3.50f, after.motorCurrentA, 1e-3f, "nor is 0 A a motor current")
        assertEquals(36.0f, after.speedKmh, 1e-3f, "nor 0 km/h a speed")
    }

    @Test
    fun everyMotionBearingFrameProducesAFreshSampleAndNothingElseDoes() {
        // MotionSampleGate discriminates on instance IDENTITY: a fresh object
        // per read would look like a new decode on every notification and a
        // wheel that had gone quiet could never be seen to go quiet, while a
        // cached object that never changes would suppress real decodes.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 100))
        val first = assertNotNull(protocol.latestMotion(0))
        assertSame(first, protocol.latestMotion(0), "re-reading is not a new decode")

        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))
        val afterMotion = assertNotNull(protocol.latestMotion(0))
        assertNotSame(first, afterMotion, "the 0x07 frame is a decode")

        protocol.onNotification(odometerFrame(byteArrayOf(0x00, 0x82.toByte(), 0xB2.toByte(), 0x5D)))
        val afterOdometer = assertNotNull(protocol.latestMotion(0))
        assertNotSame(afterMotion, afterOdometer, "the 0x04 frame is a decode")

        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 200))
        val afterLive = assertNotNull(protocol.latestMotion(0))
        assertNotSame(afterOdometer, afterLive, "the 0x00 frame is a decode")

        // A smart-BMS frame carries NO motion. Minting a new instance for it
        // would push a duplicate motion sample through the gate at cell-frame
        // rate — and a Begode sends four of those for every live frame.
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472))
        assertSame(afterLive, protocol.latestMotion(0), "a 0x01 frame is not a motion decode")
    }

    @Test
    fun resetClearsTheControllerSample() {
        // A reconnect may face a different wheel; the gate would otherwise be
        // handed the previous one's last known motion as if it were current.
        val protocol = protocolFedWithFixture()
        assertNotNull(protocol.latestMotion(0), "precondition: the fixture decoded motion")
        protocol.reset()
        assertNull(protocol.latestMotion(0))
        // The duty latch goes with it, so a wheel reporting only zeros after
        // the reset does not inherit the ET Max's proof that duty is real.
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 0))
        assertEquals(0f, assertNotNull(protocol.latestMotion(0)).dutyPercent, 0f)
    }

    @Test
    fun theControllerSurfaceStillNeverWritesToTheWheel() {
        // FFE1 is Begode's COMMAND channel. Becoming a MotionSource adds no
        // reason to speak on it: the wheel streams motion unprompted, exactly
        // as it streams its battery.
        val protocol = BegodeProtocol(cellCount = 40)
        assertTrue(protocol.handshakeCommands().isEmpty(), "a stray write could reconfigure a wheel under its rider")
        assertTrue(protocol.pollCommands().isEmpty())
        assertEquals(0L, protocol.pollIntervalMs)
    }

    // --- The 0x04 alert bitmap: the wheel's own faults ---

    @Test
    fun theRealCaptureWheelIsHealthyAndReportsNoAlertAtAll() {
        // REAL DATA, and it is why every other fault test below is synthetic:
        // byte 14 reads 0x00 in ALL 38 of the capture's 0x04 frames, so the
        // capture contains no fault to decode.
        //
        // Not a vacuous assertion despite the empty expectation — the bytes
        // either side of 14 are NOT zero, so an off-by-one lands somewhere
        // visible: byte 13 (LED mode) is 0x01 -> "Wheel alarm", byte 17 is 0x12
        // -> "Speed alarm 2" + "Over voltage". Byte 15 is 0, so this cannot see
        // a slip in that direction; the synthetic tests below pin the offset
        // exactly.
        val protocol = protocolFedWithFixture()
        assertEquals(
            emptyList(), protocol.wheelAlerts(),
            "byte 13 holds 0x01 and byte 17 holds 0x12 — an off-by-one is visible here"
        )
        assertEquals(emptyList(), assertNotNull(protocol.latestMotion(0)).faults)
    }

    @Test
    fun syntheticEveryAlertBitIsDecodedOnItsOwnAndNamedInWords() {
        // SYNTHETIC — the capture is a healthy wheel. Each bit is fed alone, so
        // a wrong mask, a swapped pair or a shifted bit fails on exactly the
        // bits it got wrong instead of hiding behind a neighbour.
        //
        // Bit order and meanings are WheelLog's GotwayAdapter; the wording is
        // VescFaults' register — sentence case, no bit indices, no enum names.
        val expected = listOf(
            0x01 to "Wheel alarm",
            0x02 to "Speed alarm 2",
            0x04 to "Speed alarm 1",
            0x08 to "Low voltage",
            0x10 to "Over voltage",
            0x20 to "Over temperature",
            0x40 to "Hall sensor error",
            0x80 to "Transport mode"
        )
        for ((bit, label) in expected) {
            val protocol = BegodeProtocol()
            protocol.onNotification(odometerFrame(etMaxOdometerBytes(), alertBitmap = bit))
            assertEquals(listOf(label), protocol.wheelAlerts(), "alert bit ${bit.toString(2)}")
        }

        // …and all of them at once come back in bit order, none swallowed.
        val all = BegodeProtocol()
        all.onNotification(odometerFrame(etMaxOdometerBytes(), alertBitmap = 0xFF))
        assertEquals(expected.map { it.second }, all.wheelAlerts())
    }

    @Test
    fun theAlertBitmapComesFromByteFourteenAndNoNeighbour() {
        // SYNTHETIC and deliberately hostile: every byte around 14 carries a
        // DIFFERENT non-zero value, so each candidate offset produces a
        // different, recognisable answer —
        //   byte 13 (LED mode, 0x03)   -> "Wheel alarm" + "Speed alarm 2"
        //   byte 14 (correct, 0x20)    -> "Over temperature"
        //   byte 15 (light mode, 0x01) -> "Wheel alarm"
        val protocol = BegodeProtocol()
        protocol.onNotification(
            odometerFrame(
                etMaxOdometerBytes(),
                settings = 0x2800,
                powerOffTime = 0x1C1E,
                tiltbackRaw = 200,
                ledMode = 0x03,
                alertBitmap = 0x20,
                lightMode = 0x01
            )
        )
        assertEquals(listOf("Over temperature"), protocol.wheelAlerts())
    }

    @Test
    fun anOverheatingWheelPutsItsFaultOnTheControllerSample() {
        // SYNTHETIC. The end of the road for a fault: ControllerData.faults is
        // what AlertEngine turns into the rider's CRITICAL notification, whose
        // text is the labels joined with commas — so this is the exact string a
        // rider reads.
        val protocol = BegodeProtocol()
        protocol.onNotification(
            odometerFrame(etMaxOdometerBytes(), alertBitmap = 0x20 or 0x40)
        )
        val m = assertNotNull(protocol.latestMotion(0), "the 0x04 frame is a motion decode")
        assertEquals(listOf("Over temperature", "Hall sensor error"), m.faults)
    }

    @Test
    fun aClearedAlertClearsTheFaultListSoTheAlarmCanReArm() {
        // SYNTHETIC, and the failure it prevents is silent: AlertEngine.fire
        // disarms CONTROLLER_FAULT after firing and re-arms it only when the
        // list goes EMPTY. A decode that latched the last fault would leave a
        // wheel permanently faulted after one over-temperature — and the NEXT
        // genuine fault would raise nothing.
        val protocol = BegodeProtocol()
        protocol.onNotification(odometerFrame(etMaxOdometerBytes(), alertBitmap = 0x20))
        assertEquals(listOf("Over temperature"), assertNotNull(protocol.latestMotion(0)).faults)

        protocol.onNotification(odometerFrame(etMaxOdometerBytes(), alertBitmap = 0x00))
        assertEquals(
            emptyList(), assertNotNull(protocol.latestMotion(0)).faults,
            "the wheel stopped reporting it, so the fault is over"
        )
    }

    @Test
    fun theRidingAlarmsAndTransportModeAreDecodedButAreNotFaults() {
        // SYNTHETIC. Four of the eight bits are deliberately kept out of
        // ControllerData.faults — see BegodeProtocol.FAULT_BITS for the full
        // argument. In short: the two speed alarms engage and release on every
        // brisk stretch, and one CRITICAL notification per excursion is the
        // notification-fatigue failure AlertEngine already refuses for duty and
        // speed; transport mode and the general wheel alarm LATCH, and a latched
        // entry holds `faults` non-empty, which permanently disarms the alert
        // and masks every real fault behind it.
        //
        // They are still decoded, and this asserts BOTH halves: present in
        // wheelAlerts(), absent from faults.
        val protocol = BegodeProtocol()
        protocol.onNotification(
            odometerFrame(etMaxOdometerBytes(), alertBitmap = 0x01 or 0x02 or 0x04 or 0x80)
        )
        assertEquals(
            listOf("Wheel alarm", "Speed alarm 2", "Speed alarm 1", "Transport mode"),
            protocol.wheelAlerts(),
            "all four are decoded"
        )
        assertEquals(
            emptyList(), assertNotNull(protocol.latestMotion(0)).faults,
            "…and none of them is a controller fault"
        )

        // The genuine faults of the same byte still get through, so this is a
        // filter and not a mute.
        protocol.onNotification(odometerFrame(etMaxOdometerBytes(), alertBitmap = 0xFF))
        assertEquals(
            listOf("Low voltage", "Over voltage", "Over temperature", "Hall sensor error"),
            assertNotNull(protocol.latestMotion(0)).faults
        )
    }

    @Test
    fun resetClearsTheWheelsAlerts() {
        // A reconnect may face a healthy wheel. A fault surviving it would fire
        // a CRITICAL notification about a wheel that never reported anything.
        val protocol = BegodeProtocol()
        protocol.onNotification(odometerFrame(etMaxOdometerBytes(), alertBitmap = 0x20))
        assertEquals(listOf("Over temperature"), protocol.wheelAlerts())

        protocol.reset()
        assertEquals(emptyList(), protocol.wheelAlerts())
        protocol.onNotification(odometerFrame(etMaxOdometerBytes(), alertBitmap = 0x00))
        assertEquals(emptyList(), assertNotNull(protocol.latestMotion(0)).faults)
    }

    // --- Tiltback speed: decoded, and deliberately not surfaced ---

    @Test
    fun theRealCaptureTiltbackIsUnsetBecauseTheWheelReportsTwoHundred() {
        // REAL DATA: bytes 10..11 read 0x00C8 = 200 in all 38 of the capture's
        // 0x04 frames, and WheelLog treats anything >= 100 as the wheel's
        // "unset" marker. So the only wheel we have cannot exercise a SET
        // tiltback, and the tests that do are synthetic.
        val protocol = protocolFedWithFixture()
        assertNull(
            protocol.tiltbackSpeed(),
            "200 is the wheel's unset marker, not a 200 km/h tiltback"
        )
    }

    @Test
    fun syntheticTiltbackComesFromBytesTenAndEleven() {
        // SYNTHETIC and hostile: every neighbouring field carries a distinct
        // value, so each wrong offset lands somewhere recognisable —
        //   bytes 8..9  (power-off timer) -> 7198, which is >= 100 and so reads
        //                                    as "unset", i.e. null
        //   bytes 10..11 (correct)        -> 50
        //   bytes 12..13 (unknown + LED)  -> 3
        val protocol = BegodeProtocol()
        protocol.onNotification(
            odometerFrame(
                etMaxOdometerBytes(),
                settings = 0x2800,
                powerOffTime = 7198,
                tiltbackRaw = 50,
                ledMode = 0x03,
                alertBitmap = 0x00
            )
        )
        assertEquals(50f, assertNotNull(protocol.tiltbackSpeed()), 0f)
    }

    @Test
    fun syntheticTiltbackTreatsOneHundredAndAboveAsUnset() {
        // SYNTHETIC. WheelLog's own threshold, and the boundary is asserted on
        // both sides: a guard written as `> 100` would report a 100 as a
        // tiltback speed.
        fun tiltbackOf(raw: Int): Float? {
            val protocol = BegodeProtocol()
            protocol.onNotification(odometerFrame(etMaxOdometerBytes(), tiltbackRaw = raw))
            return protocol.tiltbackSpeed()
        }
        assertEquals(99f, assertNotNull(tiltbackOf(99), "99 is a real tiltback speed"), 0f)
        assertNull(tiltbackOf(100), "100 is the first unset value")
        assertNull(tiltbackOf(200), "…as the real wheel reports")
        assertNull(tiltbackOf(0), "a wheel does not tilt back at a standstill")
        assertNull(tiltbackOf(-5), "nor at a negative speed")
    }

    @Test
    fun theTiltbackSpeedNeverReachesTheControllerSample() {
        // The decision this task made explicit: ControllerData is shared with
        // VESC, Kelly and FarDriver, it has no tiltback field, and nothing
        // renders one — so the number is decoded and left on the protocol
        // rather than smuggled into a field that means something else. This
        // test is what makes "and it is not surfaced" checkable rather than a
        // claim in a comment: it fails the moment someone parks it in
        // `speedKmh` or `dutyPercent` on the way past.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 100))
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))
        protocol.onNotification(odometerFrame(etMaxOdometerBytes(), tiltbackRaw = 50))

        assertEquals(50f, assertNotNull(protocol.tiltbackSpeed()), 0f)
        val m = assertNotNull(protocol.latestMotion(0))
        assertEquals(3.6f, m.speedKmh, 1e-3f, "the wheel's speed, not its tiltback setting")
        assertEquals(2f, m.dutyPercent, 0f)
    }

    // --- A motor thermistor has to prove it exists (spec D §7.1) ---

    @Test
    fun aWheelWhoseMotorTemperatureIsAlwaysZeroClaimsNoMotorSensor() {
        // SYNTHETIC — the ET Max reads a steady 20 °C, so the capture cannot
        // reach this. A wheel whose motor thermistor is unwired still emits
        // 0x07 frames, with 0 in the temperature field; `hasMotorTemp =
        // sawMotionFrame` would CLAIM the sensor and arm MOTOR_TEMP against a
        // constant zero — an alarm shown as armed that can never fire.
        //
        // A range gate cannot decide this (0 °C is a real winter reading), so
        // the discriminator is the same latch the duty field uses: one non-zero
        // reading is what proves a sensor is there.
        val protocol = BegodeProtocol()
        repeat(5) {
            protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 0, dutyRaw = 2))
        }
        assertNotNull(protocol.batteryCurrentA(), "precondition: the frames WERE decoded")
        assertNotNull(protocol.dutyPercent(), "…and this wheel's duty is real")
        assertNull(protocol.motorTempC(), "an always-zero field is not a temperature measurement")

        val m = assertNotNull(protocol.latestMotion(0))
        assertFalse(m.hasMotorTemp, "the sensor has not proved it exists")
        assertTrue(
            m.motorTempC < -50f,
            "unproved must read as the sentinel, not as 0 °C (was ${m.motorTempC})"
        )
    }

    @Test
    fun theMotorTemperatureLatchHoldsOnceProvedSoARealZeroIsStillPublished() {
        // SYNTHETIC. The latch withholds an UNPROVEN sensor; it must not filter
        // readings. A wheel ridden into a genuine 0 °C after a warm start keeps
        // its alarm armed and its reading published — anything else would drop
        // real data on the coldest day of the year.
        val protocol = BegodeProtocol()
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))
        assertTrue(assertNotNull(protocol.latestMotion(0)).hasMotorTemp)

        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 0, dutyRaw = 2))
        val cold = assertNotNull(protocol.latestMotion(0))
        assertTrue(cold.hasMotorTemp, "the sensor already proved itself")
        assertEquals(0f, cold.motorTempC, 0f, "and 0 °C is a reading, not a silence")
    }

    @Test
    fun resetClearsTheMotorTemperatureLatch() {
        // Per-wheel evidence, exactly like the duty latch: a reconnect may face
        // a wheel with no thermistor, and it must not inherit the ET Max's
        // proof that one exists.
        val protocol = protocolFedWithFixture()
        assertNotNull(protocol.motorTempC(), "precondition: the capture proved the sensor")

        protocol.reset()
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 0, dutyRaw = 2))
        assertNull(protocol.motorTempC(), "the latch must not survive a reset")
        assertFalse(assertNotNull(protocol.latestMotion(0)).hasMotorTemp)
    }

    // --- Part F's availability gate, end to end on a real wheel ---

    private fun wheelVehicle() = Vehicle(
        id = "w", name = "ET Max", iconKey = "generic",
        packs = emptyList(),
        controllers = listOf(Controller(0, "ESC0", ControllerType.BEGODE, "AA:00")),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now()
    )

    @Test
    fun theRealWheelSuppliesEveryMotionAlertPartFOffers() {
        // The first REAL consumer of the availability gate, driven by real
        // bytes rather than a hand-built ControllerData: an ET Max supplies all
        // four kinds, so a rider sees four live rows and no grey.
        val protocol = protocolFedWithFixture()
        val availability = availabilityFor(wheelVehicle(), protocol.latestMotion(0))
        for (kind in MotionAlertKind.entries) {
            assertEquals(AlertAvailability.Available, availability[kind], "$kind on a real wheel")
        }
        // Availability is not the same question as armed, and this is the one
        // place the difference shows on a HEALTHY wheel: SPEED is Available —
        // the wheel measures ground speed — but ships with no default threshold
        // (F §10.1: a speed limit is meaningless without a rider-chosen number),
        // so `armedRules` drops it as `isOff` until the rider types one. The
        // other three arm on the shipped defaults.
        assertEquals(
            setOf(MotionAlertKind.DUTY, MotionAlertKind.MOTOR_TEMP, MotionAlertKind.ESC_TEMP),
            armedRules(wheelVehicle(), protocol.latestMotion(0), AlarmDefaults.all())
                .rules.map { it.kind }.toSet(),
            "everything the hardware supplies AND the rider has a threshold for"
        )
    }

    @Test
    fun theWheelsDutyAlarmIsArmedOnMeasuredHardwarePwm() {
        // reportsDuty[BEGODE] is a STATIC table entry, and this part was planned
        // on the belief that it had to be turned off. The capture overturned
        // that: the wheel reports true hardware PWM (2 % standing still), so the
        // entry stays true ON EVIDENCE. Flipping it to false makes DUTY read
        // Unavailable(ControllerReportsNoDuty(BEGODE)) and fails here — which is
        // the point of asserting it through availabilityFor rather than only as
        // a boolean.
        val protocol = protocolFedWithFixture()
        assertEquals(
            2f, assertNotNull(protocol.latestMotion(0)).dutyPercent, 0f,
            "the evidence the table entry rests on"
        )
        assertEquals(
            AlertAvailability.Available,
            availabilityFor(wheelVehicle(), protocol.latestMotion(0))[MotionAlertKind.DUTY],
            "a wheel's ШИМ alarm is Part F's headline feature and this wheel supplies it"
        )
        assertTrue(ControllerType.BEGODE.reportsDuty)
    }

    @Test
    fun aWheelWithNoMotorThermistorGreysThatAlertWithTheReasonInWords() {
        // The end-to-end path Part F built and a wheel is the first real
        // consumer of: decode -> ControllerData -> availabilityFor -> a REASON,
        // which presentation renders as a sentence under a greyed row
        // (EnumLabels.alertUnavailableReasonLabel, exhaustive over the sealed
        // interface so every reason has words).
        val protocol = BegodeProtocol()
        // A live frame (so the board sensor and speed ARE present) and a 0x07
        // frame whose motor temperature is stuck at zero.
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 1000, tempRaw = -3069))
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 0, dutyRaw = 2))

        val availability = availabilityFor(wheelVehicle(), protocol.latestMotion(0))
        assertEquals(
            AlertAvailability.Unavailable(AlertUnavailableReason.NoMotorTempSensor),
            availability[MotionAlertKind.MOTOR_TEMP],
            "greyed WITH a reason, not silently absent"
        )
        // Only that one. The neighbouring kinds are unaffected — an absent
        // sensor must not take the rest of the screen with it.
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.ESC_TEMP])
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.SPEED])
        assertEquals(AlertAvailability.Available, availability[MotionAlertKind.DUTY])

        // Greyed in the editor…
        val drafts = alertDraftsFor(wheelVehicle(), availability)
        val motorDraft = assertNotNull(drafts.firstOrNull { it.kind == MotionAlertKind.MOTOR_TEMP })
        assertFalse(motorDraft.isEditable, "an unavailable kind is not editable")
        assertTrue(
            drafts.first { it.kind == MotionAlertKind.ESC_TEMP }.isEditable,
            "…and its neighbour still is"
        )
        // …and impossible to arm, which is F §10's actual requirement.
        assertFalse(
            armedRules(wheelVehicle(), protocol.latestMotion(0), AlarmDefaults.all())
                .rules.any { it.kind == MotionAlertKind.MOTOR_TEMP },
            "an unavailable alert must be impossible to arm"
        )
    }

    // --- Duty is a MAGNITUDE, because the frame field is signed and unbounded ---

    @Test
    fun syntheticNegativeHardwarePwmIsPublishedAsAMagnitudeSoTheShimAlarmStillFires() {
        // SYNTHETIC, and it has to be: the only capture reads a constant
        // 0x0002, so nothing in it pins this field's SIGN in either direction.
        // The field is signed 16-bit and the decode used to pass it through
        // untouched, while every consumer treats duty as a non-negative 0..100
        // magnitude compared against UPPER thresholds. A wheel reporting
        // negative hardware PWM under regen braking therefore graded NORMAL and
        // left the ШИМ alarm — the headline safety feature of this whole part —
        // silent at the exact moment a EUC's duty peaks.
        val protocol = BegodeProtocol()
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = -92))

        assertEquals(
            92f, assertNotNull(protocol.dutyPercent()), 0f,
            "a negative hardware PWM is 92 % of duty, not -92 %"
        )
        assertEquals(92f, assertNotNull(protocol.latestMotion(0)).dutyPercent, 0f)
        assertEquals(
            DutyLevel.CRITICAL,
            DutyBands.level(assertNotNull(protocol.latestMotion(0)).dutyPercent),
            "the rider-visible consequence: the alarm the part exists for must fire"
        )
        // The control that makes the assertion above non-vacuous — the raw
        // value graded NORMAL, which is what shipped.
        assertEquals(DutyLevel.NORMAL, DutyBands.level(-92f), "…and it did not, before the magnitude")
    }

    @Test
    fun syntheticANegativeOnlyWheelStillProvesItReportsDuty() {
        // The latch reads the RAW value, ahead of the magnitude: a firmware
        // that only ever reports negative PWM has still proved it fills the
        // field in, and withholding its duty would be the OTHER silent failure
        // (hasDuty false forever, the alarm greyed out on a wheel that does
        // report).
        val protocol = BegodeProtocol()
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = -3))
        assertNotNull(protocol.dutyPercent(), "a negative reading is a reading")
        assertTrue(assertNotNull(protocol.latestMotion(0)).hasDuty)
    }

    @Test
    fun syntheticAnOutOfRangeDutyIsClampedToOneHundredRatherThanShownRaw() {
        // The mirror case. This frame format has no checksum — the 5A5A5A5A
        // tail is its whole integrity check — so a garbled 0x07 can put any
        // 16-bit value here. 8000 % would render as a dial filled eighty times
        // over. Clamping keeps the failure LOUD (the alarm fires at maximum)
        // rather than dropping the frame and leaving a stale, comfortable
        // number on the dial: duty only ever escalates upwards, so over-firing
        // is the safe error.
        val protocol = BegodeProtocol()
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 8000))
        assertEquals(100f, assertNotNull(protocol.dutyPercent()), 0f)
        assertEquals(100f, assertNotNull(protocol.latestMotion(0)).dutyPercent, 0f)

        // Symmetrically for a large negative, which the magnitude turns into a
        // large positive before the clamp sees it.
        val other = BegodeProtocol()
        other.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = -8000))
        assertEquals(100f, assertNotNull(other.dutyPercent()), 0f)
    }

    @Test
    fun syntheticAnOrdinaryDutyPassesThroughUntouchedByTheMagnitudeAndTheClamp() {
        // The control for both tests above: neither `abs` nor the clamp may
        // move a value that is already in range, and 100 % itself is a real
        // reading (full modulation) rather than the clamp's own artefact.
        val protocol = BegodeProtocol()
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 63))
        assertEquals(63f, assertNotNull(protocol.dutyPercent()), 0f)

        val atTheLimit = BegodeProtocol()
        atTheLimit.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 100))
        assertEquals(100f, assertNotNull(atTheLimit.dutyPercent()), 0f)
    }

    // --- tripKm is the SESSION's distance, not the wheel's own counter ---

    @Test
    fun syntheticTheTripIsDistanceSinceThisConnectionAndNotSincePowerOn() {
        // `ControllerData.tripKm` means "distance since this connection
        // started" everywhere else in Volty (VescProtocol, VescGatewayProtocol,
        // the demo), and `RideMetrics.sessionWhPerKm` — a session's Wh over a
        // session's km — depends on it. This wheel used to publish its OWN
        // since-power-on counter there, so a rider connecting mid-ride at km 30
        // saw a one-second-old session reading 30.0 km.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 100, tripMeters = 30_000))
        protocol.onNotification(odometerFrame(meterBytes = odometerBytes(30_000)))

        assertEquals(
            0f, assertNotNull(protocol.latestMotion(0)).tripKm, 0f,
            "a session one frame old has travelled nothing, whatever the wheel's own counter says"
        )
        // The wheel's counter is still decoded — it is what pins bytes 8..9 —
        // it is simply not this field.
        assertEquals(30_000L, assertNotNull(protocol.powerOnDistanceMeters()))

        // 2.5 km further on, and the trip is that 2.5 km.
        protocol.onNotification(odometerFrame(meterBytes = odometerBytes(32_500)))
        assertEquals(2.5f, assertNotNull(protocol.latestMotion(0)).tripKm, 1e-3f)
        assertEquals(32.5f, assertNotNull(protocol.latestMotion(0)).odometerKm, 1e-3f)
    }

    @Test
    fun syntheticTheTripSurvivesPastTheSixtyFiveKilometreWrapOfTheWheelsOwnCounter() {
        // The other half of the same defect. The wheel's own field is 16-bit,
        // so it wraps at 65 535 m and the TRIP tile silently restarted at 0.0
        // for a rider past 65.5 km. The odometer is a 32-bit metre count, so
        // the derived trip simply keeps counting.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, tripMeters = 65_535))
        protocol.onNotification(odometerFrame(meterBytes = odometerBytes(8_565_341)))
        // The wheel wraps; the odometer does not.
        protocol.onNotification(liveFrame(voltageRaw = 5892, tripMeters = 0))
        protocol.onNotification(odometerFrame(meterBytes = odometerBytes(8_635_341)))

        assertEquals(
            70f, assertNotNull(protocol.latestMotion(0)).tripKm, 1e-3f,
            "70 km of session, across the point where the wheel's own counter wrapped to 0"
        )
        assertEquals(0L, assertNotNull(protocol.powerOnDistanceMeters()), "the wheel really did wrap")
    }

    @Test
    fun syntheticTheTripCannotGoNegativeAndAResetStartsANewSession() {
        val protocol = BegodeProtocol()
        protocol.onNotification(odometerFrame(meterBytes = odometerBytes(10_000)))
        protocol.onNotification(odometerFrame(meterBytes = odometerBytes(12_000)))
        assertEquals(2f, assertNotNull(protocol.latestMotion(0)).tripKm, 1e-3f)

        // A lifetime counter that went BACKWARDS means the wheel contradicted
        // itself; a negative distance helps nobody. Same guard VescProtocol has.
        protocol.onNotification(odometerFrame(meterBytes = odometerBytes(9_000)))
        assertEquals(0f, assertNotNull(protocol.latestMotion(0)).tripKm, 0f)

        // A reconnect is a NEW session: the baseline must not survive it, or
        // the next session would open at the previous one's distance.
        protocol.reset()
        protocol.onNotification(odometerFrame(meterBytes = odometerBytes(12_000)))
        assertEquals(
            0f, assertNotNull(protocol.latestMotion(0)).tripKm, 0f,
            "a reconnect starts the trip over, whatever the odometer reads"
        )
        protocol.onNotification(odometerFrame(meterBytes = odometerBytes(13_500)))
        assertEquals(1.5f, assertNotNull(protocol.latestMotion(0)).tripKm, 1e-3f)
    }

    // --- The 0x04 and 0x07 frames take the same boot gate the 0x00 frame does ---

    @Test
    fun syntheticAZeroPaddedOdometerFrameCannotEraseTheWheelsMileage() {
        // `parseLiveFrame` has been gated since Task 2 and `parseBmsTelemetry`
        // for longer; 0x04 and 0x07 were not, and the asymmetry was undocumented
        // rather than reasoned. This format has no checksum, so a boot
        // placeholder or a truncated frame mid-stream published its zeros as
        // measurements: 8 565 km of lifetime mileage became 0.0 km, and the
        // trip baseline reset under the rider.
        val protocol = BegodeProtocol()
        protocol.onNotification(odometerFrame(meterBytes = etMaxOdometerBytes(), tiltbackRaw = 50))
        val real = assertNotNull(protocol.latestMotion(0))

        protocol.onNotification(zeroPaddedFrame(type = 0x04))
        assertSame(real, protocol.latestMotion(0), "a zero-padded frame is not a decode and mints no sample")
        assertEquals(8_565_341L, assertNotNull(protocol.odometerMeters()))
        assertEquals(50f, assertNotNull(protocol.tiltbackSpeed()), 0f)

        // …and a genuine frame right after it is decoded normally.
        protocol.onNotification(odometerFrame(meterBytes = odometerBytes(8_565_400)))
        assertEquals(8_565_400L, assertNotNull(protocol.odometerMeters()))
    }

    @Test
    fun syntheticAZeroPaddedMotionFrameCannotCloseTheTwoLatchesThatNeverReopen() {
        // The sharper half: 0x07 owns two ONE-WAY latches — the motor-thermistor
        // one and the truePWM one — and neither reopens until reset(). A frame
        // that gets past the gate is permanent for the connection.
        val protocol = BegodeProtocol()
        protocol.onNotification(zeroPaddedFrame(type = 0x07))
        assertNull(protocol.latestMotion(0), "a zero-padded 0x07 is not a motion decode at all")
        assertNull(protocol.batteryCurrentA(), "…and 0 A is not a battery-current measurement")
        assertNull(protocol.motorTempC())
        assertNull(protocol.dutyPercent())

        // A real frame afterwards still works — the gate withholds a placeholder,
        // it does not disable the frame type.
        protocol.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))
        assertEquals(2f, assertNotNull(protocol.dutyPercent()), 0f)
        assertEquals(20f, assertNotNull(protocol.motorTempC()), 0f)
    }

    @Test
    fun syntheticTheBootGateJudgesTheWholePayloadAndNotAnySingleField() {
        // The discriminator is that EVERY payload byte is zero, and it has to
        // be: each field on its own has a legitimate zero. An odometer of 0 is
        // a wheel out of its box, an alert byte of 0 is a healthy wheel, 0 °C
        // is a real winter reading. A gate keyed on any one of them would
        // reject real data.
        val newWheel = BegodeProtocol()
        // Odometer 0, but the wheel has a tiltback configured — a real frame.
        newWheel.onNotification(odometerFrame(meterBytes = odometerBytes(0), tiltbackRaw = 50))
        assertEquals(0L, assertNotNull(newWheel.odometerMeters(), "a brand-new wheel really reads 0 km"))

        val coldMotor = BegodeProtocol()
        // Motor at exactly 0 °C and duty at 0, but a real battery current.
        coldMotor.onNotification(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 0, dutyRaw = 0))
        assertEquals(
            -0.67f, assertNotNull(coldMotor.batteryCurrentA(), "the frame WAS accepted"), 1e-3f
        )
    }

    // --- Synthetic frame builders (24 bytes: 55 AA + 16 payload + type + subtype + 5A x4) ---

    /** A frame of [type] whose whole 16-byte payload is zero — the wheel's boot placeholder. */
    private fun zeroPaddedFrame(type: Int): ByteArray = frame(type, 24, ByteArray(16))

    /** The 0x04 odometer field's four big-endian bytes for [meters]. */
    private fun odometerBytes(meters: Long): ByteArray = byteArrayOf(
        (meters shr 24).toByte(), (meters shr 16).toByte(), (meters shr 8).toByte(), meters.toByte()
    )


    private fun frame(type: Int, subtype: Int, payload: ByteArray): ByteArray {
        require(payload.size == 16) { "payload is frame bytes 2..17" }
        return byteArrayOf(0x55, 0xAA.toByte()) + payload +
            byteArrayOf(type.toByte(), subtype.toByte(), 0x5A, 0x5A, 0x5A, 0x5A)
    }

    /**
     * Live 0x00 frame. Payload index i is frame byte i + 2: voltage at frame
     * 2..3, speed at 4..5, an unknown word at 6..7, trip at 8..9, phase
     * current at 10..11, MPU temperature at 12..13, WheelLog's fallback PWM at
     * 14..15. Byte 19 is 24 on the real wheel (meaning unknown).
     */
    private fun liveFrame(
        voltageRaw: Int,
        speedRaw: Int = 0,
        unknown6: Int = 0,
        tripMeters: Int = 0,
        currentRaw: Int = 0,
        tempRaw: Int = 0,
        fallbackPwmRaw: Int = 0
    ): ByteArray {
        val p = ByteArray(16)
        p[0] = (voltageRaw shr 8).toByte(); p[1] = voltageRaw.toByte()
        p[2] = (speedRaw shr 8).toByte(); p[3] = speedRaw.toByte()
        p[4] = (unknown6 shr 8).toByte(); p[5] = unknown6.toByte()
        p[6] = (tripMeters shr 8).toByte(); p[7] = tripMeters.toByte()
        p[8] = (currentRaw shr 8).toByte(); p[9] = currentRaw.toByte()
        p[10] = (tempRaw shr 8).toByte(); p[11] = tempRaw.toByte()
        p[12] = (fallbackPwmRaw shr 8).toByte(); p[13] = fallbackPwmRaw.toByte()
        return frame(0x00, 24, p)
    }

    /**
     * Motion 0x07 frame: battery current at frame 2..3, an unknown word at
     * 4..5, motor temperature at 6..7, hardware duty at 8..9.
     */
    private fun motionFrame(batteryCurrentRaw: Int, motorTempRaw: Int, dutyRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[0] = (batteryCurrentRaw shr 8).toByte(); p[1] = batteryCurrentRaw.toByte()
        p[2] = 0x00; p[3] = 0x01 // the constant the real wheel sends here
        p[4] = (motorTempRaw shr 8).toByte(); p[5] = motorTempRaw.toByte()
        p[6] = (dutyRaw shr 8).toByte(); p[7] = dutyRaw.toByte()
        return frame(0x07, 24, p)
    }

    /**
     * Odometer 0x04 frame. Payload index i is frame byte i + 2: odometer at
     * frame 2..5, settings word at 6..7, power-off timer at 8..9, tiltback
     * speed at 10..11, LED mode at 13, the alert bitmap at 14, light mode at
     * 15. The defaults reproduce a healthy wheel with nothing set, so a test
     * naming one field is testing that field alone.
     */
    private fun odometerFrame(
        meterBytes: ByteArray,
        settings: Int = 0,
        powerOffTime: Int = 0,
        tiltbackRaw: Int = 0,
        ledMode: Int = 0,
        alertBitmap: Int = 0,
        lightMode: Int = 0
    ): ByteArray {
        require(meterBytes.size == 4) { "the odometer field is 4 bytes" }
        val p = ByteArray(16)
        meterBytes.copyInto(p)
        p[4] = (settings shr 8).toByte(); p[5] = settings.toByte()
        p[6] = (powerOffTime shr 8).toByte(); p[7] = powerOffTime.toByte()
        p[8] = (tiltbackRaw shr 8).toByte(); p[9] = tiltbackRaw.toByte()
        p[11] = ledMode.toByte()
        p[12] = alertBitmap.toByte()
        p[13] = lightMode.toByte()
        return frame(0x04, 24, p)
    }

    /** The capture's own odometer bytes, so a test can vary everything else. */
    private fun etMaxOdometerBytes() = byteArrayOf(0x00, 0x82.toByte(), 0xB2.toByte(), 0x5D)

    /** 0x01 telemetry frame — layout as in [BegodeProtocolTest]. */
    private fun telemetryFrame(bmsnum: Int, packVoltageRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[4] = (packVoltageRaw shr 8).toByte(); p[5] = packVoltageRaw.toByte()
        return frame(0x01, bmsnum, p)
    }
}
