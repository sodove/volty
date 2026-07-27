package ru.sodovaya.volty.data.demo

import ru.sodovaya.volty.data.ble.KableBmsRepository
import ru.sodovaya.volty.data.ble.bleRepositoryTest
import ru.sodovaya.volty.data.ble.planLinks
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DEMO_VEHICLE_ID
import ru.sodovaya.volty.domain.model.DEMO_WHEEL_VEHICLE_ID
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.presentation.root.Config
import ru.sodovaya.volty.presentation.root.homeConfigFor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * Part D Task 6: the wheel-shaped demo vehicle.
 *
 * A Begode electric unicycle is not a small scooter, and the point of this
 * profile is that somebody can see a WHEEL's dashboard without owning one. So
 * what is asserted here is precisely the set of things that differ from
 * [DemoProfile.SCOOTER] — the ones a reviewer would otherwise have to take on
 * trust from a screenshot:
 *
 *  - one BLE link owning one controller and TWO parallel branches;
 *  - duty that never reaches zero, because a wheel balances;
 *  - two temperature sensors that cross over under load;
 *  - phase current several times the battery current;
 *  - no energy counters and no eRPM at all;
 *  - a state of charge that comes from `VoltageSocEstimator`, because a Begode
 *    reports none.
 *
 * Compose is not unit-testable in this repo (no Robolectric, no
 * `compose-ui-test`, no instrumented source set), so nothing here asserts a
 * pixel. What it asserts is the DATA the two renderers are given — which is
 * where every dial's value, blank and severity is decided
 * ([ru.sodovaya.volty.presentation.ride.ClassicDialSpecs],
 * [ru.sodovaya.volty.presentation.ride.SecondaryGaugeMapper], both pure).
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class DemoWheelTest {

    private class RecordingVehicleRepository : VehicleRepository {
        val upserts = mutableListOf<Vehicle>()
        val touches = mutableListOf<String>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) { upserts += vehicle }
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) { touches += id }
    }

    private val wheel = DemoBmsSimulator(profile = DemoProfile.WHEEL)
    private val scooter = DemoBmsSimulator(profile = DemoProfile.SCOOTER)

    /** Tick index (700 ms cadence) landing on or just before [sec]. */
    private fun tickForSecond(sec: Double): Int =
        (sec * 1000.0 / DemoBmsSimulator.TICK_INTERVAL_MS).toInt()

    // Landmarks in the 180 s phase cycle: riding 0..100 s, stopped 100..120 s,
    // charging 120..180 s. CRUISE sits past the wheel's 4 s acceleration ramp
    // and well before its 5 s deceleration one.
    private val cruiseTick = tickForSecond(30.0)
    private val stoppedTick = tickForSecond(110.0)
    private val chargingTick = tickForSecond(150.0)

    // ---------------------------------------------------------------------
    // The vehicle's shape
    // ---------------------------------------------------------------------

    @Test
    fun `the demo wheel is one link owning one controller and two parallel branches`() {
        val v = KableBmsRepository.DEMO_WHEEL_VEHICLE

        assertEquals(2, v.packs.size, "a Begode is two parallel branches, got ${v.packs.size}")
        assertEquals(1, v.controllers.size, "a Begode is exactly one controller")
        assertEquals(PackTopology.PARALLEL, v.topology)
        assertEquals(ControllerType.BEGODE, v.controllers.single().controllerType)
        assertTrue(v.packs.all { it.bmsType == BmsType.BEGODE }, "branches must be BEGODE packs: ${v.packs.map { it.bmsType }}")

        // The whole point: ONE address, so planLinks folds all three sources
        // into a single BLE link — which is what a wheel physically is.
        val addresses = (v.packs.map { it.bmsAddress } + v.controllers.map { it.address }).distinct()
        assertEquals(1, addresses.size, "a wheel exposes one address, got $addresses")

        val links = planLinks(v.packs, v.controllers)
        assertEquals(1, links.size, "a wheel must plan to exactly ONE link, got $links")
        assertEquals(listOf(0), links.single().ownedControllers.map { it.globalIndex })
        assertEquals(listOf(0, 1), links.single().ownedPacks.map { it.globalIndex })
    }

    @Test
    fun `the demo wheel is a demo vehicle and its home is the Ride dashboard`() {
        val v = KableBmsRepository.DEMO_WHEEL_VEHICLE
        assertEquals(DEMO_WHEEL_VEHICLE_ID, v.id)
        assertTrue(
            v.isDemo,
            "the wheel demo must satisfy isDemo — every never-persist guard in the app reads it"
        )
        assertEquals(
            Config.Ride,
            homeConfigFor(v),
            "a vehicle with a controller lands on Ride; a wheel demo that landed on the battery dashboard would show none of the dials this task is about"
        )
    }

    // ---------------------------------------------------------------------
    // The ride curve
    // ---------------------------------------------------------------------

    @Test
    fun `wheel duty never reaches zero because a wheel is always balancing`() {
        // The single most recognisable thing about a wheel's dashboard: parked
        // and even on the charger, the DUTY dial does not read 0 — the wheel is
        // holding its rider up. The ET Max capture reads 1-2 % standing still.
        val stopped = wheel.motionAt(stoppedTick)
        val charging = wheel.motionAt(chargingTick)

        assertEquals(0f, stopped.speedKmh, "a stopped wheel reports no ground speed")
        assertEquals(0f, charging.speedKmh)
        assertTrue(
            stopped.dutyPercent > 1.0f && stopped.dutyPercent < 2.5f,
            "a parked wheel must still show its balancing duty, was ${stopped.dutyPercent}"
        )
        assertTrue(
            charging.dutyPercent > 1.0f && charging.dutyPercent < 2.5f,
            "a wheel on the charger is still upright, was ${charging.dutyPercent}"
        )

        // ...and nowhere in the whole cycle does it fall to zero.
        for (tick in 0 until tickForSecond(400.0) step 3) {
            val d = wheel.motionAt(tick).dutyPercent
            assertTrue(d > 1.0f, "wheel duty collapsed to $d at tick=$tick")
            assertTrue(d < 70f, "wheel duty implausibly high ($d) at tick=$tick")
        }

        // The contrast that makes the assertion above mean something: the
        // SCOOTER profile's duty IS zero when it stops.
        assertEquals(
            0f,
            scooter.motionAt(stoppedTick).dutyPercent,
            "the scooter curve must be untouched — its duty is 0 while parked"
        )
    }

    @Test
    fun `wheel duty rises with speed`() {
        // Both ticks land inside the wheel's 4 s acceleration ramp.
        val early = wheel.motionAt(tickForSecond(1.4))
        val later = wheel.motionAt(tickForSecond(3.5))

        assertTrue(later.speedKmh > early.speedKmh, "speed should have risen: ${early.speedKmh} -> ${later.speedKmh}")
        assertTrue(
            later.dutyPercent > early.dutyPercent + 10f,
            "duty must track rising speed: ${early.dutyPercent} -> ${later.dutyPercent}"
        )

        // A wheel is not a small scooter: it is at cruise inside ~4 s, where the
        // scooter's own curve is still barely half way up an 8 s ramp, and it
        // cruises SLOWER. Both are what makes the wheel's speedo read like a
        // wheel's rather than like the same curve renamed.
        assertTrue(
            later.speedKmh > 18f,
            "a wheel reaches cruise fast — at 3.5 s it should be near cruise, was ${later.speedKmh}"
        )
        val cruising = wheel.motionAt(cruiseTick)
        assertTrue(
            cruising.speedKmh in 22f..27f,
            "the wheel's cruise band is ~24 km/h, was ${cruising.speedKmh}"
        )
    }

    @Test
    fun `every wheel sample reports ground speed and measured hardware PWM`() {
        for (tick in 0 until tickForSecond(400.0) step 7) {
            val m = wheel.motionAt(tick)
            assertEquals(
                SpeedSource.REPORTED, m.speedSource,
                "a wheel reports ground speed — there is no MotorConfig to derive it from (tick=$tick)"
            )
            assertTrue(m.hasDuty, "the wheel's truePWM latch is closed: hasDuty must be true (tick=$tick)")
            assertTrue(m.hasMotorTemp, "the wheel has a motor thermistor (tick=$tick)")
            assertTrue(m.isConnected, "demo controller should report connected (tick=$tick)")
        }
    }

    @Test
    fun `a wheel reports no energy counters and no eRPM`() {
        // Taken at a tick where the wheel is demonstrably MOVING and has
        // travelled — so these zeros are "the frames carry no such field",
        // not "nothing has happened yet".
        val m = wheel.motionAt(cruiseTick)
        assertTrue(m.speedKmh > 20f, "fixture must be moving for this to mean anything, was ${m.speedKmh}")
        assertTrue(m.tripKm > 0.1f, "fixture must have travelled, trip was ${m.tripKm}")

        assertEquals(0f, m.eRpm, "a Begode reports no eRPM at all")
        assertEquals(0f, m.consumedAh, "a Begode reports no consumed Ah")
        assertEquals(0f, m.consumedWh, "a Begode reports no consumed Wh")
        assertEquals(0f, m.regenAh, "a Begode reports no regen Ah")
        assertEquals(0f, m.regenWh, "a Begode reports no regen Wh")

        // The contrast: the scooter profile DOES carry energy counters, so the
        // zeros above are a property of the wheel, not of the simulator.
        val s = scooter.motionAt(cruiseTick)
        assertTrue(s.consumedWh > 0f, "the scooter curve must be untouched, consumedWh was ${s.consumedWh}")
        assertTrue(s.eRpm > 0f, "the scooter curve must be untouched, eRpm was ${s.eRpm}")
    }

    @Test
    fun `board and motor temperature are two independent sensors that cross over`() {
        // Parked: the capture's own picture — a warm mainboard (27.5 C) beside
        // a cold motor (20 C).
        val parked = wheel.motionAt(stoppedTick)
        assertTrue(
            parked.motorTempC < parked.escTempC - 5f,
            "parked, the motor must be clearly COLDER than the board: motor=${parked.motorTempC} board=${parked.escTempC}"
        )

        // Cruising: the motor couples harder to load and overtakes the board.
        // A model where motor temperature is a fixed offset of the board's — the
        // scooter's `escTemp - 3` — can never produce this.
        val cruising = wheel.motionAt(cruiseTick)
        assertTrue(
            cruising.motorTempC > cruising.escTempC + 1f,
            "under sustained cruise the motor must overtake the board: motor=${cruising.motorTempC} board=${cruising.escTempC}"
        )
        assertTrue(cruising.escTempC in 25f..45f, "board temp implausible: ${cruising.escTempC}")
        assertTrue(cruising.motorTempC in 25f..50f, "motor temp implausible: ${cruising.motorTempC}")
    }

    @Test
    fun `phase current runs well above battery current, from a different frame`() {
        val cruising = wheel.motionAt(cruiseTick)
        assertTrue(cruising.batteryCurrentA > 4f, "cruising battery current was ${cruising.batteryCurrentA}")
        assertTrue(
            cruising.motorCurrentA > cruising.batteryCurrentA * 1.7f,
            "phase current must run well above battery current: phase=${cruising.motorCurrentA} battery=${cruising.batteryCurrentA}"
        )

        // Parked, the capture's own ratio: -0.67 A battery against -3.00 A phase.
        val parked = wheel.motionAt(stoppedTick)
        assertTrue(parked.batteryCurrentA > 0.2f, "a balancing wheel still draws, was ${parked.batteryCurrentA}")
        assertTrue(
            parked.motorCurrentA > parked.batteryCurrentA * 1.7f,
            "parked phase current must exceed battery current: phase=${parked.motorCurrentA} battery=${parked.batteryCurrentA}"
        )
        assertTrue(parked.motorCurrentA < 6f, "a parked wheel's phase current must not diverge, was ${parked.motorCurrentA}")
    }

    @Test
    fun `the wheel odometer starts at a used wheel's lifetime reading and only grows`() {
        // The ET Max capture's own lifetime odometer, 8 565 341 m.
        assertTrue(
            wheel.motionAt(0).odometerKm > 8000f,
            "the demo wheel is a used wheel, odometer was ${wheel.motionAt(0).odometerKm}"
        )
        assertEquals(0f, wheel.motionAt(0).tripKm, "trip starts at zero")

        var prevOdo = -1f
        var prevTrip = -1f
        for (tick in 0 until tickForSecond(400.0) step 5) {
            val m = wheel.motionAt(tick)
            assertTrue(m.odometerKm >= prevOdo, "odometer decreased at tick=$tick: $prevOdo -> ${m.odometerKm}")
            assertTrue(m.tripKm >= prevTrip, "trip decreased at tick=$tick: $prevTrip -> ${m.tripKm}")
            prevOdo = m.odometerKm
            prevTrip = m.tripKm
        }
        assertTrue(prevTrip > 0.5f, "the wheel must actually travel across a cycle, trip was $prevTrip")
    }

    @Test
    fun `the wheel rail voltage is a 24s pack, sagging under load`() {
        for (tick in 0 until tickForSecond(400.0) step 11) {
            val v = wheel.motionAt(tick).inputVoltageV
            assertTrue(v in 85f..101f, "rail voltage $v is not a 24s Li-ion pack at tick=$tick")
        }

        // Sag: at cruise the controller's rail sits measurably BELOW the
        // branch's own resting cell sum from the same tick.
        val motion = wheel.motionAt(cruiseTick)
        val branch = wheel.packSampleAt(cruiseTick, 0)
        assertTrue(
            branch.voltage - motion.inputVoltageV > 0.2f,
            "the rail must sag under load: resting=${branch.voltage} loaded=${motion.inputVoltageV}"
        )

        // Power is the product of the two things the wheel actually reports.
        assertTrue(
            abs(motion.powerW - motion.inputVoltageV * motion.batteryCurrentA) < 1f,
            "powerW must be voltage x battery current, was ${motion.powerW}"
        )
        assertTrue(motion.powerW in 400f..800f, "a wheel cruising at 25 km/h draws a few hundred watts, was ${motion.powerW}")
    }

    @Test
    fun `the two branches are distinct samples and neither reports a fuel gauge`() {
        val branches = wheel.packSamplesAt(cruiseTick)
        assertEquals(2, branches.size)
        assertEquals(
            DemoBmsSimulator.WHEEL_CELL_COUNT, branches[0].cellVoltages.size,
            "each branch is its own full series string"
        )
        assertEquals(DemoBmsSimulator.WHEEL_CELL_COUNT, branches[1].cellVoltages.size)
        assertNotEquals(
            branches[0].cellVoltages, branches[1].cellVoltages,
            "two real branches never agree to the millivolt"
        )
        for (b in branches) {
            assertFalse(b.socKnown, "a Begode branch reports no state of charge")
            assertEquals(0f, b.soc, "an unknown SoC must be published as 0, flagged by socKnown")
            assertEquals(0f, b.capacity, "a Begode reports no capacity")
            assertTrue(b.current < 0f, "both branches must be discharging while riding, was ${b.current}")
        }

        // The scooter profile still has exactly one pack of 16 cells.
        val scooterPacks = scooter.packSamplesAt(cruiseTick)
        assertEquals(1, scooterPacks.size, "the scooter curve must be untouched")
        assertEquals(DemoBmsSimulator.CELL_COUNT, scooterPacks[0].cellVoltages.size)
        assertTrue(scooterPacks[0].socKnown, "a JK BMS reports a real SoC")
    }

    // ---------------------------------------------------------------------
    // End to end through the repository
    // ---------------------------------------------------------------------

    @Test
    fun `connecting the wheel demo folds two branches in parallel`() = bleRepositoryTest { repo ->
        repo.connectDemo(DemoProfile.WHEEL)
        runCurrent()
        advanceTimeBy(50_000L)
        runCurrent()

        assertEquals(DEMO_WHEEL_VEHICLE_ID, repo.activeVehicle.value?.id)

        val vd = repo.activeVehicleData.value
        assertEquals(2, vd.packs.size, "the wheel's two branches must both be published")
        assertEquals(PackTopology.PARALLEL, vd.topology)
        assertFalse(vd.isPartial, "both branches are online in the demo")

        // Parallel: the vehicle voltage is the branch voltage, NOT their sum —
        // a series fold would read ~190 V on a 100 V wheel.
        val branchVoltage = vd.packs[0].data.voltage
        assertTrue(branchVoltage in 85f..101f, "branch voltage $branchVoltage is not a 24s string")
        assertTrue(
            abs(vd.aggregate.voltage - branchVoltage) < 1f,
            "parallel branches must average, not sum: aggregate=${vd.aggregate.voltage} branch=$branchVoltage"
        )

        // ...and the current IS the sum.
        val summed = vd.packs[0].data.current + vd.packs[1].data.current
        assertTrue(summed < -1f, "the wheel must be drawing from both branches, sum was $summed")
        assertTrue(
            abs(vd.aggregate.current - summed) < 0.01f,
            "parallel currents must sum: aggregate=${vd.aggregate.current} sum=$summed"
        )

        // The Battery tab's own flow sees the whole wheel: both strings.
        assertEquals(
            2 * DemoBmsSimulator.WHEEL_CELL_COUNT,
            repo.activeData.value.cellVoltages.size,
            "activeData must carry every cell of both branches"
        )

        // The Ride dashboard's own feed.
        val motion = repo.activeMotion.value
        assertEquals(SpeedSource.REPORTED, motion.speedSource)
        assertTrue(motion.speedKmh > 5f, "the wheel must be moving at this tick, was ${motion.speedKmh}")
        assertTrue(motion.hasDuty, "the wheel measures duty")
        assertTrue(motion.dutyPercent > 20f, "duty must track the cruise speed, was ${motion.dutyPercent}")

        // The two streams describe ONE machine. BmsData counts a discharge
        // negative and ControllerData counts it positive, so the vehicle-level
        // pack current must be the negation of the controller's battery current
        // — which also pins that the branch samples SPLIT the wheel's load
        // rather than each carrying all of it.
        assertTrue(
            abs(vd.aggregate.current + motion.batteryCurrentA) < 1f,
            "battery and motion streams disagree about the load: pack=${vd.aggregate.current} controller=${motion.batteryCurrentA}"
        )

        // A wheel is ONE controller, published as such — the vehicle-level
        // motion fold has a single member and is therefore the identity
        // (proved whole-object, against the real capture, in Part D Task 5).
        assertEquals(1, vd.controllers.size, "a wheel has exactly one controller")
        assertEquals(ControllerType.BEGODE, vd.controllers.single().controller.controllerType)
        assertTrue(vd.controllers.single().isOnline)
        assertFalse(vd.motionPartial, "a single online controller is never a partial motion aggregate")

        repo.disconnect()
        runCurrent()
    }

    @Test
    fun `the wheel demo's state of charge comes from the voltage estimator`() = bleRepositoryTest { repo ->
        // The wheel itself reports none — proved directly on the simulator's
        // own output, so this test cannot pass by the sample already carrying
        // a SoC before it reaches the estimator.
        assertFalse(
            DemoBmsSimulator(profile = DemoProfile.WHEEL).packSampleAt(0, 0).socKnown,
            "precondition: a Begode branch sample carries no SoC"
        )

        repo.connectDemo(DemoProfile.WHEEL)
        runCurrent()
        advanceTimeBy(50_000L)
        runCurrent()

        val vd = repo.activeVehicleData.value
        assertTrue(
            vd.packs.all { it.data.socKnown },
            "VoltageSocEstimator must have filled each branch's SoC in on the demo path too"
        )
        assertTrue(vd.aggregate.socKnown, "the vehicle-level SoC must be known")
        assertTrue(
            vd.aggregate.soc in 30f..95f,
            "estimated SoC must be plausible, was ${vd.aggregate.soc}"
        )

        repo.disconnect()
        runCurrent()
    }

    @Test
    fun `the wheel demo is never persisted`(): TestResult {
        val store = RecordingVehicleRepository()
        return bleRepositoryTest(vehicleRepository = store) { repo ->
        repo.connectDemo(DemoProfile.WHEEL)
        runCurrent()
        advanceTimeBy(20_000L)
        runCurrent()

        assertTrue(store.upserts.isEmpty(), "the wheel demo must never be written to the vehicle store: ${store.upserts.map { it.id }}")
        assertTrue(store.touches.isEmpty(), "the wheel demo must never be touched: ${store.touches}")

        repo.disconnect()
        runCurrent()
        }
    }

    @Test
    fun `the default demo profile is still the scooter`() = bleRepositoryTest { repo ->
        repo.connectDemo()
        runCurrent()
        advanceTimeBy(DemoBmsSimulator.TICK_INTERVAL_MS + 50L)
        runCurrent()

        assertEquals(
            DEMO_VEHICLE_ID,
            repo.activeVehicle.value?.id,
            "connectDemo() with no argument must still bring up the scooter"
        )
        assertEquals(
            DemoBmsSimulator.CELL_COUNT,
            repo.activeData.value.cellVoltages.size,
            "the scooter demo's battery path must be unchanged"
        )

        repo.disconnect()
        runCurrent()
    }
}
