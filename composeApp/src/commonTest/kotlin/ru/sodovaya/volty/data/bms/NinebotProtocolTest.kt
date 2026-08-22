package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures mirror the unencrypted Ninebot One Z frames documented by WheelLog. */
class NinebotProtocolTest {

    @Test
    fun commands_are_read_only_and_transport_is_nordic_uart() {
        val protocol = NinebotProtocol()

        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", protocol.uuids.serviceUuid)
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", protocol.uuids.notifyCharUuid)
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", protocol.uuids.writeCharUuid)
        assertTrue(protocol.handshakeCommands().isEmpty())
        assertTrue(protocol.pollCommands().isEmpty())
        assertEquals(2, protocol.packCount)
        assertNull(protocol.latestData(0))
        assertNull(protocol.latestData(1))
    }

    @Test
    fun fragmented_bms_life_and_cells_are_decoded_with_verified_units() {
        val protocol = NinebotProtocol()
        val life = ByteArray(24).apply {
            putU16(0, 0x0020) // documented DISCHARGE status bit
            putU16(2, 1234) // remaining percentage is at offset 4; offset 2 is capacity
            putU16(4, 73)
            putI16(6, -1234) // Ninebot BMS wire unit is 10 mA
            putU16(8, 5120) // Ninebot BMS wire unit is 10 mV
            this[10] = 45 // temperatures are encoded as Celsius + 20
            this[11] = 50
        }
        val cells = ByteArray(32).apply {
            putU16(0, 4000)
            putU16(2, 4010)
            putU16(4, 3990)
            putU16(6, 4020)
            putU16(8, 4005)
            putU16(10, 4015)
        }

        val lifeFrame = frame(source = 0x11, destination = 0x3e, parameter = 0x30, data = life)
        val cellsFrame = frame(source = 0x11, destination = 0x3e, parameter = 0x40, data = cells)

        protocol.onNotification(lifeFrame.copyOfRange(0, 7))
        assertNull(protocol.latestData(0), "a partial frame must not publish telemetry")
        protocol.onNotification(lifeFrame.copyOfRange(7, lifeFrame.size))
        protocol.onNotification(cellsFrame)

        val data = requireNotNull(protocol.latestData(0))
        assertEquals(51.2f, data.voltage, 0.001f)
        assertEquals(-12.34f, data.current, 0.001f)
        assertEquals(73f, data.soc, 0.001f)
        assertTrue(data.socKnown)
        assertEquals(51.2f * -12.34f, data.power, 0.01f)
        assertEquals(listOf(25f, 30f), data.temperatures)
        assertEquals(listOf(4.0f, 4.01f, 3.99f, 4.02f, 4.005f, 4.015f), data.cellVoltages)
        assertTrue(data.dischargeEnabled)
        assertTrue(data.isConnected)
    }

    @Test
    fun bad_checksum_and_unknown_source_are_ignored() {
        val protocol = NinebotProtocol()
        val data = ByteArray(24)
        val valid = frame(source = 0x11, destination = 0x3e, parameter = 0x30, data = data)
        val corrupt = valid.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }

        protocol.onNotification(corrupt)
        assertNull(protocol.latestData(0))

        protocol.onNotification(frame(source = 0x44, destination = 0x3e, parameter = 0x30, data = data))
        assertNull(protocol.latestData(0))
    }

    @Test
    fun crc_valid_wrong_command_and_destination_are_ignored() {
        val protocol = NinebotProtocol()
        val life = ByteArray(24).apply {
            putU16(4, 73)
            putU16(8, 5120)
        }

        protocol.onNotification(
            frame(
                source = 0x11,
                destination = 0x3e,
                command = 0x02,
                parameter = 0x30,
                data = life
            )
        )
        protocol.onNotification(
            frame(
                source = 0x11,
                destination = 0x09,
                parameter = 0x30,
                data = life
            )
        )

        assertNull(protocol.latestData(0))
    }

    @Test
    fun zero_live_page_does_not_publish_known_motion() {
        val protocol = NinebotProtocol()

        protocol.onNotification(
            frame(
                source = 0x14,
                destination = 0x3e,
                parameter = 0xB0,
                data = ByteArray(32)
            )
        )

        assertNull(protocol.latestMotion(0))
    }

    @Test
    fun zero_bms_cells_do_not_become_earned_zero_volt_cells() {
        val protocol = NinebotProtocol()
        val life = ByteArray(24).apply {
            putU16(4, 73)
            putU16(8, 5120)
        }

        protocol.onNotification(frame(source = 0x11, destination = 0x3e, parameter = 0x30, data = life))
        protocol.onNotification(
            frame(
                source = 0x11,
                destination = 0x3e,
                parameter = 0x40,
                data = ByteArray(32)
            )
        )

        val data = assertNotNull(protocol.latestData(0))
        assertTrue(data.cellVoltages.isEmpty())
        assertTrue(data.cellVoltages.none { it == 0f })
    }

    @Test
    fun crc_valid_all_zero_bms_life_page_does_not_replace_valid_life_telemetry() {
        val protocol = NinebotProtocol()
        val validLife = ByteArray(24).apply {
            putU16(0, 0x0020)
            putU16(4, 73)
            putI16(6, -1234)
            putU16(8, 5120)
            this[10] = 45
            this[11] = 50
        }

        protocol.onNotification(frame(source = 0x11, destination = 0x3e, parameter = 0x30, data = validLife))
        val before = assertNotNull(protocol.latestData(0))
        assertTrue(before.socKnown)
        assertTrue(before.hasPower)

        // This is a complete, CRC-valid life page, unlike the separate zero-cell fixture.
        protocol.onNotification(
            frame(
                source = 0x11,
                destination = 0x3e,
                parameter = 0x30,
                data = ByteArray(24),
            )
        )

        val after = assertNotNull(protocol.latestData(0))
        assertEquals(51.2f, after.voltage, 0.001f)
        assertEquals(-12.34f, after.current, 0.001f)
        assertEquals(73f, after.soc, 0.001f)
        assertTrue(after.socKnown)
        assertTrue(after.hasPower)
        assertEquals(before.power, after.power, 0.001f)
    }

    @Test
    fun crc_valid_all_zero_bms_life_page_is_not_a_first_life_sample() {
        val protocol = NinebotProtocol()

        protocol.onNotification(
            frame(
                source = 0x11,
                destination = 0x3e,
                parameter = 0x30,
                data = ByteArray(24),
            )
        )

        assertNull(protocol.latestData(0))
    }

    @Test
    fun cells_only_page_does_not_publish_a_complete_pack() {
        val protocol = NinebotProtocol()
        val cells = ByteArray(32).apply {
            putU16(0, 4000)
            putU16(2, 4010)
        }

        protocol.onNotification(
            frame(
                source = 0x11,
                destination = 0x3e,
                parameter = 0x40,
                data = cells
            )
        )

        assertNull(protocol.latestData(0))
        assertNull(protocol.latestData(1))
    }

    @Test
    fun wire_bms1_and_bms2_translate_to_public_pack_indices_zero_and_one() {
        val protocol = NinebotProtocol()
        val first = ByteArray(24).apply {
            putU16(4, 61)
            putU16(8, 5000)
        }
        val second = ByteArray(24).apply {
            putU16(4, 82)
            putU16(8, 5200)
        }

        protocol.onNotification(frame(source = 0x11, destination = 0x3e, parameter = 0x30, data = first))
        protocol.onNotification(frame(source = 0x12, destination = 0x3e, parameter = 0x30, data = second))

        assertEquals(50f, assertNotNull(protocol.latestData(0)).voltage, 0.001f)
        assertEquals(52f, assertNotNull(protocol.latestData(1)).voltage, 0.001f)
        assertEquals(61f, assertNotNull(protocol.latestData(0)).soc, 0.001f)
        assertEquals(82f, assertNotNull(protocol.latestData(1)).soc, 0.001f)
    }

    @Test
    fun controller_live_page_keeps_signed_speed_as_magnitude_and_live_current_power_unknown() {
        val protocol = NinebotProtocol()
        val live = ByteArray(32).apply {
            putU16(8, 73) // battery percent; retained as controller evidence only
            putI16(10, -1234) // WheelLog speed unit: 0.01 km/h
            putU32(14, 12_345) // lifetime distance in metres
            putI16(22, 385) // board temperature in 0.1 C
            putU16(24, 5120) // 0.01 V
            putI16(26, 900) // current is deliberately not published by this adapter
        }

        protocol.onNotification(frame(source = 0x14, destination = 0x3e, parameter = 0xB0, data = live))

        val motion = assertNotNull(protocol.latestMotion(0))
        assertEquals(12.34f, motion.speedKmh, 0.001f)
        assertEquals(12.345f, motion.odometerKm, 0.001f)
        assertTrue(motion.hasInputVoltage)
        assertEquals(51.2f, motion.inputVoltageV, 0.001f)
        assertTrue(motion.speedKnown)
        assertTrue(motion.speedKmh >= 0f)
        assertTrue(!motion.hasDuty)
        assertEquals(0f, motion.batteryCurrentA)
        assertTrue(!motion.hasBatteryCurrent)
        assertEquals(0f, motion.powerW)
        assertTrue(!motion.hasPower)
    }

    private fun frame(
        source: Int,
        destination: Int,
        parameter: Int,
        data: ByteArray,
        command: Int = 0x04
    ): ByteArray {
        val body = ByteArray(5 + data.size)
        body[0] = data.size.toByte()
        body[1] = source.toByte()
        body[2] = destination.toByte()
        body[3] = command.toByte()
        body[4] = parameter.toByte()
        data.copyInto(body, destinationOffset = 5)
        var sum = 0
        for (byte in body) sum = (sum + (byte.toInt() and 0xff)) and 0xffff
        val check = sum.inv() and 0xffff
        return byteArrayOf(0x5a, 0xa5.toByte(), *body, check.toByte(), (check ushr 8).toByte())
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putI16(offset: Int, value: Int) = putU16(offset, value and 0xffff)

    private fun ByteArray.putU32(offset: Int, value: Int) {
        putU16(offset, value and 0xffff)
        putU16(offset + 2, value ushr 16)
    }
}
