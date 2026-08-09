package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.VescProtocol
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescTestFrames
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.MotorConfig
import kotlinx.coroutines.flow.MutableStateFlow
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
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(null))

        repeat(3) {
            runBurstPollCycle(
                protocol = VescProtocol(),
                write = { throw IllegalStateException("WRITE_NO_RESPONSE unavailable") },
                wait = {},
                connectionState = state
            )
        }

        val reported = state.value as ConnectionState.Connected
        assertEquals(3, reported.consecutivePollWriteFailures)
        assertEquals("WRITE_NO_RESPONSE unavailable", reported.lastPollWriteFailure)
        assertTrue(
            reported != ConnectionState.Connected(null),
            "all failed writes must not be indistinguishable from a quiet link"
        )
    }

    @Test
    fun `successful plain burst writes with no decoded response remain distinguishable from write failure`() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(null))

        runBurstPollCycle(
            protocol = VescProtocol(),
            write = { throw IllegalStateException("WRITE_NO_RESPONSE unavailable") },
            wait = {},
            connectionState = state
        )
        runBurstPollCycle(
            protocol = VescProtocol(),
            write = {},
            wait = {},
            connectionState = state
        )

        val reported = state.value as ConnectionState.Connected
        assertEquals(0, reported.consecutivePollWriteFailures)
        assertNull(reported.lastPollWriteFailure)
    }
}
