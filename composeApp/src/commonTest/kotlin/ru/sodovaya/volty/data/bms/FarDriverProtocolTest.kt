package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FarDriverProtocolTest {

    @Test
    fun uses_original_ffe0_service_and_ffec_characteristic_without_commands() {
        val protocol = FarDriverProtocol()

        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", protocol.uuids.serviceUuid)
        assertEquals("0000ffec-0000-1000-8000-00805f9b34fb", protocol.uuids.notifyCharUuid)
        assertEquals("0000ffec-0000-1000-8000-00805f9b34fb", protocol.uuids.writeCharUuid)
        assertTrue(protocol.handshakeCommands().isEmpty())
        assertTrue(protocol.pollCommands().isEmpty())
        assertEquals(1, protocol.controllerCount)
        assertNull(protocol.latestMotion(0))
        assertNull(protocol.latestData(0))
    }

    @Test
    fun new_register_frames_are_reassembled_and_publish_supported_telemetry() {
        val protocol = FarDriverProtocol(
            deriveBattery = true,
            motor = MotorConfig(wheelDiameterMm = 500, gearRatio = 2f)
        )

        val voltageAndCurrent = newFrame(1) {
            u16(2, 720)
            i16(6, 200)
        }
        val phase = newFrame(2) {
            u24(6, 256)
            u24(9, 1024)
        }
        val speedAndFaults = newFrame(0) {
            u16(8, 1200)
            bytes[2] = 1
        }
        val controllerTemp = newFrame(51) { u16(12, 44) }
        val motorTempAndSoc = newFrame(53) {
            u16(2, 52)
            bytes[3] = 80
        }

        protocol.onNotification(voltageAndCurrent.copyOfRange(0, 7))
        protocol.onNotification(
            voltageAndCurrent.copyOfRange(7, 16) +
                phase +
                speedAndFaults.copyOfRange(0, 5)
        )
        protocol.onNotification(
            speedAndFaults.copyOfRange(5, 16) +
                controllerTemp +
                motorTempAndSoc
        )

        val motion = requireNotNull(protocol.latestMotion(0))
        assertEquals(72f, motion.inputVoltageV)
        assertTrue(motion.hasInputVoltage)
        assertEquals(50f, motion.batteryCurrentA)
        assertTrue(motion.hasBatteryCurrent)
        assertEquals(3600f, motion.powerW)
        assertTrue(motion.hasPower)
        assertEquals(31.25f, motion.motorCurrentA)
        assertEquals(1200f, motion.eRpm)
        assertEquals(56.54867f, motion.speedKmh, 0.001f)
        assertEquals(SpeedSource.DERIVED, motion.speedSource)
        assertEquals(44f, motion.escTempC)
        assertEquals(52f, motion.motorTempC)
        assertTrue(motion.hasMotorTemp)
        assertEquals(0.8f, motion.batteryLevelFraction)
        assertEquals(listOf("Motor Hall Error"), motion.faults)
        assertFalse(motion.hasDuty)
        assertFalse(motion.hasDistance)
        assertFalse(motion.hasEnergyCounters)

        val battery = requireNotNull(protocol.latestData(0))
        assertEquals(72f, battery.voltage)
        assertEquals(-50f, battery.current)
        assertTrue(battery.hasCurrent)
        assertEquals(-3600f, battery.power)
        assertTrue(battery.hasPower)
        assertFalse(battery.socKnown)
    }

    @Test
    fun missing_register_evidence_stays_unavailable_and_does_not_create_pack() {
        val protocol = FarDriverProtocol(deriveBattery = true)

        protocol.onNotification(newFrame(0) { u16(8, 100) })

        val motion = requireNotNull(protocol.latestMotion(0))
        assertFalse(motion.hasInputVoltage)
        assertFalse(motion.hasBatteryCurrent)
        assertFalse(motion.hasPower)
        assertEquals(0f, motion.inputVoltageV)
        assertEquals(0f, motion.batteryCurrentA)
        assertNull(protocol.latestData(0))
    }

    @Test
    fun valid_legacy_frames_decode_voltage_current_and_speed() {
        val protocol = FarDriverProtocol(motor = MotorConfig(wheelDiameterMm = 500))

        protocol.onNotification(legacyFrame(1) {
            u16(2, 600)
            i16(4, -20)
        })
        protocol.onNotification(legacyFrame(0) { u16(6, 900) })

        val motion = requireNotNull(protocol.latestMotion(0))
        assertEquals(60f, motion.inputVoltageV)
        assertEquals(-5f, motion.batteryCurrentA)
        assertEquals(-300f, motion.powerW)
        assertEquals(900f, motion.eRpm)
        assertEquals(84.823f, motion.speedKmh, 0.001f)
        assertEquals(SpeedSource.DERIVED, motion.speedSource)
    }

    @Test
    fun invalid_checksums_are_rejected_and_parser_resynchronises() {
        val protocol = FarDriverProtocol()
        val invalid = newFrame(1) { u16(2, 720) }.also { it[14] = (it[14] + 1).toByte() }
        val valid = newFrame(1) {
            u16(2, 840)
            i16(6, 100)
        }

        protocol.onNotification(byteArrayOf(0x12) + invalid + valid)

        assertEquals(84f, protocol.latestMotion(0)?.inputVoltageV)

        protocol.reset()
        protocol.onNotification(legacyFrame(1) { u16(2, 600) }.also { it[15] = (it[15] + 1).toByte() })
        assertNull(protocol.latestMotion(0))
    }

    @Test
    fun reset_clears_buffered_fragments_and_latest_samples() {
        val protocol = FarDriverProtocol()
        val frame = newFrame(1) { u16(2, 720) }

        protocol.onNotification(frame.copyOfRange(0, 5))
        protocol.reset()
        protocol.onNotification(frame.copyOfRange(5, 16))

        assertNull(protocol.latestMotion(0))
        assertNull(protocol.latestData(0))
    }

    private fun newFrame(index: Int, fill: FrameBuilder.() -> Unit = {}): ByteArray =
        FrameBuilder(index).apply(fill).buildNew()

    private fun legacyFrame(command: Int, fill: FrameBuilder.() -> Unit = {}): ByteArray =
        FrameBuilder(command).apply(fill).buildLegacy()

    private class FrameBuilder(private val id: Int) {
        val bytes = ByteArray(12)

        fun u16(offset: Int, value: Int) {
            val payloadOffset = offset - 2
            bytes[payloadOffset] = (value ushr 8).toByte()
            bytes[payloadOffset + 1] = value.toByte()
        }

        fun i16(offset: Int, value: Int) = u16(offset, value and 0xffff)

        fun u24(offset: Int, value: Int) {
            val payloadOffset = offset - 2
            bytes[payloadOffset] = (value ushr 16).toByte()
            bytes[payloadOffset + 1] = (value ushr 8).toByte()
            bytes[payloadOffset + 2] = value.toByte()
        }

        fun buildNew(): ByteArray {
            val frame = byteArrayOf(0xAA.toByte(), (0x80 or id).toByte()) + bytes
            val crc = crc16(frame)
            return frame + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
        }

        fun buildLegacy(): ByteArray {
            val frame = byteArrayOf(0xAA.toByte(), id.toByte()) + bytes
            val sum = frame.sumOf { it.toInt() and 0xff }
            return frame + byteArrayOf((sum ushr 8).toByte(), sum.toByte())
        }
    }

    private companion object {
        fun crc16(bytes: ByteArray): Int {
        var crc = 0x7F3C
        bytes.forEach { value ->
            crc = crc xor (value.toInt() and 0xff)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xffff
        }
    }
}
