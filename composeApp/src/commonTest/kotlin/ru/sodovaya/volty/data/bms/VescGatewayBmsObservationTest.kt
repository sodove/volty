package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.bms.vesc.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VescGatewayBmsObservationTest {
    private fun protocol(packs: List<GatewaySource> = listOf(GatewaySource(19))) =
        VescGatewayProtocol(emptyList(), packs, pollIntervalMs = 1000, replyTimeoutMs = 100, lateReplyGuardMs = 10)

    // Complete reply with explicit on-wire fields. Its history timestamp is app
    // receipt time; it never claims an ADC acquisition timestamp inside the BMS.
    private fun reply(cells: Int = 2, id: Int = 10, voltage: Int = 75_500_000): ByteArray {
        val bytes = mutableListOf<Byte>(96)
        fun i32(value: Int) { for (shift in listOf(24, 16, 8, 0)) bytes += (value shr shift).toByte() }
        fun i16(value: Int) { bytes += (value shr 8).toByte(); bytes += value.toByte() }
        listOf(voltage, 0, 12_500_000, 12_500_000, 18_200, 1_374_100).forEach(::i32)
        bytes += cells.toByte()
        repeat(cells) { i16(4000 + it) }
        repeat(cells) { bytes += 0 }
        bytes += 1; i16(2350)
        listOf(3120, 2880, 0, 2410, 812, 990).forEach(::i16)
        bytes += id.toByte()
        return bytes.toByteArray()
    }

    private suspend fun TestScope.cycle(p: VescGatewayProtocol, reply: (Int?) -> ByteArray): List<Int?> {
        val issued = mutableListOf<Int?>()
        val loop = launch {
            p.runPollLoop { packet ->
                val request = VescFrameAccumulator().append(packet).single()
                val forwarded = (request[0].toInt() and 255) == VescCan.OPCODE_FORWARD_CAN
                val id = if (forwarded) request[1].toInt() and 255 else null
                assertEquals(96, request[if (forwarded) 2 else 0].toInt() and 255)
                issued += id
                p.onNotification(VescPacket.frame(reply(id)))
            }
        }
        try { runCurrent(); advanceTimeBy(1); runCurrent() }
        finally { loop.cancelAndJoin() }
        return issued
    }

    private fun assertUnknown(event: TelemetryObservation, reason: TelemetryUnknown) {
        assertEquals(TelemetryOrigin.BATTERY, event.origin)
        assertEquals(TelemetryRole.CONTRIBUTOR, event.role)
        assertEquals(TelemetryMetric.entries.filter { it.origin == TelemetryOrigin.BATTERY && !it.indexed }.toSet(),
            event.readings.filter { it.cellIndex == null }.map { it.metric }.toSet())
        assertTrue(event.readings.all { it.value == null && it.unknown == reason })
    }

    // Retraction: the previous test required all received numbers to be erased
    // because the response lacks sensor ages. That suppressed a working decoder.
    @Test fun completeReplyRecordsReceivedValuesAndPreservesLiveProjection() = runTest {
        val p = protocol(); val events = mutableListOf<TelemetryObservation>()
        p.telemetryObserver = events::add
        assertEquals(listOf<Int?>(null), cycle(p) { reply() })
        val event = events.single()
        assertEquals(75.5, event.readings.single { it.metric == TelemetryMetric.VOLTAGE }.value)
        assertEquals(-12.5, event.readings.single { it.metric == TelemetryMetric.CURRENT }.value)
        assertEquals(-943.75, event.readings.single { it.metric == TelemetryMetric.POWER }.value)
        assertEquals(81.2, assertNotNull(event.readings.single { it.metric == TelemetryMetric.SOC }.value), 0.001)
        assertEquals(listOf(0, 1), events.single().readings.filter { it.metric == TelemetryMetric.CELL }.map { it.cellIndex })
        assertEquals(75.5f, assertNotNull(p.latestData()).voltage)
        cycle(p) { reply() }
        assertEquals(2, events.size)
        assertEquals(event.readings, events.last().readings, "an equal received response is another observation")
    }

    @Test fun localPackSlotsAndCellLayoutsAreIndependentOfGlobalIndices() = runTest {
        val p = protocol(listOf(GatewaySource(19, 41), GatewaySource(3, 42)))
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = events::add
        assertEquals(listOf<Int?>(41, 42), cycle(p) { reply(cells = if (it == 41) 3 else 1, id = it!!) })
        assertEquals(listOf(0, 1), events.map { it.localIndex })
        assertEquals(listOf(3, 1), events.map { event -> event.readings.count { it.metric == TelemetryMetric.CELL } })
        assertTrue(events.flatMap { it.readings }.all { it.value != null })
    }

    @Test fun shrinkingLayoutAndNoDataSentinelRetirePreviouslyDeclaredCellsUntilReset() = runTest {
        val p = protocol(); val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = events::add
        cycle(p) { reply(cells = 3) }
        cycle(p) { reply(cells = 1) }
        assertEquals(listOf(0, 1, 2), events.last().readings.filter { it.metric == TelemetryMetric.CELL }.map { it.cellIndex })
        cycle(p) { reply(cells = 0, id = 255, voltage = 0) }
        assertUnknown(events.last(), TelemetryUnknown.UNAVAILABLE)
        assertEquals(3, events.last().readings.count { it.metric == TelemetryMetric.CELL })
        p.reset(); events.clear()
        cycle(p) { reply(cells = 1) }
        assertEquals(listOf(0), events.single().readings.filter { it.metric == TelemetryMetric.CELL }.map { it.cellIndex })
    }

    @Test fun malformedOrUnsolicitedFramesDoNotPublishHistory() = runTest {
        val p = protocol(); val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = events::add
        p.onNotification(VescPacket.frame(reply()))
        assertEquals(listOf<Int?>(null), cycle(p) { byteArrayOf(96, 1) })
        assertTrue(events.isEmpty())
        assertNull(p.latestData())
    }

    @Test fun derivedSlotCannotMasqueradeAsPhysicalBattery() = runTest {
        val p = protocol(listOf(GatewaySource(19, derived = true)))
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = events::add
        cycle(p) { reply() }
        val event = events.single()
        assertEquals(TelemetryRole.DERIVED, event.role)
        assertEquals(setOf(TelemetryMetric.SOC, TelemetryMetric.VOLTAGE, TelemetryMetric.CURRENT, TelemetryMetric.POWER),
            event.readings.map { it.metric }.toSet())
        assertEquals(75.5, event.readings.single { it.metric == TelemetryMetric.VOLTAGE }.value)
    }

    @Test fun failedHistoryObserverCannotBreakLiveDecode() = runTest {
        val p = protocol(); p.telemetryObserver = { error("Recorder unavailable") }
        cycle(p) { reply() }
        assertEquals(1, p.telemetryObserverFailures)
        assertEquals(75.5f, assertNotNull(p.latestData()).voltage)
    }

    // Retraction: an earlier test inferred that reply.canId must equal the
    // FORWARD_CAN target. bms.c serializes the BMS producer's ID, which can
    // differ from the controller hosting its cache. It is not a transaction ID.
    @Test fun forwardedHostMayReportADifferentBmsCanIdentity() = runTest {
        val p = protocol(listOf(GatewaySource(19, 41), GatewaySource(3, 42)))
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = events::add
        cycle(p) { reply(id = 10) }
        assertEquals(listOf(0, 1), events.map { it.localIndex })
        assertEquals(75.5, events.first().readings.single { it.metric == TelemetryMetric.VOLTAGE }.value)
        assertEquals(75.5, events.last().readings.single { it.metric == TelemetryMetric.VOLTAGE }.value)
    }

    @Test fun bmsProducerIdentityDoesNotReplaceTheRequestedGatewayEndpoint() = runTest {
        val p = protocol(listOf(GatewaySource(19), GatewaySource(3, 42)))
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = events::add
        cycle(p) { reply(id = 42) }
        assertEquals(listOf(0, 1), events.map { it.localIndex })
        assertEquals(75.5, events.first().readings.single { it.metric == TelemetryMetric.VOLTAGE }.value)
        assertEquals(75.5, events.last().readings.single { it.metric == TelemetryMetric.VOLTAGE }.value)
    }
}
