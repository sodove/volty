package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Motion decode of the Begode live 0x00 and odometer 0x04 frames.
 *
 * **What the real capture can and cannot prove.** [BegodeDumpFixture] is 13
 * seconds of an ET Max standing still, so:
 *
 * - **trip distance is fully verified by it** — `00 3d 00 00` reads as 61 m
 *   only under the word-swapped order, and the two competing readings (3 998 km
 *   and 15.6 km) are impossible for a session that has just started;
 * - **the odometer is verified as far as plausibility goes** — 8 565 km under
 *   the plain big-endian read, versus 2.99 million km word-swapped;
 * - **speed is not verified at all.** Bytes 4..5 are `00 00` in every live
 *   frame of the capture, so every fixture-based speed assertion below asserts
 *   ZERO and pins nothing but the offset. The scale is unverifiable here by
 *   construction — see `SPEED_KMH_PER_UNIT` — and the sign and offset are
 *   pinned with SYNTHETIC frames, marked as such on each test.
 */
class BegodeMotionProtocolTest {

    private fun protocolFedWithFixture(): BegodeProtocol {
        val protocol = BegodeProtocol()
        // One notification at a time, exactly as the BLE layer delivers them:
        // at MTU 23 every 24-byte frame straddles two notifications.
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        return protocol
    }

    // --- The real capture ---

    @Test
    fun theRealCaptureDecodesAStationaryWheel() {
        // REAL DATA, and honestly weak: this asserts that the speed field
        // reads zero, which is true of the capture and would also be true of a
        // decoder pointed at any other zero bytes of the frame. It does kill a
        // decoder pointed at a NON-zero one (voltage at 2..3 would give 58.92,
        // current at 10..11 −3.30), which is what makes it worth keeping.
        val protocol = protocolFedWithFixture()
        assertEquals(
            0f,
            assertNotNull(protocol.speedKmh(), "a decoded live frame must yield a speed"),
            0f,
            "the wheel is stationary throughout the capture"
        )
    }

    @Test
    fun theRealCaptureTripIsSixtyOneMetresAndNotFourThousandKilometres() {
        // REAL DATA, and the whole point of the field. Bytes 6..9 are
        // `00 3d 00 00` in every live frame:
        //   word-swapped (correct) -> 61 m
        //   naive big-endian       -> 3 997 696 m
        //   fully little-endian    -> 15 616 m
        // Nothing downstream would notice the difference — 3 998 km is a
        // plausible-looking number on a dashboard — so the assertion is the
        // only thing standing between the rider and a fictional trip.
        val protocol = protocolFedWithFixture()
        val trip = assertNotNull(protocol.tripDistanceMeters(), "the capture carries live frames")
        assertEquals(
            61L, trip,
            "trip distance of the ET Max capture (a naive big-endian read gives 3 997 696 m)"
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

    @Test
    fun aSmartBmsWheelStillReportsItsMotion() {
        // The ET Max has a smart BMS, and the synthetic no-BMS pack is retired
        // the moment its first 0x01 frame lands. Motion must NOT be retired
        // with it — a wheel with a smart BMS still moves. (Real capture, plus a
        // synthetic check of the same thing on a wheel with no live frames at
        // all further down.)
        val protocol = protocolFedWithFixture()
        assertNull(protocol.liveVoltageOn672ScaleV(), "precondition: the synthetic pack is retired")
        assertNotNull(protocol.speedKmh(), "speed must survive the smart-BMS hand-over")
        assertNotNull(protocol.tripDistanceMeters(), "trip must survive the smart-BMS hand-over")
    }

    // --- Synthetic frames, where the capture is silent ---

    @Test
    fun syntheticSpeedComesFromBytesFourAndFive() {
        // SYNTHETIC — the capture is stationary and cannot exercise this.
        // Every other field of the frame carries a distinct non-zero value, so
        // a decoder reading the wrong offset lands on a number that is not
        // 12.34 km/h.
        val protocol = BegodeProtocol()
        protocol.onNotification(
            liveFrame(
                voltageRaw = 5892,
                speedRaw = 1234,
                tripBytes = byteArrayOf(0x00, 0x3D, 0x00, 0x00),
                currentRaw = -330,
                tempRaw = -3066
            )
        )
        // 1234 units at the (unverified) 0.01 km/h per unit scale.
        assertEquals(12.34f, assertNotNull(protocol.speedKmh()), 1e-4f)
        // ...while the battery decode of the same frame is untouched.
        assertEquals(58.92f, assertNotNull(protocol.liveVoltageOn672ScaleV()), 0.005f)
        assertEquals(-3.30f, assertNotNull(protocol.latestData(0)).current, 1e-4f)
    }

    @Test
    fun syntheticReverseMotionReadsNegativeNotSixHundredKmh() {
        // SYNTHETIC. The field is signed: an unsigned read turns a wheel
        // rolling gently backwards into 655.35 km/h, which every alarm
        // threshold downstream would treat as a catastrophe.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = -523))
        assertEquals(
            -5.23f, assertNotNull(protocol.speedKmh()), 1e-4f,
            "an unsigned read of the same bytes would report 650.13 km/h"
        )
    }

    @Test
    fun syntheticTripWordOrderIsPinnedWhereEveryReadingDiffers() {
        // SYNTHETIC, and stricter than the capture: with bytes 6..9 =
        // 12 34 56 78 the three candidate readings are all distinct —
        //   word-swapped (correct) -> 0x56781234 = 1 450 709 556
        //   naive big-endian       -> 0x12345678 =   305 419 896
        //   fully little-endian    -> 0x78563412 = 2 018 915 346
        // The capture's `00 3d 00 00` cannot distinguish a byte-swap inside
        // the words from the word swap itself; this can.
        val protocol = BegodeProtocol()
        protocol.onNotification(
            liveFrame(
                voltageRaw = 5892,
                tripBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
            )
        )
        assertEquals(1_450_709_556L, assertNotNull(protocol.tripDistanceMeters()))
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
    fun syntheticMotionSurvivesTheSmartBmsHandOver() {
        // SYNTHETIC and deterministic: the fixture proves the same thing, but
        // only as a side effect of its ordering. Retiring the synthetic pack
        // must not retire the wheel's motion.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 1234))
        protocol.onNotification(odometerFrame(byteArrayOf(0x00, 0x82.toByte(), 0xB2.toByte(), 0x5D)))
        protocol.onNotification(telemetryFrame(bmsnum = 0, packVoltageRaw = 1472))

        assertNull(protocol.liveVoltageOn672ScaleV(), "precondition: the synthetic pack is retired")
        assertEquals(12.34f, assertNotNull(protocol.speedKmh()), 1e-4f)
        assertEquals(8_565_341L, assertNotNull(protocol.odometerMeters()))
    }

    // --- Absence is absence, never zero ---

    @Test
    fun nothingDecodedMeansNoReading() {
        // Spec §7.1: a decoder that reports 0 for a field it has never seen
        // claims a measurement. 0 km/h and 0 m are both perfectly ordinary
        // real values, so they cannot double as "unknown".
        val protocol = BegodeProtocol()
        assertNull(protocol.speedKmh())
        assertNull(protocol.tripDistanceMeters())
        assertNull(protocol.odometerMeters())
    }

    @Test
    fun theOdometerAndTheLiveFrameAreIndependentReadings() {
        // SYNTHETIC. They come from different frame types and a wheel can send
        // one without the other; a single "saw something" flag would make each
        // vouch for the other.
        val liveOnly = BegodeProtocol()
        liveOnly.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 100))
        assertNotNull(liveOnly.speedKmh())
        assertNotNull(liveOnly.tripDistanceMeters())
        assertNull(liveOnly.odometerMeters(), "no 0x04 frame arrived")

        val odometerOnly = BegodeProtocol()
        odometerOnly.onNotification(odometerFrame(byteArrayOf(0x00, 0x82.toByte(), 0xB2.toByte(), 0x5D)))
        assertNotNull(odometerOnly.odometerMeters())
        assertNull(odometerOnly.speedKmh(), "no live frame arrived")
        assertNull(odometerOnly.tripDistanceMeters(), "no live frame arrived")
    }

    @Test
    fun aBootZeroLiveFrameIsNotAMotionReading() {
        // SYNTHETIC. The wheel zero-pads its live frame while booting, and the
        // existing decode already refuses to synthesise a pack from such a
        // frame (BegodeNoBmsProtocolTest). Motion takes the same gate: the
        // speed field here says 50 km/h, and publishing it would be publishing
        // the contents of a placeholder.
        val protocol = BegodeProtocol()
        protocol.onNotification(liveFrame(voltageRaw = 0, speedRaw = 5000, tripBytes = byteArrayOf(1, 2, 3, 4)))
        assertNull(protocol.speedKmh(), "a boot placeholder is not a speed measurement")
        assertNull(protocol.tripDistanceMeters(), "a boot placeholder is not a trip measurement")

        // ...and a genuine frame right after it is decoded normally.
        protocol.onNotification(liveFrame(voltageRaw = 5892, speedRaw = 1234))
        assertEquals(12.34f, assertNotNull(protocol.speedKmh()), 1e-4f)
    }

    @Test
    fun resetClearsMotion() {
        // A reconnect may face a different wheel; its trip and odometer must
        // not be inherited from the previous one.
        val protocol = protocolFedWithFixture()
        assertNotNull(protocol.speedKmh(), "precondition: the fixture decoded motion")
        assertNotNull(protocol.tripDistanceMeters())
        assertNotNull(protocol.odometerMeters())

        protocol.reset()
        assertNull(protocol.speedKmh())
        assertNull(protocol.tripDistanceMeters())
        assertNull(protocol.odometerMeters())

        // And the protocol decodes motion again from scratch afterwards.
        BegodeDumpFixture.chunks().forEach { protocol.onNotification(it) }
        assertEquals(61L, assertNotNull(protocol.tripDistanceMeters()))
        assertEquals(8_565_341L, assertNotNull(protocol.odometerMeters()))
    }

    // --- Synthetic frame builders (24 bytes: 55 AA + 16 payload + type + subtype + 5A x4) ---

    private fun frame(type: Int, subtype: Int, payload: ByteArray): ByteArray {
        require(payload.size == 16) { "payload is frame bytes 2..17" }
        return byteArrayOf(0x55, 0xAA.toByte()) + payload +
            byteArrayOf(type.toByte(), subtype.toByte(), 0x5A, 0x5A, 0x5A, 0x5A)
    }

    /**
     * Live 0x00 frame. Payload index i is frame byte i + 2: voltage at frame
     * 2..3, speed at 4..5, trip at 6..9 (raw bytes, so a test can state the
     * byte order it means), phase current at 10..11, MPU temperature at 12..13.
     * Byte 19 is 24 on the real wheel (meaning unknown).
     */
    private fun liveFrame(
        voltageRaw: Int,
        speedRaw: Int = 0,
        tripBytes: ByteArray = byteArrayOf(0, 0, 0, 0),
        currentRaw: Int = 0,
        tempRaw: Int = 0
    ): ByteArray {
        require(tripBytes.size == 4) { "the trip field is 4 bytes" }
        val p = ByteArray(16)
        p[0] = (voltageRaw shr 8).toByte(); p[1] = voltageRaw.toByte()
        p[2] = (speedRaw shr 8).toByte(); p[3] = speedRaw.toByte()
        tripBytes.copyInto(p, destinationOffset = 4)
        p[8] = (currentRaw shr 8).toByte(); p[9] = currentRaw.toByte()
        p[10] = (tempRaw shr 8).toByte(); p[11] = tempRaw.toByte()
        return frame(0x00, 24, p)
    }

    /** Odometer 0x04 frame: the 4 raw bytes at frame 2..5. */
    private fun odometerFrame(meterBytes: ByteArray): ByteArray {
        require(meterBytes.size == 4) { "the odometer field is 4 bytes" }
        val p = ByteArray(16)
        meterBytes.copyInto(p)
        return frame(0x04, 24, p)
    }

    /** 0x01 telemetry frame — layout as in [BegodeProtocolTest]. */
    private fun telemetryFrame(bmsnum: Int, packVoltageRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[4] = (packVoltageRaw shr 8).toByte(); p[5] = packVoltageRaw.toByte()
        return frame(0x01, bmsnum, p)
    }
}
