package ru.sodovaya.volty.data.bms

import ru.sodovaya.volty.domain.model.DeviceFact
import ru.sodovaya.volty.domain.model.DeviceFactKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 8: the ledger's counters and last rejection as device facts. Real dump-3 frames, edited as in [BegodeOdometerWitnessTest]. */
class BegodeRejectionFactsTest {
    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private val liveBase = bytes("55 AA 19 06 F7 43 00 6A 00 65 FE 5C F1 D2 08 A0 00 05 00 18 5A 5A 5A 5A")
    private val odoBase = bytes("55 AA 00 2A F8 A4 28 00 1C 20 00 C8 00 00 01 00 00 12 04 18 5A 5A 5A 5A")
    private fun live(powerOn: Int) = liveBase.copyOf().also { it[8] = (powerOn shr 8).toByte(); it[9] = powerOn.toByte() }
    private fun odo(m: Long) = odoBase.copyOf().also {
        it[2] = (m shr 24).toByte(); it[3] = (m shr 16).toByte(); it[4] = (m shr 8).toByte(); it[5] = m.toByte()
    }
    private val base = 2_816_164L
    private fun BegodeProtocol.pair(powerOn: Int, odometer: Long) { onNotification(live(powerOn)); onNotification(odo(odometer)) }
    private fun List<DeviceFact>.value(key: DeviceFactKey) = singleOrNull { it.key == key }?.value

    @Test fun aHealthyWheelReportsZeroRejectionsAndZeroRebases() {
        val p = BegodeProtocol(); BegodeT4Fixture.notifications.forEach { p.onNotification(it) }
        p.pair(101, base); p.pair(108, base + 7)
        val facts = p.deviceFacts()
        assertEquals("0", facts.value(DeviceFactKey.REJECTED_FRAMES))
        assertEquals("0", facts.value(DeviceFactKey.ODOMETER_REBASED))
        assertNull(facts.value(DeviceFactKey.REJECTED_LAST_RULE))
        assertNull(facts.value(DeviceFactKey.REJECTED_LAST_DETAIL))
        assertNull(facts.value(DeviceFactKey.ODOMETER_WITNESS))
    }

    @Test fun nothingIsListedBeforeAnyOdometerFrame() {
        val p = BegodeProtocol(); p.onNotification(live(101))
        assertNull(p.deviceFacts().value(DeviceFactKey.REJECTED_FRAMES))
    }

    @Test fun aTornNeedleShowsInTheFactsAndTheCachedListIsRebuilt() {
        val p = BegodeProtocol()
        p.pair(101, base)
        val before = p.deviceFacts()
        assertEquals("0", before.value(DeviceFactKey.REJECTED_FRAMES))
        p.onNotification(live(108)); p.onNotification(odo(base + 7 + 65_536))
        p.pair(115, base + 14) // the needle is decided when the next 0x04 arrives
        val facts = p.deviceFacts()
        assertEquals("1", facts.value(DeviceFactKey.REJECTED_FRAMES))
        assertEquals("odometer_witness", facts.value(DeviceFactKey.REJECTED_LAST_RULE))
        assertEquals("2816164 m / +7 m", facts.value(DeviceFactKey.REJECTED_LAST_DETAIL))
    }

    @Test fun resetZeroesTheFactsCountersAndKeepsTheRing() {
        val p = BegodeProtocol()
        p.pair(101, base); p.onNotification(live(108)); p.onNotification(odo(base + 7 + 65_536))
        p.pair(115, base + 14)
        assertEquals("1", p.deviceFacts().value(DeviceFactKey.REJECTED_FRAMES))
        p.reset()
        assertEquals(0L, p.rejectedFrameCount)
        assertNull(p.lastRejectedFrame, "the connection's newest rejection goes with the connection")
        assertEquals(1, p.rejectedFrames().size, "the ring survives")
        p.pair(101, base)
        val facts = p.deviceFacts()
        assertEquals("0", facts.value(DeviceFactKey.REJECTED_FRAMES))
        assertNull(facts.value(DeviceFactKey.REJECTED_LAST_RULE), "a stale rule from the previous connection is not shown")
    }

    @Test fun aRebaseIsCountedAsAFact() {
        // INCOHERENT fixture: the first 0x04 is the needle, so three agreeing frames rebase the odometer.
        val p = BegodeProtocol()
        p.pair(101, base + 65_536)
        for (i in 1..3) p.pair(101 + 7 * i, base + 7 * i)
        assertEquals("1", p.deviceFacts().value(DeviceFactKey.ODOMETER_REBASED))
    }

    @Test fun aDistrustedWitnessIsAFact() {
        val p = BegodeProtocol()
        for (i in 0 until 3) p.pair(101 + 7 * i, base + 7 * i)
        for (i in 1..6) p.pair(115, base + 14 + 50L * i)
        assertTrue(p.odometerWitnessDistrusted)
        assertEquals("distrusted", p.deviceFacts().value(DeviceFactKey.ODOMETER_WITNESS))
    }

    private val r13Live = bytes("55 AA 19 5D 00 00 00 4B 00 00 FF 74 EE A5 00 80 00 05 00 18 5A 5A 5A 5A")
    private val r13Pack = bytes("55 AA 00 50 00 00 03 CE 00 00 00 00 00 00 00 00 00 00 01 00 5A 5A 5A 5A")
    private fun certified() = BegodeProtocol().also { p -> repeat(6) { p.onNotification(r13Live); p.onNotification(r13Pack) } }

    @Test fun aPackVoltageRejectionIsAFactWithoutAnyOdometerFrame() {
        val p = certified()
        p.onNotification(r13Pack.copyOf().also { it[6] = 0x04; it[7] = 0xB0.toByte() }) // 120.0 V against ~97.4 V
        val facts = p.deviceFacts()
        assertEquals("1", facts.value(DeviceFactKey.REJECTED_FRAMES))
        assertEquals("pack_voltage_witness", facts.value(DeviceFactKey.REJECTED_LAST_RULE))
        assertTrue(facts.value(DeviceFactKey.REJECTED_LAST_DETAIL)!!.contains(" V / "), facts.value(DeviceFactKey.REJECTED_LAST_DETAIL))
    }

    @Test fun aSecondRejectionByAnotherRuleReplacesTheLastRule() {
        val p = certified()
        p.onNotification(r13Pack.copyOf().also { it[6] = 0x04; it[7] = 0xB0.toByte() })
        assertEquals("pack_voltage_witness", p.deviceFacts().value(DeviceFactKey.REJECTED_LAST_RULE))
        p.pair(101, base); p.onNotification(live(108)); p.onNotification(odo(base + 7 + 65_536)); p.pair(115, base + 14)
        val facts = p.deviceFacts()
        assertEquals("2", facts.value(DeviceFactKey.REJECTED_FRAMES))
        assertEquals("odometer_witness", facts.value(DeviceFactKey.REJECTED_LAST_RULE))
        assertEquals("2816164 m / +7 m", facts.value(DeviceFactKey.REJECTED_LAST_DETAIL))
    }
}
