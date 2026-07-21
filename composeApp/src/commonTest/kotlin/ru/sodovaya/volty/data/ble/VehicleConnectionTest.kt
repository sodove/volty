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
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class VehicleConnectionTest {

    private val twoPacks = listOf(
        Pack(0, "Branch 1", BmsType.ANT_BMS, "AA:01"),
        Pack(1, "Branch 2", BmsType.ANT_BMS, "AA:02")
    )

    /** Controllable time source, same idea as AlertEngineTest's fake clock. */
    private class FakeClock(private var nowMs: Long = 0L) {
        fun advance(ms: Long) { nowMs += ms }
        fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
    }

    private fun conn(
        packs: List<Pack> = twoPacks,
        topology: PackTopology = PackTopology.PARALLEL,
        sink: MutableList<VehicleData> = mutableListOf(),
        clock: FakeClock = FakeClock()
    ) = VehicleConnection(
        packs = packs,
        topology = topology,
        onVehicleData = { sink += it },
        clock = clock::now
    )

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

    // ----- Per-pack staleness (submit-driven liveness) -----

    @Test
    fun aPackSilentPastTheThresholdIsMarkedOfflineByTheOtherPacksSample() {
        val clock = FakeClock()
        val sink = mutableListOf<VehicleData>()
        val c = conn(sink = sink, clock = clock)
        c.submit(0, BmsData(voltage = 100.6f, current = 12.0f, isConnected = true))
        c.submit(1, BmsData(voltage = 100.8f, current = 12.4f, isConnected = true))
        assertEquals(24.4f, c.snapshot().aggregate.current, absoluteTolerance = 0.001f)

        clock.advance(BleConfig.packOfflineAfterMs + 1)
        val emitsBefore = sink.size
        val snap = c.submit(0, BmsData(voltage = 100.5f, current = 12.0f, isConnected = true))

        assertFalse(snap.packs[1].isOnline)
        assertTrue(snap.packs[0].isOnline)
        assertTrue(snap.isPartial)
        // The aggregate stops counting the dead branch: 24.4 A -> 12.0 A.
        assertEquals(12.0f, snap.aggregate.current, absoluteTolerance = 0.001f)
        // The offline marking folds into the submit's own emission — one, not two.
        assertEquals(emitsBefore + 1, sink.size)
    }

    @Test
    fun aPackExactlyAtTheThresholdIsStillOnline() {
        val clock = FakeClock()
        val c = conn(clock = clock)
        c.submit(0, BmsData(voltage = 100.6f, current = 12.0f, isConnected = true))
        c.submit(1, BmsData(voltage = 100.8f, current = 12.4f, isConnected = true))

        clock.advance(BleConfig.packOfflineAfterMs)
        val snap = c.submit(0, BmsData(voltage = 100.5f, current = 12.0f, isConnected = true))

        assertTrue(snap.packs[1].isOnline)
        assertFalse(snap.isPartial)
        assertEquals(24.4f, snap.aggregate.current, absoluteTolerance = 0.001f)
    }

    @Test
    fun aStalePackComesBackOnlineWhenItReportsAgain() {
        val clock = FakeClock()
        val c = conn(clock = clock)
        c.submit(0, BmsData(voltage = 100.6f, current = 12.0f, isConnected = true))
        c.submit(1, BmsData(voltage = 100.8f, current = 12.4f, isConnected = true))
        clock.advance(BleConfig.packOfflineAfterMs + 1)
        c.submit(0, BmsData(voltage = 100.5f, current = 12.0f, isConnected = true))
        assertFalse(c.snapshot().packs[1].isOnline)

        val snap = c.submit(1, BmsData(voltage = 100.7f, current = 12.4f, isConnected = true))

        assertTrue(snap.packs[1].isOnline)
        assertFalse(snap.isPartial)
        assertEquals(24.4f, snap.aggregate.current, absoluteTolerance = 0.001f)
    }

    @Test
    fun aSinglePackVehicleIsNeverMarkedOfflineByItsOwnSamples() {
        val clock = FakeClock()
        val c = conn(packs = listOf(Pack(0, "Battery", BmsType.JK_BMS, "AA:01")), clock = clock)
        c.submit(0, BmsData(voltage = 58.4f, current = 3.2f, isConnected = true))

        // Far past any threshold — its own next sample must not flag it.
        clock.advance(BleConfig.packOfflineAfterMs * 10)
        val snap = c.submit(0, BmsData(voltage = 58.3f, current = 3.1f, isConnected = true))

        assertTrue(snap.packs[0].isOnline)
        assertFalse(snap.isPartial)
        assertEquals(58.3f, snap.aggregate.voltage, absoluteTolerance = 0.001f)
        assertTrue(snap.aggregate.isConnected)
    }

    @Test
    fun losingAPackUnderSeriesTopologyReportsDisconnected() {
        val clock = FakeClock()
        val c = conn(topology = PackTopology.SERIES, clock = clock)
        c.submit(0, BmsData(voltage = 50.4f, current = 4.0f, isConnected = true))
        c.submit(1, BmsData(voltage = 50.2f, current = 4.0f, isConnected = true))
        assertTrue(c.snapshot().aggregate.isConnected)

        clock.advance(BleConfig.packOfflineAfterMs + 1)
        val snap = c.submit(0, BmsData(voltage = 50.4f, current = 4.0f, isConnected = true))

        assertTrue(snap.isPartial)
        // A series pack cannot be "switched out": the aggregate is physically
        // meaningless without it, so the vehicle reads disconnected (spec,
        // "Поведение при отвале пакета").
        assertFalse(snap.aggregate.isConnected)
    }

    @Test
    fun redundantMarkCallsDoNotEmit() {
        val sink = mutableListOf<VehicleData>()
        val c = conn(sink = sink)
        c.submit(0, BmsData(voltage = 100.6f, isConnected = true))
        c.submit(1, BmsData(voltage = 100.8f, isConnected = true))
        c.markOffline(1)
        val emits = sink.size

        c.markOffline(1) // already offline — must be a no-op
        assertEquals(emits, sink.size)

        c.markOnline(0) // already online — must be a no-op
        assertEquals(emits, sink.size)
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
