package ru.sodovaya.volty.presentation.video

import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DutySource
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.video.VideoMetric
import ru.sodovaya.volty.domain.video.VideoReading
import ru.sodovaya.volty.domain.video.VideoSnapshot
import ru.sodovaya.volty.domain.video.VideoTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * A Begode republishes its last 0x07 duty on every live and odometer frame with `dutyFresh = false`. The overlay
 * used to blank the duty on all of those and show `—` for most of every 0x07 interval. These tests drive the REAL
 * [BegodeProtocol] with real frame bytes (the same ones as `BegodeDutyFreshnessTest`) through the mapper, and
 * assert the precondition on every step (that the sample really is, or is not, fresh) so a driver that never
 * reaches the republish path cannot pass.
 */
@OptIn(ExperimentalTime::class)
class VideoDutyHoldTest {
    private val vehicle = Vehicle(
        id = "wheel",
        name = "Wheel",
        iconKey = "unicycle",
        packs = listOf(Pack(0, "Pack", BmsType.ANT_BMS, "AA")),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochMilliseconds(0L),
    )

    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val live = bytes("55 AA 19 5D 00 00 00 4B 00 00 FF 74 EE A5 00 80 00 05 00 18 5A 5A 5A 5A")
    private fun motionFrame(duty: Int): ByteArray {
        val p = ByteArray(16)
        p[1] = 0x43; p[3] = 0x01; p[6] = (duty shr 8).toByte(); p[7] = duty.toByte()
        return byteArrayOf(0x55, 0xAA.toByte()) + p + byteArrayOf(0x07, 0x18, 0x5A, 0x5A, 0x5A, 0x5A)
    }

    private fun map(
        motion: ControllerData,
        at: Long,
        hold: VideoDutyHold,
        vehicle: Vehicle = this.vehicle,
    ): VideoSnapshot = assertNotNull(
        videoTelemetrySnapshot(
            data = VehicleData(motion = motion.copy(isConnected = true, timestamp = Instant.fromEpochMilliseconds(at))),
            vehicle = vehicle,
            connection = ConnectionState.Connected(vehicle),
            receivedAtMillis = at,
            nowEpochMillis = at,
            dutyHold = hold,
        ),
    )

    private fun duty(snapshot: VideoSnapshot) = snapshot.readings.getValue(VideoMetric.DUTY)

    @Test
    fun aBegodesDutyStaysOnScreenBetween0x07FramesAndDoesNotBlink() {
        val wheel = BegodeProtocol()
        val hold = VideoDutyHold()

        wheel.onNotification(live)
        val beforeAny007 = assertNotNull(wheel.latestMotion(0))
        assertFalse(beforeAny007.hasDuty, "precondition: no 0x07 yet, nothing to show")
        assertEquals(VideoReading(null), duty(map(beforeAny007, 1_000, hold)))

        wheel.onNotification(motionFrame(37))
        val fresh = assertNotNull(wheel.latestMotion(0))
        assertTrue(fresh.dutyFresh, "precondition: this rebuild follows a 0x07")
        assertEquals(37.0, duty(map(fresh, 1_100, hold)).value)

        // The two rebuilds between 0x07 frames: the old code showed `—` for both.
        repeat(2) { step ->
            wheel.onNotification(live)
            val republished = assertNotNull(wheel.latestMotion(0))
            assertFalse(republished.dutyFresh, "precondition: a live rebuild republishes, it does not measure")
            assertEquals(37f, republished.dutyPercent)
            val snapshot = map(republished, 1_200L + step * 100, hold)
            assertEquals(37.0, duty(snapshot).value, "step $step")
            assertEquals(1_100L, snapshot.sampledAtMillis[VideoMetric.DUTY], "held duty keeps its measurement time")
        }

        wheel.onNotification(motionFrame(41))
        assertEquals(41.0, duty(map(assertNotNull(wheel.latestMotion(0)), 1_500, hold)).value)
    }

    @Test
    fun theHoldIsBoundedSoASilent0x07FieldDoesNotFreezeANumber() {
        val wheel = BegodeProtocol()
        val hold = VideoDutyHold()
        wheel.onNotification(live)
        wheel.onNotification(motionFrame(37))
        assertEquals(37.0, duty(map(assertNotNull(wheel.latestMotion(0)), 1_000, hold)).value)

        wheel.onNotification(live)
        val republished = assertNotNull(wheel.latestMotion(0))
        assertFalse(republished.dutyFresh)
        assertEquals(37.0, duty(map(republished, 2_400, hold)).value, "inside the bound (1.4 s)")
        assertEquals(VideoReading(null), duty(map(republished, 2_600, hold)), "past the bound (1.6 s)")
        // And it stays gone: nothing re-measured it.
        assertEquals(VideoReading(null), duty(map(republished, 2_700, hold)))
    }

    @Test
    fun theHeldDutyExpiresInTheTimelineSoANewSnapshotIsNotNeededToBlankIt() {
        val wheel = BegodeProtocol()
        val hold = VideoDutyHold()
        wheel.onNotification(live)
        wheel.onNotification(motionFrame(37))
        val timeline = VideoTimeline()
        timeline.offer(map(assertNotNull(wheel.latestMotion(0)), 1_000, hold))
        wheel.onNotification(live)
        timeline.offer(map(assertNotNull(wheel.latestMotion(0)), 1_200, hold))

        assertEquals(37.0, timeline.sample(1_900)?.readings?.getValue(VideoMetric.DUTY)?.value)
        // 1000 + 1500 = 2500: the held duty is gone from frames after that, while speed (2 s source rule) is still on.
        val late = assertNotNull(timeline.sample(2_600))
        assertNull(late.readings.getValue(VideoMetric.DUTY).value)
        assertNotNull(late.readings.getValue(VideoMetric.SPEED).value)
    }

    @Test
    fun aRepublishWithNoMeasurementBehindItIsStillUnknown() {
        // Deliberately incoherent: a non-fresh sample on a hold that never saw a fresh one.
        val orphan = ControllerData(dutyPercent = 67f, hasDuty = true, dutyFresh = false)
        assertEquals(VideoReading(null), duty(map(orphan, 1_000, VideoDutyHold())))
    }

    @Test
    fun aControllerThatStopsReportingDutyDropsTheHoldEvenIfTheNumberIsStillThere() {
        val hold = VideoDutyHold()
        assertEquals(37.0, duty(map(ControllerData(dutyPercent = 37f, hasDuty = true, dutyFresh = true), 1_000, hold)).value)
        // hasDuty = false next to a non-zero dutyPercent: a combination no producer emits.
        val incoherent = ControllerData(dutyPercent = 37f, hasDuty = false, dutyFresh = false)
        assertEquals(VideoReading(null), duty(map(incoherent, 1_100, hold)))
        // The hold is gone for good, not merely masked for one frame.
        val back = ControllerData(dutyPercent = 37f, hasDuty = true, dutyFresh = false)
        assertEquals(VideoReading(null), duty(map(back, 1_200, hold)))
    }

    @Test
    fun aHeldDutyNeverCrossesToAnotherVehicle() {
        val hold = VideoDutyHold()
        assertEquals(37.0, duty(map(ControllerData(dutyPercent = 37f, hasDuty = true, dutyFresh = true), 1_000, hold)).value)
        val other = vehicle.copy(id = "other")
        val republished = ControllerData(dutyPercent = 37f, hasDuty = true, dutyFresh = false)
        assertEquals(VideoReading(null), duty(map(republished, 1_100, hold, other)))
    }

    @Test
    fun aDisconnectForgetsTheHold() {
        val hold = VideoDutyHold()
        assertEquals(37.0, duty(map(ControllerData(dutyPercent = 37f, hasDuty = true, dutyFresh = true), 1_000, hold)).value)
        assertNull(
            videoTelemetrySnapshot(VehicleData(), vehicle, ConnectionState.Disconnected, 1_050, 1_050, hold),
        )
        val republished = ControllerData(dutyPercent = 37f, hasDuty = true, dutyFresh = false)
        assertEquals(VideoReading(null), duty(map(republished, 1_100, hold)))
    }

    @Test
    fun anEstimatedDutyKeepsItsProvenanceWhileHeld() {
        val hold = VideoDutyHold()
        val estimated = ControllerData(
            dutyPercent = 100f, hasDuty = true, dutyFresh = true,
            dutySource = DutySource.ESTIMATED, dutyAtCeiling = true,
        )
        assertEquals(VideoReading(100.0, estimated = true, atCeiling = true), duty(map(estimated, 1_000, hold)))
        // The republish carries the same number but a different (here: measured-looking) provenance; the held
        // copy, which the measuring frame produced, is what the overlay must keep saying.
        val republished = estimated.copy(dutyFresh = false, dutySource = DutySource.MEASURED, dutyAtCeiling = false)
        assertEquals(VideoReading(100.0, estimated = true, atCeiling = true), duty(map(republished, 1_200, hold)))
    }
}
