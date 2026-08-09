package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.VescProtocol
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescTestFrames
import ru.sodovaya.volty.domain.model.MotorConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
