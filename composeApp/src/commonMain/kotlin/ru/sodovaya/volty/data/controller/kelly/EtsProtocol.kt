package ru.sodovaya.volty.data.controller.kelly

/** Read-only high-level ETS monitor protocol. */
class EtsProtocol(
    private val sendAndReceive: suspend (ByteArray) -> ByteArray,
    private val drainStaleData: suspend () -> Unit = {}
) {
    private suspend fun sendWithRetry(txPacket: ByteArray, expectedCmd: Byte, maxAttempts: Int): Result<EtsPacket> {
        var lastError: Throwable? = null
        repeat(maxAttempts) {
            try {
                drainStaleData()
                val result = EtsPacketBuilder.parseRxResponse(sendAndReceive(txPacket), expectedCmd)
                if (result.isSuccess) return result
                lastError = result.exceptionOrNull()
            } catch (error: Exception) {
                lastError = error
            }
        }
        return Result.failure(lastError ?: EtsProtocolException("Failed after $maxAttempts attempts"))
    }

    suspend fun readVersion(): Result<EtsPacket> =
        sendWithRetry(EtsPacketBuilder.buildTxPacket(EtsCommand.CODE_VERSION), EtsCommand.CODE_VERSION, maxAttempts = 2)

    suspend fun readMonitor(): Result<IntArray> {
        val monitorData = IntArray(48)
        for ((index, command) in MonitorDefinitions.MONITOR_COMMANDS.withIndex()) {
            drainStaleData()
            val packet = EtsPacketBuilder.parseRxResponse(sendAndReceive(EtsPacketBuilder.buildTxPacket(command)), command)
                .getOrElse { return Result.failure(it) }
            for (dataIndex in 0 until minOf(packet.dataLength, 16)) {
                monitorData[index * 16 + dataIndex] = packet.data[dataIndex].toInt() and 0xFF
            }
        }
        return Result.success(monitorData)
    }

    suspend fun readPhaseCurrentAD(): Result<IntArray> =
        sendWithRetry(EtsPacketBuilder.buildTxPacket(EtsCommand.GET_PHASE_I_AD), EtsCommand.GET_PHASE_I_AD, maxAttempts = 2)
            .map { packet -> IntArray(10) { packet.data[it].toInt() and 0xFF } }
}
