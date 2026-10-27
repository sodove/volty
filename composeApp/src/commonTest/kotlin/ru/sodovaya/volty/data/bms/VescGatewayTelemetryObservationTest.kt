package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.bms.vesc.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VescGatewayTelemetryObservationTest {
    private fun protocol(sources: List<GatewaySource>) = VescGatewayProtocol(
        controllers = sources, packs = emptyList(), pollIntervalMs = 1000,
        replyTimeoutMs = 100, lateReplyGuardMs = 10)

    private suspend fun TestScope.cycle(p: VescGatewayProtocol,
        reply: (Int?, Int) -> ByteArray): List<Pair<Int?, Int>> {
        val issued = mutableListOf<Pair<Int?, Int>>()
        val loop = launch {
            p.runPollLoop { packet ->
                val payload = VescFrameAccumulator().append(packet).single()
                val forwarded = payload[0].toInt() == VescCan.OPCODE_FORWARD_CAN
                val id = if (forwarded) payload[1].toInt() and 255 else null
                val opcode = payload[if (forwarded) 2 else 0].toInt() and 255
                issued += id to opcode
                p.onNotification(VescPacket.frame(reply(id, opcode)))
            }
        }
        try { runCurrent(); advanceTimeBy(1); runCurrent() }
        finally { loop.cancel(); loop.join() }
        return issued
    }

    private fun reply(opcode: Int, id: Int? = null, current: Int = 0, distance: Int = 0): ByteArray =
        if (opcode == VescValues.OPCODE_GET_VALUES_SETUP)
            VescTestFrames.setupPayload(currentInRaw = 9900, tachAbsMRaw = distance,
                trailing = id?.let { byteArrayOf(it.toByte()) } ?: byteArrayOf())
        else VescTestFrames.valuesPayload(currentInRaw = current) +
            (id?.let { byteArrayOf(0, 0, 0, 0, it.toByte()) } ?: byteArrayOf())

    private fun TelemetryObservation.reading(metric: TelemetryMetric) = readings.single { it.metric == metric }

    @Test fun localIndicesAreNotGlobalIndicesAndValuesNeverBorrowSetupOverlays() = runTest {
        val p = protocol(listOf(GatewaySource(7, 41), GatewaySource(2, 42)))
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { if (it.role == TelemetryRole.CONTRIBUTOR) events += it }
        val issued = cycle(p) { id, op -> reply(op, id, if (id == 41) 0 else -100) }
        assertEquals(listOf(41 to 47, 42 to 47, 41 to 4, 42 to 4), issued)
        assertEquals(listOf(0, 1, 0, 1), events.map { it.localIndex })
        assertTrue(events.all { it.origin == TelemetryOrigin.CONTROLLER })
        assertTrue(events[0].readings.none { it.metric == TelemetryMetric.CONTROLLER_BATTERY_CURRENT })
        assertEquals(0.0, events[2].reading(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value)
        assertEquals(-1.0, events[3].reading(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value)
        assertTrue(events[2].readings.none { it.metric == TelemetryMetric.ODOMETER })
    }

    @Test fun missingOrWrongCanIdentityInvalidatesFactsWithoutAdvancingTripBaseline() = runTest {
        val p = protocol(listOf(GatewaySource(9, 41)))
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { if (it.role == TelemetryRole.CONTRIBUTOR) events += it }
        assertEquals(2, cycle(p) { _, op -> reply(op, if (op == 47) 42 else null, 9900, 900000) }.size)
        assertEquals(2, events.size)
        assertTrue(events.flatMap { it.readings }.all { it.value == null && it.unknown == TelemetryUnknown.PARTIAL_SOURCE })
        events.clear()
        cycle(p) { _, op -> reply(op, 41, 100, 1200000) }
        assertEquals(0.0, events.first().reading(TelemetryMetric.TRIP).value)
        assertEquals(1.0, events.last().reading(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value)
        assertNotNull(p.latestMotion(0)) // Live projection is independently preserved.
    }

    @Test fun nativeLocalControllerNeedsNoCanTrailerAndResetRestartsHistoryDistance() = runTest {
        val p = protocol(listOf(GatewaySource(8)))
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { if (it.role == TelemetryRole.CONTRIBUTOR) events += it }
        cycle(p) { _, op -> reply(op, distance = 1000000) }
        cycle(p) { _, op -> reply(op, distance = 1200000) }
        assertEquals(0.0, assertNotNull(events[2].reading(TelemetryMetric.TRIP).value), "decided one frame late (counter gate lookahead)")
        cycle(p) { _, op -> reply(op, distance = 1200000) }
        assertEquals(6, events.size)
        assertEquals(0.2, assertNotNull(events[4].reading(TelemetryMetric.TRIP).value), 0.00001)
        p.reset(); events.clear()
        cycle(p) { _, op -> reply(op, distance = 1800000) }
        assertEquals(0.0, events.first().reading(TelemetryMetric.TRIP).value)
        assertEquals(0.0, events.last().reading(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value)
    }

    @Test fun mixedNativeAndForwardedControllersCannotAttributeLateCanReplyToNative() = runTest {
        val p = protocol(listOf(GatewaySource(7), GatewaySource(2, 41)))
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { if (it.role == TelemetryRole.CONTRIBUTOR) events += it }
        val issued = cycle(p) { _, op -> reply(op, 41, 9900, 900000) }
        assertEquals(listOf(null to 47, 41 to 47, null to 4, 41 to 4), issued)
        val native = events.filter { it.localIndex == 0 }
        assertEquals(2, native.size)
        assertTrue(native.flatMap { it.readings }.all { it.value == null && it.unknown == TelemetryUnknown.PARTIAL_SOURCE })
        assertEquals(99.0, events.last().reading(TelemetryMetric.CONTROLLER_BATTERY_CURRENT).value)
    }
}
