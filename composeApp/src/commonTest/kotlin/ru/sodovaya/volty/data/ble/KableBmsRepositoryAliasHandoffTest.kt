package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Part C Task 5 — the alias-path handoff: the app lets go of its own direct
 * link to the ANT while the head unit is serving the same battery, and takes
 * it back when the head unit goes away.
 *
 * **The property these tests exist for is the last one: no gap in the
 * aggregated battery across the swap.** Everything else here is mechanism, and
 * a suite that only checked "the direct link was disconnected" would pass just
 * as happily on the implementation this task was written to rule out —
 * releasing the moment the gateway link comes up, while its hosted battery is
 * still silent, which leaves the rider with the battery on NEITHER path.
 *
 * Fakes only, like every sibling repository test: [ConnectionSession] needs a
 * real Kable peripheral, so the production wiring is installed through
 * [KableBmsRepository.installLinksForTest] and each link's own funnel is
 * driven by hand. `runCurrent()` rather than `advanceUntilIdle()` throughout —
 * once a reconnect loop is live, advancing virtual time spins it forever
 * against a BLE scanner that will never answer in a unit test.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryAliasHandoffTest {

    private class StubVehicleRepository : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    private var underTest: KableBmsRepository? = null

    @AfterTest
    fun tearDown() {
        underTest?.close()
        underTest = null
    }

    private fun newRepo(testScope: TestScope): KableBmsRepository = KableBmsRepository.forTesting(
        vehicleRepository = StubVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        coroutineContext = StandardTestDispatcher(testScope.testScheduler),
    )

    /**
     * The product owner's scooter. ONE battery, TWO paths sharing an
     * `aliasGroup`: the ANT's own BLE address (parked) and the head unit that
     * hosts it while riding, alongside the two CAN uBoxes on the same link.
     */
    private fun twoPathScooter(yieldToHeadUnit: Boolean? = null): Vehicle = Vehicle(
        id = "v-alias",
        name = "Scooter",
        iconKey = "scooter",
        packs = listOf(
            Pack(index = 0, label = "ANT", bmsType = BmsType.ANT_BMS, bmsAddress = ANT_ADDR, aliasGroup = ALIAS),
            Pack(index = 1, label = "ANT via head unit", bmsType = BmsType.VESC_BMS, bmsAddress = HU_ADDR, aliasGroup = ALIAS)
        ),
        controllers = listOf(
            Controller(index = 0, label = "Front", controllerType = ControllerType.VESC, address = HU_ADDR, canId = 41),
            Controller(index = 1, label = "Rear", controllerType = ControllerType.VESC, address = HU_ADDR, canId = 42)
        ),
        topology = PackTopology.PARALLEL,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L),
        yieldBmsToHeadUnit = yieldToHeadUnit
    )

    /**
     * Run [body], then cancel the repository's scope **whatever happens**.
     *
     * A reconnect loop schedules delayed work forever, and `runTest` advances
     * virtual time until every coroutine on its scheduler is idle — so ONE
     * loop left running past the test body makes the test hang instead of
     * finishing, allocating until the Gradle daemon dies. The first draft of
     * this file did exactly that and wedged the build for over an hour.
     *
     * A plain `repo.disconnect()` at the end of the body is not enough,
     * because it is skipped when an assertion above it fails: a broken
     * expectation would then WEDGE the suite instead of reporting itself. This
     * wrapper is what makes a failure in these tests fail fast.
     */
    private inline fun withReconnectLoopsStopped(repo: KableBmsRepository, body: () -> Unit) {
        try {
            body()
        } finally {
            repo.close()
        }
    }

    /**
     * Distinct voltages per path on purpose: they are how a test tells WHICH
     * path the aggregate is currently reading, which "isConnected == true"
     * alone cannot.
     */
    private fun sample(voltage: Float, soc: Float) =
        BmsData(voltage = voltage, current = 12.5f, soc = soc, isConnected = true)

    private fun parkedSample() = sample(voltage = 74.0f, soc = 61f)
    private fun hostedSample() = sample(voltage = 73.4f, soc = 60f)

    /** Both links installed and believed up, with the direct ANT already reporting. */
    private fun KableBmsRepository.rideReady(
        v: Vehicle
    ): List<(Int, BmsData, List<ru.sodovaya.volty.domain.model.SectionState>) -> Unit> {
        val funnels = installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)
        assertEquals(
            listOf(ANT_ADDR, HU_ADDR),
            linkAddressesForTest(),
            "the fixture's link order is what funnels[0]/[1] mean below"
        )
        markLinkOnlineForTest(ANT_ADDR)
        funnels[0](0, parkedSample(), emptyList())
        return funnels
    }

    // ----- 1. The trigger is the hosted BMS REPORTING, not the link coming up -----

    @Test
    fun `a head-unit link that is up but silent does not cost us the direct link`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoPathScooter()
        repo.rideReady(v)

        repo.markLinkOnlineForTest(HU_ADDR)
        runCurrent()

        assertEquals(2, repo.linkCountForTest(), "nothing has reported over the head unit yet")
        assertContains(repo.linkAddressesForTest(), ANT_ADDR)
        assertTrue(
            repo.activeVehicleData.value.aggregate.isConnected,
            "and the battery is still live on the direct path"
        )
        assertEquals(74.0f, repo.activeVehicleData.value.aggregate.voltage, absoluteTolerance = 0.001f)
    }

    @Test
    fun `the hosted battery reporting releases the direct link`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoPathScooter()
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)

        funnels[1](0, hostedSample(), emptyList())
        runCurrent()

        assertEquals(listOf(HU_ADDR), repo.linkAddressesForTest(), "the direct ANT link is released")
        assertEquals(
            ConnectionState.Connected(v),
            repo.connectionState.value,
            "and the vehicle stays Connected on the head unit alone"
        )
    }

    // ----- 2. THE acceptance property: no gap in the aggregated battery -----

    /**
     * The user-visible contract. Every snapshot published from the first real
     * sample onwards must carry a live battery — through the head unit coming
     * up, through the swap itself, and after it.
     *
     * The recorded history is asserted wholesale rather than item by item
     * because [kotlinx.coroutines.flow.StateFlow] conflates: a strict
     * awaitItem() sequence would silently tolerate a dropped intermediate. The
     * failure this test is aimed at is not a one-emission blip anyway — it is
     * a battery that is absent for as long as the hosted BMS stays quiet,
     * which is exactly what a "release when the gateway link is up" trigger
     * produces and what `assertFalse(seen.any { !it.aggregate.isConnected })`
     * catches at every step below.
     */
    @Test
    fun `the aggregated battery never gaps across the swap`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoPathScooter()
        val funnels = repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)

        val seen = mutableListOf<VehicleData>()
        val recorder: Job = launch { repo.activeVehicleData.collect { seen += it } }
        runCurrent()
        seen.clear() // the pre-connection VehicleData() default is not part of the ride

        fun assertNoGap(step: String) {
            val offline = seen.filter { !it.aggregate.isConnected }
            assertTrue(
                offline.isEmpty(),
                "$step: the aggregated battery went offline ${offline.size} time(s) — " +
                    "packs=${seen.map { s -> s.packs.map { it.pack.index to it.isOnline } }}"
            )
            assertTrue(seen.isNotEmpty(), "$step: nothing was published at all")
        }

        // Parked: only the direct ANT is up.
        repo.markLinkOnlineForTest(ANT_ADDR)
        funnels[0](0, parkedSample(), emptyList())
        runCurrent()
        assertNoGap("parked")

        // The head unit comes up. Its hosted battery has said nothing yet, so
        // the direct link MUST stay — this is the §9.4 ordering.
        repo.markLinkOnlineForTest(HU_ADDR)
        runCurrent()
        assertNoGap("head unit up, hosted battery still silent")
        assertEquals(2, repo.linkCountForTest())

        // The swap: the hosted battery reports for the first time.
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()
        assertNoGap("the swap itself")
        assertEquals(listOf(HU_ADDR), repo.linkAddressesForTest())

        // Riding on: the head unit keeps delivering the same battery.
        funnels[1](0, hostedSample().copy(voltage = 72.8f), emptyList())
        runCurrent()
        assertNoGap("riding on the hosted path")
        assertEquals(
            72.8f,
            repo.activeVehicleData.value.aggregate.voltage,
            absoluteTolerance = 0.001f,
            message = "the battery is the head unit's now"
        )

        recorder.cancel()
    }

    /**
     * The other half of "no gap": the aggregate must follow the battery, not
     * the pack numbering. `collapseAliases` prefers the lowest-indexed ONLINE
     * member, and the direct path is index 0 — so leaving it marked online
     * after its link is gone would pin the parked 74.0 V reading on the
     * dashboard for a full `packOfflineAfterMs` while a live hosted sample sat
     * beside it. Stale-but-plausible, which is worse than absent.
     */
    @Test
    fun `the aggregate follows the hosted path the moment the direct link is released`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoPathScooter()
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)
        assertEquals(74.0f, repo.activeVehicleData.value.aggregate.voltage, absoluteTolerance = 0.001f)

        funnels[1](0, hostedSample(), emptyList())
        runCurrent()

        val snap = repo.activeVehicleData.value
        assertEquals(1, snap.packs.size, "the alias group is still counted once")
        assertEquals(1, snap.packs.single().pack.index, "and the survivor is the hosted path")
        assertTrue(snap.packs.single().isOnline)
        assertEquals(73.4f, snap.aggregate.voltage, absoluteTolerance = 0.001f)
        assertFalse(snap.isPartial, "one battery on one live path is not a partial vehicle")
        assertEquals(
            73.4f,
            repo.activeData.value.voltage,
            absoluteTolerance = 0.001f,
            message = "the Battery screen's own flow must not lag a sample behind the swap"
        )
    }

    // ----- 3. Re-raise -----

    @Test
    fun `dropping the head-unit link re-raises the direct BMS link`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoPathScooter()
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()
        assertEquals(listOf(HU_ADDR), repo.linkAddressesForTest())

        withReconnectLoopsStopped(repo) {
            repo.simulateLinkDropForTest(HU_ADDR, "Link dropped")
            runCurrent()

            assertContains(
                repo.linkAddressesForTest(),
                ANT_ADDR,
                "the released link must come back when the head unit goes away"
            )
            assertEquals(2, repo.linkCountForTest())
            val antLoop = assertNotNull(
                repo.linkReconnectJobForTest(ANT_ADDR),
                "the returning link is handed to the ordinary reconnect loop, not left idle"
            )
            assertTrue(antLoop.isActive)

            repo.disconnect()
            runCurrent()
            assertFalse(antLoop.isActive, "disconnect must stop the re-raised link's loop")
        }
    }

    /**
     * Re-raising must be idempotent per drop. A dead link is reported twice in
     * practice — the peripheral's state observer and the stale-sample watchdog
     * both fire — and `onLinkDrop` dedups on "believed up", which the re-raise
     * has to sit inside or the returning link is raised twice and the second
     * copy fights the first for the same address.
     */
    @Test
    fun `a duplicate drop report does not raise the direct link twice`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoPathScooter()
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()

        withReconnectLoopsStopped(repo) {
            repo.simulateLinkDropForTest(HU_ADDR, "state observer")
            repo.simulateLinkDropForTest(HU_ADDR, "watchdog")
            runCurrent()

            assertEquals(
                listOf(ANT_ADDR),
                repo.linkAddressesForTest().filter { it == ANT_ADDR },
                "exactly one direct link, however many times the drop is reported"
            )
            assertEquals(2, repo.linkCountForTest())
        }
    }

    /**
     * A full user disconnect must not be undone by a drop report racing it —
     * the re-raise resurrects a link, and doing that after the sweep would
     * leave a live link behind a connection the user closed.
     */
    @Test
    fun `a released link is not re-raised after the user disconnects`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoPathScooter()
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()

        withReconnectLoopsStopped(repo) {
            // Report the drop FIRST — simulateLinkDropForTest reads the live
            // link list, and after the sweep there is nothing left to name —
            // then let the user's disconnect win the race before the drop is
            // delivered.
            repo.simulateLinkDropForTest(HU_ADDR, "late drop racing disconnect")
            repo.disconnect()
            runCurrent()

            assertEquals(0, repo.linkCountForTest(), "no link may be resurrected behind a user disconnect")
            assertEquals(ConnectionState.Disconnected, repo.connectionState.value)
            assertNull(repo.linkReconnectJobForTest(ANT_ADDR))
        }
    }

    // ----- 4. The toggle -----

    @Test
    fun `an alias group spanning direct and hosted plans the handoff by default`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoPathScooter(yieldToHeadUnit = null)
        repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)

        assertEquals(
            listOf(
                AliasHandoff(
                    aliasGroup = ALIAS,
                    gatewayAddress = HU_ADDR,
                    hostedPackIndex = 1,
                    directAddress = ANT_ADDR,
                    directPackIndex = 0
                )
            ),
            repo.aliasHandoffsForTest(),
            "unset means ON, and it is planned against the vehicle-GLOBAL pack indices"
        )
    }

    @Test
    fun `turning the toggle off keeps both links up when the hosted battery reports`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = twoPathScooter(yieldToHeadUnit = false)
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)

        assertTrue(repo.aliasHandoffsForTest().isEmpty(), "opting out plans no handoff at all")
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()

        assertEquals(listOf(ANT_ADDR, HU_ADDR), repo.linkAddressesForTest())
        assertTrue(
            repo.activeVehicleData.value.packs.single().isOnline,
            "both paths stay online, and the alias collapse still counts the battery once"
        )
    }

    // ----- 5. Regression: the vehicles that existed before this feature -----

    /**
     * The single-link case, which must be untouched: no plan, no release, no
     * extra emission — the sample path is byte-identical to what it was.
     */
    @Test
    fun `a one-BMS vehicle with no gateway plans nothing and keeps its link`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = singlePackVehicle(
            id = "v-solo", name = "Solo", iconKey = "battery",
            bmsType = BmsType.JK_BMS, bmsAddress = ANT_ADDR,
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0L)
        )
        val funnels = repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)
        repo.markLinkOnlineForTest(ANT_ADDR)

        val seen = mutableListOf<VehicleData>()
        val recorder = launch { repo.activeVehicleData.collect { seen += it } }
        runCurrent()
        // Structural, not `assertEquals(VehicleData(), …)`: the default carries
        // a Clock.System.now() timestamp, so whole-object equality compares two
        // different instants and always fails.
        assertEquals(1, seen.size, "only the pre-connection default so far")
        assertTrue(seen.single().packs.isEmpty())
        assertFalse(seen.single().aggregate.isConnected)

        seen.clear()
        funnels[0](0, parkedSample(), emptyList())
        runCurrent()

        // EXACTLY one snapshot per sample, as before this feature: the
        // handoff's extra mark-offline emission would show up here as a
        // second entry, so this pins that a plain vehicle never enters the
        // handoff path at all.
        assertEquals(1, seen.size, "one sample must still publish one snapshot, not two")
        assertTrue(seen.single().aggregate.isConnected)
        assertEquals(74.0f, seen.single().aggregate.voltage, absoluteTolerance = 0.001f)
        assertTrue(repo.aliasHandoffsForTest().isEmpty())
        assertEquals(listOf(ANT_ADDR), repo.linkAddressesForTest())
        recorder.cancel()
    }

    /**
     * The ordinary multi-link vehicle Part A shipped — two independent BMS at
     * two addresses, no head unit anywhere. Neither link may be released, and
     * both keep feeding the aggregate.
     */
    @Test
    fun `an ordinary two-BMS vehicle is untouched`() = runTest {
        val repo = newRepo(this).also { underTest = it }
        val v = Vehicle(
            id = "v-two-bms", name = "Rig", iconKey = "battery",
            packs = listOf(
                Pack(index = 0, label = "Main", bmsType = BmsType.ANT_BMS, bmsAddress = ANT_ADDR),
                Pack(index = 1, label = "Aux", bmsType = BmsType.JBD_BMS, bmsAddress = HU_ADDR)
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Instant.fromEpochSeconds(0L)
        )
        val funnels = repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)
        repo.markLinkOnlineForTest(ANT_ADDR)
        repo.markLinkOnlineForTest(HU_ADDR)
        funnels[0](0, parkedSample(), emptyList())
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()

        assertTrue(repo.aliasHandoffsForTest().isEmpty())
        assertEquals(listOf(ANT_ADDR, HU_ADDR), repo.linkAddressesForTest())
        assertEquals(2, repo.activeVehicleData.value.packs.size, "two batteries, both counted")
        assertTrue(repo.activeVehicleData.value.packs.all { it.isOnline })
    }

    private companion object {
        const val ANT_ADDR = "AA:BB:CC:DD:EE:A1"
        const val HU_ADDR = "AA:BB:CC:DD:EE:0C"
        const val ALIAS = "ant-72v"
    }
}
