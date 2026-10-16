package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.bms.vesc.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VescCanReportingTest {
    @Test fun coalesced_plain_and_setup_replies_emit_only_the_setup_network_report() {
        val p = VescProtocol()
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        val plain = VescPacket.frame(VescTestFrames.valuesPayload(currentInRaw = 1000))
        val setup = VescPacket.frame(VescTestFrames.setupPayload(currentMotorRaw = 0, currentInRaw = -700))
        p.onNotification(plain + setup + plain)
        val report = events.filter { it.role == TelemetryRole.REPORTED_CAN }.single()
        assertEquals(TelemetryOrigin.CONTROLLER, report.origin)
        assertEquals(0, report.localIndex)
        assertEquals(setOf(TelemetryMetric.MOTOR_CURRENT, TelemetryMetric.CONTROLLER_BATTERY_CURRENT), report.readings.map { it.metric }.toSet())
        assertEquals(0.0, report.readings.single { it.metric == TelemetryMetric.MOTOR_CURRENT }.value)
        assertEquals(-7.0, report.readings.single { it.metric == TelemetryMetric.CONTROLLER_BATTERY_CURRENT }.value)
        val physical = events.filter { it.role == TelemetryRole.CONTRIBUTOR }
        assertEquals(3, physical.size)
        assertTrue(physical[1].readings.none { it.metric == TelemetryMetric.CONTROLLER_POWER })
        assertEquals(listOf(10.0, 10.0), physical.flatMap { it.readings }.filter { it.metric == TelemetryMetric.CONTROLLER_BATTERY_CURRENT }.map { it.value })
    }

    @Test fun plain_values_alone_cannot_invent_a_network_sum() {
        val p = VescProtocol()
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        p.onNotification(VescPacket.frame(VescTestFrames.valuesPayload()))
        assertTrue(events.isNotEmpty())
        assertTrue(events.none { it.role == TelemetryRole.REPORTED_CAN })
    }

    private suspend fun TestScope.gatewayCycle(replyId: Int): List<TelemetryObservation> {
        val p = VescGatewayProtocol(controllers = listOf(GatewaySource(7, 41)), packs = emptyList(),
            pollIntervalMs = 1000, replyTimeoutMs = 100, lateReplyGuardMs = 10)
        val events = mutableListOf<TelemetryObservation>(); p.telemetryObserver = { events += it }
        val loop = launch {
            p.runPollLoop { packet ->
                val request = VescFrameAccumulator().append(packet).single()
                val offset = if (request[0].toInt() == VescCan.OPCODE_FORWARD_CAN) 2 else 0
                val opcode = request[offset].toInt() and 255
                val reply = when (opcode) {
                    VescValues.OPCODE_GET_VALUES_SETUP -> VescTestFrames.setupPayload(currentMotorRaw = 0,
                        currentInRaw = 2000, trailing = byteArrayOf(replyId.toByte()))
                    VescValues.OPCODE_GET_VALUES -> VescTestFrames.valuesPayload(currentInRaw = 1000) +
                        byteArrayOf(0, 0, 0, 0, replyId.toByte())
                    else -> byteArrayOf(opcode.toByte())
                }
                p.onNotification(VescPacket.frame(reply))
            }
        }
        try { runCurrent(); advanceTimeBy(1); runCurrent() }
        finally { loop.cancelAndJoin() }
        return events
    }

    @Test fun forwarded_network_report_uses_local_index_and_verified_reporting_node() = runTest {
        val events = gatewayCycle(41)
        val report = events.filter { it.role == TelemetryRole.REPORTED_CAN }.single()
        assertEquals(0, report.localIndex)
        assertEquals(TelemetryOrigin.CONTROLLER, report.origin)
        assertEquals(20.0, report.readings.single { it.metric == TelemetryMetric.CONTROLLER_BATTERY_CURRENT }.value)
        assertEquals(0.0, report.readings.single { it.metric == TelemetryMetric.MOTOR_CURRENT }.value)
    }

    @Test fun wrong_forwarded_node_cannot_certify_network_sum_values_for_the_requested_source() = runTest {
        val events = gatewayCycle(42)
        val report = events.filter { it.role == TelemetryRole.REPORTED_CAN }.single()
        assertEquals(2, report.readings.size)
        assertTrue(report.readings.all { it.value == null && it.unknown == TelemetryUnknown.PARTIAL_SOURCE })
    }
}
