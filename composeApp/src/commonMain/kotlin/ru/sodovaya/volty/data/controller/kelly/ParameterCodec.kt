package ru.sodovaya.volty.data.controller.kelly

import kotlin.math.pow

enum class ParamSize { BIT, BYTE, WORD }
enum class ParamType { UNSIGNED, HEX, ASCII, SIGNED }

/** Pure ETS parameter codec. It does not communicate with a controller. */
object ParameterCodec {
    fun readParam(data: IntArray, offset: Int, size: ParamSize, position: Int, type: ParamType): String {
        val length = if (size == ParamSize.WORD) position + 1 else 1
        return when (type) {
            ParamType.UNSIGNED -> when (size) {
                ParamSize.BIT -> ((data[offset] / 2.0.pow(position).toInt()) and 1).toString()
                ParamSize.BYTE -> data[offset].toString()
                ParamSize.WORD -> (offset until offset + length).fold(0) { value, index -> value * 256 + data[index] }.toString()
            }
            ParamType.HEX -> (offset until offset + length).joinToString("") { data[it].toString(16).padStart(2, '0') }
            ParamType.ASCII -> (offset until offset + length).joinToString("") { data[it].toChar().toString() }
            ParamType.SIGNED -> data[offset].toByte().toInt().toString()
        }
    }

    fun writeParam(data: IntArray, offset: Int, size: ParamSize, position: Int, type: ParamType, value: String): Boolean =
        when (type) {
            ParamType.UNSIGNED -> when (size) {
                ParamSize.BIT -> when (value) {
                    "1" -> { data[offset] = data[offset] or 2.0.pow(position).toInt(); true }
                    "0" -> { data[offset] = data[offset] and 2.0.pow(position).toInt().inv(); true }
                    else -> false
                }
                ParamSize.BYTE -> { data[offset] = value.toInt(); true }
                ParamSize.WORD -> {
                    var remaining = value.toInt()
                    for (index in offset..offset + position) {
                        val power = 256.0.pow(position - (index - offset)).toInt()
                        data[index] = remaining / power
                        if (index < offset + position) remaining -= data[index] * power
                    }
                    true
                }
            }
            ParamType.HEX -> if (value.length != (position + 1) * 2) false else {
                for (index in offset..offset + position) data[index] = value.substring((index - offset) * 2, (index - offset) * 2 + 2).toInt(16)
                true
            }
            ParamType.ASCII -> if (value.length != position + 1) false else {
                for (index in offset..offset + position) data[index] = value[index - offset].code
                true
            }
            ParamType.SIGNED -> { data[offset] = value.toInt(); true }
        }
}
