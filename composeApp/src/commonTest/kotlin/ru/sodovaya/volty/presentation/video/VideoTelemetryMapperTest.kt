package ru.sodovaya.volty.presentation.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DutySource
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.DEMO_VEHICLE_ID
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.video.VideoMetric
import ru.sodovaya.volty.domain.video.VideoReading

@OptIn(ExperimentalTime::class)
class VideoTelemetryMapperTest {
    private val nowMillis = 20_000L
    private val receiptMillis = 3_600_100L
    private val vehicle = Vehicle(
        id = "bike",
        name = "Bike",
        iconKey = "bicycle",
        packs = listOf(Pack(0, "Pack", BmsType.ANT_BMS, "AA")),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = instant(0L),
    )

    @Test
    fun knownZeroReadingsSurviveAndEstimatedDutyKeepsItsProvenance() {
        val data = liveData(
            motion = ControllerData(
                speedKmh = 0f,
                speedSource = ru.sodovaya.volty.domain.model.SpeedSource.REPORTED,
                dutyPercent = 84.5f,
                hasDuty = true,
                dutySource = DutySource.ESTIMATED,
                dutyAtCeiling = true,
                motorCurrentA = 0f,
                isConnected = true,
                escTempC = 0f,
                timestamp = instant(nowMillis),
            ),
            battery = BmsData(
                voltage = 52f,
                soc = 0f,
                socKnown = true,
                current = 0f,
                hasCurrent = true,
                power = 0f,
                hasPower = true,
                temperatures = listOf(25f, Float.NaN, Float.POSITIVE_INFINITY, 31f),
                isConnected = true,
                timestamp = instant(nowMillis),
            ),
        )

        val snapshot = mapped(data)

        assertEquals(0.0, snapshot.readings[VideoMetric.SPEED]?.value)
        assertEquals(
            VideoReading(value = 84.5, estimated = true, atCeiling = true),
            snapshot.readings[VideoMetric.DUTY],
        )
        assertEquals(0.0, snapshot.readings[VideoMetric.SOC]?.value)
        assertEquals(52.0, snapshot.readings[VideoMetric.VOLTAGE]?.value)
        assertEquals(0.0, snapshot.readings[VideoMetric.BATTERY_CURRENT]?.value)
        assertEquals(0.0, snapshot.readings[VideoMetric.BATTERY_POWER]?.value)
        assertEquals(0.0, snapshot.readings[VideoMetric.CONTROLLER_CURRENT]?.value)
        assertEquals(0.0, snapshot.readings[VideoMetric.CONTROLLER_POWER]?.value)
        assertEquals(31.0, snapshot.readings[VideoMetric.BATTERY_TEMPERATURE]?.value)
        assertEquals(0.0, snapshot.readings[VideoMetric.ESC_TEMPERATURE]?.value)
        assertEquals(0.0, snapshot.readings[VideoMetric.MOTOR_CURRENT]?.value)
    }

    @Test
    fun incoherentFalseKnownFlagsHideNonzeroValuesWithoutBorrowingAnotherSource() {
        val snapshot = mapped(
            liveData(
                motion = ControllerData(
                    speedKmh = 71f,
                    speedSource = ru.sodovaya.volty.domain.model.SpeedSource.NONE,
                    dutyPercent = 83f,
                    hasDuty = false,
                    motorCurrentA = 29f,
                    batteryCurrentA = 41f,
                    hasBatteryCurrent = false,
                    powerW = 4200f,
                    hasPower = false,
                    escTempC = -100f,
                    motorTempC = 54f,
                    hasMotorTemp = false,
                    isConnected = true,
                    timestamp = instant(nowMillis),
                ),
                battery = BmsData(
                    voltage = 48f,
                    current = 9f,
                    hasCurrent = false,
                    power = 700f,
                    hasPower = false,
                    soc = 61f,
                    socKnown = false,
                    temperatures = listOf(26f),
                    isConnected = true,
                    timestamp = instant(nowMillis),
                ),
            ),
        )

        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.SPEED])
        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.DUTY])
        // The Light HUD's only evidence for phase current is a nonzero value or
        // known power; this nonzero phase current remains displayable.
        assertEquals(29.0, snapshot.readings[VideoMetric.MOTOR_CURRENT]?.value)
        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.CONTROLLER_CURRENT])
        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.CONTROLLER_POWER])
        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.ESC_TEMPERATURE])
        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.MOTOR_TEMPERATURE])
        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.SOC])
        assertEquals(48.0, snapshot.readings[VideoMetric.VOLTAGE]?.value)
        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.BATTERY_CURRENT])
        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.BATTERY_POWER])
        assertEquals(26.0, snapshot.readings[VideoMetric.BATTERY_TEMPERATURE]?.value)
    }

    @Test
    fun staleMotionIsUnknownWhileFreshBatteryTelemetryRemainsAvailable() {
        val data = liveData(
            motion = ControllerData(
                speedKmh = 12f,
                speedSource = ru.sodovaya.volty.domain.model.SpeedSource.REPORTED,
                dutyPercent = 50f,
                hasDuty = true,
                motorCurrentA = 8f,
                batteryCurrentA = 5f,
                hasBatteryCurrent = true,
                powerW = 100f,
                hasPower = true,
                escTempC = 32f,
                motorTempC = 35f,
                hasMotorTemp = true,
                isConnected = true,
                timestamp = instant(nowMillis - 2_001L),
            ),
            battery = BmsData(
                voltage = 52f,
                current = -3f,
                hasCurrent = true,
                power = -156f,
                hasPower = true,
                soc = 64f,
                socKnown = true,
                temperatures = listOf(30f),
                isConnected = true,
                timestamp = instant(nowMillis),
            ),
        )

        val snapshot = mapped(data)

        assertMotionUnknown(snapshot)
        assertEquals(64.0, snapshot.readings[VideoMetric.SOC]?.value)
        assertEquals(52.0, snapshot.readings[VideoMetric.VOLTAGE]?.value)
        assertEquals(-3.0, snapshot.readings[VideoMetric.BATTERY_CURRENT]?.value)
        assertEquals(-156.0, snapshot.readings[VideoMetric.BATTERY_POWER]?.value)
        assertNull(snapshot.readings[VideoMetric.CONTROLLER_CURRENT]?.value)
        assertNull(snapshot.readings[VideoMetric.CONTROLLER_POWER]?.value)
        assertEquals(30.0, snapshot.readings[VideoMetric.BATTERY_TEMPERATURE]?.value)
    }

    @Test
    fun futureAndOlderThanTwoSecondsSamplesAreNotLive() {
        val base = liveData(
            motion = ControllerData(
                speedKmh = 15f,
                speedSource = ru.sodovaya.volty.domain.model.SpeedSource.REPORTED,
                isConnected = true,
                timestamp = instant(nowMillis - 2_000L),
            ),
            battery = BmsData(soc = 70f, socKnown = true, isConnected = true, timestamp = instant(nowMillis)),
        )
        assertEquals(15.0, mapped(base).readings[VideoMetric.SPEED]?.value)

        assertNull(mapped(base.copy(motion = base.motion.copy(timestamp = instant(nowMillis - 2_001L))))
            .readings[VideoMetric.SPEED]?.value)
        assertNull(mapped(base.copy(motion = base.motion.copy(timestamp = instant(nowMillis + 1L))))
            .readings[VideoMetric.SPEED]?.value)
    }

    @Test
    fun nonFiniteKnownNumbersRemainUnknown() {
        val snapshot = mapped(
            liveData(
                motion = ControllerData(
                    speedKmh = Float.NaN,
                    speedSource = ru.sodovaya.volty.domain.model.SpeedSource.REPORTED,
                    isConnected = true,
                    timestamp = instant(nowMillis),
                ),
                battery = BmsData(
                    voltage = Float.POSITIVE_INFINITY,
                    current = Float.NaN,
                    hasCurrent = true,
                    power = Float.NEGATIVE_INFINITY,
                    hasPower = true,
                    soc = Float.NaN,
                    socKnown = true,
                    temperatures = listOf(Float.NaN, Float.POSITIVE_INFINITY),
                    isConnected = true,
                    timestamp = instant(nowMillis),
                ),
            ),
        )

        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.SPEED])
        assertBatteryUnknown(snapshot)
    }

    @Test
    fun zeroDefaultBatteryVoltageIsUnknownEvenOnAConnectedFreshPack() {
        val snapshot = mapped(
            liveData(
                battery = BmsData(isConnected = true, timestamp = instant(nowMillis)),
            ),
        )

        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.VOLTAGE])
    }

    @Test
    fun staleDutyIsUnknownWithoutDiscardingOtherFreshControllerMetrics() {
        val snapshot = mapped(
            liveData(
                motion = ControllerData(
                    speedKmh = 21f,
                    speedSource = ru.sodovaya.volty.domain.model.SpeedSource.REPORTED,
                    dutyPercent = 67f,
                    hasDuty = true,
                    dutyFresh = false,
                    isConnected = true,
                    timestamp = instant(nowMillis),
                ),
            ),
        )

        assertEquals(21.0, snapshot.readings[VideoMetric.SPEED]?.value)
        assertEquals(VideoReading(null), snapshot.readings[VideoMetric.DUTY])
    }

    @Test
    fun offlineAndPartialSourcesCannotPublishRetainedOrSubsetValues() {
        val live = liveData(
            motion = ControllerData(
                speedKmh = 15f,
                speedSource = ru.sodovaya.volty.domain.model.SpeedSource.REPORTED,
                dutyPercent = 72f,
                hasDuty = true,
                powerW = 500f,
                hasPower = true,
                isConnected = true,
                timestamp = instant(nowMillis),
            ),
            battery = BmsData(
                voltage = 50f,
                current = 10f,
                hasCurrent = true,
                power = 500f,
                hasPower = true,
                soc = 57f,
                socKnown = true,
                isConnected = true,
                timestamp = instant(nowMillis),
            ),
        )

        val motionOffline = mapped(live.copy(motion = live.motion.copy(isConnected = false)))
        assertMotionUnknown(motionOffline)
        assertEquals(57.0, motionOffline.readings[VideoMetric.SOC]?.value)

        val batteryOffline = mapped(live.copy(aggregate = live.aggregate.copy(isConnected = false)))
        assertEquals(15.0, batteryOffline.readings[VideoMetric.SPEED]?.value)
        assertBatteryUnknown(batteryOffline)

        val motionPartial = mapped(live.copy(motionPartial = true))
        assertMotionUnknown(motionPartial)
        assertEquals(57.0, motionPartial.readings[VideoMetric.SOC]?.value)

        val batteryPartial = mapped(live.copy(isPartial = true))
        assertEquals(15.0, batteryPartial.readings[VideoMetric.SPEED]?.value)
        assertBatteryUnknown(batteryPartial)
    }

    @Test
    fun disconnectedOrMissingVehicleProducesNoSnapshot() {
        val data = liveData()

        assertNull(rawMapped(data, connection = ConnectionState.Disconnected))
        assertNull(rawMapped(data, vehicle = null, connection = ConnectionState.Connected(vehicle)))

        val otherVehicle = vehicle.copy(id = "other-bike")
        assertNull(
            rawMapped(
                data,
                vehicle = vehicle,
                connection = ConnectionState.Connected(otherVehicle),
            ),
        )
    }

    @Test
    fun snapshotCarriesReceiptTimeVehicleIdentityAndDemoMarker() {
        val demo = vehicle.copy(id = DEMO_VEHICLE_ID)

        val snapshot = mapped(liveData(), vehicle = demo, connection = ConnectionState.Connected(demo))

        assertEquals(receiptMillis, snapshot.timeMillis)
        assertEquals(DEMO_VEHICLE_ID, snapshot.vehicleId)
        assertTrue(snapshot.demo)
        assertTrue(demo.isDemo)
    }

    @Test
    fun sourceAgeLimitsReceiptExpiryAndSiblingUpdatesDoNotExtendIt() {
        val motionTimestamp = instant(nowMillis - 1_990L)
        val data = liveData(
            motion = ControllerData(
                speedKmh = 18f,
                speedSource = ru.sodovaya.volty.domain.model.SpeedSource.REPORTED,
                isConnected = true,
                timestamp = motionTimestamp,
            ),
            battery = BmsData(
                soc = 64f,
                socKnown = true,
                isConnected = true,
                timestamp = instant(nowMillis),
            ),
        )

        val first = assertNotNull(
            videoTelemetrySnapshot(
                data = data,
                vehicle = vehicle,
                connection = ConnectionState.Connected(vehicle),
                receivedAtMillis = receiptMillis,
                nowEpochMillis = nowMillis,
            ),
        )

        assertEquals(receiptMillis + 10L, first.expiresAtMillis[VideoMetric.SPEED])
        assertEquals(receiptMillis + 2_000L, first.expiresAtMillis[VideoMetric.SOC])
        assertEquals(receiptMillis - 1_990L, first.sampledAtMillis[VideoMetric.SPEED])
        assertEquals(receiptMillis, first.sampledAtMillis[VideoMetric.SOC])

        // A BMS-only emission advances receipt/frame time and the BMS timestamp,
        // while the controller timestamp remains unchanged. Its absolute expiry
        // must therefore remain fixed rather than borrowing the sibling's update.
        val next = assertNotNull(
            videoTelemetrySnapshot(
                data = data.copy(
                    aggregate = data.aggregate.copy(timestamp = instant(nowMillis + 5L)),
                ),
                vehicle = vehicle,
                connection = ConnectionState.Connected(vehicle),
                receivedAtMillis = receiptMillis + 5L,
                nowEpochMillis = nowMillis + 5L,
            ),
        )

        assertEquals(first.expiresAtMillis[VideoMetric.SPEED], next.expiresAtMillis[VideoMetric.SPEED])
        assertEquals(first.sampledAtMillis[VideoMetric.SPEED], next.sampledAtMillis[VideoMetric.SPEED])
        assertEquals(receiptMillis + 5L, next.sampledAtMillis[VideoMetric.SOC])
        assertEquals(receiptMillis + 5L + 2_000L, next.expiresAtMillis[VideoMetric.SOC])
    }

    private fun mapped(
        data: VehicleData = liveData(),
        vehicle: Vehicle? = this.vehicle,
        connection: ConnectionState = ConnectionState.Connected(vehicle),
    ) = assertNotNull(rawMapped(data, vehicle, connection))

    private fun rawMapped(
        data: VehicleData,
        vehicle: Vehicle? = this.vehicle,
        connection: ConnectionState = ConnectionState.Connected(vehicle),
    ) = videoTelemetrySnapshot(
        data = data,
        vehicle = vehicle,
        connection = connection,
        receivedAtMillis = receiptMillis,
        nowEpochMillis = nowMillis,
    )

    private fun liveData(
        motion: ControllerData = ControllerData(isConnected = true, timestamp = instant(nowMillis)),
        battery: BmsData = BmsData(isConnected = true, timestamp = instant(nowMillis)),
    ) = VehicleData(motion = motion, aggregate = battery)

    private fun assertMotionUnknown(snapshot: ru.sodovaya.volty.domain.video.VideoSnapshot) {
        listOf(
            VideoMetric.SPEED,
            VideoMetric.DUTY,
            VideoMetric.ESC_TEMPERATURE,
            VideoMetric.MOTOR_TEMPERATURE,
            VideoMetric.MOTOR_CURRENT,
            VideoMetric.CONTROLLER_CURRENT,
            VideoMetric.CONTROLLER_POWER,
        ).forEach { assertEquals(VideoReading(null), snapshot.readings[it], "$it") }
    }

    private fun assertBatteryUnknown(snapshot: ru.sodovaya.volty.domain.video.VideoSnapshot) {
        listOf(
            VideoMetric.SOC,
            VideoMetric.VOLTAGE,
            VideoMetric.BATTERY_CURRENT,
            VideoMetric.BATTERY_POWER,
            VideoMetric.BATTERY_TEMPERATURE,
        ).forEach { assertEquals(VideoReading(null), snapshot.readings[it], "$it") }
    }

    private fun instant(epochMillis: Long) = Instant.fromEpochMilliseconds(epochMillis)
}
