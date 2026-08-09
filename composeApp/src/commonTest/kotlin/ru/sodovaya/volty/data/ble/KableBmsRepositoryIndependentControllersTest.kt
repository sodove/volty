package ru.sodovaya.volty.data.ble

import app.cash.turbine.test
import ru.sodovaya.volty.data.bms.VescProtocol
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Part C task 6 (characterisation, spec `C-multi-controller.md` §7): "no CAN —
 * two separate BLE links, each a single-controller [VescProtocol]" is claimed
 * to already work end to end, folded through the same multi-link machinery
 * and [ru.sodovaya.volty.domain.stats.MotionAggregator] Part A shipped for
 * packs. Nothing before this task actually drove TWO controllers together
 * through the real repository wiring, so this file is the proof.
 *
 * What already had coverage before this task, deliberately NOT repeated here:
 *  - the pure fold arithmetic (sum current/power, max speed/duty/temp, the
 *    offline/partial rules) — [ru.sodovaya.volty.domain.stats.MotionAggregatorTest],
 *    driven against hand-built `ControllerState` lists with no BLE at all;
 *  - [VehicleConnection.submitMotion]'s own per-controller staleness sweep
 *    (a controller silent past `BleConfig.packOfflineAfterMs` goes offline off
 *    a sibling's submit, latent materialisation) — [VehicleConnectionMotionTest];
 *  - ONE controller through the full repo pipeline (funnel → `activeMotion` /
 *    `activeVehicleData`) — [KableBmsRepositoryMotionTest], [KableBmsRepositoryVescTest];
 *  - TWO independent links folding on the BATTERY side (sum current, partial,
 *    one link dropping, reconnect) — [KableBmsRepositoryMultiLinkTest].
 *
 * What was missing, and what this file pins: two controllers at two distinct
 * BLE addresses, planned by the real [planLinks] into two non-gateway links
 * (each resolving to a plain [VescProtocol], not [ru.sodovaya.volty.data.bms.VescGatewayProtocol]),
 * driven through [KableBmsRepository.installLinksForTest] and its motion
 * funnels — proving the wiring, not just the maths — plus the controller-side
 * mirror of the battery multi-link drop test: one link's [ConnectionState]
 * folding survives while the other keeps reporting.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryIndependentControllersTest {

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
     * An AWD scooter with two independently-addressed VESCs and no CAN link
     * between them — the exact scenario spec §7 describes, and the shape
     * Part E's FarDriver AWD reuses verbatim once its protocol lands.
     */
    private fun dualVescVehicle(): Vehicle = Vehicle(
        id = "v-dual-vesc",
        name = "AWD Scooter",
        iconKey = "scooter",
        packs = emptyList(),
        controllers = listOf(
            Controller(index = 0, label = "Front", controllerType = ControllerType.VESC, address = ADDR_A),
            Controller(index = 1, label = "Rear", controllerType = ControllerType.VESC, address = ADDR_B)
        ),
        topology = PackTopology.PARALLEL,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    private fun motion(speed: Float, duty: Float, motorA: Float, battA: Float, power: Float) = ControllerData(
        speedKmh = speed,
        speedSource = SpeedSource.REPORTED,
        dutyPercent = duty,
        motorCurrentA = motorA,
        batteryCurrentA = battA,
        powerW = power,
        isConnected = true
    )

    // ----- Both links raise, each a plain (non-gateway) VescProtocol -----

    @Test
    fun `two distinct controller addresses raise two non-gateway VescProtocol links`() = repoTest { repo ->
        val v = dualVescVehicle()
        val funnels = repo.installLinksForTest(v, v.primaryAddress, type = null)
        assertEquals(2, funnels.size, "two distinct controller addresses must raise two links")
        assertEquals(2, repo.linkCountForTest())

        val specs = repo.linkSpecsForTest()
        assertEquals(setOf(ADDR_A, ADDR_B), specs.map { it.address }.toSet())
        specs.forEach { spec ->
            // Full-equality, not `isGatewayLink == false` (which the fixture
            // forces) nor a bare size check: the load-bearing fact is that an
            // independently-addressed controller still plans to the UNTAGGED
            // Part A shape. A canId or a kind appearing here is what would flip
            // the link onto the gateway multiplexer, and it is invisible to the
            // compiler — same type, fields just not passed.
            assertEquals(
                listOf(OwnedSource(globalIndex = spec.ownedControllers.single().globalIndex)),
                spec.ownedControllers,
                "one plain controller, no canId and no kind tag — exactly as before Part C"
            )
            assertIs<VescProtocol>(
                repo.createProtocolForTest(spec, v),
                "each link must build a plain single-controller VescProtocol, not the gateway multiplexer"
            )
        }
    }

    // ----- The fold: sum current/power, max speed/duty, through the REAL funnels -----

    @Test
    fun `the aggregate sums current and power but takes the max of speed and duty across both links`() = repoTest { repo ->
        val v = dualVescVehicle()
        repo.installLinksForTest(v, v.primaryAddress, type = null)
        val funnels = repo.linkMotionFunnelsForTest()
        assertEquals(2, funnels.size)

        // Each link speaks its own LOCAL index 0 (one controller apiece); the
        // funnel translates it to that link's owned GLOBAL controller index —
        // this is the production translation, not a hand-built ControllerState.
        funnels[0](0, motion(speed = 30f, duty = 50f, motorA = 20f, battA = 10f, power = 700f))
        funnels[1](0, motion(speed = 29f, duty = 55f, motorA = 22f, battA = 12f, power = 800f))
        advanceUntilIdle()

        val vd = repo.activeVehicleData.value
        assertEquals(2, vd.controllers.size, "both global controller slots must materialise")
        assertTrue(vd.controllers.all { it.isOnline })
        assertFalse(vd.motionPartial)

        assertEquals(30f, vd.motion.speedKmh, absoluteTolerance = 0.001f) // MAX(30, 29)
        assertEquals(55f, vd.motion.dutyPercent, absoluteTolerance = 0.001f) // MAX(50, 55)
        assertEquals(42f, vd.motion.motorCurrentA, absoluteTolerance = 0.001f) // SUM(20, 22)
        assertEquals(22f, vd.motion.batteryCurrentA, absoluteTolerance = 0.001f) // SUM(10, 12)
        assertEquals(1500f, vd.motion.powerW, absoluteTolerance = 0.001f) // SUM(700, 800)

        // The published surface must agree with the vehicle-level snapshot.
        assertEquals(vd.motion, repo.activeMotion.value)
    }

    @Test
    fun `activeMotion emits the folded aggregate as each link reports, via Turbine`() = repoTest { repo ->
        val v = dualVescVehicle()
        repo.installLinksForTest(v, v.primaryAddress, type = null)
        val funnels = repo.linkMotionFunnelsForTest()

        repo.activeMotion.test {
            assertEquals(SpeedSource.NONE, awaitItem().speedSource)

            funnels[0](0, motion(speed = 30f, duty = 50f, motorA = 20f, battA = 10f, power = 700f))
            testScheduler.advanceUntilIdle()
            val afterFront = awaitItem()
            assertEquals(30f, afterFront.speedKmh, absoluteTolerance = 0.001f)
            assertEquals(10f, afterFront.batteryCurrentA, absoluteTolerance = 0.001f)

            funnels[1](0, motion(speed = 29f, duty = 55f, motorA = 22f, battA = 12f, power = 800f))
            testScheduler.advanceUntilIdle()
            val afterBoth = awaitItem()
            assertEquals(30f, afterBoth.speedKmh, absoluteTolerance = 0.001f) // still MAX
            assertEquals(22f, afterBoth.batteryCurrentA, absoluteTolerance = 0.001f) // now SUM
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ----- One link drops: the other keeps reporting, the vehicle stays online -----

    @Test
    fun `one link dropping leaves the other controller reporting and the vehicle stays online`() = repoTest { repo ->
        val v = dualVescVehicle()
        repo.installLinksForTest(v, v.primaryAddress, type = null)
        repo.markLinkOnlineForTest(ADDR_A)
        repo.markLinkOnlineForTest(ADDR_B)
        val funnels = repo.linkMotionFunnelsForTest()

        funnels[0](0, motion(speed = 30f, duty = 50f, motorA = 20f, battA = 10f, power = 700f))
        funnels[1](0, motion(speed = 29f, duty = 55f, motorA = 22f, battA = 12f, power = 800f))
        assertFalse(repo.activeVehicleData.value.motionPartial)

        repo.simulateLinkDropForTest(ADDR_B, "Link dropped")
        runCurrent()

        // The vehicle stays Connected on the surviving link while preserving
        // the dropped controller's reconnect reason. Mirrors
        // KableBmsRepositoryMultiLinkTest's battery-side sibling, but for a
        // link that owns a CONTROLLER rather than a pack.
        val connected = repo.connectionState.value as ConnectionState.Connected
        val retrying = connected.linkReconnecting.single()
        assertEquals(ADDR_B, retrying.address)
        assertEquals("Link dropped", retrying.reason)
        assertNull(repo.linkReconnectJobForTest(ADDR_A), "the healthy link must not be disturbed")
        val jobB = assertNotNull(repo.linkReconnectJobForTest(ADDR_B))
        assertTrue(jobB.isActive)

        // The drop path retires the rear immediately; no sibling sample or
        // staleness wait is required before its cached current disappears.
        val afterDrop = repo.activeVehicleData.value
        assertTrue(afterDrop.motionPartial)
        assertFalse(afterDrop.controllers[1].isOnline)
        assertEquals(10f, afterDrop.motion.batteryCurrentA, absoluteTolerance = 0.001f)

        // Front keeps sampling and remains the complete live aggregate.
        funnels[0](0, motion(speed = 30f, duty = 50f, motorA = 20f, battA = 10f, power = 700f))

        val snap = repo.activeVehicleData.value
        assertTrue(snap.motionPartial)
        assertTrue(snap.controllers[0].isOnline)
        assertFalse(snap.controllers[1].isOnline)
        assertEquals(30f, snap.motion.speedKmh, absoluteTolerance = 0.001f)
        assertEquals(10f, snap.motion.batteryCurrentA, absoluteTolerance = 0.001f) // dead link's current drops out of the sum
        assertEquals(700f, snap.motion.powerW, absoluteTolerance = 0.001f)
        assertTrue(repo.connectionState.value is ConnectionState.Connected, "the vehicle stays online on the survivor")

        repo.disconnect()
        runCurrent()
        assertFalse(jobB.isActive, "disconnect must stop the dropped link's loop")
    }

    // ----- `I` Task 8: the retained motion window is VEHICLE-level, not per-controller -----

    /**
     * **[KableBmsRepository.motionSamples] retains the fold, not the contributors.**
     *
     * The buffer behind it was written since Part A and read nowhere, so nothing
     * had ever forced the question. Its one consumer integrates `power × dt`
     * ([ru.sodovaya.volty.domain.stats.RideEnergy]) — and on this vehicle the raw
     * samples INTERLEAVE front, rear, front, rear. A trapezoid over that reads one
     * controller's 700 W as the whole vehicle's, i.e. reports roughly half the
     * energy an AWD scooter actually drew, which is the same class of quiet lie
     * this whole part is about.
     *
     * Asserted through the real link funnels rather than by pushing into the
     * buffer, because the decision under test is *which value the funnel pushes*.
     */
    @Test
    fun `the retained motion window holds the vehicle-level fold and not one controller's sample`() = repoTest { repo ->
        val v = dualVescVehicle()
        repo.installLinksForTest(v, v.primaryAddress, type = null)
        val funnels = repo.linkMotionFunnelsForTest()

        funnels[0](0, motion(speed = 30f, duty = 50f, motorA = 20f, battA = 10f, power = 700f))
        advanceUntilIdle()
        funnels[1](0, motion(speed = 29f, duty = 55f, motorA = 22f, battA = 12f, power = 800f))
        advanceUntilIdle()

        val window = repo.motionSamples(1.hours).first()
        assertEquals(2, window.size, "one retained entry per motion sample, as before")
        // The first entry is the fold of the front controller alone (the rear has
        // not reported yet); the second is the fold of both. Neither is a raw
        // contributor: 1500 W is a number no single sample carried.
        assertEquals(700f, window[0].powerW, absoluteTolerance = 0.001f)
        assertEquals(
            1500f,
            window[1].powerW,
            absoluteTolerance = 0.001f,
            "SUM(700, 800) — the raw rear sample's own 800 W would be the per-controller bug"
        )
        assertEquals(22f, window[1].batteryCurrentA, absoluteTolerance = 0.001f)
        assertEquals(30f, window[1].speedKmh, absoluteTolerance = 0.001f, "MAX, not the rear's 29")
        assertEquals(repo.activeMotion.value, window.last(), "the retained tail IS what activeMotion published")
    }

    /**
     * A motion sample the orchestrator cannot place is not retained.
     *
     * `submitMotion` returns its snapshot unchanged for an unknown controller
     * index, and with no controller online at all that snapshot is
     * `ControllerData(isConnected = false)` — a freshly-timestamped 0 W placeholder
     * whose `hasPower` still defaults true, i.e. exactly the phantom zero the
     * integrator must never be handed. A sample the vehicle refused to attribute
     * contributed no distance either, so it contributes no energy.
     */
    @Test
    fun `a motion sample the vehicle cannot place is not retained as a phantom zero`() = repoTest { repo ->
        val v = dualVescVehicle()
        repo.installLinksForTest(v, v.primaryAddress, type = null)
        val channel = assertNotNull(repo.sampleFunnelChannelForTest())

        // Global controller index 7 on a two-controller vehicle: no slot, no
        // latent slot, so `submitMotion` returns its snapshot untouched — and
        // with neither controller yet online that snapshot is
        // `ControllerData(isConnected = false)`, i.e. a 0 W row wearing a fresh
        // timestamp. Integrating THAT is the confident zero this contract is about.
        channel.trySend(
            MotionSample(
                globalControllerIndex = 7,
                data = motion(speed = 30f, duty = 50f, motorA = 20f, battA = 10f, power = 700f)
            )
        )
        advanceUntilIdle()

        assertFalse(repo.activeMotion.value.isConnected, "precondition: the vehicle placed nothing")
        assertEquals(
            emptyList(),
            repo.motionSamples(1.hours).first(),
            "nothing placed, nothing retained — neither the phantom nor the unattributed raw sample"
        )
    }

    /**
     * **[KableBmsRepository.motionSamples] emits once PER MOTION SAMPLE, and that
     * cadence is the contract — not merely the contents.**
     *
     * `RideDashboardComponent` is the sole writer of `sessionWhPerKm` for **every**
     * vehicle since `I` Task 8, counterless or not, and it writes only from this
     * flow. So a `motionSamples` that answered once and completed — a `flowOf(...)`,
     * a `take(1)`, a debounce — would freeze the consumption chip on real hardware
     * for the rest of the ride, including a VESC's *measured* average, which worked
     * before this branch. The two tests above read the flow with `first()`: they
     * assert what it says and nothing about how often it says it, so neither of
     * them can see that regression.
     *
     * Three emissions, not two: a `StateFlow`-derived flow replays its current
     * value on subscription, and that initial empty window is what the component's
     * collector starts from.
     */
    @Test
    fun `the retained motion window re-emits on every sample and not once per collector`() = repoTest { repo ->
        val v = dualVescVehicle()
        repo.installLinksForTest(v, v.primaryAddress, type = null)
        val funnels = repo.linkMotionFunnelsForTest()
        val scheduler = testScheduler

        repo.motionSamples(1.hours).test {
            assertEquals(emptyList(), awaitItem(), "the window as it stands at subscription")

            funnels[0](0, motion(speed = 30f, duty = 50f, motorA = 20f, battA = 10f, power = 700f))
            scheduler.advanceUntilIdle()
            assertEquals(1, awaitItem().size, "the front controller's sample re-opened the window")

            funnels[1](0, motion(speed = 29f, duty = 55f, motorA = 22f, battA = 12f, power = 800f))
            scheduler.advanceUntilIdle()
            val second = awaitItem()
            assertEquals(2, second.size, "and so did the rear's — a one-shot flow ends before here")
            assertEquals(1500f, second.last().powerW, absoluteTolerance = 0.001f)

            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val ADDR_A = "AA:BB:CC:DD:EE:0A"
        const val ADDR_B = "AA:BB:CC:DD:EE:0B"
    }
}
