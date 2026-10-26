package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import ru.sodovaya.volty.domain.model.DeviceFact
import ru.sodovaya.volty.domain.model.DeviceFactKey
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryObservation
import ru.sodovaya.volty.domain.model.TelemetryOrigin
import ru.sodovaya.volty.domain.model.TelemetryRole
import ru.sodovaya.volty.domain.model.TelemetryUnknown

class KingSongProtocolTest {

    @Test
    fun `A9 live frame publishes its fresh controller and battery readings`() {
        val protocol = KingSongProtocol()
        val observed = capture(protocol)
        val frame = liveFrame(
            voltageCv = 12621,
            speedCentiKmh = 1675,
            distanceMeters = 1_234_000,
            currentCentiA = -250,
            temperatureCentiC = 2150
        )

        val invalid = liveFrame().also { it[18] = 0x00 }
        protocol.onNotification(invalid.copyOfRange(0, 7))
        assertTrue(observed.isEmpty(), "a partial frame is not a decoded telemetry update")
        protocol.onNotification(invalid.copyOfRange(7, invalid.size))
        assertTrue(observed.isEmpty(), "a rejected frame must not become telemetry")
        protocol.onNotification(frame)

        assertEquals(2, observed.size)
        assertHistoryAccepts(observed)
        val controller = observed.single { it.origin == TelemetryOrigin.CONTROLLER }
        assertEquals(TelemetryRole.CONTRIBUTOR, controller.role)
        assertEquals(0, controller.localIndex)
        assertEquals(
            setOf(TelemetryMetric.SPEED, TelemetryMetric.CONTROLLER_BATTERY_CURRENT,
                TelemetryMetric.INPUT_VOLTAGE, TelemetryMetric.CONTROLLER_POWER,
                TelemetryMetric.ESC_TEMPERATURE, TelemetryMetric.ODOMETER, TelemetryMetric.TRIP),
            controller.metrics()
        )
        assertEquals(16.75, controller.reading(TelemetryMetric.SPEED).value!!, 0.001)
        assertEquals(2.5, controller.reading(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value!!, 0.001)
        assertEquals(126.21, controller.reading(TelemetryMetric.INPUT_VOLTAGE).value!!, 0.001)

        val battery = observed.single { it.origin == TelemetryOrigin.BATTERY }
        assertEquals(TelemetryRole.DERIVED, battery.role)
        assertEquals(0, battery.localIndex)
        assertEquals(
            setOf(TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT),
            battery.metrics()
        )
        assertEquals(126.21, battery.reading(TelemetryMetric.VOLTAGE).value!!, 0.001)
        assertEquals(-2.5, battery.reading(TelemetryMetric.CURRENT).value!!, 0.001)
        assertEquals(21.5, controller.reading(TelemetryMetric.ESC_TEMPERATURE).value!!, 0.001)
    }

    @Test
    fun `repeated identical accepted A9 frames each publish a fresh update`() {
        val protocol = KingSongProtocol()
        val observed = capture(protocol)
        val frame = liveFrame(voltageCv = 12600, speedCentiKmh = 500, currentCentiA = -100)

        protocol.onNotification(frame)
        protocol.onNotification(frame.copyOf())

        assertEquals(4, observed.size)
        assertEquals(2, observed.count { it.origin == TelemetryOrigin.CONTROLLER })
        assertEquals(2, observed.count { it.origin == TelemetryOrigin.BATTERY })
        assertTrue(observed.filter { it.origin == TelemetryOrigin.BATTERY }
            .all { it.role == TelemetryRole.DERIVED && it.localIndex == 0 })
        assertTrue(observed.filter { it.origin == TelemetryOrigin.CONTROLLER }
            .all { it.metrics() == setOf(TelemetryMetric.SPEED, TelemetryMetric.CONTROLLER_BATTERY_CURRENT,
                TelemetryMetric.INPUT_VOLTAGE, TelemetryMetric.CONTROLLER_POWER,
                TelemetryMetric.ESC_TEMPERATURE, TelemetryMetric.ODOMETER, TelemetryMetric.TRIP) })
    }

    @Test
    fun `ancillary and BMS packets publish only fields carried by that packet`() {
        val protocol = KingSongProtocol()
        val observed = capture(protocol)
        protocol.onNotification(liveFrame(voltageCv = 12600, speedCentiKmh = 700, currentCentiA = -200))
        observed.clear()

        protocol.onNotification(frame(0xB9, 0x14).apply { putLe(14, 2400) })
        val stats = observed.single()
        assertEquals(TelemetryOrigin.CONTROLLER, stats.origin)
        assertEquals(setOf(TelemetryMetric.ESC_TEMPERATURE), stats.metrics())
        observed.clear()

        protocol.onNotification(frame(0xF5, 0x14).apply { this[15] = 44 })
        val output = observed.single()
        assertEquals(TelemetryOrigin.CONTROLLER, output.origin)
        assertEquals(setOf(TelemetryMetric.DUTY), output.metrics())
        observed.clear()

        protocol.onNotification(bmsFrame(0xF1, 0x00).apply {
            putLe(2, 12546); putLe(4, -340); putLe(6, 800); putLe(8, 1000)
        })
        val summary = observed.single()
        assertEquals(TelemetryOrigin.BATTERY, summary.origin)
        assertEquals(0, summary.localIndex)
        assertEquals(setOf(TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT, TelemetryMetric.SOC), summary.metrics())
        observed.clear()

        protocol.onNotification(bmsFrame(0xF2, 0x00).apply {
            putLe(2, 12400); putLe(4, -200); putLe(6, 400); putLe(8, 800)
        })
        val secondPack = observed.single()
        assertEquals(TelemetryOrigin.BATTERY, secondPack.origin)
        assertEquals(TelemetryRole.CONTRIBUTOR, secondPack.role)
        assertEquals(1, secondPack.localIndex)
        assertEquals(setOf(TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT, TelemetryMetric.SOC), secondPack.metrics())
        observed.clear()

        protocol.onNotification(bmsFrame(0xF1, 0x02).apply {
            for (i in 0 until 7) putLe(2 + i * 2, 3300 + i)
        })
        val cells = observed.single()
        assertEquals(TelemetryOrigin.BATTERY, cells.origin)
        assertEquals(0, cells.localIndex)
        assertEquals(listOf(TelemetryMetric.CELL, TelemetryMetric.CELL, TelemetryMetric.CELL,
            TelemetryMetric.CELL, TelemetryMetric.CELL, TelemetryMetric.CELL, TelemetryMetric.CELL),
            cells.readings.map { it.metric })
        assertEquals((0..6).toList(), cells.readings.map { it.cellIndex })
        assertTrue(cells.readings.all { it.value != null })
        cells.readings.zip(3300..3306).forEach { (reading, milliVolts) ->
            assertEquals(milliVolts / 1000.0, reading.value!!, 0.001)
        }

        observed.clear()
        protocol.onNotification(bmsFrame(0xF1, 0x02).apply {
            for (i in 0 until 7) putLe(2 + i * 2, if (i == 3) 5100 else 3400 + i)
        })
        val mixedCells = observed.single()
        assertEquals((0..6).toList(), mixedCells.readings.map { it.cellIndex })
        val invalidMiddleCell = mixedCells.readings.single { it.cellIndex == 3 }
        assertNull(invalidMiddleCell.value)
        assertEquals(TelemetryUnknown.INVALID_VALUE, invalidMiddleCell.unknown)
        assertEquals(3.402, mixedCells.readings.single { it.cellIndex == 2 }.value!!, 0.001)
    }

    @Test
    fun `page six eighth temperature does not claim the whole pack maximum`() {
        val protocol = KingSongProtocol()
        val observed = capture(protocol)
        protocol.onNotification(bmsFrame(0xF1, 0x01).apply {
            repeat(7) { i -> putLe(2 + i * 2, 3230) }
        })
        val sevenSensorSample = observed.single().readings.single { it.metric == TelemetryMetric.BATTERY_TEMPERATURE }
        assertEquals(50.0, sevenSensorSample.value!!, 0.001)
        observed.clear()

        protocol.onNotification(bmsFrame(0xF1, 0x06).apply { putLe(10, 2930) })

        val eighthSensorUpdate = observed.single().readings.single {
            it.metric == TelemetryMetric.BATTERY_TEMPERATURE
        }
        assertNull(eighthSensorUpdate.value)
        assertEquals(TelemetryUnknown.PARTIAL_SOURCE, eighthSensorUpdate.unknown)
    }

    @Test
    fun `read only stream has no commands and exposes FFE0 FFE1`() {
        val protocol = KingSongProtocol()

        assertEquals(emptyList(), protocol.handshakeCommands())
        assertEquals(emptyList(), protocol.pollCommands())
        assertEquals(0L, protocol.pollIntervalMs)
        assertEquals(2, protocol.packCount)
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", protocol.uuids.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", protocol.uuids.notifyCharUuid)
    }

    @Test
    fun `A9 live frame is parsed across split notifications and noise`() {
        val protocol = KingSongProtocol()
        val frame = liveFrame(
            voltageCv = 12621,
            speedCentiKmh = 1675,
            distanceMeters = 1_234_000,
            currentCentiA = -250,
            temperatureCentiC = 2150
        )

        protocol.onNotification(
            byteArrayOf(0x13, 0x37, 0xAA.toByte()) + frame.copyOfRange(0, 7)
        )
        assertNull(protocol.latestData())
        protocol.onNotification(frame.copyOfRange(7, frame.size))

        val data = assertNotNull(protocol.latestData())
        assertEquals(126.21f, data.voltage, 0.001f)
        assertEquals(-2.50f, data.current, 0.001f)
        assertTrue(data.hasCurrent)
        assertFalse(data.hasPower)
        assertFalse(data.socKnown, "A9 byte 15 is the E0 mode marker, not a proven SoC")
        assertEquals(listOf(21.5f), data.temperatures)
        assertTrue(data.cellVoltages.isEmpty())

        val motion = assertNotNull(protocol.latestMotion(0))
        assertEquals(16.75f, motion.speedKmh, 0.001f)
        assertEquals(SpeedSource.REPORTED, motion.speedSource)
        assertEquals(126.21f, motion.inputVoltageV, 0.001f)
        assertEquals(2.50f, motion.batteryCurrentA, 0.001f)
        assertEquals(1_234f, motion.odometerKm, 0.001f)
        assertEquals(0f, motion.tripKm, 0.001f)
        assertFalse(motion.hasDuty)
    }

    @Test
    fun `A9 distance uses KingSong byte order and trip is the session delta`() {
        val protocol = KingSongProtocol()
        val first = liveFrame(distanceMeters = 123_456)
        val second = liveFrame(distanceMeters = 123_556)

        protocol.onNotification(first)
        val firstMotion = assertNotNull(protocol.latestMotion(0))
        assertEquals(123.456f, firstMotion.odometerKm, 0.001f)
        assertEquals(0f, firstMotion.tripKm, 0.001f)

        protocol.onNotification(second)
        val secondMotion = assertNotNull(protocol.latestMotion(0))
        assertEquals(123.556f, secondMotion.odometerKm, 0.001f)
        assertEquals(0.1f, secondMotion.tripKm, 0.001f)
    }

    @Test
    fun `bad tail is rejected like an invalid frame checksum and parser resynchronises`() {
        val protocol = KingSongProtocol()
        val invalid = liveFrame(voltageCv = 12_000).also { it[18] = 0x00 }
        val valid = liveFrame(voltageCv = 12_100)

        protocol.onNotification(invalid + valid)

        val data = assertNotNull(protocol.latestData())
        assertEquals(121f, data.voltage, 0.001f)
    }

    @Test
    fun `smart BMS summary maps voltage current and proven state of charge`() {
        val protocol = KingSongProtocol()
        protocol.onNotification(bmsFrame(0xF1, 0x00).apply {
            putLe(2, 12546)
            putLe(4, -340)
            putLe(6, 800)
            putLe(8, 1000)
            putLe(10, 17)
        })

        val data = assertNotNull(protocol.latestData(0))
        assertEquals(125.46f, data.voltage, 0.001f)
        assertEquals(-3.40f, data.current, 0.001f)
        assertEquals(80f, data.soc, 0.001f)
        assertTrue(data.socKnown)
        assertFalse(data.hasPower)
        assertTrue(data.cellVoltages.isEmpty())
    }

    @Test
    fun `exact loeuc S22 BMS sweep maps cycles and page six temperature`() {
        val protocol = KingSongProtocol()
        s22F1Sweep().forEach(protocol::onNotification)

        val data = assertNotNull(protocol.latestData(0))
        assertEquals(32, data.numCycles)
        assertEquals(8, data.temperatures.size)
        assertEquals(27f, data.temperatures[7], 0.001f)
    }

    @Test
    fun `B9 temperature latches over later A9 temperature`() {
        val protocol = KingSongProtocol()
        val live = liveFrame(temperatureCentiC = 2_800)
        val rideStats = frame(0xB9, 0x14).apply {
            putLe(14, 2_400)
        }

        protocol.onNotification(live)
        protocol.onNotification(rideStats)
        assertEquals(24f, assertNotNull(protocol.latestMotion(0)).escTempC, 0.001f)

        protocol.onNotification(live)
        assertEquals(24f, assertNotNull(protocol.latestMotion(0)).escTempC, 0.001f)
        assertEquals(listOf(28f), assertNotNull(protocol.latestData()).temperatures)
    }

    @Test
    fun `F5 byte fifteen reports duty and survives the next A9`() {
        val protocol = KingSongProtocol()
        val output = frame(0xF5, 0x14).apply { this[15] = 44 }

        protocol.onNotification(liveFrame())
        protocol.onNotification(output)
        val afterOutput = assertNotNull(protocol.latestMotion(0))
        assertEquals(44f, afterOutput.dutyPercent, 0.001f)
        assertTrue(afterOutput.hasDuty)

        protocol.onNotification(liveFrame())
        val afterLive = assertNotNull(protocol.latestMotion(0))
        assertEquals(44f, afterLive.dutyPercent, 0.001f)
        assertTrue(afterLive.hasDuty)
    }

    @Test
    fun `F5 zero duty is known zero rather than absent`() {
        val protocol = KingSongProtocol()
        protocol.onNotification(liveFrame())
        protocol.onNotification(frame(0xF5, 0x14))

        val motion = assertNotNull(protocol.latestMotion(0))
        assertEquals(0f, motion.dutyPercent, 0.001f)
        assertTrue(motion.hasDuty)
    }

    @Test
    fun `temperature and five cell pages are accumulated without inventing voltage`() {
        val protocol = KingSongProtocol()
        // Cell pages before summary are retained but do not publish a zero
        // voltage sample.
        protocol.onNotification(bmsFrame(0xF1, 0x02).apply {
            for (i in 0 until 7) putLe(2 + i * 2, 3_300 + i)
        })
        assertNull(protocol.latestData(0))

        protocol.onNotification(bmsFrame(0xF1, 0x01).apply {
            listOf(2_980, 2_990, 3_000, 3_010, 3_020, 3_030, 3_040)
                .forEachIndexed { i, raw -> putLe(2 + i * 2, raw) }
        })
        protocol.onNotification(bmsFrame(0xF1, 0x00).apply {
            putLe(2, 84_00)
            putLe(4, 0)
            putLe(6, 500)
            putLe(8, 1000)
        })

        val data = assertNotNull(protocol.latestData(0))
        assertEquals(84f, data.voltage, 0.001f)
        assertEquals(50f, data.soc, 0.001f)
        assertEquals(listOf(25f, 26f, 27f, 28f, 29f, 30f, 31f), data.temperatures)
        assertEquals(7, data.cellVoltages.size)
        assertEquals(3.300f, data.cellVoltages.first(), 0.001f)

        // Four more pages make 30 cells; values beyond the physical 30-cell
        // limit are ignored rather than surfaced as fabricated cells.
        for (page in 3..6) {
            protocol.onNotification(bmsFrame(0xF1, page).apply {
                for (i in 0 until 7) putLe(2 + i * 2, 3_400 + page * 10 + i)
            })
        }
        assertEquals(30, assertNotNull(protocol.latestData(0)).cellVoltages.size)
    }

    @Test
    fun `F2 summary is exposed as the second pack and does not overwrite F1`() {
        val protocol = KingSongProtocol()
        protocol.onNotification(bmsFrame(0xF1, 0x00).apply {
            putLe(2, 12500); putLe(4, 100); putLe(6, 900); putLe(8, 1000)
        })
        protocol.onNotification(bmsFrame(0xF2, 0x00).apply {
            putLe(2, 12400); putLe(4, -200); putLe(6, 400); putLe(8, 800)
        })

        assertEquals(125f, assertNotNull(protocol.latestData(0)).voltage, 0.001f)
        assertEquals(124f, assertNotNull(protocol.latestData(1)).voltage, 0.001f)
        assertNull(protocol.latestData(2))
    }

    @Test
    fun `reset drops buffered bytes cached packs and motion`() {
        val protocol = KingSongProtocol()
        val frame = liveFrame(voltageCv = 12600)
        protocol.onNotification(frame.copyOfRange(0, 4))
        protocol.reset()
        protocol.onNotification(frame.copyOfRange(4, frame.size))
        assertNull(protocol.latestData())
        assertNull(protocol.latestMotion(0))
    }

    // KingSong decodes nothing the common sections lack: A9 voltage/current/speed/distance/board temperature,
    // B9 controller temperature, F5 duty and the F1/F2 smart-BMS pages (SoC, cycles, cells, temperatures)
    // all reach ControllerData / BmsData. The remaining/factory capacity words only feed the SoC, and
    // their unit is not established by this decoder, so they are not facts. Hence: an empty list.
    @Test
    fun `device facts stay empty after live ride-stats output and the S22 BMS sweep`() {
        val protocol = KingSongProtocol()
        assertTrue(protocol is ProtocolFacts)
        assertEquals(emptyList(), protocol.deviceFacts())
        protocol.onNotification(liveFrame(voltageCv = 12_600, speedCentiKmh = 500, distanceMeters = 123_456))
        protocol.onNotification(frame(0xB9, 0x14).apply { putLe(14, 2_800) })
        protocol.onNotification(frame(0xF5, 0x14).apply { this[15] = 44 })
        s22F1Sweep().forEach(protocol::onNotification)
        assertNotNull(protocol.latestMotion(0), "precondition: telemetry was decoded")
        assertEquals(32, assertNotNull(protocol.latestData(0)).numCycles, "precondition: the sweep was decoded")
        assertEquals(emptyList(), protocol.deviceFacts())
    }

    private fun liveFrame(
        voltageCv: Int = 12_600,
        speedCentiKmh: Int = 0,
        distanceMeters: Long = 0,
        currentCentiA: Int = 0,
        temperatureCentiC: Int = 20_00
    ): ByteArray = frame(0xA9, 0x14).apply {
        putLe(2, voltageCv)
        putLe(4, speedCentiKmh)
        putKingSongDistance(6, distanceMeters)
        putLe(10, currentCentiA)
        putLe(12, temperatureCentiC)
        this[15] = 0xE0.toByte()
    }

    private fun bmsFrame(type: Int, page: Int): ByteArray = frame(type, page)

    private fun capture(protocol: KingSongProtocol): MutableList<TelemetryObservation> =
        mutableListOf<TelemetryObservation>().also { protocol.telemetryObserver = it::add }

    private fun TelemetryObservation.metrics(): Set<TelemetryMetric> = readings.map { it.metric }.toSet()

    private fun TelemetryObservation.reading(metric: TelemetryMetric) = readings.single { it.metric == metric }

    private fun frame(type: Int, page: Int): ByteArray = ByteArray(20).apply {
        this[0] = 0xAA.toByte()
        this[1] = 0x55.toByte()
        this[16] = type.toByte()
        this[17] = page.toByte()
        this[18] = 0x5A.toByte()
        this[19] = 0x5A.toByte()
    }

    private fun ByteArray.putLe(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }

    private fun ByteArray.putKingSongDistance(offset: Int, value: Long) {
        this[offset] = (value shr 16).toByte()
        this[offset + 1] = (value shr 24).toByte()
        this[offset + 2] = value.toByte()
        this[offset + 3] = (value shr 8).toByte()
    }

    private fun s22F1Sweep(): List<ByteArray> = listOf(
        "AA 55 4C 2A 00 00 A2 01 E8 03 20 00 E8 03 1F 0E F1 00 5A 5A",
        "AA 55 AB 0B AC 0B A8 0B AC 0B C8 0B AE 0B B4 0B F1 01 5A 5A",
        "AA 55 1B 0E 1B 0E 1B 0E 1D 0E 1F 0E 1B 0E 1B 0E F1 02 5A 5A",
        "AA 55 0C 0E 1B 0E 1B 0E 21 0E 21 0E 21 0E 1B 0E F1 03 5A 5A",
        "AA 55 1D 0E 14 0E 16 0E 17 0E 16 0E 16 0E 17 0E F1 04 5A 5A",
        "AA 55 FD 0D FF 0D FD 0D FD 0D FD 0D FF 0D FF 0D F1 05 5A 5A",
        "AA 55 FF 0D FD 0D 00 00 00 00 B8 0B 00 00 00 00 F1 06 5A 5A",
    ).map { line ->
        line.split(" ").map { it.toInt(radix = 16).toByte() }.toByteArray()
    }
}
