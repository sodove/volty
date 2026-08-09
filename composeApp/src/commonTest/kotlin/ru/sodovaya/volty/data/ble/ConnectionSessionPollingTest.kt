package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.VescProtocol
import ru.sodovaya.volty.data.bms.VescGatewayProtocol
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescTestFrames
import ru.sodovaya.volty.domain.model.MotorConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionSessionPollingTest {

    private fun opcode(command: ByteArray): Int = command[2].toInt()

    @Test
    fun `plain poll cycles reread commands after SETUP selects opcode 47`() = runTest {
        val protocol = VescProtocol()
        val issued = mutableListOf<Int>()

        runBurstPollCycle(protocol, { command ->
            issued += opcode(command)
            if (opcode(command) == 47) protocol.onNotification(VescPacket.frame(VescTestFrames.setupPayload()))
        }, {})
        runBurstPollCycle(protocol, { command -> issued += opcode(command) }, {})

        assertEquals(listOf(47, 4, 47), issued)
    }

    @Test
    fun `plain poll cycles reread commands after GET_VALUES selects opcode 4`() = runTest {
        val protocol = VescProtocol(motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254))
        val issued = mutableListOf<Int>()

        runBurstPollCycle(protocol, { command ->
            issued += opcode(command)
            if (opcode(command) == 4) protocol.onNotification(VescPacket.frame(VescTestFrames.valuesPayload()))
        }, {})
        runBurstPollCycle(protocol, { command -> issued += opcode(command) }, {})

        assertEquals(listOf(47, 4, 4), issued)
    }

    @Test
    fun `silent plain poll cycles bound the dual opcode probe`() = runTest {
        val protocol = VescProtocol()
        val issued = mutableListOf<Int>()

        repeat(5) { runBurstPollCycle(protocol, { command -> issued += opcode(command) }, {}) }

        assertEquals(listOf(47, 4, 47, 4, 47, 4, 47, 47), issued)
    }

    @Test
    fun `a thrown plain burst write is retained as a poll outcome instead of escaping`() = runTest {
        val result = runCatching {
            runBurstPollCycle(VescProtocol(), write = {
                throw IllegalStateException("WRITE_NO_RESPONSE unavailable")
            }, wait = {})
        }

        assertTrue(result.isSuccess, "a poll write failure must be recorded, then the next cycle retried")
    }

    @Test
    fun `an all write failure plain link reports its failure streak instead of quiet`() = runTest {
        var failures = 0
        var lastFailure: String? = null

        repeat(3) {
            runBurstPollCycle(
                protocol = VescProtocol(),
                write = { throw IllegalStateException("WRITE_NO_RESPONSE unavailable") },
                wait = {},
                onWriteFailure = { failure ->
                    failures += 1
                    lastFailure = failure.message
                }
            )
        }

        assertEquals(3, failures)
        assertEquals("WRITE_NO_RESPONSE unavailable", lastFailure)
    }

    @Test
    fun `successful plain burst writes with no decoded response remain distinguishable from write failure`() = runTest {
        var failures = 0
        var lastFailure: String? = null

        runBurstPollCycle(
            protocol = VescProtocol(),
            write = { throw IllegalStateException("WRITE_NO_RESPONSE unavailable") },
            wait = {},
            onWriteFailure = { failure ->
                failures += 1
                lastFailure = failure.message
            }
        )
        runBurstPollCycle(
            protocol = VescProtocol(),
            write = {},
            wait = {},
            onWriteSuccess = {
                failures = 0
                lastFailure = null
            }
        )

        assertEquals(0, failures)
        assertNull(lastFailure)
    }

    @Test
    fun `plain VESC notifications no decoder understands are diagnosed instead of redialled`() {
        val protocol = VescProtocol()
        val activity = NoSampleEverWatchdogActivity(protocol)
        processObservedSessionNotification(protocol, byteArrayOf(0x01, 0x02, 0x03), activity)

        assertEquals(
            NoSampleEverWatchdogDecision.NOT_UNDERSTOOD,
            activity.decision()
        )
    }

    @Test
    fun `plain VESC with no notifications still takes the existing redial path`() {
        assertEquals(
            NoSampleEverWatchdogDecision.REDIAL,
            NoSampleEverWatchdogActivity(VescProtocol()).decision()
        )
    }

    @Test
    fun `plain VESC production burst write failure is neither redialled nor called quiet`() = runTest {
        val protocol = VescProtocol()
        val activity = NoSampleEverWatchdogActivity(protocol)
        processObservedSessionNotification(protocol, byteArrayOf(0x01, 0x02, 0x03), activity)
        runSessionBurstPollCycle(
            protocol = protocol,
            activity = activity,
            write = { throw IllegalStateException("WRITE_NO_RESPONSE unavailable") },
            wait = {}
        )

        assertEquals(
            NoSampleEverWatchdogDecision.WRITE_FAILED,
            activity.decision()
        )

        activity.recordPollWriteSuccess()
        assertEquals(
            NoSampleEverWatchdogDecision.NOT_UNDERSTOOD,
            activity.decision(),
            "the accepted write clears only the write-failure diagnosis; the notification is still real"
        )
    }

    @Test
    fun `serial gateway keeps its existing no-decode watchdog redial`() {
        val protocol = VescGatewayProtocol(controllers = emptyList(), packs = emptyList())
        val activity = NoSampleEverWatchdogActivity(protocol)
        processObservedSessionNotification(protocol, byteArrayOf(0x01, 0x02, 0x03), activity)

        assertEquals(
            NoSampleEverWatchdogDecision.REDIAL,
            activity.decision()
        )
    }

    @Test
    fun `battery protocol keeps its existing no-decode watchdog redial`() {
        val protocol = BegodeProtocol()
        val activity = NoSampleEverWatchdogActivity(protocol)
        processObservedSessionNotification(protocol, byteArrayOf(0x01, 0x02, 0x03), activity)

        assertEquals(NoSampleEverWatchdogDecision.REDIAL, activity.decision())
    }
}
