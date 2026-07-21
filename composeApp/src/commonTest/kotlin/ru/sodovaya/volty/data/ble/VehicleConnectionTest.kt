package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.VehicleData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class VehicleConnectionTest {

    private val twoPacks = listOf(
        Pack(0, "Branch 1", BmsType.ANT_BMS, "AA:01"),
        Pack(1, "Branch 2", BmsType.ANT_BMS, "AA:02")
    )

    private fun conn(
        packs: List<Pack> = twoPacks,
        topology: PackTopology = PackTopology.PARALLEL,
        sink: MutableList<VehicleData> = mutableListOf()
    ) = VehicleConnection(packs = packs, topology = topology, onVehicleData = { sink += it })

    @Test
    fun startsWithEveryPackOfflineAndNoAggregate() {
        val c = conn()
        val snap = c.snapshot()
        assertEquals(2, snap.packs.size)
        assertTrue(snap.packs.none { it.isOnline })
        assertFalse(snap.aggregate.isConnected)
    }

    @Test
    fun aSubmittedSampleBringsItsPackOnline() {
        val c = conn()
        c.submit(0, BmsData(voltage = 100.6f, current = 12f, isConnected = true))
        val snap = c.snapshot()
        assertTrue(snap.packs[0].isOnline)
        assertFalse(snap.packs[1].isOnline)
        assertTrue(snap.isPartial)
        assertEquals(100.6f, snap.aggregate.voltage, absoluteTolerance = 0.001f)
    }

    @Test
    fun bothPacksOnlineClearPartialAndSumCurrent() {
        val c = conn()
        c.submit(0, BmsData(voltage = 100.6f, current = 12.0f, isConnected = true))
        c.submit(1, BmsData(voltage = 100.8f, current = 12.4f, isConnected = true))
        val snap = c.snapshot()
        assertFalse(snap.isPartial)
        assertEquals(24.4f, snap.aggregate.current, absoluteTolerance = 0.001f)
    }

    @Test
    fun markingOnePackOfflineKeepsTheOtherFeedingTheAggregate() {
        val c = conn()
        c.submit(0, BmsData(voltage = 100.6f, current = 12.0f, isConnected = true))
        c.submit(1, BmsData(voltage = 100.8f, current = 12.4f, isConnected = true))
        c.markOffline(1)
        val snap = c.snapshot()
        assertTrue(snap.isPartial)
        assertTrue(snap.packs[0].isOnline)
        assertEquals(12.0f, snap.aggregate.current, absoluteTolerance = 0.001f)
        assertTrue(snap.aggregate.isConnected)
    }

    @Test
    fun anOfflinePackKeepsItsLastData() {
        val c = conn()
        c.submit(1, BmsData(voltage = 100.8f, soc = 73f, isConnected = true))
        c.markOffline(1)
        assertEquals(73f, c.snapshot().packs[1].data.soc)
    }

    @Test
    fun emitsOnEverySubmit() {
        val sink = mutableListOf<VehicleData>()
        val c = conn(sink = sink)
        c.submit(0, BmsData(voltage = 100f, isConnected = true))
        c.submit(0, BmsData(voltage = 101f, isConnected = true))
        assertEquals(2, sink.size)
        assertEquals(101f, sink.last().aggregate.voltage, absoluteTolerance = 0.001f)
    }

    @Test
    fun seriesGoesDisconnectedWhenAPackDrops() {
        val c = conn(topology = PackTopology.SERIES)
        c.submit(0, BmsData(voltage = 50.4f, isConnected = true))
        c.submit(1, BmsData(voltage = 50.2f, isConnected = true))
        assertTrue(c.snapshot().aggregate.isConnected)
        c.markOffline(1)
        assertFalse(c.snapshot().aggregate.isConnected)
    }

    @Test
    fun submitForAnUnknownPackIndexIsIgnored() {
        val c = conn()
        c.submit(7, BmsData(voltage = 100f, isConnected = true))
        assertTrue(c.snapshot().packs.none { it.isOnline })
    }

    @Test
    fun singlePackBehavesExactlyLikeBefore() {
        val c = conn(packs = listOf(Pack(0, "Battery", BmsType.JK_BMS, "AA:01")))
        val sample = BmsData(voltage = 58.4f, current = 3.2f, soc = 91f, isConnected = true)
        c.submit(0, sample)
        val snap = c.snapshot()
        assertFalse(snap.isPartial)
        assertEquals(sample.voltage, snap.aggregate.voltage)
        assertEquals(sample.current, snap.aggregate.current)
        assertEquals(sample.soc, snap.aggregate.soc)
    }
}
