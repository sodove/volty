package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.model.SpeedSource

class KingSongProtocolTest {

    @Test
    fun `read only stream has no commands and exposes FFE0 FFE1`() {
        val protocol = KingSongProtocol()

        assertEquals(emptyList(), protocol.handshakeCommands())
        assertEquals(emptyList(), protocol.pollCommands())
        assertEquals(0L, protocol.pollIntervalMs)
        assertEquals(2, protocol.packCount)
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", protocol.uuids.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", protocol.uuids.notifyCharUuid)
    }

    @Test
    fun `A9 live frame is parsed across split notifications and noise`() {
        val protocol = KingSongProtocol()
        val frame = liveFrame(
            voltageCv = 12621,
            speedCentiKmh = 1675,
            distanceMeters = 1_234_000,
            currentCentiA = -250,
            temperatureCentiC = 2150
        )

        protocol.onNotification(
            byteArrayOf(0x13, 0x37, 0xAA.toByte()) + frame.copyOfRange(0, 7)
        )
        assertNull(protocol.latestData())
        protocol.onNotification(frame.copyOfRange(7, frame.size))

        val data = assertNotNull(protocol.latestData())
        assertEquals(126.21f, data.voltage, 0.001f)
        assertEquals(-2.50f, data.current, 0.001f)
        assertTrue(data.hasCurrent)
        assertFalse(data.hasPower)
        assertFalse(data.socKnown, "A9 byte 15 is the E0 mode marker, not a proven SoC")
        assertEquals(listOf(21.5f), data.temperatures)
        assertTrue(data.cellVoltages.isEmpty())

        val motion = assertNotNull(protocol.latestMotion(0))
        assertEquals(16.75f, motion.speedKmh, 0.001f)
        assertEquals(SpeedSource.REPORTED, motion.speedSource)
        assertEquals(126.21f, motion.inputVoltageV, 0.001f)
        assertEquals(2.50f, motion.batteryCurrentA, 0.001f)
        assertEquals(1_234f, motion.odometerKm, 0.001f)
        assertEquals(0f, motion.tripKm, 0.001f)
        assertFalse(motion.hasDuty)
    }

    @Test
    fun `bad tail is rejected like an invalid frame checksum and parser resynchronises`() {
        val protocol = KingSongProtocol()
        val invalid = liveFrame(voltageCv = 12_000).also { it[18] = 0x00 }
        val valid = liveFrame(voltageCv = 12_100)

        protocol.onNotification(invalid + valid)

        val data = assertNotNull(protocol.latestData())
        assertEquals(121f, data.voltage, 0.001f)
    }

    @Test
    fun `smart BMS summary maps voltage current and proven state of charge`() {
        val protocol = KingSongProtocol()
        protocol.onNotification(bmsFrame(0xF1, 0x00).apply {
            putLe(2, 12546)
            putLe(4, -340)
            putLe(6, 800)
            putLe(8, 1000)
            putLe(10, 17)
        })

        val data = assertNotNull(protocol.latestData(0))
        assertEquals(125.46f, data.voltage, 0.001f)
        assertEquals(-3.40f, data.current, 0.001f)
        assertEquals(80f, data.soc, 0.001f)
        assertTrue(data.socKnown)
        assertFalse(data.hasPower)
        assertTrue(data.cellVoltages.isEmpty())
    }

    @Test
    fun `temperature and five cell pages are accumulated without inventing voltage`() {
        val protocol = KingSongProtocol()
        // Cell pages before summary are retained but do not publish a zero
        // voltage sample.
        protocol.onNotification(bmsFrame(0xF1, 0x02).apply {
            for (i in 0 until 7) putLe(2 + i * 2, 3_300 + i)
        })
        assertNull(protocol.latestData(0))

        protocol.onNotification(bmsFrame(0xF1, 0x01).apply {
            listOf(2_980, 2_990, 3_000, 3_010, 3_020, 3_030, 3_040)
                .forEachIndexed { i, raw -> putLe(2 + i * 2, raw) }
        })
        protocol.onNotification(bmsFrame(0xF1, 0x00).apply {
            putLe(2, 84_00)
            putLe(4, 0)
            putLe(6, 500)
            putLe(8, 1000)
        })

        val data = assertNotNull(protocol.latestData(0))
        assertEquals(84f, data.voltage, 0.001f)
        assertEquals(50f, data.soc, 0.001f)
        assertEquals(listOf(25f, 26f, 27f, 28f, 29f, 30f, 31f), data.temperatures)
        assertEquals(7, data.cellVoltages.size)
        assertEquals(3.300f, data.cellVoltages.first(), 0.001f)

        // Four more pages make 30 cells; values beyond the physical 30-cell
        // limit are ignored rather than surfaced as fabricated cells.
        for (page in 3..6) {
            protocol.onNotification(bmsFrame(0xF1, page).apply {
                for (i in 0 until 7) putLe(2 + i * 2, 3_400 + page * 10 + i)
            })
        }
        assertEquals(30, assertNotNull(protocol.latestData(0)).cellVoltages.size)
    }

    @Test
    fun `F2 summary is exposed as the second pack and does not overwrite F1`() {
        val protocol = KingSongProtocol()
        protocol.onNotification(bmsFrame(0xF1, 0x00).apply {
            putLe(2, 12500); putLe(4, 100); putLe(6, 900); putLe(8, 1000)
        })
        protocol.onNotification(bmsFrame(0xF2, 0x00).apply {
            putLe(2, 12400); putLe(4, -200); putLe(6, 400); putLe(8, 800)
        })

        assertEquals(125f, assertNotNull(protocol.latestData(0)).voltage, 0.001f)
        assertEquals(124f, assertNotNull(protocol.latestData(1)).voltage, 0.001f)
        assertNull(protocol.latestData(2))
    }

    @Test
    fun `reset drops buffered bytes cached packs and motion`() {
        val protocol = KingSongProtocol()
        val frame = liveFrame(voltageCv = 12600)
        protocol.onNotification(frame.copyOfRange(0, 4))
        protocol.reset()
        protocol.onNotification(frame.copyOfRange(4, frame.size))
        assertNull(protocol.latestData())
        assertNull(protocol.latestMotion(0))
    }

    private fun liveFrame(
        voltageCv: Int,
        speedCentiKmh: Int = 0,
        distanceMeters: Long = 0,
        currentCentiA: Int = 0,
        temperatureCentiC: Int = 20_00
    ): ByteArray = frame(0xA9, 0x14).apply {
        putLe(2, voltageCv)
        putLe(4, speedCentiKmh)
        putLe32(6, distanceMeters)
        putLe(10, currentCentiA)
        putLe(12, temperatureCentiC)
        this[15] = 0xE0.toByte()
    }

    private fun bmsFrame(type: Int, page: Int): ByteArray = frame(type, page)

    private fun frame(type: Int, page: Int): ByteArray = ByteArray(20).apply {
        this[0] = 0xAA.toByte()
        this[1] = 0x55.toByte()
        this[16] = type.toByte()
        this[17] = page.toByte()
        this[18] = 0x5A.toByte()
        this[19] = 0x5A.toByte()
    }

    private fun ByteArray.putLe(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }

    private fun ByteArray.putLe32(offset: Int, value: Long) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
        this[offset + 2] = (value shr 16).toByte()
        this[offset + 3] = (value shr 24).toByte()
    }
}
