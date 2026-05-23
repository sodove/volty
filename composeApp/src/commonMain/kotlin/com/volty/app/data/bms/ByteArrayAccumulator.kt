package com.volty.app.data.bms

/**
 * Mutable byte buffer that supports append, trim-leading, and reset.
 * Used for accumulating BLE notification chunks.
 */
class ByteArrayAccumulator {
    private var data = ByteArray(0)

    fun append(chunk: ByteArray) { data = data + chunk }

    fun toByteArray(): ByteArray = data

    fun trimLeading(count: Int) {
        data = if (count >= data.size) ByteArray(0) else data.copyOfRange(count, data.size)
    }

    fun reset() { data = ByteArray(0) }

    val size: Int get() = data.size
}
