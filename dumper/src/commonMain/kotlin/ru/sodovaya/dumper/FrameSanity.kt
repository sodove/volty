package ru.sodovaya.dumper

/**
 * Counts Begode frames in a raw notification stream, and nothing else.
 *
 * Pure: no BLE, no files, no decoding of voltages, currents or temperatures.
 * It reads exactly two bytes at known offsets — the frame type and the frame
 * number — because those are the open questions the dump exists to answer.
 * Interpreting the payload is BegodeProtocol's job.
 */
class FrameSanity {

    data class Observations(
        val notifications: Int,
        val bytes: Int,
        val frames: Int,
        val frameTypes: Set<Int>,
        val bmsNums: Set<Int>,
        /** Highest packet index seen on a cell frame, or -1 when none arrived. */
        val maxCellPacket: Int
    )

    private companion object {
        const val FRAME_SIZE = 24
        const val TYPE_INDEX = 18
        const val NUM_INDEX = 19
        const val TAIL_START = 20
        const val HEADER_0 = 0x55
        const val HEADER_1 = 0xAA
        const val TAIL_BYTE = 0x5A
        const val TYPE_BMS = 0x01
        const val TYPE_CELLS_1 = 0x02
        const val TYPE_CELLS_2 = 0x03
    }

    private val buffer = ArrayList<Int>()

    private var notifications = 0
    private var bytes = 0
    private var frames = 0
    private val frameTypes = mutableSetOf<Int>()
    private val bmsNums = mutableSetOf<Int>()
    private var maxCellPacket = -1

    fun feed(chunk: ByteArray) {
        notifications++
        bytes += chunk.size
        for (b in chunk) buffer.add(b.toInt() and 0xFF)
        drain()
    }

    fun observations(): Observations = Observations(
        notifications = notifications,
        bytes = bytes,
        frames = frames,
        frameTypes = frameTypes.toSet(),
        bmsNums = bmsNums.toSet(),
        maxCellPacket = maxCellPacket
    )

    fun reset() {
        buffer.clear()
        notifications = 0
        bytes = 0
        frames = 0
        frameTypes.clear()
        bmsNums.clear()
        maxCellPacket = -1
    }

    /**
     * Consume every complete frame currently buffered. On a header whose tail
     * does not check out, advance by a single byte rather than a whole frame:
     * a 0x55 0xAA pair can legitimately occur inside payload data, and skipping
     * 24 bytes on a false positive would swallow the real frame behind it.
     */
    private fun drain() {
        var i = 0
        while (true) {
            val start = indexOfHeader(from = i) ?: break
            if (buffer.size - start < FRAME_SIZE) {
                i = start
                break
            }
            if (hasValidTail(start)) {
                record(start)
                i = start + FRAME_SIZE
            } else {
                i = start + 1
            }
        }
        // Drop everything before the first byte still in play, so the buffer
        // does not grow without bound across a long recording.
        if (i > 0) repeat(minOf(i, buffer.size)) { buffer.removeAt(0) }
    }

    private fun indexOfHeader(from: Int): Int? {
        var i = from
        while (i + 1 < buffer.size) {
            if (buffer[i] == HEADER_0 && buffer[i + 1] == HEADER_1) return i
            i++
        }
        return null
    }

    private fun hasValidTail(start: Int): Boolean =
        (TAIL_START until FRAME_SIZE).all { buffer[start + it] == TAIL_BYTE }

    private fun record(start: Int) {
        frames++
        val type = buffer[start + TYPE_INDEX]
        val num = buffer[start + NUM_INDEX]
        frameTypes.add(type)
        when (type) {
            TYPE_BMS -> bmsNums.add(num)
            TYPE_CELLS_1, TYPE_CELLS_2 -> if (num > maxCellPacket) maxCellPacket = num
        }
    }
}
