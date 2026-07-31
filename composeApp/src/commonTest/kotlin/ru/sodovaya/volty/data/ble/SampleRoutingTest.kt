package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.bms.BmsUuids
import ru.sodovaya.volty.data.bms.MotionSource
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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
 *
 * The second half of this class pins the channel funnel: `onSample` enriches
 * on the session coroutine and sends a [PackSample] into a channel; a single
 * consumer coroutine owns every shared-state mutation (submit → ring buffer →
 * `_activeData`). With one link the behaviour must be indistinguishable from
 * the pre-channel synchronous funnel.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
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

    /** A fake protocol that is both a BmsProtocol and a MotionSource. */
    private class FakeMotionProtocol(
        private var motion: ControllerData?
    ) : BmsProtocol(), MotionSource {
        override val uuids = BmsUuids("0000", "0001", "0002")
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun onNotification(data: ByteArray) {}
        override val packCount = 0
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() {}
        override val controllerCount = 1
        override fun latestMotion(controllerIndex: Int) = motion
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

    @Test
    fun controller_only_protocol_keeps_link_alive_via_motion() {
        val gate = MotionSampleGate(1)
        val proto = FakeMotionProtocol(ControllerData(speedKmh = 5f))
        var got: ControllerData? = null
        val alive = routeControllerSamples(proto, gate) { _, d -> got = d }
        assertTrue(alive)
        assertEquals(5f, got?.speedKmh)
    }

    @Test
    fun no_motion_decode_reports_not_alive() {
        val alive = routeControllerSamples(FakeMotionProtocol(null), MotionSampleGate(1)) { _, _ -> }
        assertFalse(alive)
    }

    // ----- The channel funnel (serialisation barrier, multi-link Task 2) -----

    private class NoopVehicleRepository : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
        // Explicit, because both of VehicleRepository's gauge-peak members are abstract:
        // no fake gets a silent default. Nothing in this file rides a learned dial range
        // (G §9.2), and an EMPTY map is the honest answer rather than a missing one --
        // absence in that map means "has learned nothing", which is exactly the case here.
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    /** Every test here owns its repository through [bleRepositoryTest] — see there for why that is not optional. */
    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = NoopVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        body = body
    )

    private fun funnelVehicle(type: BmsType) = singlePackVehicle(
        id = "v-funnel",
        name = "Funnel",
        iconKey = "scooter",
        bmsType = type,
        bmsAddress = FUNNEL_ADDRESS,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0)
    )

    @Test
    fun `an enriched sample crosses the channel into the shared state intact`() = repoTest { repo ->
        // A Begode reports no SoC; the estimator runs on the SESSION side of
        // the channel, so the enrichment must arrive at the shared state
        // intact. 40 cells at 3.75 V map linearly between LI_ION_NMC's
        // emptyCellV = 3.30 and defaultHighV = 4.20: (3.75 - 3.30) / 0.90 = 50 %.
        val funnel = repo.installSampleFunnelForTest(
            funnelVehicle(BmsType.BEGODE), FUNNEL_ADDRESS, BmsType.BEGODE
        )
        val branch = BmsData(
            voltage = 40 * 3.75f,
            current = 4.2f,
            cellVoltages = List(40) { 3.75f },
            isConnected = true
        )
        funnel(0, branch, emptyList())

        assertEquals(50.0f, repo.activeData.value.soc, absoluteTolerance = 0.1f)
        assertEquals(150.0f, repo.activeData.value.voltage, absoluteTolerance = 0.01f)
        val pack = repo.activeVehicleData.value.packs.first()
        assertTrue(pack.isOnline, "the enriched sample must bring the pack online")
        assertEquals(50.0f, pack.data.soc, absoluteTolerance = 0.1f)
    }

    @Test
    fun `the graph window already holds a sample when activeData announces it`() = repoTest { repo ->
        val funnel = repo.installSampleFunnelForTest(
            funnelVehicle(BmsType.JK_BMS), FUNNEL_ADDRESS, BmsType.JK_BMS
        )
        // Unconfined collector: runs inline the moment _activeData announces
        // a sample, reading the ring-buffer window at exactly that instant —
        // if the consumer set _activeData before pushing, the window would
        // miss the announced sample (the one-sample graph lag regression).
        val windows = mutableListOf<List<BmsData>>()
        val collector = launch(Dispatchers.Unconfined) {
            repo.samples(5.minutes).collect { windows += it }
        }
        val sample = BmsData(voltage = 83.16f, cellVoltages = List(20) { 4.158f }, isConnected = true)
        funnel(0, sample, emptyList())
        collector.cancel()

        val announced = windows.last()
        assertEquals(1, announced.size, "the window must contain the announced sample")
        assertEquals(83.16f, announced.single().voltage, absoluteTolerance = 0.001f)
        assertEquals(83.16f, repo.activeData.value.voltage, absoluteTolerance = 0.001f)
    }

    @Test
    fun `a PackSample sent straight into the channel reaches the shared state through the consumer`() = repoTest { repo ->
        // Bypass onSample and inject a post-enrichment PackSample directly —
        // the exact shape a second link's session will produce in Task 3.
        // Index 1 proves the consumer submits the GLOBAL pack index.
        repo.installSampleFunnelForTest(funnelVehicle(BmsType.BEGODE), FUNNEL_ADDRESS, BmsType.BEGODE)
        val channel = assertNotNull(
            repo.sampleFunnelChannelForTest(),
            "installing the pipeline must install the channel"
        )
        val data = BmsData(voltage = 148.4f, current = 8.85f, isConnected = true)

        assertTrue(channel.trySend(PackSample(1, data, emptyList())).isSuccess)

        val snap = repo.activeVehicleData.value
        assertEquals(2, snap.packs.size, "Begode branch 1 must materialise")
        assertTrue(snap.packs[1].isOnline, "the sample must land on global pack 1")
        assertEquals(148.4f, repo.activeData.value.voltage, absoluteTolerance = 0.01f)
        assertEquals(8.85f, repo.activeData.value.current, absoluteTolerance = 0.01f)
    }

    @Test
    fun `a burst at sample rate is never dropped by the channel`() = repoTest { repo ->
        val funnel = repo.installSampleFunnelForTest(
            funnelVehicle(BmsType.JK_BMS), FUNNEL_ADDRESS, BmsType.JK_BMS
        )
        // 30 back-to-back samples — far denser than the 1-6 Hz a real link
        // produces. trySend must not drop a single one.
        repeat(30) { i ->
            funnel(0, BmsData(voltage = 80.0f + i * 0.1f, isConnected = true), emptyList())
        }

        val window = repo.samples(5.minutes).first()
        assertEquals(30, window.size, "every sample of the burst must reach the ring buffer")
        assertEquals(82.9f, window.last().voltage, absoluteTolerance = 0.001f)
        assertEquals(82.9f, repo.activeData.value.voltage, absoluteTolerance = 0.001f)
    }

    private companion object {
        const val FUNNEL_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
