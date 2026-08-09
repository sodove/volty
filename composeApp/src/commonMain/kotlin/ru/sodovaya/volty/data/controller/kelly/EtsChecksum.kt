package ru.sodovaya.volty.data.controller.kelly

object EtsChecksum {
    fun calculate(data: ByteArray, offset: Int, length: Int): Byte {
        var sum = 0
        for (index in offset until offset + length) sum += data[index]
        return sum.toByte()
    }
}
