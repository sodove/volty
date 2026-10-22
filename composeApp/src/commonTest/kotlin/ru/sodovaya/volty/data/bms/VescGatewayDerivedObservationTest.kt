package ru.sodovaya.volty.data.bms

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.ble.ReceivedTelemetry
import ru.sodovaya.volty.data.ble.SessionTelemetryCapture
import ru.sodovaya.volty.data.ble.TelemetryReceiptTime
import ru.sodovaya.volty.data.bms.vesc.VescBmsValues
import ru.sodovaya.volty.data.bms.vesc.VescCan
import ru.sodovaya.volty.data.bms.vesc.VescFrameAccumulator
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescTestFrames
import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.TelemetryMetric
import ru.sodovaya.volty.domain.model.TelemetryObservation
import ru.sodovaya.volty.domain.model.TelemetryOrigin
import ru.sodovaya.volty.domain.model.TelemetryRole
import ru.sodovaya.volty.domain.model.TelemetryUnknown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class VescGatewayDerivedObservationTest {
    private companion object {
        const val CAN_A = 41
        const val CAN_B = 42
        const val POLL_MS = 1_000L
        val DERIVED_SCALARS = setOf(TelemetryMetric.SOC, TelemetryMetric.VOLTAGE,
            TelemetryMetric.CURRENT, TelemetryMetric.POWER)
    }

    private data class Request(val canId: Int?, val opcode: Int)
    private data class Poller(val job: Job, val issued: MutableList<Request>)

    private fun TestScope.protocol(controllerIds: List<Int> = listOf(CAN_A)) = VescGatewayProtocol(
        controllers = controllerIds.mapIndexed { index, canId ->
            GatewaySource(index, canId, MotorConfig(wheelDiameterMm = 254))
        },
        packs = listOf(GatewaySource(0, derived = true)),
        pollIntervalMs = POLL_MS,
        replyTimeoutMs = 100,
        lateReplyGuardMs = 10,
        nowMs = { currentTime }
    )

    /** Drive the real serial poll loop and answer its issued request inline. */
    private fun CoroutineScope.startPoller(protocol: VescGatewayProtocol,
        deliver: (ByteArray, Request) -> Unit = { notification, _ -> protocol.onNotification(notification) },
        respond: (Request) -> ByteArray?): Poller {
        val issued = mutableListOf<Request>()
        val job = launch {
            protocol.runPollLoop { packet ->
                val payload = VescFrameAccumulator().append(packet).single()
                val first = payload[0].toInt() and 0xFF
                val request = if (first == VescCan.OPCODE_FORWARD_CAN) {
                    Request(payload[1].toInt() and 0xFF, payload[2].toInt() and 0xFF)
                } else Request(null, first)
                issued += request
                respond(request)?.let { deliver(VescPacket.frame(it), request) }
            }
        }
        return Poller(job, issued)
    }

    private fun setup(canId: Int, networkCurrentRaw: Int, levelRaw: Int = 840,
        reportedCanId: Int? = canId) =
        VescTestFrames.setupPayload(currentInRaw = networkCurrentRaw, battLevelRaw = levelRaw,
            trailing = reportedCanId?.let { byteArrayOf(it.toByte()) } ?: byteArrayOf())

    private fun values(canId: Int, localCurrentRaw: Int, inputVoltageRaw: Int = 782,
        reportedCanId: Int? = canId) =
        VescTestFrames.valuesPayload(currentInRaw = localCurrentRaw, vInRaw = inputVoltageRaw) +
            byteArrayOf(0, 0, 0, 0) + (reportedCanId?.let { byteArrayOf(it.toByte()) } ?: byteArrayOf())

    private fun noBmsData(): ByteArray = buildList {
        add(VescBmsValues.OPCODE_BMS_GET_VALUES.toByte())
        repeat(6) { VescTestFrames.i32(this, 0) }
        add(0) // cells
        add(0) // temperature sensors
        repeat(4) { VescTestFrames.i16(this, 0) } // fixed climate block
        VescTestFrames.i16(this, 0) // SoC
        VescTestFrames.i16(this, 0) // SoH
        add(0xFF.toByte()) // firmware's explicit "no BMS data yet" sentinel
    }.toByteArray()

    private fun hostedBms(): ByteArray = buildList {
        add(VescBmsValues.OPCODE_BMS_GET_VALUES.toByte())
        listOf(75_500_000, 0, 12_500_000, 12_500_000, 18_200, 1_374_100).forEach {
            VescTestFrames.i32(this, it)
        }
        add(2) // cells
        VescTestFrames.i16(this, 4_012); VescTestFrames.i16(this, 3_998)
        add(0); add(1) // balancing flags
        add(1) // temperature sensors
        VescTestFrames.i16(this, 2_350)
        listOf(3_120, 2_880, 0, 2_410, 812, 990).forEach { VescTestFrames.i16(this, it) }
        add(10) // BMS producer CAN id; not the forwarded host id
    }.toByteArray()

    private fun scriptedReply(request: Request, validControllers: Boolean,
        hosted: Boolean = false): ByteArray? = when (request.opcode) {
        VescValues.OPCODE_GET_VALUES_SETUP -> if (validControllers) setup(requireNotNull(request.canId), 9_999)
            else byteArrayOf(VescValues.OPCODE_GET_VALUES_SETUP.toByte())
        VescValues.OPCODE_GET_VALUES -> if (validControllers) values(requireNotNull(request.canId), 3_000)
            else byteArrayOf(VescValues.OPCODE_GET_VALUES.toByte())
        VescBmsValues.OPCODE_BMS_GET_VALUES -> if (hosted) hostedBms() else noBmsData()
        else -> null
    }

    private fun derived(events: List<TelemetryObservation>) = events.filter {
        it.origin == TelemetryOrigin.BATTERY && it.role == TelemetryRole.DERIVED
    }

    private fun numericDerived(events: List<TelemetryObservation>) = derived(events).filter { event ->
        event.readings.any { it.value != null }
    }

    private fun TelemetryObservation.reading(metric: TelemetryMetric) =
        readings.single { it.metric == metric }

    @Test fun fallbackWaitsForWarmupAndPublishesOnlyEarnedVirtualScalars() = runTest {
        val protocol = protocol(listOf(CAN_A, CAN_B))
        val observations = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = observations::add
        val poller = startPoller(protocol) { request ->
            if (request.opcode == VescValues.OPCODE_GET_VALUES && request.canId == CAN_B)
                values(CAN_B, 1_000, inputVoltageRaw = 0)
            else scriptedReply(request, validControllers = true)
        }
        try {
            runCurrent()
            assertEquals(listOf(CAN_A, CAN_B), poller.issued.filter {
                it.opcode == VescValues.OPCODE_GET_VALUES_SETUP
            }.map { it.canId })
            assertTrue(numericDerived(observations).isEmpty(), "the first sentinel starts, but does not skip, the six-second warm-up")
            advanceTimeBy(VescGatewayProtocol.HOSTED_BMS_WARMUP_MS - 1); runCurrent()
            assertTrue(numericDerived(observations).isEmpty())
            advanceTimeBy(1); runCurrent()

            val event = numericDerived(observations).single()
            assertEquals(TelemetryOrigin.BATTERY, event.origin)
            assertEquals(TelemetryRole.DERIVED, event.role)
            assertEquals(0, event.localIndex)
            assertEquals(DERIVED_SCALARS, event.readings.map { it.metric }.toSet())
            assertEquals(84.0, assertNotNull(event.reading(TelemetryMetric.SOC).value), 0.001)
            assertEquals(78.2, assertNotNull(event.reading(TelemetryMetric.VOLTAGE).value), 0.001)
            assertEquals(-40.0, assertNotNull(event.reading(TelemetryMetric.CURRENT).value), 0.001,
                "the local current is the sum of GET_VALUES replies, not SETUP's network-scope current")
            assertNull(event.reading(TelemetryMetric.POWER).value,
                "one controller without a measured rail cannot make the summed power complete")
            assertEquals(TelemetryUnknown.UNAVAILABLE, event.reading(TelemetryMetric.POWER).unknown)
            assertTrue(assertNotNull(protocol.latestData(0)).hasPower,
                "the history observation retains the legacy live BmsData projection")
        } finally { poller.job.cancelAndJoin() }
    }

    @Test fun equalAcceptedControllerRepliesProduceDistinctFreshFallbackObservations() = runTest {
        val protocol = protocol()
        val observations = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = observations::add
        var identityMode = 0 // 0 = matching id, 1 = wrong id, 2 = omitted id
        val poller = startPoller(protocol) { request ->
            if (identityMode == 0) scriptedReply(request, validControllers = true)
            else when (request.opcode) {
                VescValues.OPCODE_GET_VALUES_SETUP -> setup(
                    requireNotNull(request.canId), 9_999,
                    reportedCanId = if (identityMode == 1) CAN_A + 1 else null
                )
                VescValues.OPCODE_GET_VALUES -> values(
                    requireNotNull(request.canId), 3_000,
                    reportedCanId = if (identityMode == 1) CAN_A + 1 else null
                )
                VescBmsValues.OPCODE_BMS_GET_VALUES -> noBmsData()
                else -> null
            }
        }
        try {
            runCurrent()
            assertTrue(numericDerived(observations).isEmpty())
            advanceTimeBy(VescGatewayProtocol.HOSTED_BMS_WARMUP_MS + 2 * POLL_MS); runCurrent()

            val published = numericDerived(observations)
            assertEquals(3, published.size, "each of three accepted identical replies is a fresh observation")
            assertEquals(1, published.map { it.readings }.distinct().size,
                "unchanged values still arrive in three distinct controller replies")

            identityMode = 1
            advanceTimeBy(POLL_MS); runCurrent()
            assertEquals(3, numericDerived(observations).size,
                "a decoder reply with another controller's CAN id is not a receipt for this source")

            identityMode = 2
            advanceTimeBy(POLL_MS); runCurrent()
            assertEquals(3, numericDerived(observations).size,
                "a valid decoded body without the forwarded controller id cannot earn a derived sample")
            assertEquals(11, poller.issued.count { it.opcode == VescBmsValues.OPCODE_BMS_GET_VALUES })
        } finally { poller.job.cancelAndJoin() }
    }

    @Test fun undecodableRepliesDoNotRestampCachedControllerInputsOnLaterCycles() = runTest {
        val protocol = protocol()
        val observations = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = observations::add
        var validControllers = true
        val poller = startPoller(protocol) { scriptedReply(it, validControllers) }
        try {
            runCurrent()
            advanceTimeBy(VescGatewayProtocol.HOSTED_BMS_WARMUP_MS); runCurrent()
            val before = numericDerived(observations).single()
            val cachedLiveData = assertNotNull(protocol.latestData(0))

            // Right opcodes with undecodable bodies complete the exchange but
            // leave the last live projection cached. They are not decode receipts.
            validControllers = false
            advanceTimeBy(3 * POLL_MS); runCurrent()

            assertEquals(10, poller.issued.count { it.opcode == VescValues.OPCODE_GET_VALUES_SETUP })
            assertEquals(10, poller.issued.count { it.opcode == VescValues.OPCODE_GET_VALUES })
            assertEquals(10, poller.issued.count { it.opcode == VescBmsValues.OPCODE_BMS_GET_VALUES })
            assertEquals(listOf(before), numericDerived(observations),
                "advancing time and polling against cached values is not a new numeric observation")
            assertEquals(cachedLiveData.timestamp, assertNotNull(protocol.latestData(0)).timestamp,
                "keep the existing live projection's original decode timestamp")
        } finally { poller.job.cancelAndJoin() }
    }

    @Test fun silentBmsFallbackIsCapturedInsideAcceptedControllerNotificationScope() = runTest {
        val protocol = protocol(listOf(CAN_A, CAN_B))
        val received = mutableListOf<ReceivedTelemetry>()
        val fallbackReceipts = mutableListOf<Pair<ReceivedTelemetry, Request?>>()
        var currentRequest: Request? = null
        var clockTick = 0L
        val capture = SessionTelemetryCapture(protocol, "silent-bms", {
            clockTick++
            TelemetryReceiptTime(1_000 + clockTick, clockTick)
        }, { true }, { telemetry ->
            received += telemetry
            if (telemetry.observation.origin == TelemetryOrigin.BATTERY &&
                telemetry.observation.role == TelemetryRole.DERIVED &&
                telemetry.observation.readings.any { it.value != null }
            ) fallbackReceipts += telemetry to currentRequest
        })
        val poller = startPoller(protocol, deliver = { notification, request ->
            currentRequest = request
            assertTrue(capture.processNotification(notification))
            currentRequest = null
        }) { request ->
            if (request.opcode == VescBmsValues.OPCODE_BMS_GET_VALUES) null
            else scriptedReply(request, validControllers = true)
        }
        try {
            runCurrent()
            advanceTimeBy(VescGatewayProtocol.HOSTED_BMS_WARMUP_MS + POLL_MS); runCurrent()

            assertEquals(7, poller.issued.count { it.opcode == VescBmsValues.OPCODE_BMS_GET_VALUES },
                "the serial poll issued repeated BMS requests and crossed the six-second silence warm-up")
            assertEquals(78.2f, assertNotNull(protocol.latestData(0)).voltage,
                "the live fallback was computed after the BMS silence boundary")
            // The qualifying timeout was after this cycle's last notification.
            // Issue the next real controller round before expecting its receipt.
            advanceTimeBy(POLL_MS); runCurrent()
            assertEquals(8, poller.issued.count { it.opcode == VescBmsValues.OPCODE_BMS_GET_VALUES })
            assertTrue(fallbackReceipts.isNotEmpty(),
                "a fallback derived from controller replies must arrive while a session receipt scope is active")
            assertTrue(fallbackReceipts.all { (telemetry, request) ->
                telemetry.receipt.sessionId == "silent-bms" && request?.opcode == VescValues.OPCODE_GET_VALUES
            }, "the derived sample belongs to the accepted GET_VALUES notification, not an unreceipted timeout")
            assertTrue(fallbackReceipts.all { (telemetry, _) ->
                telemetry.observation.reading(TelemetryMetric.CURRENT).value == -60.0
            }, "both fresh controller contributions must survive until the receipt-scoped fold")
            assertTrue(fallbackReceipts.all { (_, request) -> request?.canId == CAN_B })
            assertNull(protocol.telemetryObserver, "capture restores the observer after each incoming notification")
        } finally {
            poller.job.cancelAndJoin()
            capture.close()
        }
    }

    @Test fun aRecentHostedReplyOutranksFallbackAndVirtualBatteryNeverClaimsCellsOrPackTemperature() = runTest {
        val protocol = protocol()
        val observations = mutableListOf<TelemetryObservation>()
        protocol.telemetryObserver = observations::add
        var hosted = false
        var controllerIdentityValid = true
        val poller = startPoller(protocol) { request ->
            if (controllerIdentityValid) scriptedReply(request, validControllers = true, hosted = hosted)
            else when (request.opcode) {
                VescValues.OPCODE_GET_VALUES_SETUP -> setup(
                    requireNotNull(request.canId), 9_999, reportedCanId = CAN_A + 1
                )
                VescValues.OPCODE_GET_VALUES -> values(
                    requireNotNull(request.canId), 9_000, inputVoltageRaw = 650, reportedCanId = CAN_A + 1
                )
                VescBmsValues.OPCODE_BMS_GET_VALUES -> if (hosted) hostedBms() else noBmsData()
                else -> null
            }
        }
        try {
            runCurrent()
            advanceTimeBy(VescGatewayProtocol.HOSTED_BMS_WARMUP_MS); runCurrent()
            assertEquals(1, numericDerived(observations).size)

            hosted = true
            advanceTimeBy(POLL_MS); runCurrent()
            val hostedData = assertNotNull(protocol.latestData(0))
            assertEquals(75.5f, hostedData.voltage)
            assertEquals(2, numericDerived(observations).size, "the accepted host snapshot is observed")

            hosted = false
            controllerIdentityValid = false
            advanceTimeBy(POLL_MS); runCurrent()
            assertEquals(75.5f, assertNotNull(protocol.latestData(0)).voltage,
                "a fresh hosted reading keeps priority over the rail-derived substitute")
            assertEquals(2, numericDerived(observations).size,
                "the fallback does not overwrite the hosted snapshot inside its precedence window")

            advanceTimeBy(VescGatewayProtocol.STALE_READING_MS + POLL_MS); runCurrent()
            assertEquals(22, poller.issued.count { it.opcode == VescBmsValues.OPCODE_BMS_GET_VALUES },
                "the serial poll reaches cycles after the hosted snapshot's precedence window")
            assertEquals(2, numericDerived(observations).size,
                "after hosted precedence expires, stale qualified snapshots cannot certify later wrong-id live values")
            assertEquals(75.5, assertNotNull(numericDerived(observations).last()
                .reading(TelemetryMetric.VOLTAGE).value), 0.001,
                "wrong-id 65 V and 90 A live decodes are never attached to the old accepted receipt")

            assertTrue(derived(observations).isNotEmpty())
            assertTrue(derived(observations).all { event ->
                event.role == TelemetryRole.DERIVED && event.readings.all { it.metric in DERIVED_SCALARS }
            }, "the virtual battery has scalar readings only, with no physical cells or pack thermistor")
            assertFalse(derived(observations).any { event ->
                event.readings.any { it.metric == TelemetryMetric.CELL || it.metric == TelemetryMetric.BATTERY_TEMPERATURE }
            })
        } finally { poller.job.cancelAndJoin() }
    }
}
