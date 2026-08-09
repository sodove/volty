package ru.sodovaya.volty.data.bms.vesc

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VescSetupConfigTest {

    @Test
    fun `the f32auto test encoder round trips through the production reader`() {
        val payload = byteArrayOfF32Auto(0.6f, 1f, 2.5f, 0f)
        val reader = VescReader(payload)

        assertTrue(abs(reader.f32auto() - 0.6f) < 0.001f)
        assertEquals(1f, reader.f32auto())
        assertEquals(2.5f, reader.f32auto())
        assertEquals(0f, reader.f32auto())
    }

    @Test
    fun `a configured controller reports its own wheel geometry`() {
        val cfg = VescSetupConfig.decode(mcconfTempPayload(poles = 30, gear = 1f, diameterM = 0.6f))

        assertEquals(15, cfg?.motorConfig?.polePairs)
        assertEquals(600, cfg?.motorConfig?.wheelDiameterMm)
        assertEquals(1f, cfg?.motorConfig?.gearRatio)
    }

    @Test
    fun `an unconfigured controller offers no geometry rather than a zero one`() {
        val cfg = VescSetupConfig.decode(mcconfTempPayload(poles = 30, gear = 1f, diameterM = 0f))

        assertTrue(cfg != null)
        assertNull(cfg.motorConfig)
    }

    @Test
    fun `a frame short of the geometry is not a config`() {
        assertNull(VescSetupConfig.decode(mcconfTempPayload().copyOf(45)))
    }

    @Test
    fun `another opcode is not this frame`() {
        val wrong = mcconfTempPayload().also { it[0] = VescValues.OPCODE_GET_VALUES.toByte() }

        assertNull(VescSetupConfig.decode(wrong))
    }

    @Test
    fun `our derived speed agrees with the firmware formula for the same config`() {
        val poles = 30
        val diameterM = 0.6f
        val gear = 2.5f
        val eRpm = 3000f
        val firmwareMs = (eRpm / (poles / 2f) / 60f) * diameterM * Math.PI.toFloat() / gear
        val cfg = VescSetupConfig.decode(mcconfTempPayload(poles, gear, diameterM))!!

        val ours = VescValues.derivedSpeedKmh(eRpm, cfg.motorConfig!!)!!

        assertEquals(firmwareMs * 3.6f, ours, 0.01f)
    }

    private fun mcconfTempPayload(
        poles: Int = 30,
        gear: Float = 1f,
        diameterM: Float = 0.6f
    ): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes += VescSetupConfig.OPCODE_GET_MCCONF_TEMP.toByte()
        repeat(10) { appendF32Auto(bytes, 0f) }
        bytes += poles.toByte()
        appendF32Auto(bytes, gear)
        appendF32Auto(bytes, diameterM)
        return bytes.toByteArray()
    }

    private fun byteArrayOfF32Auto(vararg values: Float): ByteArray {
        val bytes = mutableListOf<Byte>()
        values.forEach { appendF32Auto(bytes, it) }
        return bytes.toByteArray()
    }

    /**
     * VESC's auto float has the same bits as an IEEE binary32 for the finite
     * normal values used by this fixed-layout frame. Writing the bits here,
     * instead of copying bytes, keeps the fixture tied to the field values.
     */
    private fun appendF32Auto(out: MutableList<Byte>, value: Float) {
        val bits = value.toBits()
        out += ((bits ushr 24) and 0xFF).toByte()
        out += ((bits ushr 16) and 0xFF).toByte()
        out += ((bits ushr 8) and 0xFF).toByte()
        out += (bits and 0xFF).toByte()
    }
}
