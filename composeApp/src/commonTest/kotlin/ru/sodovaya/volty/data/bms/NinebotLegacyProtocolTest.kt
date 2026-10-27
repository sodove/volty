package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import ru.sodovaya.volty.domain.model.DeviceFact
import ru.sodovaya.volty.domain.model.DeviceFactKey
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryObservation
import ru.sodovaya.volty.domain.model.TelemetryOrigin
import ru.sodovaya.volty.domain.model.TelemetryRole
import ru.sodovaya.volty.domain.model.SpeedSource

/** Fixtures follow WheelLog's read-only NinebotAdapter (55 AA / B0 live page). */
class NinebotLegacyProtocolTest {

    @Test
    fun `legacy link is passive and uses the FFE0 FFE1 UART`() {
        val protocol = NinebotLegacyProtocol()

        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", protocol.uuids.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", protocol.uuids.notifyCharUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", protocol.uuids.writeCharUuid)
        assertTrue(protocol.handshakeCommands().isEmpty())
        assertTrue(protocol.pollCommands().isEmpty())
        assertEquals(0L, protocol.pollIntervalMs)
        assertEquals(1, protocol.packCount)
        assertNull(protocol.latestData())
    }

    @Test
    fun `B0 live page is decoded only after a complete split frame`() {
        val protocol = NinebotLegacyProtocol()
        val frame = liveFrame(
            batteryPercent = 78,
            distanceMeters = 2_660_251,
            temperatureCentiC = 3_700,
            voltageCentiV = 6_150,
            currentCentiA = 1_234,
            speedCentiKmh = 2_716
        )

        protocol.onNotification(byteArrayOf(0x12, 0x34, 0x55) + frame.copyOfRange(0, 8))
        assertNull(protocol.latestData(), "a partial frame must not publish telemetry")

        protocol.onNotification(frame.copyOfRange(8, frame.size))
        val data = requireNotNull(protocol.latestData())
        assertEquals(61.5f, data.voltage, 0.001f)
        assertEquals(12.34f, data.current, 0.001f)
        assertEquals(78f, data.soc, 0.001f)
        assertTrue(data.socKnown)
        assertEquals(61.5f * 12.34f, data.power, 0.01f)
        assertEquals(listOf(37f), data.temperatures)
        assertTrue(data.isConnected)

        val motion = requireNotNull(protocol.latestMotion(0))
        assertEquals(27.16f, motion.speedKmh, 0.001f)
        assertEquals(SpeedSource.REPORTED, motion.speedSource)
        assertEquals(2_660.251f, motion.odometerKm, 0.001f)
        assertEquals(0f, motion.tripKm, 0.001f)
        assertTrue(motion.hasDistance)
    }

    @Test
    fun `identical accepted B0 pages publish fresh battery and controller fields`() {
        val protocol = NinebotLegacyProtocol()
        val events = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = events::add
        val frame = liveFrame(
            batteryPercent = 78,
            distanceMeters = 2_660_251,
            temperatureCentiC = 3_700,
            voltageCentiV = 6_150,
            currentCentiA = 1_234,
            speedCentiKmh = 2_716
        )

        protocol.onNotification(frame)
        protocol.onNotification(frame.copyOf())

        assertEquals(4, events.size, "each accepted B0 page contributes a battery and controller observation")
        assertHistoryAccepts(events)
        val batteryEvents = events.filter { it.origin == TelemetryOrigin.BATTERY }
        val controllerEvents = events.filter { it.origin == TelemetryOrigin.CONTROLLER }
        assertEquals(2, batteryEvents.size)
        assertEquals(2, controllerEvents.size)
        batteryEvents.forEach { event ->
            assertEquals(TelemetryRole.CONTRIBUTOR, event.role)
            assertEquals(0, event.localIndex)
            assertEquals(
                setOf(TelemetryMetric.SOC, TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT,
                    TelemetryMetric.POWER, TelemetryMetric.BATTERY_TEMPERATURE),
                event.readings.map { it.metric }.toSet()
            )
        }
        controllerEvents.forEach { event ->
            assertEquals(TelemetryRole.CONTRIBUTOR, event.role)
            assertEquals(0, event.localIndex)
            assertEquals(
                setOf(TelemetryMetric.SPEED, TelemetryMetric.INPUT_VOLTAGE,
                    TelemetryMetric.CONTROLLER_BATTERY_CURRENT, TelemetryMetric.CONTROLLER_POWER,
                    TelemetryMetric.ESC_TEMPERATURE, TelemetryMetric.ODOMETER, TelemetryMetric.TRIP),
                event.readings.map { it.metric }.toSet()
            )
        }
        assertEquals(batteryEvents[0].readings.toSet(), batteryEvents[1].readings.toSet())
        assertEquals(controllerEvents[0].readings.toSet(), controllerEvents[1].readings.toSet())
    }

    @Test
    fun `a different register does not restamp cached B0 values`() {
        val protocol = NinebotLegacyProtocol()
        val events = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = events::add
        protocol.onNotification(liveFrame(voltageCentiV = 6_150, currentCentiA = 1_234))
        val before = requireNotNull(protocol.latestData())
        events.clear()

        protocol.onNotification(frame(parameter = 0xB1, payload = ByteArray(LIVE_PAYLOAD_SIZE) { 0x2a }))

        assertTrue(events.isEmpty(), "unsupported register data cannot refresh cached B0 measurements")
        val after = requireNotNull(protocol.latestData())
        assertEquals(before.voltage, after.voltage, 0.001f)
        assertEquals(before.current, after.current, 0.001f)
        assertEquals(before.soc, after.soc, 0.001f)
    }

    @Test
    fun `noise concatenated frames and bad checksum resynchronise`() {
        val protocol = NinebotLegacyProtocol()
        val invalid = liveFrame(voltageCentiV = 6_100).also {
            it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte()
        }
        val valid = liveFrame(voltageCentiV = 6_200)

        protocol.onNotification(byteArrayOf(0x01, 0x02, 0x55) + invalid + valid)

        val data = requireNotNull(protocol.latestData())
        assertEquals(62f, data.voltage, 0.001f)
    }

    @Test
    fun `unsupported command and malformed live payload are ignored`() {
        val protocol = NinebotLegacyProtocol()

        protocol.onNotification(frame(parameter = 0x10, payload = ByteArray(14) { 0x31 }))
        protocol.onNotification(frame(parameter = 0xB0, payload = ByteArray(31)))
        assertNull(protocol.latestData())
    }

    @Test
    fun `reset clears buffered and published state`() {
        val protocol = NinebotLegacyProtocol()
        val frame = liveFrame()

        protocol.onNotification(frame.copyOfRange(0, 5))
        protocol.reset()
        protocol.onNotification(frame.copyOfRange(5, frame.size))
        assertNull(protocol.latestData())

        protocol.onNotification(frame)
        assertTrue(protocol.latestData() != null)
        protocol.reset()
        assertNull(protocol.latestData())
    }

    // The B0 page's battery percent, distance, speed, voltage, current and temperature are all in
    // BmsData / ControllerData; the decoder reads no model, serial or firmware. Empty list.
    @Test
    fun `device facts stay empty after a B0 live page`() {
        val protocol = NinebotLegacyProtocol()
        assertTrue(protocol is ProtocolFacts)
        assertEquals(emptyList(), protocol.deviceFacts())
        protocol.onNotification(liveFrame(batteryPercent = 78, distanceMeters = 2_660_251))
        assertEquals(78f, requireNotNull(protocol.latestData()).soc, 0.001f)
        assertEquals(emptyList(), protocol.deviceFacts())
    }

    @Test
    fun `a torn B0 distance adds nothing to the trip and the same page's live values stay raw`() {
        val protocol = NinebotLegacyProtocol()
        protocol.onNotification(liveFrame(distanceMeters = 5_000, speedCentiKmh = 1_000))
        protocol.onNotification(liveFrame(distanceMeters = 5_100, speedCentiKmh = 1_100))
        // +65 536 m on the distance only (the checksum is computed over the torn value, so it is valid).
        protocol.onNotification(liveFrame(distanceMeters = 5_100 + 65_536, speedCentiKmh = 1_200, voltageCentiV = 6_150))
        val needle = requireNotNull(protocol.latestMotion(0))
        assertEquals(12f, needle.speedKmh, 0.001f, "live values of a needle page stay raw")
        assertEquals(61.5f, needle.inputVoltageV, 0.001f)
        assertEquals(70.636f, needle.odometerKm, 0.001f, "the raw odometer is published as today")
        protocol.onNotification(liveFrame(distanceMeters = 5_200))
        protocol.onNotification(liveFrame(distanceMeters = 5_300))

        assertEquals(0.2f, requireNotNull(protocol.latestMotion(0)).tripKm, 0.001f)
        assertEquals(1L, protocol.rejectedFrameCount)
    }

    @Test
    fun `a persistent backward reset re-baselines after three pages and the trip keeps growing`() {
        val protocol = NinebotLegacyProtocol()
        listOf(9_000L, 9_100L, 9_200L, 100L, 200L, 300L, 400L, 500L).forEach {
            protocol.onNotification(liveFrame(distanceMeters = it))
        }
        val motion = requireNotNull(protocol.latestMotion(0))
        assertEquals(0.5f, motion.odometerKm, 0.001f)
        // 9 000 -> 9 200 (0.2) + the confirmed chain 100 -> 300 (0.2) + 300 -> 400 (0.1); 500 is held.
        assertEquals(0.5f, motion.tripKm, 0.001f, "previously the trip restarted from 0 on the first backward step")
        assertEquals(1L, protocol.rebaseCount)
    }

    private fun liveFrame(
        batteryPercent: Int = 0,
        distanceMeters: Long = 0,
        temperatureCentiC: Int = 0,
        voltageCentiV: Int = 6_000,
        currentCentiA: Int = 0,
        speedCentiKmh: Int = 0
    ): ByteArray = ByteArray(LIVE_PAYLOAD_SIZE).apply {
        putU16(8, batteryPercent)
        putU32(14, distanceMeters)
        putU16(22, temperatureCentiC)
        putU16(24, voltageCentiV)
        putI16(26, currentCentiA)
        putU16(28, speedCentiKmh)
    }.let { frame(parameter = 0xB0, payload = it) }

    /** WheelLog's legacy frame: 55 AA, len=(parameter+payload), fields, CRC-LE. */
    private fun frame(
        source: Int = 0x01,
        destination: Int = 0x09,
        parameter: Int,
        payload: ByteArray
    ): ByteArray {
        val len = payload.size + 2
        val body = ByteArray(len + 2).apply {
            this[0] = len.toByte()
            this[1] = source.toByte()
            this[2] = destination.toByte()
            this[3] = parameter.toByte()
            payload.copyInto(this, destinationOffset = 4)
        }
        var sum = 0
        for (index in 0 until body.size) sum = (sum + body[index].u8()) and 0xffff
        val checksum = sum.inv() and 0xffff
        return byteArrayOf(
            0x55,
            0xAA.toByte(),
            *body,
            checksum.toByte(),
            (checksum ushr 8).toByte()
        )
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putI16(offset: Int, value: Int) = putU16(offset, value and 0xffff)

    private fun ByteArray.putU32(offset: Int, value: Long) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private fun Byte.u8(): Int = toInt() and 0xff

    private companion object {
        const val LIVE_PAYLOAD_SIZE = 32
    }
}
