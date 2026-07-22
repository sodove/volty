package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.bms.BmsUuids
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.SectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `routePackSamples` feeds two consumers with deliberately different diets:
 *
 *  - its return value is LINK liveness — true whenever any pack has a decode
 *    cached at all, new or not. This is what refreshes the session watchdog's
 *    `lastSampleAtMs`, exactly as before the gate existed. A device that
 *    keeps notifying without producing new decodes (a JK BMS answering the
 *    handshake with settings/device-info frames only, never a fresh 0x02) is
 *    a live link and must not be torn down into a reconnect loop;
 *
 *  - `onNewSample` is PACK liveness — called only when [PackSampleGate]
 *    confirms a genuinely new decode. This is what feeds
 *    [VehicleConnection]'s per-pack staleness sweep and the ring buffer.
 */
class SampleRoutingTest {

    /** Minimal protocol stub: serves whatever cached decode the test plants. */
    private class StubProtocol(override val packCount: Int) : BmsProtocol() {
        val cached = arrayOfNulls<BmsData>(packCount)
        val stubSections = mutableMapOf<Int, List<SectionState>>()
        var sectionsReads = 0
        override val uuids = BmsUuids("0000", "0001", "0002")
        override fun handshakeCommands(): List<ByteArray> = emptyList()
        override fun pollCommands(): List<ByteArray> = emptyList()
        override fun onNotification(data: ByteArray) {}
        override fun latestData(packIndex: Int): BmsData? = cached[packIndex]
        override fun sections(packIndex: Int): List<SectionState> {
            sectionsReads++
            return stubSections[packIndex] ?: emptyList()
        }
        override fun reset() {}
    }

    @Test
    fun aNotificationWithNoDecodeAtAllIsNotLinkLiveness() {
        val protocol = StubProtocol(packCount = 1)
        val gate = PackSampleGate(1)
        val samples = mutableListOf<Int>()
        assertFalse(routePackSamples(protocol, gate) { i, _, _ -> samples += i })
        assertTrue(samples.isEmpty())
    }

    @Test
    fun aNewDecodeIsBothLinkLivenessAndASample() {
        val protocol = StubProtocol(packCount = 1)
        val gate = PackSampleGate(1)
        protocol.cached[0] = BmsData(voltage = 100f)
        val samples = mutableListOf<Int>()
        assertTrue(routePackSamples(protocol, gate) { i, _, _ -> samples += i })
        assertEquals(listOf(0), samples)
    }

    @Test
    fun aNotificationThatProducesNoNewDecodeStillRefreshesLinkLiveness() {
        val protocol = StubProtocol(packCount = 1)
        val gate = PackSampleGate(1)
        protocol.cached[0] = BmsData(voltage = 100f)
        routePackSamples(protocol, gate) { _, _, _ -> }

        // Next notification decodes nothing new — the protocol re-serves the
        // same cached instance (settings frame, device info, garbage chunk).
        val samples = mutableListOf<Int>()
        val linkAlive = routePackSamples(protocol, gate) { i, _, _ -> samples += i }

        assertTrue(
            linkAlive,
            "a cached decode proves the link is alive — the watchdog must stay fed"
        )
        assertTrue(samples.isEmpty(), "no new decode must reach onSample or the ring buffer")
    }

    @Test
    fun aSilentBranchStopsSamplingButKeepsTheLinkAlive() {
        val protocol = StubProtocol(packCount = 2)
        val gate = PackSampleGate(2)
        protocol.cached[0] = BmsData(voltage = 74.1f)
        protocol.cached[1] = BmsData(voltage = 74.2f)
        routePackSamples(protocol, gate) { _, _, _ -> }

        // Branch 1 goes quiet; branch 0 keeps decoding.
        protocol.cached[0] = BmsData(voltage = 74.0f)
        val samples = mutableListOf<Int>()
        val linkAlive = routePackSamples(protocol, gate) { i, _, _ -> samples += i }

        assertTrue(linkAlive)
        assertEquals(listOf(0), samples, "only the branch that decoded feeds pack liveness")
    }

    @Test
    fun sectionsRideAlongWithEachGatedSample() {
        val protocol = StubProtocol(packCount = 2)
        val gate = PackSampleGate(2)
        protocol.cached[0] = BmsData(voltage = 148.4f)
        protocol.cached[1] = BmsData(voltage = 148.5f)
        val branch0Sections = listOf(
            SectionState(index = 0, voltage = 74.1f, cellRange = 0..19),
            SectionState(index = 1, voltage = 74.2f, cellRange = 20..39)
        )
        protocol.stubSections[0] = branch0Sections

        val delivered = mutableMapOf<Int, List<SectionState>>()
        routePackSamples(protocol, gate) { i, _, sections -> delivered[i] = sections }

        // Each pack's breakdown lands on that pack, never on its neighbour.
        assertEquals(branch0Sections, delivered[0])
        assertEquals(emptyList(), delivered[1])
        assertEquals(2, protocol.sectionsReads)
    }

    @Test
    fun sectionsAreNotRebuiltForAPackTheGateSuppressed() {
        val protocol = StubProtocol(packCount = 1)
        val gate = PackSampleGate(1)
        protocol.cached[0] = BmsData(voltage = 148.4f)
        routePackSamples(protocol, gate) { _, _, _ -> }
        val readsAfterFirst = protocol.sectionsReads

        // Same cached decode re-served: no new sample, so no sections read
        // either — the breakdown is only ever fetched beside a gated sample.
        routePackSamples(protocol, gate) { _, _, _ -> }
        assertEquals(readsAfterFirst, protocol.sectionsReads)
    }
}
