package ru.sodovaya.volty.data.controller.kelly

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EtsProtocolTest {
    @Test
    fun packetBuilder_preservesEmptyCommandChecksum() {
        assertContentEquals(
            byteArrayOf(0x11, 0x00, 0x11),
            EtsPacketBuilder.buildTxPacket(EtsCommand.CODE_VERSION)
        )
    }

    @Test
    fun packetBuilder_sumsCommandLengthAndPayloadModulo256() {
        assertContentEquals(
            byteArrayOf(0xF2.toByte(), 0x03, 0x00, 0x10, 0x00, 0x05),
            EtsPacketBuilder.buildTxPacket(0xF2.toByte(), byteArrayOf(0x00, 0x10, 0x00))
        )
    }

    @Test
    fun packetParser_rejectsWrongChecksum() {
        val result = EtsPacketBuilder.parseRxResponse(
            byteArrayOf(0x3A, 0x02, 0x01, 0x09, 0xFF.toByte()),
            EtsCommand.USER_MONITOR1
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun packetParser_rejectsWrongCommand() {
        val result = EtsPacketBuilder.parseRxResponse(
            byteArrayOf(0x3B, 0x00, 0x3B),
            EtsCommand.USER_MONITOR1
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun packetParser_returnsCommandLengthPayloadAndChecksumFromValidResponse() {
        val raw = byteArrayOf(0x11, 0x02, 0x01, 0x09, 0x1D)

        val packet = EtsPacketBuilder.parseRxResponse(raw, EtsCommand.CODE_VERSION).getOrThrow()

        assertEquals(EtsCommand.CODE_VERSION, packet.command)
        assertEquals(2, packet.dataLength)
        assertContentEquals(byteArrayOf(0x01, 0x09), packet.data)
        assertEquals(0x1D, packet.checksum)
    }

    @Test
    fun checksum_usesSignedBytesAndTruncatesToByte() {
        assertEquals(
            0xFD.toByte(),
            EtsChecksum.calculate(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 0, 3)
        )
    }

    @Test
    fun monitorCommands_areTheThreeKellyMonitorRequests() {
        assertContentEquals(
            byteArrayOf(0x3A, 0x3B, 0x3C),
            MonitorDefinitions.MONITOR_COMMANDS
        )
    }

    @Test
    fun monitorDefinitions_decodeBigEndianMotorSpeed() {
        val monitorData = IntArray(48)
        monitorData[18] = 0x01
        monitorData[19] = 0x2C

        assertEquals("300", MonitorDefinitions.readMonitorValues(monitorData).getValue("Motor Speed"))
    }

    @Test
    fun readMonitor_sendsThreeCommandsInOrderAndConcatenatesTheirPayloads() = runTest {
        val requests = mutableListOf<ByteArray>()
        val protocol = EtsProtocol(sendAndReceive = { request ->
            requests += request.copyOf()
            val data = ByteArray(16) { (requests.size * 16 - 16 + it).toByte() }
            ByteArray(19).also { response ->
                response[0] = request[0]
                response[1] = data.size.toByte()
                data.copyInto(response, 2)
                response[18] = EtsChecksum.calculate(response, 0, 18)
            }
        })

        val monitorData = protocol.readMonitor().getOrThrow()

        assertEquals(
            listOf(
                byteArrayOf(0x3A, 0x00, 0x3A).toList(),
                byteArrayOf(0x3B, 0x00, 0x3B).toList(),
                byteArrayOf(0x3C, 0x00, 0x3C).toList()
            ),
            requests.map(ByteArray::toList)
        )
        assertContentEquals(IntArray(48) { it }, monitorData)
    }
}
