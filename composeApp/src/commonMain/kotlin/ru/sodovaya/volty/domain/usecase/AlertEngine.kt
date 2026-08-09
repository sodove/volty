package ru.sodovaya.volty.domain.usecase

import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.armedRules
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.motionAlertRules
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.notification.Notifier
import ru.sodovaya.volty.util.formatFixed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The **one-shot** half of the alert system (F §2): a discrete event crossed a
 * line, so post a notification once, then stay quiet until it clears.
 *
 * The continuous, graded audible alarm is
 * [ru.sodovaya.volty.domain.alert.AlarmController]'s job and is not a
 * notification at all. Duty and speed belong entirely to it — see
 * [evaluateMotion] for why they raise no notification here.
 */
@OptIn(ExperimentalTime::class)
class AlertEngine(
    private val bmsRepository: BmsRepository,
    private val notifier: Notifier,
    private val clock: () -> Instant = { Clock.System.now() }
) {

    private val lastFired = mutableMapOf<Pair<String, AlertKind>, Instant>()
    private val armed = mutableMapOf<Pair<String, AlertKind>, Boolean>()

    /**
     * The highest level a levelled kind has reached during its **current arming
     * episode**, per (vehicle, kind); 0 between episodes.
     *
     * Needed because the notification is posted once per episode but its
     * severity must describe the worst the metric got, and the two instants can
     * differ: while [debounce] is holding a fire back, the reading may climb to
     * level 2 and fall back to level 1 without ever reaching level 0, so the
     * level *at the moment of firing* would understate the episode.
     */
    private val peakLevel = mutableMapOf<Pair<String, AlertKind>, Int>()

    private val debounce = 3.seconds

    // Alert notification ids. Starts well clear of the foreground/live
    // notification id (1001) so a long session can't collide with it.
    private var alertCounter = 10_000

    /** Which stream produced a tick. See [start] for why the two are kept apart. */
    private sealed interface Tick {
        data class Battery(val data: BmsData, val vehicle: Vehicle?) : Tick
        data class Motion(val motion: ControllerData, val vehicle: Vehicle?) : Tick
    }

    /**
     * Two independent triggers, **one collector**.
     *
     * *Why two triggers.* `activeMotion` is deliberately NOT folded into the
     * battery `combine`. A three-way combine re-runs the *battery* evaluation on
     * every motion sample, which arrives an order of magnitude more often than a
     * BMS frame, and that is not neutral: [fire] returns on the [debounce]
     * *before* clearing [armed], so a battery alert held back by the debounce
     * fires on the next evaluation — which under a three-way combine would be the
     * next motion tick rather than the next BMS frame. Adding a motor controller
     * to a vehicle would then change when its cell alerts fire. Battery alert
     * timing is out of Part F's scope (F §1, "battery alert logic (already
     * exists, unchanged)"), so each kind of alert is evaluated exactly when its
     * own data changes and never when the other's does.
     *
     * *Why one collector.* [lastFired], [armed], [peakLevel] and [alertCounter]
     * are plain unsynchronised mutable state, and this engine runs on
     * `Dispatchers.Default` (`VoltyApplication`) — a multi-threaded dispatcher.
     * Two `scope.launch` blocks would be the first concurrent access to them in
     * this class's history, and **the fact that the two paths write disjoint keys
     * does not make that safe**: concurrent `put` into one `LinkedHashMap` can
     * corrupt the map whatever the keys are, and `alertCounter++` is a
     * read-modify-write that is not atomic under any key scheme. Measured, not
     * theorised: driving both paths concurrently on `Dispatchers.Default` with
     * two *different* vehicle ids produced duplicate notification ids in 10/10
     * runs at 40 000 alerts, and a duplicate id makes `NotificationManager.notify`
     * *replace* the earlier notification — a controller fault silently erasing a
     * cell alert.
     *
     * `merge` keeps the two triggers separate while giving the collector body a
     * single coroutine: a flow never emits concurrently to one collector, so
     * every mutation of the state above happens in one sequence with proper
     * happens-before between ticks. **Do not split this back into two
     * `scope.launch` blocks** — confining both paths to one dispatcher thread
     * would work equally well, but only confinement that survives being read by
     * the next person is worth having, and this one cannot be undone by accident.
     */
    fun start(scope: CoroutineScope) {
        val battery = bmsRepository.activeData
            .combine(bmsRepository.activeVehicle) { d, v -> d to v }
            .distinctUntilChanged()
            .map { (data, vehicle) -> Tick.Battery(data, vehicle) }
        val motion = bmsRepository.activeMotion
            .combine(bmsRepository.activeVehicle) { m, v -> m to v }
            .distinctUntilChanged()
            .map { (motion, vehicle) -> Tick.Motion(motion, vehicle) }
        scope.launch {
            merge(battery, motion).collect { tick ->
                when (tick) {
                    is Tick.Battery -> evaluate(tick.data, tick.vehicle)
                    is Tick.Motion -> evaluateMotion(tick.motion, tick.vehicle)
                }
            }
        }
    }

    fun evaluateForTest(data: BmsData, vehicle: Vehicle?) = evaluate(data, vehicle)

    fun evaluateMotionForTest(motion: ControllerData, vehicle: Vehicle?) =
        evaluateMotion(motion, vehicle)

    private fun evaluate(data: BmsData, vehicle: Vehicle?) {
        if (vehicle == null || !data.isConnected) return
        val cfg = resolveAlertConfig(vehicle.alertConfig, vehicle.chemistry)
        val now = clock()

        val cells = data.cellVoltages
        val maxCell = cells.maxOrNull() ?: 0f
        val minCell = cells.minOrNull() ?: 0f
        val deltaMv = ((maxCell - minCell) * 1000f).toInt()
        val maxTemp = data.temperatures.maxOrNull() ?: 0f

        fire(AlertKind.CELL_HIGH, vehicle, now,
            triggered = maxCell > cfg.cellHighV,
            recovered = maxCell < cfg.cellHighV - 0.05f,
            severity = AlertSeverity.CRITICAL,
            title = "Cell voltage high",
            text = "Max cell ${formatV(maxCell)} V on ${vehicle.name}"
        )

        if (minCell > 0.1f) {
            fire(AlertKind.CELL_LOW, vehicle, now,
                triggered = minCell < cfg.cellLowV,
                recovered = minCell > cfg.cellLowV + 0.05f,
                severity = AlertSeverity.CRITICAL,
                title = "Cell voltage low",
                text = "Min cell ${formatV(minCell)} V on ${vehicle.name}"
            )
        }

        if (cells.isNotEmpty()) {
            fire(AlertKind.CELL_DELTA, vehicle, now,
                triggered = deltaMv > cfg.cellDeltaMv,
                recovered = deltaMv < cfg.cellDeltaMv - 30,
                severity = AlertSeverity.WARNING,
                title = "Cell imbalance",
                text = "Δ ${deltaMv} mV on ${vehicle.name}"
            )
        }

        if (data.temperatures.isNotEmpty()) {
            // Two tiers off the SAME max-sensor reading. WARN fires only in the
            // band [warn, high) so a single rising trend escalates warn -> high
            // instead of emitting both at once; HIGH owns everything at/above the
            // critical threshold (which is also where the BMS trips its own
            // protection, so WARN is the user's lead time before that).
            fire(AlertKind.TEMPERATURE_WARN, vehicle, now,
                triggered = maxTemp > cfg.temperatureWarnC && maxTemp < cfg.temperatureHighC,
                recovered = maxTemp < cfg.temperatureWarnC - 3f,
                severity = AlertSeverity.WARNING,
                title = "Temperature warning",
                text = "${maxTemp.toInt()}°C on ${vehicle.name}"
            )
            fire(AlertKind.TEMPERATURE_HIGH, vehicle, now,
                triggered = maxTemp > cfg.temperatureHighC,
                recovered = maxTemp < cfg.temperatureHighC - 3f,
                severity = AlertSeverity.CRITICAL,
                title = "Temperature high",
                text = "${maxTemp.toInt()}°C on ${vehicle.name}"
            )
        }

        // The SoC alerts are the ONLY ones gated on socKnown: a dumb Begode
        // with no cell count publishes soc = 0 meaning "unknown", not "empty",
        // and alarming on it every connect would train the user to ignore the
        // one alert that matters. A genuine 0 % arrives with socKnown = true
        // and still fires. Every other alert reads its own physical quantity
        // and is untouched by an unknown SoC.
        if (data.socKnown) {
            fire(AlertKind.SOC_LOW, vehicle, now,
                triggered = data.soc.toInt() < cfg.socLowPercent,
                recovered = data.soc.toInt() > cfg.socLowPercent + 3,
                severity = AlertSeverity.WARNING,
                title = "Battery low",
                text = "${data.soc.toInt()}% on ${vehicle.name}"
            )

            cfg.socCutoffPercent?.let { cutoff ->
                fire(AlertKind.SOC_CUTOFF, vehicle, now,
                    triggered = data.soc.toInt() < cutoff,
                    recovered = data.soc.toInt() > cutoff + 2,
                    severity = AlertSeverity.CRITICAL,
                    title = "Discharge cutoff",
                    text = "${data.soc.toInt()}% — stop now (${vehicle.name})"
                )
            }
        }

        if (cfg.chargeCompleteNotify) {
            val isFull = data.soc >= 99.9f && data.hasCurrent && abs(data.current) < 0.1f
            fire(AlertKind.CHARGE_COMPLETE, vehicle, now,
                triggered = isFull,
                recovered = data.soc < 98f,
                severity = AlertSeverity.INFO,
                title = "Charge complete",
                text = "${vehicle.name} reached 100%"
            )
        }
    }

    /**
     * The motion one-shots (F §2, §3): controller fault, and the two temperature
     * kinds. Same debounce/arm/recover machinery as the battery alerts above —
     * only the source of the readings differs.
     *
     * **Duty and speed raise nothing here on purpose.** They are the continuous
     * alarm's metrics: a duty excursion past 80 % is ordinary riding and happens
     * repeatedly in a single ride, so one notification per excursion would be
     * dozens of notifications per ride — F §10's train-the-rider-to-ignore-it
     * failure. They are already served, live and graded, by [ru.sodovaya.volty.domain.alert.AlarmController].
     * Temperature is the opposite shape: slow, near-monotone, and genuinely the
     * "discrete event crossed a line" F §2 describes.
     *
     * **A disconnected sample fires nothing and changes nothing.**
     * `activeMotion` emits a zero-filled placeholder `ControllerData()` while
     * nothing is connected (see [ru.sodovaya.volty.domain.alert.availabilityFor]),
     * and that is not a measurement. Returning before touching [armed] or
     * [peakLevel] is what keeps a kind from being left permanently armed (a
     * placeholder read as "recovered", re-arming a kind whose real reading never
     * dropped) or permanently silent (a placeholder read as "still triggered",
     * holding the arm down through the whole gap). The same judgement
     * [ru.sodovaya.volty.domain.alert.AlarmController] makes for the alarm,
     * differing only in that the alarm has live output to silence and this has
     * only memory to leave alone.
     */
    private fun evaluateMotion(motion: ControllerData, vehicle: Vehicle?) {
        if (vehicle == null || !motion.isConnected) return
        val now = clock()

        // Always CRITICAL: a fault is the controller saying it has stopped
        // trusting itself, and there is no rider-set threshold to grade it
        // against. It needs no availability gate of its own either — a fault
        // list can only be non-empty because a controller decoder put something
        // in it, and `isConnected` above is true only when a controller is
        // actually online (MotionAggregator sets it).
        fire(AlertKind.CONTROLLER_FAULT, vehicle, now,
            triggered = motion.faults.isNotEmpty(),
            recovered = motion.faults.isEmpty(),
            severity = AlertSeverity.CRITICAL,
            title = "Controller fault",
            text = "${motion.faults.joinToString(", ")} on ${vehicle.name}"
        )

        // Recomputed from THIS sample, every sample — never cached. Every
        // sensor-backed kind is AlertAvailability.Unknown until the first sample
        // arrives and `armedRules` drops Unknown, so a value computed once when
        // the engine starts would arm no temperature alert for the whole ride,
        // and nothing would report it (AlarmController's KDoc states the same
        // obligation for the alarm). It also follows the vehicle: the rules and
        // the availability both come from the `vehicle` this sample arrived with.
        val rules = armedRules(vehicle, motion, vehicle.motionAlertRules)
        for (rule in rules.rules) when (rule.kind) {
            MotionAlertKind.MOTOR_TEMP -> fireLevelled(
                AlertKind.MOTOR_TEMP_HIGH, rule, vehicle, now,
                value = motion.motorTempC,
                title = "Motor temperature high",
                text = "Motor ${motion.motorTempC.toInt()}°C on ${vehicle.name}"
            )
            MotionAlertKind.ESC_TEMP -> fireLevelled(
                AlertKind.ESC_TEMP_HIGH, rule, vehicle, now,
                value = motion.escTempC,
                title = "Controller temperature high",
                text = "ESC ${motion.escTempC.toInt()}°C on ${vehicle.name}"
            )
            // Exhaustive and deliberately empty — see the KDoc above. Listed
            // rather than `else` so a new MotionAlertKind has to answer this
            // question instead of silently inheriting "no notification".
            MotionAlertKind.DUTY, MotionAlertKind.SPEED -> Unit
        }
    }

    /**
     * [fire] for a kind whose rider config carries 0..3 levels: **one**
     * notification per arming episode, at whichever level first engages, and it
     * re-arms only once the reading has fallen below *every* level.
     *
     * Posting per level instead would turn one continuous climb through a
     * rider's three steps into three notifications (F §10). What the levels do
     * carry is the [AlertSeverity]: the rider's most urgent enabled step is
     * CRITICAL, anything below it WARNING — relative to their own list, so a
     * single-step rule (the shipped temperature defaults) reads CRITICAL rather
     * than being permanently demoted for having only one step.
     */
    private fun fireLevelled(
        kind: AlertKind, rule: AlertRule, vehicle: Vehicle, now: Instant,
        value: Float, title: String, text: String
    ) {
        val key = vehicle.id to kind
        val level = rule.engagedLevel(value)
        // 0 both clears the episode's peak and is the recovery condition below,
        // which is why they cannot drift apart.
        val peak = if (level == 0) 0 else maxOf(level, peakLevel[key] ?: 0)
        peakLevel[key] = peak
        fire(kind, vehicle, now,
            triggered = level > 0,
            recovered = level == 0,
            severity = if (peak >= rule.topEnabledLevel) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
            title = title,
            text = text
        )
    }

    private fun fire(
        kind: AlertKind, vehicle: Vehicle, now: Instant,
        triggered: Boolean, recovered: Boolean,
        severity: AlertSeverity, title: String, text: String
    ) {
        val key = vehicle.id to kind
        val isArmed = armed.getOrPut(key) { true }
        if (recovered && !isArmed) armed[key] = true
        if (!triggered || !isArmed) return
        val last = lastFired[key]
        if (last != null && (now - last) < debounce) return
        lastFired[key] = now
        armed[key] = false
        notifier.showAlert(title, text, severity, alertCounter++)
    }

    private fun formatV(v: Float): String = formatFixed(v, 2)
}

/**
 * The highest **enabled** level [value] has reached — 1-based, 0 when it has
 * reached none, which is also "this alert is not currently engaged".
 *
 * No hysteresis, unlike [ru.sodovaya.volty.domain.alert.AlarmController]'s
 * release bands: a one-shot's anti-chatter is arm/recover plus the debounce, and
 * adding a second damping mechanism on top would only delay the *re-arm*, i.e.
 * make the engine slower to notice the next genuine episode.
 *
 * Levels ascend ([AlertRule] enforces it), so the last engaged one is the
 * highest. A disabled level is skipped without shifting the ones above it, so
 * the position is the level's index in the rider's list rather than a count of
 * the enabled ones — the same rule the alarm follows. A NaN reading fails `>=`
 * and so reads as below every level, silencing rather than latching.
 */
private fun AlertRule.engagedLevel(value: Float): Int {
    var engaged = 0
    for ((index, level) in levels.withIndex()) {
        if (level.enabled && value >= level.thresholdValue) engaged = index + 1
    }
    return engaged
}

/**
 * The position of the rider's most urgent **enabled** step — the one that means
 * CRITICAL. 0 when every step is muted, which is unreachable as a severity
 * because [engagedLevel] then returns 0 too and nothing fires.
 */
private val AlertRule.topEnabledLevel: Int get() = levels.indexOfLast { it.enabled } + 1
