package ru.sodovaya.volty.data.controller.kelly

/** ETS commands used by Volty's read-only Kelly monitor. */
object EtsCommand {
    const val CODE_VERSION: Byte = 0x11
    const val GET_PHASE_I_AD: Byte = 0x35
    const val USER_MONITOR1: Byte = 0x3A
    const val USER_MONITOR2: Byte = 0x3B
    const val USER_MONITOR3: Byte = 0x3C
}
