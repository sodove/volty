package ru.sodovaya.volty.domain.energy

import ru.sodovaya.volty.data.ble.VehicleConnection
import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BatteryCapacity
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.CapacityUnit
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.wheelVehicle
import ru.sodovaya.volty.domain.stats.VoltageSocEstimator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class VehicleEnergyTrackerTest {

    private class FakeClock(var nowMs: Long = 1_000_000L) {
        fun advance(ms: Long) { nowMs += ms }
        fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
    }

    private val chem = Chemistry.LI_ION_NMC
    private val epoch = Instant.fromEpochSeconds(0)

    /** The T4's shape: a no-SoC Begode pack (24S) plus its controller. */
    private fun wheel(
        cells: Int? = 24,
        capacity: BatteryCapacity? = null,
        derivedTyped: Int? = null,
    ): Vehicle = wheelVehicle("v1", "T4", "unicycle", "AA:BB", chem, epoch).let { v ->
        v.copy(
            packs = v.packs.map { it.copy(cellCount = cells) },
            controllers = v.controllers.map { it.copy(derivedCellCount = derivedTyped, providesDerivedBattery = derivedTyped != null) },
            batteryCapacity = capacity,
        )
    }

    private inner class Rig(
        val vehicle: Vehicle,
        memory: EnergyMemory = EnergyMemory.EMPTY,
        hint: () -> Int? = { null },
        val tracker: VehicleEnergyTracker = VehicleEnergyTracker(vehicle, memory, hint),
    ) {
        val clock = FakeClock()
        val published = mutableListOf<VehicleData>()
        val conn = VehicleConnection(
            packs = vehicle.packs,
            controllers = vehicle.controllers,
            topology = vehicle.topology,
            onVehicleData = { published += it },
            clock = clock::now,
            energy = tracker,
        )
        val last: VehicleData get() = published.last()

        /**
         * One 250 ms step of a live link: a pack sample carrying the PER-PACK voltage estimate that the
         * repository's enrichment would have put there (the sag-following number this model replaces),
         * then a motion sample carrying the battery current (null = not measured, with the incoherent
         * 80 A placeholder so a consumer that ignores the flag is caught).
         */
        fun step(volts: Float, amps: Float?, cells: Int = 24) {
            clock.advance(150)
            val raw = VoltageSocEstimator.estimateFromPackVoltage(volts, cells, chem, vehicle.alertConfig) ?: 0f
            conn.submit(
                0,
                BmsData(voltage = volts, soc = raw, socKnown = true, hasCurrent = false, current = 0f, isConnected = true)
            )
            clock.advance(100)
            conn.submitMotion(
                0,
                ControllerData(
                    batteryCurrentA = amps ?: 80f,
                    hasBatteryCurrent = amps != null,
                    isConnected = true,
                )
            )
        }

        fun run(steps: Int, volts: Float, amps: Float?) = repeat(steps) { step(volts, amps) }
    }

    private val entered30Ah = BatteryCapacity(30f, CapacityUnit.AH)

    @Test
    fun aT4HardPullKeepsTheAggregateSteady() {
        val rig = Rig(wheel(capacity = entered30Ah))
        rig.run(steps = 28, volts = 90f, amps = 0f) // 7 s at rest: seeds
        val before = rig.last.aggregate
        assertTrue(before.socKnown, "precondition: the counter has seeded")

        rig.run(steps = 20, volts = 78f, amps = 200f) // a 5 s, 200 A pull sagging 12 V

        val after = rig.last.aggregate
        val rawAtSag = VoltageSocEstimator.estimateFromPackVoltage(78f, 24, chem, AlertConfig())!!
        assertTrue(abs(rawAtSag - before.soc) > 10f, "precondition: the voltage estimate alone collapses under this pull")
        assertTrue(after.socKnown)
        assertTrue(abs(after.soc - before.soc) < 2f, "pull moved the aggregate ${before.soc} -> ${after.soc}")
        assertTrue(after.soc <= before.soc + 0.01f, "a discharge never ticks the readout up")
    }

    @Test
    fun alertsSeeTheSmoothedSoc() {
        val rig = Rig(wheel(capacity = entered30Ah))
        rig.run(28, 90f, 0f)
        rig.run(20, 78f, 200f)

        val snap = rig.last
        val energy = assertNotNull(snap.energy)
        assertTrue(energy.modelled)
        val tracked = assertNotNull(energy.socPercent)
        // What AlertEngine reads is the aggregate (activeData) - it must be the tracker's number, not the sample's.
        assertEquals(tracked, snap.aggregate.soc, 0f)
        assertEquals(tracked, snap.packs[0].data.soc, 0f, "the no-SoC pack shows the same charge")
        assertTrue(snap.aggregate.socKnown && snap.packs[0].data.socKnown)
        val rawAtSag = VoltageSocEstimator.estimateFromPackVoltage(78f, 24, chem, AlertConfig())!!
        assertTrue(abs(snap.aggregate.soc - rawAtSag) > 10f, "not the sagging per-sample estimate")
    }

    @Test
    fun anUnseededTrackerPublishesUnknownRatherThanTheVoltageEstimate() {
        val rig = Rig(wheel(capacity = entered30Ah))
        rig.step(90f, 0f) // the very first sample: nothing earned yet
        val agg = rig.last.aggregate
        assertFalse(agg.socKnown, "the tracker owns the SoC and has none yet: unknown, not the sample's estimate")
        assertEquals(0f, agg.soc, 0f)
        assertFalse(rig.last.packs[0].data.socKnown)
        assertNull(rig.last.energy!!.socPercent)
        assertNull(rig.last.energy!!.remainingWh)
    }

    @Test
    fun aSmartBmsSocPassesThrough() {
        val ant = Pack(0, "ANT", BmsType.ANT_BMS, "AA:01", cellCount = 24)
        val v = Vehicle(id = "ant", name = "Scooter", iconKey = "scooter", packs = listOf(ant), chemistry = chem, createdAt = epoch)
        val clock = FakeClock()
        val published = mutableListOf<VehicleData>()
        val conn = VehicleConnection(
            packs = v.packs, topology = v.topology, onVehicleData = { published += it }, clock = clock::now,
            energy = VehicleEnergyTracker(v, EnergyMemory.EMPTY),
        )
        repeat(40) { i ->
            clock.advance(250)
            val volts = if (i < 20) 90f else 78f // the same pull, 200 A
            conn.submit(
                0,
                BmsData(voltage = volts, current = if (i < 20) 0f else -200f, hasCurrent = true,
                    soc = 73f, socKnown = true, capacity = 100f, isConnected = true)
            )
        }
        val snap = published.last()
        assertEquals(73f, snap.aggregate.soc, 0f)
        assertEquals(73f, snap.packs[0].data.soc, 0f)
        val energy = assertNotNull(snap.energy)
        assertFalse(energy.modelled)
        assertEquals(CapacitySource.REPORTED, energy.capacitySource)
        assertEquals(100f, energy.usableAh)
        // Remaining energy is x NOMINAL pack voltage (24 x 3.7 V), never the sagged 78 V.
        assertEquals(0.73f * 100f * 24 * 3.7f, energy.remainingWh!!, 0.5f)
    }

    @Test
    fun aMixedVehicleKeepsTheReportingPacksSocAndTheModelStaysOff() {
        val packs = listOf(
            Pack(0, "ANT", BmsType.ANT_BMS, "AA:01", cellCount = 24),
            Pack(1, "Wheel", BmsType.BEGODE, "AA:02", cellCount = 24),
        )
        val v = Vehicle(id = "mix", name = "Mixed", iconKey = "x", packs = packs, chemistry = chem, createdAt = epoch)
        val clock = FakeClock()
        val published = mutableListOf<VehicleData>()
        val conn = VehicleConnection(
            packs = v.packs, topology = v.topology, onVehicleData = { published += it }, clock = clock::now,
            energy = VehicleEnergyTracker(v, EnergyMemory.EMPTY),
        )
        repeat(30) {
            clock.advance(250)
            conn.submit(0, BmsData(voltage = 90f, soc = 60f, socKnown = true, capacity = 50f, isConnected = true))
            conn.submit(1, BmsData(voltage = 90f, soc = 44f, socKnown = true, isConnected = true))
        }
        val snap = published.last()
        assertEquals(60f, snap.packs[0].data.soc, 0f)
        assertEquals(44f, snap.packs[1].data.soc, 0f, "the no-SoC pack keeps its own estimate: the model is off")
        val energy = assertNotNull(snap.energy)
        assertFalse(energy.modelled)
        assertEquals(60f, energy.socPercent!!, 0.01f, "range reads the reporting packs only")
        assertEquals(50f, energy.usableAh)
    }

    @Test
    fun aCurrentThatStopsIsNotIntegrated() {
        // Incoherent on purpose: hasBatteryCurrent = false next to batteryCurrentA = 80 (Rig.step does this for null).
        val unmeasured = Rig(wheel(capacity = entered30Ah))
        unmeasured.run(28, 90f, 0f)
        val before = unmeasured.last.aggregate.soc
        unmeasured.run(240, 90f, null) // 60 s with a stale 80 A "reading"
        assertEquals(before, unmeasured.last.aggregate.soc, 0.5f, "an unmeasured current must not drain the counter")

        // Control: the same 80 A, MEASURED, does drain it - so the test above can fail.
        val measured = Rig(wheel(capacity = entered30Ah))
        measured.run(28, 90f, 0f)
        val measuredBefore = measured.last.aggregate.soc
        measured.run(240, 90f, 80f)
        assertTrue(measuredBefore - measured.last.aggregate.soc > 3f, "80 A for 60 s on 30 Ah is ~4 points")
    }

    @Test
    fun enteredCapacityBeatsLearned() {
        val learned = LearnedCapacity(usableAh = 50f, weight = 60f, rides = 3)
        val entered = Rig(wheel(capacity = entered30Ah), EnergyMemory(learned, null, null))
        entered.run(28, 90f, 0f)
        val e = assertNotNull(entered.last.energy)
        val expected = 30f * OcvCurve.usableFraction(chem, chem.emptyCellV, chem.defaultHighV)
        assertEquals(CapacitySource.ENTERED, e.capacitySource)
        assertEquals(expected, e.usableAh!!, 0.001f)
        assertEquals(learned, e.learned, "the learned figure is still carried for the editor")

        val none = Rig(wheel(), EnergyMemory(learned, null, null))
        none.run(28, 90f, 0f)
        assertEquals(CapacitySource.LEARNED, none.last.energy!!.capacitySource)
        assertEquals(50f, none.last.energy!!.usableAh)

        val nothing = Rig(wheel())
        nothing.run(28, 90f, 0f)
        assertEquals(CapacitySource.NONE, nothing.last.energy!!.capacitySource)
        assertNull(nothing.last.energy!!.remainingWh, "no capacity, no energy figure")
    }

    @Test
    fun remainingEnergyUsesTheNominalVoltage() {
        val rig = Rig(wheel(capacity = entered30Ah))
        rig.run(28, 90f, 0f)
        rig.run(20, 78f, 200f)
        val e = rig.last.energy!!
        assertEquals(24 * 3.7f, e.nominalPackV!!, 0.001f)
        assertEquals(e.socPercent!! / 100f * e.usableAh!! * 24 * 3.7f, e.remainingWh!!, 0.01f)
    }

    @Test
    fun aPersistedCounterAndLearnedCapacityReachTheTracker() {
        val vSoc = VoltageSocEstimator.estimateFromPackVoltage(90f, 24, chem, AlertConfig())!!
        val persisted = vSoc + 6f // within the seed tolerance: the persisted value wins over the bare voltage
        val learned = LearnedCapacity(41f, 40f, 2)
        val rig = Rig(wheel(), EnergyMemory(learned, 0.07f, PersistedCharge(persisted, 5L)))
        rig.run(28, 90f, 0f)
        val e = rig.last.energy!!
        assertEquals(persisted, e.socPercent!!, 0.6f)
        assertEquals(learned, e.learned)
        assertEquals(41f, e.usableAh)
        assertEquals(0.07f, e.resistanceOhm!!, 1e-6f)
    }

    @Test
    fun anOutOfRangeStoredResistanceIsNotSeeded() {
        for (bad in listOf(5f, 0f, Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            val rig = Rig(wheel(), EnergyMemory(null, bad, null))
            rig.run(28, 90f, 0f)
            assertNull(rig.last.energy!!.resistanceOhm, "stored $bad ohm is not a resistance")
        }
        val ok = Rig(wheel(), EnergyMemory(null, PackResistanceEstimator.MAX_OHM, null))
        ok.run(2, 90f, 0f)
        assertEquals(PackResistanceEstimator.MAX_OHM, ok.last.energy!!.resistanceOhm)
    }

    @Test
    fun noSeriesCountMeansNoTrackerAndTheSnapshotPassesThrough() {
        val rig = Rig(wheel(cells = null))
        rig.run(10, 90f, 0f)
        assertNull(rig.last.energy)
        assertTrue(rig.last.aggregate.socKnown, "the sample's own estimate is untouched when nothing can be modelled")
        assertNull(rig.tracker.seriesCells)
    }

    @Test
    fun theSeriesCountComesFromTheHintThenTheTypedControllerCount() {
        val hinted = Rig(wheel(cells = null), hint = { 24 })
        hinted.run(2, 90f, 0f)
        assertEquals(24, hinted.tracker.seriesCells)
        assertEquals(24 * 3.7f, hinted.last.energy!!.nominalPackV!!, 0.001f)

        val typed = Rig(wheel(cells = null, derivedTyped = 20))
        typed.run(2, 90f, 0f)
        assertEquals(20, typed.tracker.seriesCells)

        // A stored pack count outranks a hint.
        val stored = Rig(wheel(cells = 22), hint = { 24 })
        stored.run(2, 90f, 0f)
        assertEquals(22, stored.tracker.seriesCells)
    }

    @Test
    fun aChangedSeriesCountDropsTheMemoryAndAsksForTheNewOne() {
        var hint: Int? = 24
        val asked = mutableListOf<Int>()
        val v = wheel(cells = null)
        val tracker = VehicleEnergyTracker(v, EnergyMemory(LearnedCapacity(40f, 40f, 2), null, null), { hint }, { asked += it })
        val rig = Rig(v, tracker = tracker)
        rig.run(2, 90f, 0f)
        assertEquals(40f, rig.last.energy!!.learned!!.usableAh)
        hint = 20
        rig.run(2, 90f, 0f)
        assertEquals(listOf(24, 20), asked)
        assertNull(rig.last.energy!!.learned, "a different battery does not inherit the old learn")
    }

    @Test
    fun anAggregateThatIsNotEvidenceIsPassedThroughUntouched() {
        val rig = Rig(wheel(capacity = entered30Ah))
        rig.run(28, 90f, 0f)
        // The dumb-Begode "unscaled" sentinel: voltage 0 with an unknown SoC.
        rig.conn.submit(0, BmsData(voltage = 0f, soc = 0f, socKnown = false, isConnected = true))
        assertNull(rig.last.energy)
        assertFalse(rig.last.aggregate.socKnown)
        assertEquals(0f, rig.last.aggregate.soc, 0f)
    }

    @Test
    fun theTrackerAppliesExactlyOncePerPublishedSnapshot() {
        val v = wheel()
        var applies = 0
        val applied = mutableListOf<VehicleData>()
        val counting = object : VehicleEnergyTracker(v, EnergyMemory.EMPTY) {
            override fun apply(snapshot: VehicleData, now: Instant): VehicleData {
                applies++
                return super.apply(snapshot, now).also { applied += it }
            }
        }
        val published = mutableListOf<VehicleData>()
        val clock = FakeClock()
        val conn = VehicleConnection(
            packs = v.packs, controllers = v.controllers, topology = v.topology,
            onVehicleData = { published += it }, clock = clock::now, energy = counting,
        )
        val a = conn.submit(0, BmsData(voltage = 90f, isConnected = true))
        assertEquals(1, applies)
        val b = conn.submitMotion(0, ControllerData(batteryCurrentA = 0f, isConnected = true))
        assertEquals(2, applies)
        conn.markOffline(0)
        assertEquals(3, applies)
        assertEquals(3, published.size)
        // submit / submitMotion hand back the very instance that was applied and published.
        assertSame(published[0], a); assertSame(applied[0], a)
        assertSame(published[1], b); assertSame(applied[1], b)
    }

    @Test
    fun aRestToRestRideTeachesTheCapacityAndItTakesEffect() {
        val rig = Rig(wheel())
        rig.run(40, 96f, 0f)      // 10 s at rest, nearly full
        rig.run(480, 90f, 40f)    // 120 s riding at 40 A: 1.333 Ah
        rig.run(40, 86.4f, 0f)    // rest again
        rig.run(1, 86.4f, 30f)    // leaving rest closes the episode
        val (low, high) = VoltageSocEstimator.boundsFor(chem, AlertConfig())
        val socStart = OcvCurve.stretchedSocPercent(chem, 96f / 24, low, high)
        val socEnd = OcvCurve.stretchedSocPercent(chem, 86.4f / 24, low, high)
        val expected = 1.3333f / ((socStart - socEnd) / 100f)
        val e = rig.last.energy!!
        val learned = assertNotNull(e.learned)
        assertEquals(1, learned.rides)
        assertEquals(expected, learned.usableAh, expected * 0.08f)
        assertEquals(CapacitySource.LEARNED, e.capacitySource)
        assertEquals(learned.usableAh, e.usableAh)
        assertEquals(learned, rig.tracker.memory().learned, "and it is what gets saved")
    }

    /** Pack volts (24S) whose stretched OCV SoC is [soc]; bisection, the curve is monotonic. */
    private fun packVoltsAt(soc: Float): Float {
        val (low, high) = VoltageSocEstimator.boundsFor(chem, AlertConfig())
        var lo = 2.0f
        var hi = 4.5f
        repeat(60) {
            val mid = (lo + hi) / 2
            if (OcvCurve.stretchedSocPercent(chem, mid, low, high) < soc) lo = mid else hi = mid
        }
        return (lo + hi) / 2 * 24
    }

    /** Ride 40 A down from [fromSoc] to [toSoc] on a 30 Ah pack (1 % = 108 steps), then rest ~10 s there. */
    private fun Rig.rideBetweenStops(fromSoc: Float, toSoc: Float) {
        val steps = ((fromSoc - toSoc) * 108f).toInt()
        run(steps, packVoltsAt((fromSoc + toSoc) / 2) - 2f, 40f)
        run(40, packVoltsAt(toSoc), 0f)
    }

    @Test
    fun aCityRideWithManyShortStopsStillLearnsOnceTheCumulativeDropIsLargeEnough() {
        val rig = Rig(wheel())
        rig.run(40, packVoltsAt(96f), 0f)
        var soc = 96f
        for (next in listOf(90f, 84f, 78f, 72f, 66f)) {
            rig.rideBetweenStops(soc, next)
            soc = next
            rig.run(1, packVoltsAt(soc) - 2f, 30f) // leaving the stop closes the episode
        }
        val learned = assertNotNull(rig.last.energy!!.learned, "five 6 % stops add up to a learnable drop")
        assertEquals(1, learned.rides, "exactly one learn: 96 -> 72 (24 %), then 72 -> 66 is too small again")
        assertEquals(30f, learned.usableAh, 3f)
    }

    @Test
    fun aChargeBetweenStopsResetsTheLearningAnchor() {
        val rig = Rig(wheel())
        rig.run(40, packVoltsAt(96f), 0f)
        rig.rideBetweenStops(96f, 90f)
        rig.run(1, packVoltsAt(90f) - 2f, 30f)
        rig.rideBetweenStops(90f, 84f)
        rig.run(1, packVoltsAt(84f) - 2f, 30f)
        rig.run(40, packVoltsAt(98f), 0f) // plugged in: SoC rose above the old anchor (96) while parked
        rig.run(1, packVoltsAt(98f) - 2f, 30f)
        var soc = 98f
        for (next in listOf(92f, 86f, 80f, 74f)) {
            rig.rideBetweenStops(soc, next)
            soc = next
            rig.run(1, packVoltsAt(soc) - 2f, 30f)
        }
        // A stale anchor at 96 would learn at 74 from 12 % of pre-charge draw plus 24 % after it (~49 Ah, and
        // earlier). The anchor moved to 98 at the charge, so the single learn is 98 -> 74 on 7.2 Ah = 30 Ah.
        val learned = assertNotNull(rig.last.energy!!.learned)
        assertEquals(1, learned.rides)
        assertEquals(30f, learned.usableAh, 3f, "no draw from before the charge is counted")
    }
}
