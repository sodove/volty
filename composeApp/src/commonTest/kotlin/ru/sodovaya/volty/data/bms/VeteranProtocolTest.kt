package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.domain.plausibility.LedgerKind
import ru.sodovaya.volty.domain.plausibility.PlausibilityRule

class VeteranProtocolTest {

    @Test fun observerPublishesOnlyCompleteFreshControllerFrameAndSeparateDerivedRail() {
        val protocol = VeteranProtocol()
        val observed = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = observed::add
        val frame = controllerFrame(voltageRaw = 12_525, speedRaw = -321,
            phaseCurrentRaw = -250, temperatureRaw = 3500, pwmRaw = 7850)
        protocol.onNotification(frame.copyOfRange(0, 13))
        assertTrue(observed.isEmpty())
        protocol.onNotification(frame.copyOfRange(13, frame.size))
        assertEquals(2, observed.size)
        val controller = observed.single { it.origin == TelemetryOrigin.CONTROLLER }.readings.associateBy { it.metric }
        assertEquals(32.1, controller.getValue(TelemetryMetric.SPEED).value!!, 0.001)
        assertEquals(-25.0, controller.getValue(TelemetryMetric.MOTOR_CURRENT).value!!, 0.001)
        assertEquals(-19.625, controller.getValue(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value!!, 0.001)
        assertFalse(controller.containsKey(TelemetryMetric.ERPM), "The frame has no ERPM field")
        val rail = observed.single { it.origin == TelemetryOrigin.BATTERY }
        assertEquals(TelemetryRole.DERIVED, rail.role)
        assertEquals(0, rail.localIndex)
        val battery = rail.readings.associateBy { it.metric }
        assertEquals(125.25, battery.getValue(TelemetryMetric.VOLTAGE).value!!, 0.001)
        assertEquals(19.625, battery.getValue(TelemetryMetric.CURRENT).value!!, 0.001)
        assertEquals(2458.03125, battery.getValue(TelemetryMetric.POWER).value!!, 0.01)
        assertFalse(battery.containsKey(TelemetryMetric.CELL))
    }

    @Test fun smartBmsPagesDoNotRestampCachedCurrentCellsOrAssembledVoltage() {
        val protocol = VeteranProtocol()
        val observed = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = observed::add
        protocol.onNotification(bmsFrame(packet = 0, current1Raw = 345))
        val current = observed.single { it.origin == TelemetryOrigin.BATTERY && it.role == TelemetryRole.CONTRIBUTOR }
        assertEquals(0, current.localIndex)
        assertEquals(listOf(TelemetryMetric.CURRENT), current.readings.map { it.metric })
        assertEquals(-3.45, current.readings.single().value!!, 0.001)
        observed.clear()
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4100 + it }, reportedCellCount = 36))
        val cells = observed.single { it.origin == TelemetryOrigin.BATTERY && it.role == TelemetryRole.CONTRIBUTOR }.readings
        assertEquals((0..14).toList(), cells.filter { it.metric == TelemetryMetric.CELL }.map { it.cellIndex })
        assertFalse(cells.any { it.metric in setOf(TelemetryMetric.CURRENT, TelemetryMetric.VOLTAGE, TelemetryMetric.POWER) })
        assertTrue(cells.filter { it.metric in setOf(TelemetryMetric.CELL_MIN, TelemetryMetric.CELL_MAX, TelemetryMetric.CELL_DELTA) }
            .let { it.size == 3 && it.all { value -> value.value == null && value.unknown == TelemetryUnknown.PARTIAL_SOURCE } })
        observed.clear()
        protocol.onNotification(bmsFrame(packet = 5, cells = IntArray(15) { if (it == 0) 50000 else 4200 }, reportedCellCount = 36))
        val other = observed.single { it.origin == TelemetryOrigin.BATTERY && it.role == TelemetryRole.CONTRIBUTOR }
        assertEquals(1, other.localIndex)
        val invalid = other.readings.single { it.metric == TelemetryMetric.CELL && it.cellIndex == 0 }
        assertNull(invalid.value); assertEquals(TelemetryUnknown.INVALID_VALUE, invalid.unknown)
    }

    @Test fun rejectedFrameAndObserverFailureCannotTurnIntoHistoryFactsOrBreakLiveDecode() {
        val protocol = VeteranProtocol()
        val observed = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = observed::add
        val valid = bmsFrame(packet = 0, current1Raw = 100)
        protocol.onNotification(valid.copyOf().apply { this[5] = (this[5].toInt() xor 1).toByte() })
        assertTrue(observed.isEmpty())
        protocol.telemetryObserver = { throw IllegalStateException("test observer") }
        protocol.onNotification(controllerFrame(voltageRaw = 12400))
        assertEquals(124f, assertNotNull(protocol.latestData()).voltage, 0.001f)
        assertEquals(2L, protocol.telemetryObserverFailures)
    }

    @Test fun freshPageDoesNotBorrowFactsEvenWhenCachedPackIsComplete() {
        val protocol = VeteranProtocol()
        protocol.onNotification(bmsFrame(packet = 0, current1Raw = 300))
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4100 }, reportedCellCount = 36))
        protocol.onNotification(bmsFrame(packet = 2, cells = IntArray(15) { 4100 }))
        protocol.onNotification(bmsFrame(packet = 3, cells = IntArray(6) { 4100 }, temperatures = IntArray(6) { 2500 }))
        assertTrue(assertNotNull(protocol.latestData(0)).hasPower, "Reach a fully assembled retained pack")
        val observed = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = observed::add
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4200 }, reportedCellCount = 36))
        val fresh = observed.single { it.origin == TelemetryOrigin.BATTERY && it.role == TelemetryRole.CONTRIBUTOR }
        assertEquals((0..14).toList(), fresh.readings.filter { it.metric == TelemetryMetric.CELL }.map { it.cellIndex })
        assertTrue(fresh.readings.filter { it.metric == TelemetryMetric.CELL }.all { it.value == 4.2 })
        assertFalse(fresh.readings.any { it.metric in setOf(TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT,
            TelemetryMetric.POWER, TelemetryMetric.SOC, TelemetryMetric.BATTERY_TEMPERATURE) })
        assertTrue(fresh.readings.filter { it.metric in setOf(TelemetryMetric.CELL_MIN, TelemetryMetric.CELL_MAX,
            TelemetryMetric.CELL_DELTA) }.all { it.unknown == TelemetryUnknown.PARTIAL_SOURCE && it.value == null })
    }

    @Test fun reportedZeroSocAndFreshRailEstimateHaveDifferentOwners() {
        val protocol = VeteranProtocol()
        protocol.onNotification(bmsFrame(packet = 0, current1Raw = 300))
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4100 }, reportedCellCount = 36))
        protocol.onNotification(bmsFrame(packet = 2, cells = IntArray(15) { 4100 }))
        protocol.onNotification(bmsFrame(packet = 3, cells = IntArray(6) { 4100 }, temperatures = IntArray(6) { 2500 }))
        assertEquals(0f, assertNotNull(protocol.latestData(0)).soc, "Reach a complete cached physical pack with reported zero")
        val observed = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = observed::add
        protocol.onNotification(bmsFrame(packet = 2, cells = IntArray(15) { 4100 }).apply {
            this[50] = 0; appendCrc(this, 83)
        })
        val physical = observed.single { it.origin == TelemetryOrigin.BATTERY && it.role == TelemetryRole.CONTRIBUTOR }
        assertEquals(0.0, physical.readings.single { it.metric == TelemetryMetric.SOC }.value)
        observed.clear()
        protocol.onNotification(controllerFrame(voltageRaw = 12525))
        assertEquals(0f, assertNotNull(protocol.latestData(0)).soc, "Live decoder retains the reported zero")
        val rail = observed.single { it.origin == TelemetryOrigin.BATTERY && it.role == TelemetryRole.DERIVED }
        assertTrue(assertNotNull(rail.readings.single { it.metric == TelemetryMetric.SOC }.value) > 0.0,
            "History rail must use this frame's voltage curve, not restamp the cached physical zero")
        assertFalse(observed.any { it.origin == TelemetryOrigin.BATTERY && it.role == TelemetryRole.CONTRIBUTOR })
    }

    @Test
    fun `Veteran stream is read only and uses the Nordic UART UUIDs`() {
        val protocol = VeteranProtocol()

        assertEquals(emptyList(), protocol.handshakeCommands())
        assertEquals(emptyList(), protocol.pollCommands())
        assertEquals(0L, protocol.pollIntervalMs)
        assertEquals(2, protocol.packCount)
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", protocol.uuids.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", protocol.uuids.notifyCharUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", protocol.uuids.writeCharUuid)
    }

    @Test
    fun `valid controller frame is reassembled across noise and split notifications`() {
        val protocol = VeteranProtocol()
        val frame = controllerFrame(
            voltageRaw = 12_525,
            speedRaw = -321,
            distanceRaw = 54_321,
            totalDistanceRaw = 65_432,
            phaseCurrentRaw = -250,
            temperatureRaw = 3_500,
            versionRaw = 5_001,
            pwmRaw = 7_850
        )

        protocol.onNotification(byteArrayOf(0x13, 0x37, 0xDC.toByte()) + frame.copyOfRange(0, 11))
        assertNull(protocol.latestData())
        assertNull(protocol.latestMotion(0))
        protocol.onNotification(frame.copyOfRange(11, frame.size))

        val battery = assertNotNull(protocol.latestData())
        assertEquals(125.25f, battery.voltage, 0.001f)
        assertFalse(battery.hasCurrent, "bytes 16..17 are phase current, not BMS current")
        assertFalse(battery.hasPower, "Veteran's controller frame has no battery-current field")
        assertTrue(battery.socKnown, "the WheelLog model voltage curve is proven for Lynx")
        assertEquals("Lynx", protocol.model)
        assertEquals("005.0.01", protocol.firmwareVersion)

        val motion = assertNotNull(protocol.latestMotion(0))
        assertEquals(32.1f, motion.speedKmh, 0.001f)
        assertEquals(SpeedSource.REPORTED, motion.speedSource)
        assertEquals(78.5f, motion.dutyPercent, 0.001f)
        assertTrue(motion.hasDuty)
        assertEquals(-25f, motion.motorCurrentA, 0.001f)
        assertEquals(-19.625f, motion.batteryCurrentA, 0.001f)
        assertTrue(motion.hasBatteryCurrent)
        assertEquals(125.25f, motion.inputVoltageV, 0.001f)
        assertTrue(motion.hasInputVoltage)
        assertEquals(-2_458.03125f, motion.powerW, 0.01f)
        assertTrue(motion.hasPower)
        assertEquals(65.432f, motion.odometerKm, 0.001f)
        assertEquals(0f, motion.tripKm, 0.001f)
        assertTrue(motion.hasDistance)
        assertEquals(35f, motion.escTempC, 0.001f)
        assertFalse(motion.hasMotorTemp)
    }

    @Test
    fun `bad legacy shape is skipped and following valid frame is recovered`() {
        val protocol = VeteranProtocol()
        val invalid = controllerFrame(versionRaw = 5_001).also { it[22] = 1 }
        val valid = controllerFrame(voltageRaw = 12_400, versionRaw = 5_001)

        protocol.onNotification(invalid + valid)

        assertEquals(124f, assertNotNull(protocol.latestData()).voltage, 0.001f)
    }

    @Test
    fun `header-like bytes inside payload do not trigger a false resynchronisation`() {
        val protocol = VeteranProtocol()
        val frame = controllerFrame(len = 71, voltageRaw = 12_350, versionRaw = 4_001).also {
            it[40] = 0xDC.toByte(); it[41] = 0x5A; it[42] = 0x5C
            appendCrc(it, 71)
        }

        protocol.onNotification(frame)

        assertEquals(123.5f, assertNotNull(protocol.latestData()).voltage, 0.001f)
    }

    @Test
    fun `long frame requires CRC32 and a corrupted frame does not replace old sample`() {
        val protocol = VeteranProtocol()
        val valid = controllerFrame(len = 71, voltageRaw = 12_350, versionRaw = 4_001)
        protocol.onNotification(valid)
        assertEquals(123.5f, assertNotNull(protocol.latestData()).voltage, 0.001f)

        val corrupted = valid.copyOf().also { it[12] = (it[12].toInt() xor 0x01).toByte() }
        protocol.onNotification(corrupted)

        assertEquals(123.5f, assertNotNull(protocol.latestData()).voltage, 0.001f)
    }

    @Test
    fun `smart BMS pages expose complete packs with cells temperatures current and derived power`() {
        val protocol = VeteranProtocol()
        protocol.onNotification(controllerFrame(versionRaw = 5_001))

        protocol.onNotification(bmsFrame(packet = 0, current1Raw = 123, current2Raw = -234))
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4_100 + it }))
        protocol.onNotification(bmsFrame(packet = 2, cells = IntArray(15) { 4_120 + it }))
        protocol.onNotification(
            bmsFrame(
                packet = 3,
                cells = IntArray(6) { 4_140 + it },
                temperatures = IntArray(6) { 2_500 + it * 10 }
            )
        )
        protocol.onNotification(bmsFrame(packet = 4, current1Raw = 123, current2Raw = -234))
        protocol.onNotification(bmsFrame(packet = 5, cells = IntArray(15) { 4_100 + it }))
        protocol.onNotification(bmsFrame(packet = 6, cells = IntArray(15) { 4_120 + it }))
        protocol.onNotification(
            bmsFrame(
                packet = 7,
                cells = IntArray(6) { 4_140 + it },
                temperatures = IntArray(6) { 2_500 + it * 10 }
            )
        )

        val first = assertNotNull(protocol.latestData(0))
        val second = assertNotNull(protocol.latestData(1))
        assertEquals(36, first.cellVoltages.size)
        assertEquals(36, second.cellVoltages.size)
        assertEquals(148.365f, first.voltage, 0.001f)
        assertEquals(-1.23f, first.current, 0.001f, "positive Leaperkim wire current means discharge")
        assertTrue(first.hasCurrent)
        assertEquals(first.voltage * first.current, first.power, 0.001f)
        assertTrue(first.hasPower)
        assertTrue(first.power < 0f, "BmsData power is charge-positive")
        assertEquals(second.voltage * second.current, second.power, 0.001f)
        assertTrue(second.hasPower)
        assertEquals(listOf(25f, 25.1f, 25.2f, 25.3f, 25.4f, 25.5f), first.temperatures)
        assertEquals(2.34f, second.current, 0.001f, "negative Leaperkim wire current means charging")
        assertTrue(second.power > 0f, "charging power must be positive in BmsData")
    }

    @Test
    fun `partial smart BMS pages never publish a fabricated pack voltage`() {
        val protocol = VeteranProtocol()
        protocol.onNotification(controllerFrame(versionRaw = 5_001))
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4_100 }))
        protocol.onNotification(bmsFrame(packet = 2, cells = IntArray(15) { 4_100 }))

        assertNull(protocol.latestData(0))
        assertNull(protocol.latestData(1))
    }

    @Test
    fun `main page does not make an incomplete smart pack replace main telemetry`() {
        val protocol = VeteranProtocol()

        protocol.onNotification(LYNX_S_PAGE_0.hexBytes())

        assertNotNull(protocol.latestData(0))
        assertNull(protocol.latestData(1))
    }

    @Test
    fun `short Patton page does not read CRC trailer as reported values`() {
        val protocol = VeteranProtocol()

        protocol.onNotification(PATTON_PAGE_2.hexBytes())

        val battery = assertNotNull(protocol.latestData(0))
        assertEquals(123.72f, battery.voltage, 0.001f)
        assertFalse(battery.hasCurrent)
        assertFalse(battery.hasPower)
        assertEquals(94f, battery.soc, 0.01f)
    }

    @Test
    fun `24 bit version bytes identify the Nosfet 5010 profile`() {
        val protocol = VeteranProtocol()

        protocol.onNotification(controllerFrame(versionRaw = 501_008))

        assertEquals("Nosfet Apex", protocol.model)
        assertEquals("501.0.08", protocol.firmwareVersion)
    }

    @Test
    fun `reported page 2 SoC remains known after complete cell pages`() {
        val protocol = VeteranProtocol()

        protocol.onNotification(LYNX_S_PAGE_2.hexBytes())
        protocol.onNotification(
            bmsFrame(
                packet = 1,
                versionRaw = 9_004,
                cells = IntArray(15) { 4_100 + it }
            )
        )
        protocol.onNotification(
            bmsFrame(
                packet = 3,
                versionRaw = 9_004,
                cells = IntArray(6) { 4_140 + it },
                temperatures = IntArray(6) { 2_500 + it * 10 }
            )
        )

        val battery = assertNotNull(protocol.latestData(0))
        assertEquals(78f, battery.soc, 0.001f)
        assertTrue(battery.socKnown)
    }

    @Test
    fun `reported cell count is read from payload rather than CRC`() {
        val protocol = VeteranProtocol()

        protocol.onNotification(
            bmsFrame(
                packet = 1,
                reportedCellCount = 30,
                cells = IntArray(15) { 4_100 }
            )
        )
        protocol.onNotification(
            bmsFrame(
                packet = 2,
                cells = IntArray(15) { 4_100 }
            )
        )

        val battery = assertNotNull(protocol.latestData(0))
        assertEquals(30, battery.cellVoltages.size)
    }

    @Test
    fun `reset clears the frame accumulator metadata packs and motion`() {
        val protocol = VeteranProtocol()
        val frame = controllerFrame(versionRaw = 5_001)
        protocol.onNotification(frame.copyOfRange(0, 10))
        protocol.reset()
        protocol.onNotification(frame.copyOfRange(10, frame.size))

        assertNull(protocol.latestData())
        assertNull(protocol.latestMotion(0))
        assertEquals("Leaperkim", protocol.model)
        assertNull(protocol.firmwareVersion)
    }

    // ---- device facts: the only thing Veteran decodes that no common section shows is the BMS's
    // own declared series-cell count per pack (page 1 / page 5, byte 52). Model, firmware and hardware
    // code reach the Connections section through ProtocolDiagnostics; speed, duty, odometer, trip,
    // temperatures and SoC are in ControllerData / BmsData. No capture carries page 1, so these use
    // the file's own synthetic bmsFrame helper.

    @Test fun deviceFactsAreEmptyForRealControllerAndBmsCaptures() {
        val protocol = VeteranProtocol()
        assertTrue(protocol is ProtocolFacts)
        assertEquals(emptyList(), protocol.deviceFacts())
        listOf(LYNX_S_PAGE_0, LYNX_S_PAGE_2, PATTON_PAGE_2).forEach { protocol.onNotification(it.hexBytes()) }
        assertNotNull(protocol.latestMotion(0), "precondition: the captures were really decoded")
        assertEquals(emptyList(), protocol.deviceFacts(), "no page 1/5 seen: no declared count")
    }

    @Test fun declaredCellCountIsListedPerPackAtIndexPackPlusOne() {
        val protocol = VeteranProtocol()
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4100 }, reportedCellCount = 36))
        assertEquals(listOf(DeviceFact(DeviceFactKey.BMS_DECLARED_CELLS, "36", index = 1)), protocol.deviceFacts())
        protocol.onNotification(bmsFrame(packet = 5, cells = IntArray(15) { 4100 }, reportedCellCount = 30))
        assertEquals(
            listOf(
                DeviceFact(DeviceFactKey.BMS_DECLARED_CELLS, "36", index = 1),
                DeviceFact(DeviceFactKey.BMS_DECLARED_CELLS, "30", index = 2),
            ),
            protocol.deviceFacts(),
        )
    }

    @Test fun secondPackAloneKeepsItsOwnIndex() {
        val protocol = VeteranProtocol()
        protocol.onNotification(bmsFrame(packet = 5, cells = IntArray(15) { 4100 }, reportedCellCount = 42))
        assertEquals(listOf(DeviceFact(DeviceFactKey.BMS_DECLARED_CELLS, "42", index = 2)), protocol.deviceFacts())
    }

    @Test fun outOfRangeCountIsNotAFactAndNeverErasesAValidOne() {
        val protocol = VeteranProtocol()
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4100 }, reportedCellCount = 0))
        assertEquals(emptyList(), protocol.deviceFacts(), "0 is not a count")
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4100 }, reportedCellCount = 43))
        assertEquals(emptyList(), protocol.deviceFacts(), "43 is above the 42-cell maximum")
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4100 }, reportedCellCount = 36))
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4100 }, reportedCellCount = 99))
        assertEquals(listOf(DeviceFact(DeviceFactKey.BMS_DECLARED_CELLS, "36", index = 1)), protocol.deviceFacts())
    }

    @Test fun factsAreTheSameInstanceUntilACountChangesAndResetForgetsThem() {
        val protocol = VeteranProtocol()
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4100 }, reportedCellCount = 36))
        val first = protocol.deviceFacts()
        protocol.onNotification(controllerFrame(voltageRaw = 12_400))
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4150 }, reportedCellCount = 36))
        assertTrue(first === protocol.deviceFacts(), "unchanged inputs must return the cached list")
        protocol.onNotification(bmsFrame(packet = 1, cells = IntArray(15) { 4150 }, reportedCellCount = 30))
        assertEquals("30", protocol.deviceFacts().single().value)
        protocol.reset()
        assertEquals(emptyList(), protocol.deviceFacts())
    }
    @Test
    fun `a torn total distance adds nothing to the trip, does not zero it and the same frame's live values stay raw`() {
        val protocol = VeteranProtocol()
        protocol.onNotification(controllerFrame(totalDistanceRaw = 10_000, speedRaw = 100))
        protocol.onNotification(controllerFrame(totalDistanceRaw = 10_100, speedRaw = 200))
        // +65 536 m on the counter only; this frame's speed and voltage are real (the CRC is valid over the needle).
        protocol.onNotification(controllerFrame(totalDistanceRaw = 10_100 + 65_536, speedRaw = 321, voltageRaw = 12_525))
        val needle = assertNotNull(protocol.latestMotion(0))
        assertEquals(32.1f, needle.speedKmh, 0.001f, "live values of a needle frame stay raw")
        assertEquals(125.25f, needle.inputVoltageV, 0.001f)
        assertEquals(75.636f, needle.odometerKm, 0.001f, "the raw odometer is published as today")
        protocol.onNotification(controllerFrame(totalDistanceRaw = 10_200, speedRaw = 300))
        protocol.onNotification(controllerFrame(totalDistanceRaw = 10_300, speedRaw = 300))

        // Today a backward step zeroes the trip and re-baselines; now 10 000 -> 10 200 m is decided (10 300 is held).
        assertEquals(0.2f, assertNotNull(protocol.latestMotion(0)).tripKm, 0.001f)
        assertEquals(1L, protocol.rejectedFrameCount)
        assertEquals(PlausibilityRule.TRIP_COUNTER_STEP, protocol.rejectedFrames().single().rule)
    }

    @Test
    fun `a persistent backward reset re-baselines after three frames and the trip keeps growing from where it was`() {
        val protocol = VeteranProtocol()
        listOf(50_000, 50_100, 50_200).forEach { protocol.onNotification(controllerFrame(totalDistanceRaw = it)) }
        listOf(100, 200, 300, 400, 500).forEach { protocol.onNotification(controllerFrame(totalDistanceRaw = it)) }
        val motion = assertNotNull(protocol.latestMotion(0))
        assertEquals(0.5f, motion.odometerKm, 0.001f)
        // 50 000 -> 50 200 (0.2 km) + the confirmed chain 100 -> 300 (0.2 km) + 300 -> 400 (0.1 km); 500 is held.
        assertEquals(0.5f, motion.tripKm, 0.001f, "previously the trip restarted from 0 on the first backward step")
        assertEquals(1L, protocol.rebaseCount)
        assertEquals(LedgerKind.REBASED, protocol.rejectedFrames().last().kind)
        protocol.reset()
        assertEquals(0L, protocol.rebaseCount, "the ledger counters are per connection")
        assertEquals(1, protocol.rejectedFrames().size, "and the ring survives")
    }

    private companion object {
        fun controllerFrame(
            len: Int = 32,
            voltageRaw: Int = 12_000,
            speedRaw: Int = 0,
            distanceRaw: Int = 0,
            totalDistanceRaw: Int = 0,
            phaseCurrentRaw: Int = 0,
            temperatureRaw: Int = 2_500,
            versionRaw: Int = 5_001,
            pwmRaw: Int = 0
        ): ByteArray = ByteArray(len + 4).apply {
            this[0] = 0xDC.toByte(); this[1] = 0x5A; this[2] = 0x5C; this[3] = len.toByte()
            putBe(4, voltageRaw); putBe(6, speedRaw); putReverseBe(8, distanceRaw); putReverseBe(12, totalDistanceRaw)
            putBe(16, phaseCurrentRaw); putBe(18, temperatureRaw)
            putBe(20, 0); putBe(22, 0); putBe(24, 0); putBe(26, 0)
            this[28] = (versionRaw ushr 8).toByte(); this[29] = versionRaw.toByte(); this[30] = (versionRaw ushr 16).toByte()
            putBe(32, 0)
            if (size >= 36) putBe(34, pwmRaw)
            if (len > 38) appendCrc(this, len)
        }

        fun bmsFrame(
            packet: Int,
            cells: IntArray = intArrayOf(),
            temperatures: IntArray = intArrayOf(),
            current1Raw: Int = 0,
            current2Raw: Int = 0,
            versionRaw: Int = 5_001,
            reportedCellCount: Int? = null
        ): ByteArray = controllerFrame(len = 83, versionRaw = versionRaw).apply {
            this[46] = packet.toByte()
            if (packet == 0 || packet == 4) {
                putBe(69, current1Raw); putBe(71, current2Raw)
            } else if (packet == 1 || packet == 5) {
                reportedCellCount?.let { this[52] = it.toByte() }
                cells.forEachIndexed { i, value -> putBe(53 + i * 2, value) }
            } else if (packet == 2 || packet == 6) {
                cells.forEachIndexed { i, value -> putBe(53 + i * 2, value) }
            } else if (packet == 3 || packet == 7) {
                cells.forEachIndexed { i, value -> putBe(59 + i * 2, value) }
                temperatures.forEachIndexed { i, value -> putBe(47 + i * 2, value) }
            }
            appendCrc(this, 83)
        }

        fun ByteArray.putBe(offset: Int, value: Int) {
            this[offset] = (value shr 8).toByte(); this[offset + 1] = value.toByte()
        }

        fun ByteArray.putReverseBe(offset: Int, value: Int) {
            this[offset] = (value shr 8).toByte(); this[offset + 1] = value.toByte()
            this[offset + 2] = (value shr 24).toByte(); this[offset + 3] = (value shr 16).toByte()
        }

        fun appendCrc(frame: ByteArray, len: Int) {
            var crc = 0xFFFF_FFFFL
            for (i in 0 until len) {
                crc = crc xor (frame[i].toInt() and 0xFF).toLong()
                repeat(8) { crc = if ((crc and 1L) != 0L) (crc ushr 1) xor 0xEDB88320L else crc ushr 1 }
            }
            crc = crc.inv() and 0xFFFF_FFFFL
            frame[len] = (crc ushr 24).toByte(); frame[len + 1] = (crc ushr 16).toByte()
            frame[len + 2] = (crc ushr 8).toByte(); frame[len + 3] = crc.toByte()
        }
    }
}

private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private const val PATTON_PAGE_2 =
    "DC5A5C3230540000EE48001BEE48001B00000DDC0D4D000007D003200FB1000219D00000006F0000808080808080022D010033AA33DD"

private const val LYNX_S_PAGE_0 =
    "DC5A5C4937190000DF6C00007042000300070C8E0379000003C002EE232C00780006007E80C800008080808080800000000BFFFFFFFFFF3211FF430AEF0CDE022A003300000002000501A0CA44"

private const val LYNX_S_PAGE_2 =
    "DC5A5C53371F0000DF6C00007042000300060C8E0378000003C002EE232C00780006007780C80000808080808080022801004E80800F520F530F4B0F550F550F550F550F540F4F0F550F560F560F550F560F52FFCECC8B"
