package ru.sodovaya.volty.data.demo

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * One tick of the demo: every pack's battery sample plus the controller's
 * motion sample, all computed off the SAME tick index so the two streams stay
 * perfectly correlated.
 *
 * [packs] is a LIST because a demo vehicle may own more than one pack — a
 * Begode wheel is two parallel branches behind one BLE link — and the consumer
 * builds one `PackState` per entry, positionally against the demo vehicle's own
 * `packs`. A scooter's list has exactly one entry and behaves as the single
 * sample it always was.
 */
data class DemoTick(
    val packs: List<BmsData>,
    val motion: ControllerData
)

/**
 * Pure, deterministic-ish generator of a realistic synthetic [BmsData] stream
 * for the "Try demo" mode. No BLE, no I/O — every value is computed from the
 * tick index so [sampleAt] is trivially unit-testable.
 *
 * Models a pack cycling through three phases (~3 min loop):
 *  - **A "riding"** (~100s): discharge, current oscillating roughly -3..-12 A with
 *    smooth sine noise and occasional 2-3 s regen spikes (+2..+5 A).
 *  - **B "stopped"** (~20 s): current ≈ 0, power ≈ 0.
 *  - **C "charging"** (~60 s): current +4..+6 A — flips the dashboard hero to the
 *    green charging card, which is the most interesting state for reviewers.
 *
 * SOC starts at 76 %, drains slowly during A and rises during C. The pack stays
 * clean (no faults, balanced cells bar one slightly-low cell to exercise the
 * min-highlight in the cells view).
 *
 * The simulator carries no mutable state: pass it nothing, then either drive it
 * with [run] (a 700 ms loop) or evaluate single points with [sampleAt].
 *
 * Task 12 added a second, independent stream off the SAME tick index: [motionAt]
 * is the synthetic controller's ride curve (speed / duty / odometer) for "Try
 * demo" mode, sharing [phaseOf] with the battery stream so the vehicle only
 * "moves" while riding — zero speed while stopped or charging.
 *
 * ## Two vehicles, one mechanism (Part D Task 6)
 *
 * [profile] picks WHICH vehicle is being simulated. The phase clock, the SoC
 * integrator, the distance integrator and the tick loop are shared verbatim;
 * what a profile changes is the SHAPE of the vehicle underneath them — how many
 * packs it has, how many cells each carries, whether its BMS reports a fuel
 * gauge at all, and how its speed / duty / currents / temperatures move.
 *
 * [DemoProfile.SCOOTER] is the original curve, unchanged to the last random
 * draw. [DemoProfile.WHEEL] is a Begode electric unicycle, and it is genuinely a
 * different machine:
 *  - **two parallel branches** at one address, so [packSamplesAt] returns two
 *    samples and the vehicle-level battery is what `PackAggregator` folds them
 *    into;
 *  - **no fuel gauge**: a Begode reports voltage, current, cells and
 *    temperatures and nothing else, so the branch samples carry
 *    `socKnown = false` and `capacity = 0` exactly as `BegodeProtocol` publishes
 *    them — the SoC a rider sees is `VoltageSocEstimator`'s, applied by the
 *    caller, on the real path;
 *  - **duty at a standstill**: a wheel balances, so its hardware PWM never
 *    reaches 0 while it is upright (the ET Max capture reads 1-2 % parked). The
 *    scooter's duty is 0 the instant it stops; the wheel's is not, and that is
 *    the single most recognisable thing about a wheel's dashboard;
 *  - **two temperatures from two sensors**, board and motor, moving
 *    independently rather than one tracking the other at a fixed offset;
 *  - **battery current and phase current from different frames** — at low speed
 *    the phase current is several times the battery current, which is what makes
 *    a wheel's CURRENT dial read the way it does;
 *  - **no energy counters and no eRPM at all**: `consumedWh`, `consumedAh`,
 *    `regenWh`, `regenAh` and `eRpm` stay 0 because a Begode reports none of
 *    them. This is deliberately NOT papered over — the demo shows what the
 *    hardware shows, including what it cannot say.
 */
@OptIn(ExperimentalTime::class)
class DemoBmsSimulator(
    private val tickIntervalMs: Long = TICK_INTERVAL_MS,
    private val profile: DemoProfile = DemoProfile.SCOOTER,
) {

    companion object {
        const val TICK_INTERVAL_MS: Long = 700L

        // Phase durations expressed in seconds.
        private const val PHASE_A_SECONDS = 100
        private const val PHASE_B_SECONDS = 20
        private const val PHASE_C_SECONDS = 60
        private const val CYCLE_SECONDS = PHASE_A_SECONDS + PHASE_B_SECONDS + PHASE_C_SECONDS

        const val CELL_COUNT = 16
        const val CAPACITY_AH = 35.0f
        const val NUM_CYCLES = 42

        /** Cells in series of ONE branch of the demo wheel — a 100.8 V Begode. */
        const val WHEEL_CELL_COUNT = 24

        /** Parallel branches of the demo wheel: `packs = [0, 1]` at one address. */
        const val WHEEL_PACK_COUNT = 2

        // SOC bounds (kept well inside 0..100 so noise never clips weirdly).
        private const val SOC_START = 76.0f
        private const val SOC_MIN = 35.0f
        private const val SOC_MAX = 92.0f

        // Stable per-run seed: same demo curve every launch (nice for screenshots).
        private const val SEED = 8765309L
        // Separate offset for the motion stream's noise draws, so its jitter
        // doesn't correlate tick-for-tick with the battery stream's.
        private const val MOTION_SEED_OFFSET = 900_001L
        // Per-pack offset, so two parallel branches of the same wheel do not
        // draw IDENTICAL cell noise on the same tick — two real branches never
        // agree to the millivolt. Pack 0 adds nothing, which is what keeps the
        // single-pack scooter curve bit-for-bit what it always was.
        private const val PACK_SEED_OFFSET = 70_003L

        // Ride-curve tuning (Task 12). Speed only moves during Phase.RIDING:
        // a smooth accelerate / cruise-with-oscillation / decelerate profile.
        private const val RIDE_ACCEL_SECONDS = 8.0
        private const val RIDE_DECEL_SECONDS = 8.0
        private const val CRUISE_SPEED_KMH = 28.0
        private const val OSC_AMPLITUDE_KMH = 6.0
        private const val TOP_SPEED_KMH = 45.0

        // Odometer baseline: a "used" demo vehicle, not a fresh-off-the-line one.
        private const val ODOMETER_START_KM = 1245.0

        // --- The wheel's own ride curve (Part D Task 6) ------------------------
        // A EUC is not a small scooter: it accelerates harder, cruises slower,
        // and never stops balancing. Every number below is a wheel number.

        /** A wheel reaches cruise in half the time a scooter's curve takes. */
        private const val WHEEL_ACCEL_SECONDS = 4.0
        private const val WHEEL_DECEL_SECONDS = 5.0
        private const val WHEEL_CRUISE_SPEED_KMH = 24.0
        private const val WHEEL_OSC_AMPLITUDE_KMH = 4.0

        /**
         * The wheel's no-load top speed — what its hardware PWM is a fraction
         * OF. A EUC's duty is essentially `speed / topSpeed` plus whatever load
         * costs, which is why duty is the number a wheel rider watches: it is
         * how much headroom is left before the motor cannot hold the rider up.
         */
        private const val WHEEL_TOP_SPEED_KMH = 50.0

        /**
         * Duty of a wheel standing still, in percent — the ET Max capture's own
         * 1-2 %. A balancing wheel is always working; this is the reading that
         * makes a wheel's dashboard unmistakably a wheel's.
         */
        private const val WHEEL_BALANCING_DUTY_PERCENT = 1.5

        /** Battery current a parked, balancing wheel draws, in amps. */
        private const val WHEEL_STANDSTILL_CURRENT_A = 0.7

        /** Battery amps per km/h of ground speed while riding. */
        private const val WHEEL_CURRENT_A_PER_KMH = 0.21

        /**
         * The phase-current divisor's floor. Phase current is roughly
         * `batteryCurrent / dutyFraction`, which explodes as duty approaches 0;
         * a real wheel's phase current at a standstill is a couple of amps, not
         * fifty, so the divisor bottoms out here.
         */
        private const val WHEEL_MIN_PHASE_DUTY_FRACTION = 0.35

        /** Nothing a demo wheel does should show more phase current than this. */
        private const val WHEEL_MAX_PHASE_CURRENT_A = 60.0

        /** Pack sag under load, volts per amp — a 100 V wheel droops a little. */
        private const val WHEEL_SAG_V_PER_A = 0.08

        /** Charge current of the demo wheel, in amps (a 5 A Begode brick). */
        private const val WHEEL_CHARGE_CURRENT_A = 5.0

        /**
         * The demo wheel's lifetime odometer: the ET Max capture's own reading
         * (8 565 341 m). A wheel this demo is modelled on is not new.
         */
        private const val WHEEL_ODOMETER_START_KM = 8565.0
    }

    /**
     * What the simulated vehicle IS, as opposed to what it is doing. Everything
     * here is a property of the machine rather than of the moment, which is why
     * it is resolved once from [profile] instead of branched at every call site.
     */
    private class Shape(
        val packCount: Int,
        val cellCount: Int,
        val capacityAhPerPack: Float,
        val numCycles: Int,
        val tempSensorCount: Int,
        /**
         * False for a BMS that reports no state of charge and no capacity — a
         * Begode branch. Those samples carry `socKnown = false` and zeroed
         * charge/capacity, and the caller's [ru.sodovaya.volty.domain.stats.VoltageSocEstimator]
         * fills the SoC in from the cells, exactly as it does for a real wheel.
         */
        val reportsFuelGauge: Boolean,
    )

    private val shape: Shape = when (profile) {
        DemoProfile.SCOOTER -> Shape(
            packCount = 1,
            cellCount = CELL_COUNT,
            capacityAhPerPack = CAPACITY_AH,
            numCycles = NUM_CYCLES,
            tempSensorCount = 4,
            reportsFuelGauge = true,
        )
        DemoProfile.WHEEL -> Shape(
            packCount = WHEEL_PACK_COUNT,
            cellCount = WHEEL_CELL_COUNT,
            // A Begode reports no capacity at all; the branch sample carries 0
            // and this value is never read for the wheel. Kept honest anyway.
            capacityAhPerPack = 0f,
            numCycles = 0,
            tempSensorCount = 2,
            reportsFuelGauge = false,
        )
    }

    /** How many packs this profile's demo vehicle owns. */
    val packCount: Int get() = shape.packCount

    /**
     * Suspend loop: compute every pack's battery sample AND the motion sample
     * for the current tick (same tick index — the streams stay perfectly
     * correlated), hand them to [emit], then [delay] one [tickIntervalMs]
     * interval. Runs until the calling scope is cancelled.
     */
    suspend fun run(emit: (DemoTick) -> Unit): Unit = coroutineScope {
        var tick = 0
        while (isActive) {
            emit(tickAt(tick))
            tick++
            delay(tickIntervalMs)
        }
    }

    /** Everything one tick of this profile produces. */
    fun tickAt(tickIndex: Int): DemoTick = DemoTick(
        packs = packSamplesAt(tickIndex),
        motion = motionAt(tickIndex)
    )

    /** One battery sample per pack of this profile's vehicle, in pack order. */
    fun packSamplesAt(tickIndex: Int): List<BmsData> =
        List(shape.packCount) { packSampleAt(tickIndex, it) }

    /**
     * Pure function: the synthetic [BmsData] at [tickIndex] (0-based) for pack
     * 0. Timestamp is the wall clock at call time so the ring buffer / graph
     * windows behave exactly as with a real connection.
     */
    fun sampleAt(tickIndex: Int): BmsData = packSampleAt(tickIndex, 0)

    /**
     * Pure function: pack [packIndex]'s synthetic [BmsData] at [tickIndex].
     *
     * The vehicle-level current is SPLIT across the packs, and each pack carries
     * its own full-voltage cell string — which is what parallel branches
     * physically are, and what makes `PackAggregator`'s parallel fold
     * (average voltage, summed current) reproduce the whole vehicle.
     */
    fun packSampleAt(tickIndex: Int, packIndex: Int): BmsData {
        val rnd = Random(SEED + tickIndex + packIndex * PACK_SEED_OFFSET)
        val elapsedSec = tickIndex * tickIntervalMs / 1000.0
        val cyclePos = (elapsedSec % CYCLE_SECONDS)
        val phase = phaseOf(cyclePos)

        val soc = socAt(tickIndex).coerceIn(0f, 100f)
        val vehicleCurrent = currentAt(phase, elapsedSec, cyclePos, rnd)

        val cells = cellVoltages(soc, rnd)
        val voltage = cells.sum()
        val current = vehicleCurrent / shape.packCount
        val power = voltage * current

        val temps = temperatures(elapsedSec, rnd)

        val charge = if (shape.reportsFuelGauge) shape.capacityAhPerPack * soc / 100f else 0f

        return BmsData(
            voltage = voltage,
            current = current,
            power = power,
            // A Begode branch reports no fuel gauge: soc = 0 here means
            // UNKNOWN, and socKnown = false is what says so. The caller runs
            // VoltageSocEstimator over the cells, the same object the real
            // wheel's samples go through.
            soc = if (shape.reportsFuelGauge) soc else 0f,
            socKnown = shape.reportsFuelGauge,
            charge = charge,
            capacity = if (shape.reportsFuelGauge) shape.capacityAhPerPack else 0f,
            numCycles = shape.numCycles,
            cellVoltages = cells,
            temperatures = temps,
            chargeEnabled = true,
            dischargeEnabled = true,
            bmsFaults = emptyList(),
            isConnected = true,
            timestamp = Clock.System.now()
        )
    }

    /**
     * Pure function: the synthetic [ControllerData] at [tickIndex] — the ride
     * curve for "Try demo" mode's controller, shaped by [profile]. Shares
     * [phaseOf] with [sampleAt] so the vehicle only "moves" while
     * [Phase.RIDING].
     */
    fun motionAt(tickIndex: Int): ControllerData = when (profile) {
        DemoProfile.SCOOTER -> scooterMotionAt(tickIndex)
        DemoProfile.WHEEL -> wheelMotionAt(tickIndex)
    }

    /**
     * Task 12's VESC scooter ride curve: a smooth accelerate /
     * cruise-with-oscillation / decelerate speed profile, zero while stopped or
     * charging (parked). [dutyPercent] is a direct, strictly increasing function
     * of speed (never saturating below [TOP_SPEED_KMH] over the curve's actual
     * range) so the motor "load" always tracks how fast the demo is "going".
     * Odometer/trip are integrated from the same speed curve in tick-sized
     * steps, exactly like [socAt] — never negative, so both are monotonic
     * non-decreasing across ticks.
     */
    private fun scooterMotionAt(tickIndex: Int): ControllerData {
        val rnd = Random(SEED + tickIndex + MOTION_SEED_OFFSET)
        val elapsedSec = tickIndex * tickIntervalMs / 1000.0
        val cyclePos = elapsedSec % CYCLE_SECONDS
        val phase = phaseOf(cyclePos)

        val speed = speedAt(phase, cyclePos)
        val duty = dutyFor(speed)
        val distanceKm = distanceAt(tickIndex)
        val escTemp = escTempAt(elapsedSec, duty, rnd)
        val inputVoltage = 58f + (rnd.nextFloat() - 0.5f) * 0.6f
        val motorCurrent = (duty / 100f) * 18f
        val batteryCurrent = motorCurrent * 0.9f

        return ControllerData(
            speedKmh = speed.toFloat(),
            speedSource = SpeedSource.REPORTED,
            dutyPercent = duty,
            motorCurrentA = motorCurrent,
            batteryCurrentA = batteryCurrent,
            inputVoltageV = inputVoltage,
            powerW = inputVoltage * batteryCurrent,
            eRpm = (speed * 133.0).toFloat(),
            escTempC = escTemp,
            motorTempC = escTemp - 3f,
            hasMotorTemp = true,
            odometerKm = (ODOMETER_START_KM + distanceKm).toFloat(),
            tripKm = distanceKm.toFloat(),
            consumedAh = (distanceKm * 0.9).toFloat(),
            consumedWh = (distanceKm * 0.9 * inputVoltage).toFloat(),
            regenAh = 0f,
            regenWh = 0f,
            faults = emptyList(),
            isConnected = true,
            timestamp = Clock.System.now()
        )
    }

    /**
     * The Begode wheel's ride curve — the same phase clock, a different machine.
     *
     * What differs from [scooterMotionAt], and why:
     *  - **duty never reaches 0.** [wheelDutyFor] adds
     *    [WHEEL_BALANCING_DUTY_PERCENT] to the speed term, so a parked wheel
     *    still reads ~1.5 %, which is what the ET Max capture shows and what a
     *    wheel physically must do to stay upright. It is still strictly
     *    increasing in speed, so the duty dial tracks the speedo.
     *  - **`hasDuty = true`.** The wheel reports TRUE hardware PWM, so the flag
     *    the Begode protocol latches is set here from the start. (A wheel whose
     *    latch has never fired is the *unknown* case, and the dashboard renders
     *    that as a confident 0 % — recorded as Part G §9, deliberately not
     *    simulated here.)
     *  - **two independent temperatures.** The board sensor and the motor
     *    thermistor are two different sensors on a wheel (27.5 °C against
     *    20 °C in the same seconds of the capture); the motor's runs cooler at
     *    rest and climbs harder under sustained load, so under a long cruise it
     *    crosses ABOVE the board — which the scooter's fixed `escTemp - 3` can
     *    never show.
     *  - **phase current is not proportional to battery current.** They come
     *    from different frames on a real wheel and differ by roughly the duty
     *    fraction: −0.67 A battery against −3.00 A phase on the parked capture.
     *  - **no energy counters, no eRPM.** A Begode reports none, so all five
     *    fields stay 0 — and the consumption readouts therefore have only the
     *    instantaneous `power / speed` to work from. That is the honest picture,
     *    not an omission.
     *  - **speed is REPORTED even at 0.** A wheel reports ground speed directly;
     *    there is no `MotorConfig` and no derivation anywhere in this path.
     */
    private fun wheelMotionAt(tickIndex: Int): ControllerData {
        val rnd = Random(SEED + tickIndex + MOTION_SEED_OFFSET)
        val elapsedSec = tickIndex * tickIntervalMs / 1000.0
        val cyclePos = elapsedSec % CYCLE_SECONDS
        val phase = phaseOf(cyclePos)

        val speed = speedAt(phase, cyclePos)
        val duty = wheelDutyFor(speed)
        val distanceKm = distanceAt(tickIndex)

        // Rail voltage follows the SAME SoC curve the battery stream integrates,
        // minus sag under load — so the Ride dashboard's voltage and the Battery
        // tab's cannot drift apart the way two unrelated curves would.
        val soc = socAt(tickIndex).coerceIn(0f, 100f)
        val batteryCurrent = wheelBatteryCurrentA(speed)
        val restingVoltage = nominalCellVoltage(soc).toDouble() * shape.cellCount
        val inputVoltage =
            (restingVoltage - batteryCurrent * WHEEL_SAG_V_PER_A).toFloat() +
                (rnd.nextFloat() - 0.5f) * 0.4f

        val phaseCurrent = wheelPhaseCurrentA(batteryCurrent, duty)
        val boardTemp = wheelBoardTempAt(elapsedSec, duty, rnd)
        val motorTemp = wheelMotorTempAt(elapsedSec, duty, rnd)

        return ControllerData(
            speedKmh = speed.toFloat(),
            // A wheel reports GROUND speed — no MotorConfig, no derivation.
            speedSource = SpeedSource.REPORTED,
            dutyPercent = duty,
            // The wheel's truePWM latch, closed: this is hardware PWM.
            hasDuty = true,
            motorCurrentA = phaseCurrent.toFloat(),
            batteryCurrentA = batteryCurrent.toFloat(),
            inputVoltageV = inputVoltage,
            powerW = inputVoltage * batteryCurrent.toFloat(),
            // A Begode reports no eRPM at all.
            eRpm = 0f,
            escTempC = boardTemp,
            motorTempC = motorTemp,
            hasMotorTemp = true,
            odometerKm = (WHEEL_ODOMETER_START_KM + distanceKm).toFloat(),
            tripKm = distanceKm.toFloat(),
            // A Begode reports no energy counters — not zero-because-idle, zero
            // because the frames carry no such field. The flag is what says so
            // out loud (G §9.1): without it the demo wheel's consumption card
            // reads "avg 0.0 Wh/km" for the whole simulated ride.
            consumedAh = 0f,
            consumedWh = 0f,
            regenAh = 0f,
            regenWh = 0f,
            hasEnergyCounters = false,
            faults = emptyList(),
            isConnected = true,
            timestamp = Clock.System.now()
        )
    }

    private enum class Phase { RIDING, STOPPED, CHARGING }

    private fun phaseOf(cyclePos: Double): Phase = when {
        cyclePos < PHASE_A_SECONDS -> Phase.RIDING
        cyclePos < PHASE_A_SECONDS + PHASE_B_SECONDS -> Phase.STOPPED
        else -> Phase.CHARGING
    }

    /**
     * SOC integrated across whole completed cycles plus the current partial one.
     * Drains ~0.01 %/s during riding, flat while stopped, and recovers during
     * charging. Clamped to [SOC_MIN]..[SOC_MAX] so a long demo session keeps
     * cycling visibly instead of pinning at an extreme.
     */
    private fun socAt(tickIndex: Int): Float {
        val totalSec = tickIndex * tickIntervalMs / 1000.0
        var soc = SOC_START.toDouble()
        val stepSec = tickIntervalMs / 1000.0
        var t = 0.0
        // Integrate in tick-sized steps; cheap enough for demo cadence and keeps
        // the curve consistent with the per-phase current sign.
        while (t < totalSec) {
            val cyclePos = t % CYCLE_SECONDS
            soc += when (phaseOf(cyclePos)) {
                Phase.RIDING -> -0.012 * stepSec
                Phase.STOPPED -> 0.0
                Phase.CHARGING -> 0.045 * stepSec
            }
            soc = soc.coerceIn(SOC_MIN.toDouble(), SOC_MAX.toDouble())
            t += stepSec
        }
        return soc.toFloat()
    }

    /**
     * Speed (km/h) at a point in the cycle: zero outside [Phase.RIDING], and
     * inside it a trapezoid — accelerate over the first accelerate window,
     * cruise with a gentle sine oscillation around the profile's cruise speed,
     * then decelerate back to zero before the phase ends. Never negative, and
     * never reaches the profile's top speed (the duty scale's ceiling), so the
     * duty function stays strictly increasing with speed across the whole curve.
     */
    private fun speedAt(phase: Phase, cyclePos: Double): Double {
        if (phase != Phase.RIDING) return 0.0
        val accelSeconds = if (profile == DemoProfile.WHEEL) WHEEL_ACCEL_SECONDS else RIDE_ACCEL_SECONDS
        val decelSeconds = if (profile == DemoProfile.WHEEL) WHEEL_DECEL_SECONDS else RIDE_DECEL_SECONDS
        val cruise = if (profile == DemoProfile.WHEEL) WHEEL_CRUISE_SPEED_KMH else CRUISE_SPEED_KMH
        val amplitude = if (profile == DemoProfile.WHEEL) WHEEL_OSC_AMPLITUDE_KMH else OSC_AMPLITUDE_KMH
        val ceiling = if (profile == DemoProfile.WHEEL) WHEEL_TOP_SPEED_KMH else TOP_SPEED_KMH
        val raw = when {
            cyclePos < accelSeconds ->
                cruise * (cyclePos / accelSeconds)
            cyclePos > PHASE_A_SECONDS - decelSeconds ->
                cruise * ((PHASE_A_SECONDS - cyclePos) / decelSeconds)
            else ->
                cruise + amplitude * sin((cyclePos - accelSeconds) / 9.0)
        }
        return raw.coerceIn(0.0, ceiling)
    }

    /** Duty is a plain linear function of speed: rises exactly as speed does. */
    private fun dutyFor(speedKmh: Double): Float =
        (speedKmh / TOP_SPEED_KMH * 100.0).coerceIn(0.0, 100.0).toFloat()

    /**
     * The wheel's hardware PWM: the same linear speed term, plus the balancing
     * floor a wheel can never drop below while it is upright. Strictly
     * increasing in speed over the curve's range, like [dutyFor], and clamped so
     * the floor can never push it past 100 %.
     */
    private fun wheelDutyFor(speedKmh: Double): Float =
        (WHEEL_BALANCING_DUTY_PERCENT + speedKmh / WHEEL_TOP_SPEED_KMH * 100.0)
            .coerceIn(0.0, 100.0).toFloat()

    /** Battery current the wheel draws at [speedKmh], amps, always positive (VESC convention). */
    private fun wheelBatteryCurrentA(speedKmh: Double): Double =
        WHEEL_STANDSTILL_CURRENT_A + speedKmh * WHEEL_CURRENT_A_PER_KMH

    /**
     * Phase current from battery current: `battery / dutyFraction`, with the
     * divisor floored so a parked wheel reads a couple of amps instead of
     * diverging. Always at least the battery current — a motor cannot pull less
     * phase current than the pack is delivering.
     */
    private fun wheelPhaseCurrentA(batteryCurrentA: Double, dutyPercent: Float): Double {
        val fraction = max(dutyPercent / 100.0, WHEEL_MIN_PHASE_DUTY_FRACTION)
        return min(batteryCurrentA / fraction, WHEEL_MAX_PHASE_CURRENT_A)
    }

    /** ESC temperature: a slow sine drift plus a little heat from motor load. */
    private fun escTempAt(elapsedSec: Double, dutyPercent: Float, rnd: Random): Float {
        val drift = 4.0 * sin(elapsedSec / 50.0)
        val loadHeat = dutyPercent * 0.12
        val noise = (rnd.nextFloat() - 0.5f) * 0.6f
        return (34.0 + drift + loadHeat + noise).toFloat()
    }

    /** The wheel's mainboard sensor: cooler base than a scooter ESC, gentler load coupling. */
    private fun wheelBoardTempAt(elapsedSec: Double, dutyPercent: Float, rnd: Random): Float {
        val drift = 2.5 * sin(elapsedSec / 60.0)
        val loadHeat = dutyPercent * 0.09
        val noise = (rnd.nextFloat() - 0.5f) * 0.4f
        return (28.0 + drift + loadHeat + noise).toFloat()
    }

    /**
     * The wheel's motor thermistor: its OWN sensor, not an offset of the board's.
     * Starts colder (the capture reads 20 °C motor against 27.5 °C board on a
     * parked wheel) and couples harder to load, so a sustained cruise takes it
     * above the mainboard — the crossover a single-sensor model cannot show.
     */
    private fun wheelMotorTempAt(elapsedSec: Double, dutyPercent: Float, rnd: Random): Float {
        val drift = 1.5 * sin(elapsedSec / 80.0)
        val loadHeat = dutyPercent * 0.28
        val noise = (rnd.nextFloat() - 0.5f) * 0.3f
        return (21.0 + drift + loadHeat + noise).toFloat()
    }

    /**
     * Distance travelled from tick 0 through [tickIndex], integrated in
     * tick-sized steps exactly like [socAt] — the odometer/trip baseline for
     * the ride curve. [speedAt] is never negative, so this never decreases.
     */
    private fun distanceAt(tickIndex: Int): Double {
        val totalSec = tickIndex * tickIntervalMs / 1000.0
        val stepSec = tickIntervalMs / 1000.0
        var dist = 0.0
        var t = 0.0
        while (t < totalSec) {
            val cyclePos = t % CYCLE_SECONDS
            dist += speedAt(phaseOf(cyclePos), cyclePos) * (stepSec / 3600.0)
            t += stepSec
        }
        return dist
    }

    private fun currentAt(phase: Phase, elapsedSec: Double, cyclePos: Double, rnd: Random): Float =
        if (profile == DemoProfile.WHEEL) wheelCurrentAt(phase, cyclePos, rnd)
        else when (phase) {
            Phase.RIDING -> {
                // Smooth oscillation -3..-12 A via sine, plus a little jitter.
                val base = -7.5 + 4.0 * sin(elapsedSec / 6.0)
                val jitter = (rnd.nextFloat() - 0.5f) * 1.2f
                var c = (base + jitter).toFloat()
                // Occasional 2-3 s regen spike (positive current).
                if (isRegenWindow(cyclePos)) {
                    c = 2f + rnd.nextFloat() * 3f
                }
                c.coerceIn(-12f, 5f)
            }
            Phase.STOPPED -> (rnd.nextFloat() - 0.5f) * 0.3f // ~0 A
            Phase.CHARGING -> 4f + rnd.nextFloat() * 2f       // +4..+6 A
        }

    /**
     * The wheel's pack current, in the BATTERY convention (negative = drawing
     * down the pack), derived from the SAME load model the motion stream
     * publishes — so the Battery tab and the Ride dashboard cannot disagree
     * about what the wheel is doing. A parked wheel still draws: it is
     * balancing, not off.
     */
    private fun wheelCurrentAt(phase: Phase, cyclePos: Double, rnd: Random): Float {
        val jitter = (rnd.nextFloat() - 0.5f) * 0.4f
        return when (phase) {
            Phase.RIDING -> (-wheelBatteryCurrentA(speedAt(phase, cyclePos))).toFloat() + jitter
            Phase.STOPPED -> (-WHEEL_STANDSTILL_CURRENT_A).toFloat() + jitter * 0.25f
            Phase.CHARGING -> WHEEL_CHARGE_CURRENT_A.toFloat() + jitter
        }
    }

    /** Regen spikes land in two short windows during the riding phase. */
    private fun isRegenWindow(cyclePos: Double): Boolean {
        val inFirst = cyclePos in 30.0..33.0
        val inSecond = cyclePos in 70.0..72.0
        return inFirst || inSecond
    }

    /**
     * The resting per-cell voltage at [soc] — 3.30 V empty to 4.20 V full,
     * which is exactly [ru.sodovaya.volty.domain.model.Chemistry.LI_ION_NMC]'s
     * usable window. Shared by the cell list and (for the wheel) the
     * controller's rail voltage.
     */
    private fun nominalCellVoltage(soc: Float): Float = 3.3f + soc * 0.9f / 100f

    private fun cellVoltages(soc: Float, rnd: Random): List<Float> {
        val nominal = nominalCellVoltage(soc) // ~3.3..4.2 V across SOC range
        return List(shape.cellCount) { i ->
            val noise = (rnd.nextFloat() - 0.5f) * 0.016f // ±8 mV
            // Keep one cell slightly low so the cells view shows a min highlight.
            val bias = if (i == 7) -0.015f else 0f
            (nominal + noise + bias).coerceIn(2.5f, 4.25f)
        }
    }

    private fun temperatures(elapsedSec: Double, rnd: Random): List<Float> {
        // 24..31 °C drifting slowly; T2 (index 1) always ~2° hotter.
        val drift = 3.5 * sin(elapsedSec / 40.0)
        val base = 27.0 + drift
        return List(shape.tempSensorCount) { i ->
            val hotBias = if (i == 1) 2.0 else 0.0
            val perSensor = (i - 1.5) * 0.4
            val noise = (rnd.nextFloat() - 0.5f) * 0.4f
            (base + hotBias + perSensor + noise).toFloat()
        }
    }
}
