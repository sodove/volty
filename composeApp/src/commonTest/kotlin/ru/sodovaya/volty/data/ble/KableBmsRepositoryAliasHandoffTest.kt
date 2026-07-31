package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
 * it back when the head unit goes away — whether it goes away by dropping the
 * link or by staying up and no longer serving the battery.
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
        // Explicit, because both of VehicleRepository's gauge-peak members are abstract:
        // no fake gets a silent default. Nothing in this file rides a learned dial range
        // (G §9.2), and an EMPTY map is the honest answer rather than a missing one --
        // absence in that map means "has learned nothing", which is exactly the case here.
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    /** Every test here owns its repository through [bleRepositoryTest] — see there for why that is not optional. */
    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = StubVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        body = body
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
    fun `a head-unit link that is up but silent does not cost us the direct link`() = repoTest { repo ->
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
    fun `the hosted battery reporting releases the direct link`() = repoTest { repo ->
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
     * **Recorded through [KableBmsRepository.vehicleDataTapForTest], not by
     * collecting [KableBmsRepository.activeVehicleData], and that is what gives
     * this test its teeth.** The release does two things in one synchronous
     * consumer pass — `submit` the hosted sample, then `markOffline` the direct
     * pack — and the whole property is that they happen in that order: inverted,
     * the mark-offline publishes a snapshot whose alias group has no online
     * member at all. A [kotlinx.coroutines.flow.StateFlow] CONFLATES that
     * intermediate away, so a collector sees only the good final value and the
     * inversion passes. The tap sits on the orchestrator's own `onVehicleData`
     * and drops nothing, so every snapshot the code publishes is judged —
     * including the one the bug would produce.
     *
     * The failure it is aimed at is not a one-emission blip either: a "release
     * when the gateway link is up" trigger leaves the battery absent for as
     * long as the hosted BMS stays quiet. Both are caught by the same
     * `assertNoGap` at every step below.
     */
    @Test
    fun `the aggregated battery never gaps across the swap`() = repoTest { repo ->
        val v = twoPathScooter()
        val seen = mutableListOf<VehicleData>()
        // Before the install: the orchestrator captures this callback at
        // construction, exactly as it captures orchestratorClockForTest.
        repo.vehicleDataTapForTest = { seen += it }
        val funnels = repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)

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
    fun `the aggregate follows the hosted path the moment the direct link is released`() = repoTest { repo ->
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
    fun `dropping the head-unit link re-raises the direct BMS link`() = repoTest { repo ->
        val v = twoPathScooter()
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()
        assertEquals(listOf(HU_ADDR), repo.linkAddressesForTest())

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

    /**
     * **The hole this task's first cut left open, and the harm it costs.**
     *
     * The head unit stays connected to the phone and keeps delivering its CAN
     * uBoxes; what it stops delivering is the battery — the ANT drifting out of
     * the HEAD UNIT's range, or unplugged from it. The link never drops, so a
     * re-raise triggered only by `onLinkDrop` never fires, and with our own
     * direct link already released the rider has no battery data for the rest
     * of the ride. That is precisely the §9.4 harm the release side is guarded
     * against, arriving from the other direction.
     *
     * Asserted on the AGGREGATE, because "the direct link came back" is
     * mechanism and "the battery the rider sees comes back" is the property.
     * The recovery step drives [KableBmsRepository.linkSampleFunnelsForTest] —
     * the funnels of the links the app *currently holds* — rather than the
     * install-time funnels the other tests use, precisely so it cannot pass by
     * feeding a link the app no longer has: with the re-raise removed, the ANT
     * is not among them, nothing is driven, and the assertions below see the
     * head unit's frozen 73.4 V instead of a live 74.0 V.
     */
    @Test
    fun `a hosted battery that goes silent under a live head unit brings the direct link back`() = repoTest { repo ->
        var nowMs = 1_000_000L
        repo.orchestratorClockForTest = { Instant.fromEpochMilliseconds(nowMs) }
        val v = twoPathScooter()
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()
        assertEquals(listOf(HU_ADDR), repo.linkAddressesForTest(), "the direct link is released, as it should be")

        // The ride continues: the head unit's uBoxes keep reporting, so its
        // link stays healthy and its own stale-sample watchdog never fires.
        // The battery behind it has gone quiet for longer than any pack is
        // allowed to.
        nowMs += BleConfig.packOfflineAfterMs + 1_000L
        repo.linkMotionFunnelsForTest().single()(0, ControllerData(speedKmh = 31f, isConnected = true))
        runCurrent()

        assertContains(
            repo.linkAddressesForTest(),
            ANT_ADDR,
            "the head unit is up but no longer serving the battery — the direct link has to come back"
        )
        assertTrue(
            assertNotNull(repo.linkReconnectJobForTest(ANT_ADDR)).isActive,
            "and through the ordinary reconnect loop, exactly as on the drop path"
        )
        assertFalse(
            repo.activeVehicleData.value.aggregate.isConnected,
            "meanwhile the path we gave up on must not keep showing its frozen last reading — " +
                "absent is honest, stale-but-plausible is not"
        )

        // The ANT answers directly again. Only the links the app actually
        // holds can report, and the hosted path is still silent.
        val live = repo.linkSampleFunnelsForTest()
        repo.linkAddressesForTest().forEachIndexed { i, address ->
            if (address == ANT_ADDR) live[i](0, parkedSample(), emptyList())
        }
        runCurrent()

        val snap = repo.activeVehicleData.value
        assertTrue(snap.aggregate.isConnected, "the rider's battery is back")
        assertEquals(
            74.0f,
            snap.aggregate.voltage,
            absoluteTolerance = 0.001f,
            message = "and it is the direct path's live reading, not the head unit's frozen one"
        )
        assertEquals(1, snap.packs.size, "still one battery — the alias group is counted once")
    }

    /**
     * **The head unit dies in the one-instruction window where the direct link
     * is owed a re-raise but has not been torn down yet.**
     *
     * The release cannot tear the link down inline — `disconnectLink` suspends
     * and takes `sessionLock`, neither of which the sample consumer may do — so
     * it records the debt, marks the direct pack offline and LAUNCHES the
     * teardown. For as long as that coroutine is queued the direct link is both
     * owed a return and still installed, and "already installed" is one of the
     * three refusals of the re-raise. A gateway drop landing there used to take
     * the debt, be refused, and throw it away; the teardown then ran with
     * nothing owing the link a return, and the rider had no battery for the
     * rest of the ride — head unit gone, own link deliberately dropped.
     *
     * Driven by ordering the two coroutines on the test dispatcher: the drop is
     * queued FIRST and the hosted sample arrives second, so when `runCurrent`
     * drains the queue the drop is serviced while the release's `disconnectLink`
     * is still waiting behind it. That is the real interleaving, not a mock of
     * one.
     *
     * Asserted on the AGGREGATE the rider sees, and specifically on the direct
     * path's live 74.0 V, driven through
     * [KableBmsRepository.linkSampleFunnelsForTest] — the funnels of the links
     * the app CURRENTLY holds. With the debt discarded the ANT is not among
     * them, nothing is driven, and the last snapshot the orchestrator published
     * is still the dead head unit's frozen 73.4 V on pack 1.
     *
     * Recorded through [KableBmsRepository.vehicleDataTapForTest] rather than
     * read off `activeVehicleData` at the end, because the tail of this drain
     * belongs to the reconnect loops both halves start: with no real peripheral
     * behind them their first attempt fails, and a failing attempt that had to
     * rebuild the pipeline clears it — blanking `activeVehicleData` for reasons
     * that have nothing to do with the handoff. The tap sees what the
     * orchestrator actually published while it was alive.
     */
    @Test
    fun `a head unit that dies mid-release still gives the direct link back`() = repoTest { repo ->
        val v = twoPathScooter()
        val seen = mutableListOf<VehicleData>()
        repo.vehicleDataTapForTest = { seen += it }
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)

        // Three coroutines, queued in this order and drained in it:
        //  (1) the head unit's drop — serviced while (2)'s teardown is still
        //      pending, which IS the window;
        //  (2) the release, queued by the hosted sample's consumer pass;
        //  (3) the ANT answering directly again — queued from the test body, so
        //      it lands after (1) and (2) but ahead of the reconnect loops they
        //      start, whose doomed first attempt would otherwise tear the test
        //      pipeline down before the battery could be observed.
        repo.simulateLinkDropForTest(HU_ADDR, "head unit dies mid-release")  // (1)
        funnels[1](0, hostedSample(), emptyList())                           // (2)
        launch {                                                             // (3)
            val live = repo.linkSampleFunnelsForTest()
            repo.linkAddressesForTest().forEachIndexed { i, address ->
                if (address == ANT_ADDR) live[i](0, parkedSample(), emptyList())
            }
        }
        runCurrent()

        // The property first, the mechanism after it.
        val snap = seen.last()
        assertTrue(snap.aggregate.isConnected, "the rider's battery survived the head unit")
        assertEquals(
            74.0f,
            snap.aggregate.voltage,
            absoluteTolerance = 0.001f,
            message = "the battery must be the direct path's live reading, not the dead head unit's frozen one"
        )
        assertEquals(1, snap.packs.size, "still one battery — the alias group is counted once")
        assertEquals(0, snap.packs.single().pack.index, "and the path serving it is the direct one")

        assertContains(
            repo.linkAddressesForTest(),
            ANT_ADDR,
            "a re-raise refused because the link was still installed must not be a re-raise CANCELLED"
        )
        assertTrue(
            assertNotNull(repo.linkReconnectJobForTest(ANT_ADDR)).isActive,
            "and it comes back through the ordinary reconnect loop, as on every other re-raise path"
        )
    }

    // ----- 3b. Damping: the battery must not blink absent on a flapping head unit -----

    /**
     * A hosted BMS that reports roughly on the `packOfflineAfterMs` boundary
     * makes the handoff oscillate: report → we release the direct link, go
     * quiet → we take it back, report → we release it again. Each cycle leaves
     * the battery on NEITHER path for a moment (the direct link is only
     * reconnecting, the hosted one has just been retired), so the rider watches
     * the battery blink absent over and over.
     *
     * The damper is a hold-down started by the re-raise: for
     * [KableBmsRepository.HANDOFF_RELEASE_HOLD_DOWN_MS] afterwards this gateway
     * cannot take the direct link again. Deliberately a DELAY and not a veto —
     * the second half of this test is the half that keeps it honest.
     */
    @Test
    fun `a head unit whose battery just went silent cannot take the direct link straight back`() = repoTest { repo ->
        var nowMs = 1_000_000L
        repo.orchestratorClockForTest = { Instant.fromEpochMilliseconds(nowMs) }
        val v = twoPathScooter()
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)

        funnels[1](0, hostedSample(), emptyList())
        runCurrent()
        assertEquals(listOf(HU_ADDR), repo.linkAddressesForTest(), "the first handoff is uncontroversial")

        // The hosted battery goes quiet while the uBoxes keep the link healthy,
        // so the direct link comes back — one full flap.
        nowMs += BleConfig.packOfflineAfterMs + 1_000L
        repo.linkMotionFunnelsForTest().single()(0, ControllerData(speedKmh = 31f, isConnected = true))
        runCurrent()
        assertContains(repo.linkAddressesForTest(), ANT_ADDR, "the re-raise fires, as it must")

        // And the hosted battery pipes up again seconds later. Handing the link
        // straight back is what makes the battery blink.
        nowMs += 5_000L
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()

        assertContains(
            repo.linkAddressesForTest(),
            ANT_ADDR,
            "a head unit that just lost the battery has not earned it back yet — releasing again " +
                "here is the flap the rider sees as the battery blinking absent"
        )
        assertEquals(2, repo.linkCountForTest())
    }

    /**
     * The other side of the damper: it must not become a veto. A head unit that
     * genuinely settles down and keeps serving the battery still wins the
     * direct link — the hold-down only postpones the decision, it never
     * cancels it.
     */
    @Test
    fun `a head unit that settles down still wins the battery once the hold-down expires`() = repoTest { repo ->
        var nowMs = 1_000_000L
        repo.orchestratorClockForTest = { Instant.fromEpochMilliseconds(nowMs) }
        val v = twoPathScooter()
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)

        funnels[1](0, hostedSample(), emptyList())
        runCurrent()
        nowMs += BleConfig.packOfflineAfterMs + 1_000L
        repo.linkMotionFunnelsForTest().single()(0, ControllerData(speedKmh = 31f, isConnected = true))
        runCurrent()
        assertEquals(2, repo.linkCountForTest(), "the link is back and the hold-down is armed")

        nowMs += KableBmsRepository.HANDOFF_RELEASE_HOLD_DOWN_MS
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()

        assertEquals(
            listOf(HU_ADDR),
            repo.linkAddressesForTest(),
            "the hold-down delays the handoff; it does not cancel it"
        )
    }

    /**
     * Re-raising must be idempotent per drop. A dead link is reported twice in
     * practice — the peripheral's state observer and the stale-sample watchdog
     * both fire — and `onLinkDrop` dedups on "believed up", which the re-raise
     * has to sit inside or the returning link is raised twice and the second
     * copy fights the first for the same address.
     */
    @Test
    fun `a duplicate drop report does not raise the direct link twice`() = repoTest { repo ->
        val v = twoPathScooter()
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()

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

    /**
     * A full user disconnect must not be undone by a drop report racing it —
     * the re-raise resurrects a link, and doing that after the sweep would
     * leave a live link behind a connection the user closed.
     *
     * What this pins, precisely: `onLinkDrop`'s OWN `userInitiatedDisconnect`
     * guard, which returns before the re-raise is ever entered — the outcome,
     * not the mechanism. The three refusals inside the re-raise itself are
     * unreachable from here (`disconnect()` sets the flag and empties the link
     * list in one critical section, so it can never present them one at a
     * time) and are pinned individually against [yieldedLinksToRaise] in
     * [AliasHandoffTest].
     */
    @Test
    fun `a released link is not re-raised after the user disconnects`() = repoTest { repo ->
        val v = twoPathScooter()
        val funnels = repo.rideReady(v)
        repo.markLinkOnlineForTest(HU_ADDR)
        funnels[1](0, hostedSample(), emptyList())
        runCurrent()

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

    // ----- 4. The toggle -----

    @Test
    fun `an alias group spanning direct and hosted plans the handoff by default`() = repoTest { repo ->
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
    fun `turning the toggle off keeps both links up when the hosted battery reports`() = repoTest { repo ->
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
    fun `a one-BMS vehicle with no gateway plans nothing and keeps its link`() = repoTest { repo ->
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
    fun `an ordinary two-BMS vehicle is untouched`() = repoTest { repo ->
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
