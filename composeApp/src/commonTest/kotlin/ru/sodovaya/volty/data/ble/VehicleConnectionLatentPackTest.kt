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

/**
 * Latent pack slots: packs the PROTOCOL says may exist but the stored profile
 * does not know about. BegodeProtocol always reports packCount = 2, yet a
 * wheel without a smart BMS only ever produces data for pack 0 — publishing
 * the second slot eagerly would pin a permanently-offline phantom "Pack 2"
 * card (and a permanent isPartial) on every such wheel. A latent slot is
 * invisible until its first sample and a full citizen from then on.
 */
@OptIn(ExperimentalTime::class)
class VehicleConnectionLatentPackTest {

    private val stored = Pack(0, "Battery", BmsType.BEGODE, "AA:01")
    private val latent = Pack(1, "Pack 2", BmsType.BEGODE, "AA:01")

    private class FakeClock(private var nowMs: Long = 0L) {
        fun advance(ms: Long) { nowMs += ms }
        fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
    }

    private fun conn(
        sink: MutableList<VehicleData> = mutableListOf(),
        clock: FakeClock = FakeClock()
    ) = VehicleConnection(
        packs = listOf(stored),
        latentPacks = listOf(latent),
        topology = PackTopology.PARALLEL,
        onVehicleData = { sink += it },
        clock = clock::now
    )

    @Test
    fun aLatentSlotIsInvisibleUntilItsFirstSample() {
        val c = conn()
        // Before anything: only the stored pack exists.
        assertEquals(listOf(0), c.snapshot().packs.map { it.pack.index })

        // A dumb Begode: pack 0 streams, pack 1 never says a word.
        val snap = c.submit(0, BmsData(voltage = 147.3f, current = -3.5f, isConnected = true))

        assertEquals(1, snap.packs.size, "the silent latent slot must not be published")
        assertEquals(0, snap.packs[0].pack.index)
        assertTrue(snap.packs[0].isOnline)
        assertFalse(snap.isPartial, "a wheel with no second branch is not partial")
        assertEquals(147.3f, snap.aggregate.voltage, absoluteTolerance = 0.001f)
        assertEquals(-3.5f, snap.aggregate.current, absoluteTolerance = 0.001f)
        assertTrue(snap.aggregate.isConnected)
    }

    @Test
    fun aLatentSlotMaterialisesOnItsFirstSampleAndJoinsTheAggregate() {
        val c = conn()
        c.submit(0, BmsData(voltage = 148.4f, current = 8.85f, isConnected = true))
        // The smart-BMS case: branch 1 decodes after all.
        val snap = c.submit(1, BmsData(voltage = 148.4f, current = 8.85f, isConnected = true))

        assertEquals(listOf(0, 1), snap.packs.map { it.pack.index }, "materialised in index order")
        assertEquals("Pack 2", snap.packs[1].pack.label)
        assertTrue(snap.packs.all { it.isOnline })
        assertFalse(snap.isPartial)
        // Two parallel branches: currents sum, voltages average.
        assertEquals(17.70f, snap.aggregate.current, absoluteTolerance = 0.01f)
        assertEquals(148.4f, snap.aggregate.voltage, absoluteTolerance = 0.01f)
    }

    @Test
    fun aMaterialisedSlotIsAFullCitizenOfTheStalenessSweep() {
        val clock = FakeClock()
        val c = conn(clock = clock)
        c.submit(0, BmsData(voltage = 148.4f, current = 8.85f, isConnected = true))
        c.submit(1, BmsData(voltage = 148.4f, current = 8.85f, isConnected = true))

        // Branch 1 goes silent past the threshold: it must be marked offline
        // and STAY in the list (grey card), not vanish back into latency.
        clock.advance(BleConfig.packOfflineAfterMs + 1)
        val snap = c.submit(0, BmsData(voltage = 148.3f, current = 8.85f, isConnected = true))

        assertEquals(2, snap.packs.size, "a branch that has reported stays visible when lost")
        assertFalse(snap.packs[1].isOnline)
        assertTrue(snap.isPartial)
        assertEquals(8.85f, snap.aggregate.current, absoluteTolerance = 0.01f)
    }

    @Test
    fun anIndexNeitherStoredNorLatentIsStillIgnored() {
        val c = conn()
        val snap = c.submit(7, BmsData(voltage = 100f, isConnected = true))
        assertEquals(listOf(0), snap.packs.map { it.pack.index })
        assertTrue(snap.packs.none { it.isOnline })
    }

    @Test
    fun aLatentDuplicateOfAStoredIndexNeverShadowsIt() {
        // Defensive: if configuration ever hands the same index both ways,
        // the stored pack (user configuration) wins and no duplicate slot
        // appears — VehicleConnection.submit matches packs by index, and a
        // duplicate would leave one slot permanently unreachable.
        val c = VehicleConnection(
            packs = listOf(stored),
            latentPacks = listOf(stored.copy(label = "Impostor"), latent),
            topology = PackTopology.PARALLEL,
            onVehicleData = {}
        )
        val snap = c.submit(0, BmsData(voltage = 147.3f, isConnected = true))
        assertEquals(1, snap.packs.size)
        assertEquals("Battery", snap.packs[0].pack.label, "the stored pack must win")
    }

    @Test
    fun materialisationEmitsExactlyOneSnapshot() {
        val sink = mutableListOf<VehicleData>()
        val c = conn(sink = sink)
        c.submit(0, BmsData(voltage = 148.4f, isConnected = true))
        val emitsBefore = sink.size
        c.submit(1, BmsData(voltage = 148.4f, isConnected = true))
        assertEquals(emitsBefore + 1, sink.size, "materialise + sample = one emission")
    }
}
