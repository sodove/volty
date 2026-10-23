package ru.sodovaya.volty.data.bms

/**
 * A Begode T4 (24S, no smart-BMS cell pages) as its rider captured it on 2026-10-23, chronological,
 * fragmented as the wheel sends it: live 0x00 at ~60.5 V on the 67.2 V reference, 0x04, 0x07, and
 * 0x01 branch frames carrying the real pack voltage (0x038C = 90.8 V). Replayable on the emulator
 * with tools/begode-emulator.
 */
object BegodeT4Fixture {
    val notifications: List<ByteArray> = listOf(
        "55 AA 17 A5 00 00 00 4B 00 00 FF 74 EE A5 00 80 00 05 00 18",
        "5A 5A 5A 5A 55 AA 00 50 00 00 03 8C 00 00 00 00 00 00 00 00",
        "00 00 01 00 5A 5A 5A 5A 55 AA 00 2A 90 60 28 00 1B EF 00 C8",
        "00 00 00 00 00 12 04 18 5A 5A 5A 5A 55 AA 00 18 00 00 00 00",
        "00 00 00 00 00 00 00 00 00 00 07 18 5A 5A 5A 5A",
        "55 AA 17 A5 00 00 00 4B 00 00 FF 74 EE AA 00 80 00 05 00 18",
        "5A 5A 5A 5A 55 AA 00 50 00 00 03 8C 00 00 00 00 00 00 00 00",
        "00 00 01 00 5A 5A 5A 5A 55 AA 00 2A 90 60 28 00 1B EF 00 C8",
        "00 00 00 00 00 12 04 18 5A 5A 5A 5A 55 AA 00 13 00 00 00 00",
        "00 00 00 00 00 00 00 00 00 00 07 18 5A 5A 5A 5A 55 AA 17 A5",
        "00 00 00 4B 00 00 FF 60 EE A8 00 80 00 05 00 18 5A 5A 5A 5A",
        "55 AA 00 50 00 00 03 8C 00 00 00 00 00 00 00 00 00 00 01 00",
        "5A 5A 5A 5A 55 AA 00 2A 90 60 28 00 1B EE 00 C8 00 00 00 00",
        "00 12 04 18 5A 5A 5A 5A 55 AA 00 1D 00 00 00 00 00 00 00 00",
        "00 00 00 00 00 00 07 18 5A 5A 5A 5A 55 AA 17 A5 00 00 00 4B",
        "00 00 FF 6A EE A6 00 80 00 05 00 18 5A 5A 5A 5A 55 AA 00 50",
        "00 00 03 8C 00 00 00 00 00 00 00 00 00 00 01 00 5A 5A 5A 5A",
        "55 AA 00 2A 90 60 28 00 1B EE 00 C8 00 00 00 00 00 12 04 18",
        "5A 5A 5A 5A 55 AA 00 1B 00 00 00 00 00 00 00 00 00 00 00 00",
        "00 00 07 18 5A 5A 5A 5A",
        "55 AA 17 A5 00 00 00 4B 00 00 FF 74 EE A9 00 80 00 05 00 18",
        "5A 5A 5A 5A 55 AA 00 50 00 00 03 8C 00 00 00 00 00 00 00 00",
        "00 00 01 00 5A 5A 5A 5A 55 AA 00 2A 90 60 28 00 1B EE 00 C8",
        "00 00 00 00 00 12 04 18 5A 5A 5A 5A 55 AA 00 1E 00 00 00 00",
        "00 00 00 00 00 00 00 00 00 00 07 18 5A 5A 5A 5A",
        "55 AA 17 A5 00 00 00 4B 00 00 FF 74 EE AA 00 80 00 05 00 18",
        "5A 5A 5A 5A 55 AA 00 50 00 00 03 8C 00 00 00 00 00 00 00 00",
        "00 00 01 00 5A 5A 5A 5A 55 AA 00 2A 90 60 28 00 1B ED 00 C8",
        "00 00 00 00 00 12 04 18 5A 5A 5A 5A 55 AA 00 22 00 00 00 00",
        "00 00 00 00 00 00 00 00 00 00 07 18 5A 5A 5A 5A",
        "55 AA 17 9E 00 00 00 4B 00 00 FF 74 EE A5 00 80 00 05 00 18",
        "5A 5A 5A 5A 55 AA 00 50 00 00 03 8B 00 00 00 00 00 00 00 00",
        "00 00 01 00 5A 5A 5A 5A 55 AA 00 2A 90 60 28 00 1B ED 00 C8"
    ).map { hex -> hex.split(" ").map { it.toInt(16).toByte() }.toByteArray() }
}
