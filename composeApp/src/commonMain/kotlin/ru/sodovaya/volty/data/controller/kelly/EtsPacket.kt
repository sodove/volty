package ru.sodovaya.volty.data.controller.kelly

data class EtsPacket(
    val command: Byte,
    val dataLength: Int,
    val data: ByteArray,
    val checksum: Byte
) {
    override fun equals(other: Any?): Boolean = other is EtsPacket &&
        command == other.command && dataLength == other.dataLength &&
        data.contentEquals(other.data) && checksum == other.checksum

    override fun hashCode(): Int = 31 * (31 * (31 * command.toInt() + dataLength) + data.contentHashCode()) + checksum.toInt()
}

object EtsPacketBuilder {
    const val MAX_DATA_LENGTH = 16
    const val MAX_PACKET_SIZE = 19

    fun buildTxPacket(command: Byte, data: ByteArray = byteArrayOf()): ByteArray {
        require(data.size <= MAX_DATA_LENGTH) { "Data exceeds max length of $MAX_DATA_LENGTH" }
        return ByteArray(data.size + 3).also { packet ->
            packet[0] = command
            packet[1] = data.size.toByte()
            if (data.isEmpty()) {
                packet[2] = command
            } else {
                data.copyInto(packet, 2)
                packet[data.size + 2] = EtsChecksum.calculate(packet, 0, data.size + 2)
            }
        }
    }

    fun parseRxPacket(raw: ByteArray): EtsPacket {
        require(raw.size >= 3) { "Packet too short: ${raw.size} bytes" }
        val dataLength = raw[1].toInt() and 0xFF
        val clampedLength = minOf(dataLength, MAX_DATA_LENGTH)
        require(raw.size >= clampedLength + 3) {
            "Packet incomplete: expected ${clampedLength + 3}, got ${raw.size}"
        }
        return EtsPacket(raw[0], clampedLength, raw.copyOfRange(2, 2 + clampedLength), raw[clampedLength + 2])
    }

    fun parseRxResponse(raw: ByteArray, expectedCmd: Byte): Result<EtsPacket> = try {
        val packet = parseRxPacket(raw)
        when {
            packet.command != expectedCmd -> Result.failure(EtsProtocolException("Command mismatch"))
            EtsChecksum.calculate(raw, 0, packet.dataLength + 2) != packet.checksum ->
                Result.failure(EtsProtocolException("Checksum mismatch"))
            else -> Result.success(packet)
        }
    } catch (error: IllegalArgumentException) {
        Result.failure(EtsProtocolException(error.message ?: "Invalid packet"))
    }
}

class EtsProtocolException(message: String) : Exception(message)
