package ru.sodovaya.volty.data.bms.vesc

/**
 * **The one encoder for VESC telemetry payloads in this suite.**
 *
 * Three test files hand-assembled these two payloads from the field tables, each
 * with its own copy of the big-endian writers and its own transcription of the
 * field ORDER — `VescValuesTest`, `VescGatewayProtocolTest`, and (as of `I` Task
 * 10) the cross-layer consumption test. Divergent CONSTANTS are the point of
 * those files and stay theirs; a divergent field order would mean one of them
 * silently testing a different wire than the decoder reads, which is the failure
 * mode a hand-copied encoder has.
 *
 * So: this object owns the layout and the two writers, every parameter is named
 * after its wire field, and each call site keeps a thin wrapper supplying its own
 * numbers. The defaults here are a healthy uBox — the ones `VescValuesTest` has
 * always used — so a wrapper only names what it actually varies.
 *
 * **Payloads, not frames.** `VescValuesTest` decodes payloads directly; the other
 * two wrap them in [VescPacket.frame]. Framing is the caller's, so this object
 * cannot accidentally become the reason a test agrees with an encoder of ours
 * rather than with the wire.
 */
object VescTestFrames {

    fun i16(o: MutableList<Byte>, v: Int) {
        o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte()
    }

    fun i32(o: MutableList<Byte>, v: Int) {
        o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
        o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte()
    }

    /**
     * `COMM_GET_VALUES_SETUP` (47), in the order [VescValues.decodeSetupValues]
     * reads it. Defaults decode to 52.0/68.0 °C, -82.5/52.4 A, 76.0 %, 47.0 km/h,
     * 78.2 V, 84 %, 15.4 Ah, 980.0 Wh, 1284.6 km.
     *
     * [trailing] is whatever follows `fault_code` on the wire — the firmware
     * sends a `vesc_id` byte this decoder never reads, and one test asserts the
     * decode survives it. Empty by default so a caller that does not care about
     * that byte does not have to know it exists.
     */
    fun setupPayload(
        tempMosRaw: Int = 520,
        tempMotorRaw: Int = 680,
        currentMotorRaw: Int = -8_250,
        currentInRaw: Int = 5_240,
        dutyRaw: Int = 760,
        rpm: Int = 12_000,
        speedMsRaw: Int = 13_056,
        vInRaw: Int = 782,
        battLevelRaw: Int = 840,
        ampHoursRaw: Int = 154_000,
        ampHoursChgRaw: Int = 21_000,
        wattHoursRaw: Int = 9_800_000,
        wattHoursChgRaw: Int = 1_200_000,
        tachMRaw: Int = 12_400_000,
        tachAbsMRaw: Int = 1_284_600_000,
        positionRaw: Int = 0,
        faultCode: Int = 0,
        trailing: ByteArray = ByteArray(0)
    ): ByteArray {
        val o = mutableListOf<Byte>()
        o += VescValues.OPCODE_GET_VALUES_SETUP.toByte()
        i16(o, tempMosRaw); i16(o, tempMotorRaw)
        i32(o, currentMotorRaw); i32(o, currentInRaw)
        i16(o, dutyRaw)
        i32(o, rpm)
        i32(o, speedMsRaw)
        i16(o, vInRaw); i16(o, battLevelRaw)
        i32(o, ampHoursRaw); i32(o, ampHoursChgRaw)
        i32(o, wattHoursRaw); i32(o, wattHoursChgRaw)
        i32(o, tachMRaw); i32(o, tachAbsMRaw)
        i32(o, positionRaw)
        o += faultCode.toByte()
        return o.toByteArray() + trailing
    }

    /**
     * `COMM_GET_VALUES` (4), in the order [VescValues.decodeValues] reads it.
     *
     * [tachRaw] / [tachAbsRaw] are raw tachometer COUNTS here, not metres — the
     * decoder deliberately does not publish them as distances, which is why the
     * defaults are small numbers rather than the SETUP frame's millimetres.
     */
    fun valuesPayload(
        tempMosRaw: Int = 520,
        tempMotorRaw: Int = 680,
        currentMotorRaw: Int = -8_250,
        currentInRaw: Int = 5_240,
        idRaw: Int = 0,
        iqRaw: Int = 0,
        dutyRaw: Int = 760,
        rpm: Int = 10_000,
        vInRaw: Int = 782,
        ampHoursRaw: Int = 154_000,
        ampHoursChgRaw: Int = 21_000,
        wattHoursRaw: Int = 9_800_000,
        wattHoursChgRaw: Int = 1_200_000,
        tachRaw: Int = 1_000,
        tachAbsRaw: Int = 2_000,
        faultCode: Int = 0
    ): ByteArray {
        val o = mutableListOf<Byte>()
        o += VescValues.OPCODE_GET_VALUES.toByte()
        i16(o, tempMosRaw); i16(o, tempMotorRaw)
        i32(o, currentMotorRaw); i32(o, currentInRaw)
        i32(o, idRaw); i32(o, iqRaw)
        i16(o, dutyRaw)
        i32(o, rpm)
        i16(o, vInRaw)
        i32(o, ampHoursRaw); i32(o, ampHoursChgRaw)
        i32(o, wattHoursRaw); i32(o, wattHoursChgRaw)
        i32(o, tachRaw); i32(o, tachAbsRaw)
        o += faultCode.toByte()
        return o.toByteArray()
    }
}
