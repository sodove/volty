package ru.sodovaya.volty.domain.energy

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.nameplateAh
import ru.sodovaya.volty.domain.stats.VoltageSocEstimator
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Where the usable capacity behind an [EnergyState] came from. */
enum class CapacitySource { ENTERED, REPORTED, LEARNED, NONE }

/**
 * What the energy model knows about the vehicle right now. [modelled] is true exactly when the
 * tracker owns the SoC (no online pack reports one); then [socPercent] is the number the dashboard
 * and the alert engine see. [remainingWh] is SoC x [usableAh] x [nominalPackV] - never x live voltage.
 */
data class EnergyState(
    val socPercent: Float?,
    val usableAh: Float?,
    val nominalPackV: Float?,
    val remainingWh: Float?,
    val capacitySource: CapacitySource,
    val learned: LearnedCapacity?,
    val resistanceOhm: Float?,
    val modelled: Boolean,
)

/**
 * Runs the energy model over each published [VehicleData] of one vehicle: the charge counter,
 * the online resistance fit and the capacity learner.
 *
 * **Who owns the SoC.**
 *  - No online pack reports a SoC: the tracker owns it. The aggregate's `soc` and every online
 *    no-SoC pack's `data.soc` become the tracker's displayed value, and `socKnown` is whether that
 *    value exists. When the tracker returns null (not yet seeded, or re-seeding after a long gap)
 *    the SoC is published as UNKNOWN; the per-pack voltage estimate already in the sample is
 *    deliberately never used as a fallback, because it is the sag-following number this model
 *    replaces (T4 field report, 2026-10-24).
 *  - Every online pack reports one: SoC is untouched; [EnergyState] only adds REPORTED capacity and
 *    remaining energy.
 *  - Mixed (some report, some do not) - RULING: the model stays off for SoC. The reporting packs'
 *    SoC stands exactly as the aggregator produced it, [EnergyState.modelled] is false, and the
 *    capacity (hence range) comes from the reporting packs only: summed for PARALLEL, the largest
 *    for SERIES. Presenting a modelled guess for the no-SoC share next to measured packs would put
 *    a guess where the rider reads a measurement.
 *
 * **Integration needs a live link.** The counter does not integrate a step of 2 s or more, so samples
 * must arrive well under 2 s apart; a link that is slower than that holds the SoC rather than moves it.
 *
 * **Series cells** are resolved the way the per-pack enrichment in `KableBmsRepository` does: the
 * vehicle's pack `cellCount` (PARALLEL: the first known; SERIES: the sum, only if every pack knows
 * its own), else [seriesCellsHint] (the Begode protocol's PROVEN `derivedCellCount()`), else the
 * count the rider typed on the controller that provides the battery. A cell-list length is NOT used:
 * a Begode publishes a prefix of its cells while the pages arrive, and a prefix is not a count.
 * No count, no tracker: the snapshot passes through untouched. The tracker is (re)built when the
 * count first becomes known, and again if it later changes (a different battery: memory is dropped).
 *
 * **Memory.** [memory] seeds the first engine. Because loading it is suspend and the count may only
 * be known at runtime, the owner can instead pass [EnergyMemory.EMPTY] and [awaitMemory] = true, learn
 * the count from [onSeriesCells], load, and call [installMemory]. An install is honoured only
 * until the counter has seeded (after that the live counter is truer than the stored one); a learned
 * capacity is adopted whenever none is held yet.
 *
 * **Learning.** A segment opens at the first rest after the counter seeds and is closed at the end
 * of each later rest episode (the rest sample about 2 s before the episode ended is its end point), feeding
 * [CapacityLearner.merge]. A re-seed (long gap) abandons the open segment.
 *
 * Not thread-safe: called from the single sample funnel.
 */
@OptIn(ExperimentalTime::class)
open class VehicleEnergyTracker(
    private val vehicle: Vehicle,
    memory: EnergyMemory,
    private val seriesCellsHint: () -> Int? = { null },
    /** Called when a series count is resolved that no engine has been built for. */
    private val onSeriesCells: (Int) -> Unit = {},
    awaitMemory: Boolean = false,
) {
    private var memory: EnergyMemory = memory
    private var engine: Engine? = null

    /** False while the owner still owes an [installMemory]; [memory] must not be saved before then. */
    var memoryReady: Boolean = !awaitMemory
        private set

    /** The series count the current engine runs on, or null before one was resolved. */
    val seriesCells: Int? get() = engine?.cells

    private var lastAtMillis: Long? = null

    /** For the persistence owner: the vehicle this tracker belongs to. */
    val vehicleId: String get() = vehicle.id
    val chemistry: ru.sodovaya.volty.domain.model.Chemistry get() = vehicle.chemistry

    /** Owner bookkeeping for the 30 s save interval; Long.MIN_VALUE = not started. */
    var lastSaveAtMs: Long = Long.MIN_VALUE

    private inner class Engine(val cells: Int, mem: EnergyMemory) {
        val bounds = VoltageSocEstimator.boundsFor(vehicle.chemistry, vehicle.alertConfig)
        val resistance = PackResistanceEstimator(
            mem.resistanceOhm?.takeIf {
                it.isFinite() && it >= PackResistanceEstimator.MIN_OHM && it <= PackResistanceEstimator.MAX_OHM
            }
        )
        var learned: LearnedCapacity? = mem.learned
        val charge = ChargeTracker(
            ChargeConfig(vehicle.chemistry, bounds.first, bounds.second, null),
            mem.counter
        )
        var wasSeeded = false

        // Learn bookkeeping: end of the previous rest episode, and the latest sample of the current one.
        var anchor: RestPoint? = null
        val recent = ArrayDeque<RestPoint>()
        var inRest = false

        init { refreshCapacity() }

        val enteredUsableAh: Float?
            get() = vehicle.batteryCapacity?.nameplateAh(cells, vehicle.chemistry)
                ?.let { it * OcvCurve.usableFraction(vehicle.chemistry, bounds.first, bounds.second) }
                ?.takeIf { it.isFinite() && it > 0f }

        val usableAh: Float? get() = enteredUsableAh ?: learned?.usableAh?.takeIf { it > 0f }

        val source: CapacitySource
            get() = when {
                enteredUsableAh != null -> CapacitySource.ENTERED
                learned != null -> CapacitySource.LEARNED
                else -> CapacitySource.NONE
            }

        fun refreshCapacity() {
            charge.config = charge.config.copy(usableAh = usableAh)
        }

        fun abandonSegment() {
            anchor = null; recent.clear(); inRest = false
        }

        fun trackLearning(atMillis: Long, restSoc: Float?) {
            if (restSoc != null) {
                inRest = true
                recent.addLast(RestPoint(restSoc, charge.integratedAh, atMillis, charge.gapMillis))
                while (recent.last().at - recent.first().at > END_SETTLE_MS) recent.removeFirst()
            } else if (inRest) {
                closeEpisode()
            }
        }

        /** The rest episode that just ended becomes the segment end (if one is open) and the next start. */
        fun closeEpisode() {
            val end = recent.firstOrNull()
            recent.clear()
            inRest = false
            if (end == null) return
            val start = anchor
            if (start != null) {
                val merged = CapacityLearner.merge(
                    learned,
                    LearnSegment(
                        restSocStart = start.soc,
                        restSocEnd = end.soc,
                        dischargedAh = end.ah - start.ah,
                        durationMillis = end.at - start.at,
                        gapMillis = end.gap - start.gap,
                    )
                )
                if (merged !== learned) {
                    learned = merged
                    refreshCapacity()
                }
            }
            anchor = end
        }
    }

    private class RestPoint(val soc: Float, val ah: Double, val at: Long, val gap: Long)

    private companion object {
        /**
         * A rest episode's end point is the rest sample about this long BEFORE it ended, not the last one:
         * the last sample can already carry the sag of the pull that ended the rest (voltage and current
         * arrive as separate samples), and a learn built on it would be wrong by tens of percent.
         */
        const val END_SETTLE_MS = 2_000L
    }

    /**
     * Adopts [loaded] (the stored memory for the current series count). Honoured for the counter and
     * resistance only while nothing has seeded yet; a learned capacity is adopted if none is held.
     */
    fun installMemory(loaded: EnergyMemory) {
        memory = loaded
        memoryReady = true
        val e = engine ?: return
        val cells = e.cells
        if (e.charge.counterPercent == null && e.learned == null && !e.wasSeeded) {
            engine = Engine(cells, loaded)
        } else if (e.learned == null && loaded.learned != null) {
            e.learned = loaded.learned
            e.refreshCapacity()
        }
    }

    /** Ends the open rest episode (the ride is over) so a final segment can be learned. */
    fun finish() {
        engine?.let { if (it.inRest) it.closeEpisode() }
    }

    /** What to persist now. Counter is the live counter when there is one, else what was loaded. */
    fun memory(): EnergyMemory {
        val e = engine ?: return memory
        val counter = e.charge.counterPercent?.let { PersistedCharge(it, lastAtMillis ?: 0L) } ?: memory.counter
        return EnergyMemory(e.learned, e.resistance.ohm, counter)
    }

    open fun apply(snapshot: VehicleData, now: Instant): VehicleData {
        val agg = snapshot.aggregate
        // Not evidence: a disconnected aggregate, or the dumb-Begode "unscaled" voltage sentinel.
        if (!agg.isConnected || !(agg.voltage > 0f) || !agg.voltage.isFinite()) return snapshot
        val online = snapshot.packs.filter { it.isOnline }
        if (online.isEmpty()) return snapshot
        val cells = resolveSeriesCells() ?: return snapshot
        val e = ensureEngine(cells)
        val reporting = online.filter { it.pack.bmsType.reportsStateOfCharge }
        val nominalV = cells * vehicle.chemistry.nominalCellV

        return when {
            reporting.size == online.size -> snapshot.copy(energy = reportedState(agg, e, nominalV))
            reporting.isNotEmpty() -> snapshot.copy(energy = mixedState(snapshot, reporting, e, nominalV))
            else -> modelled(snapshot, online, e, cells, nominalV, now.toEpochMilliseconds())
        }
    }

    private fun modelled(
        snapshot: VehicleData,
        online: List<PackState>,
        e: Engine,
        cells: Int,
        nominalV: Float,
        atMillis: Long,
    ): VehicleData {
        val agg = snapshot.aggregate
        val current = dischargeCurrent(snapshot, online)
        if (current != null) e.resistance.feed(agg.voltage, current, atMillis)
        lastAtMillis = atMillis
        val soc = e.charge.update(ChargeInput(atMillis, agg.voltage, cells, current, e.resistance.ohm))
        if (soc == null) {
            e.abandonSegment()
        } else {
            e.wasSeeded = true
            e.trackLearning(atMillis, e.charge.restVoltageSocPercent)
        }
        val known = soc != null
        val shown = soc ?: 0f
        val packs = snapshot.packs.map { ps ->
            if (ps.isOnline && !ps.pack.bmsType.reportsStateOfCharge) {
                ps.copy(data = ps.data.copy(soc = shown, socKnown = known))
            } else ps
        }
        val usable = e.usableAh
        val energy = EnergyState(
            socPercent = soc,
            usableAh = usable,
            nominalPackV = nominalV,
            remainingWh = if (soc != null && usable != null) soc / 100f * usable * nominalV else null,
            capacitySource = e.source,
            learned = e.learned,
            resistanceOhm = e.resistance.ohm,
            modelled = true,
        )
        return snapshot.copy(
            packs = packs,
            aggregate = agg.copy(soc = shown, socKnown = known),
            energy = energy,
        )
    }

    private fun reportedState(agg: BmsData, e: Engine, nominalV: Float): EnergyState {
        val capacity = agg.capacity.takeIf { it > 0f && it.isFinite() }
        val soc = agg.soc.takeIf { agg.socKnown }
        return EnergyState(
            socPercent = soc,
            usableAh = capacity,
            nominalPackV = nominalV,
            remainingWh = if (soc != null && capacity != null) soc / 100f * capacity * nominalV else null,
            capacitySource = if (capacity != null) CapacitySource.REPORTED else CapacitySource.NONE,
            learned = e.learned,
            resistanceOhm = e.resistance.ohm,
            modelled = false,
        )
    }

    private fun mixedState(snapshot: VehicleData, reporting: List<PackState>, e: Engine, nominalV: Float): EnergyState {
        val capacities = reporting.map { it.data.capacity }.filter { it > 0f && it.isFinite() }
        val capacity = when {
            capacities.isEmpty() -> null
            snapshot.topology == PackTopology.SERIES -> capacities.max()
            else -> capacities.sum()
        }
        val socs = reporting.filter { it.data.socKnown }.map { it.data.soc }
        val soc = if (socs.isEmpty()) null else socs.average().toFloat()
        return EnergyState(
            socPercent = soc,
            usableAh = capacity,
            nominalPackV = nominalV,
            remainingWh = if (soc != null && capacity != null) soc / 100f * capacity * nominalV else null,
            capacitySource = if (capacity != null) CapacitySource.REPORTED else CapacitySource.NONE,
            learned = e.learned,
            resistanceOhm = e.resistance.ohm,
            modelled = false,
        )
    }

    /**
     * Battery current, discharge-positive. Precedence: the controller's battery current when it is
     * connected and MEASURED (BegodeProtocol publishes `-batteryCurrentA` so that `power = V x I`
     * is positive on discharge); else the packs' own measured currents (BmsData.current is
     * charge-positive, hence negated): summed for PARALLEL, the first for SERIES; else null.
     */
    private fun dischargeCurrent(snapshot: VehicleData, online: List<PackState>): Float? {
        val motion = snapshot.motion
        if (motion.isConnected && motion.hasBatteryCurrent && motion.batteryCurrentA.isFinite()) {
            return motion.batteryCurrentA
        }
        val measured = online.filter { it.data.hasCurrent && it.data.current.isFinite() }
        if (measured.isEmpty()) return null
        return if (snapshot.topology == PackTopology.SERIES) -measured.first().data.current
        else measured.sumOf { -it.data.current.toDouble() }.toFloat()
    }

    private fun resolveSeriesCells(): Int? {
        val counts = vehicle.packs.map { it.cellCount?.takeIf { c -> c > 0 } }
        val fromPacks = if (vehicle.topology == PackTopology.SERIES) {
            if (counts.isNotEmpty() && counts.all { it != null }) counts.sumOf { it!! } else null
        } else counts.firstOrNull { it != null }
        return fromPacks
            ?: seriesCellsHint()?.takeIf { it > 0 }
            ?: vehicle.controllers.firstOrNull { it.providesDerivedBattery && (it.derivedCellCount ?: 0) > 0 }
                ?.derivedCellCount
    }

    private fun ensureEngine(cells: Int): Engine {
        val existing = engine
        if (existing != null && existing.cells == cells) return existing
        // First engine takes the memory it was constructed with; a changed count is a different battery.
        val fresh = Engine(cells, if (existing == null) memory else EnergyMemory.EMPTY)
        engine = fresh
        if (existing != null) memory = EnergyMemory.EMPTY
        onSeriesCells(cells)
        return fresh
    }
}
