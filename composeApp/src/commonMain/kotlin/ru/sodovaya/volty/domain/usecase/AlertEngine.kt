package ru.sodovaya.volty.domain.usecase

import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.CalibrationMute
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.NotifyKind
import ru.sodovaya.volty.domain.alert.TemperatureGate
import ru.sodovaya.volty.domain.alert.armedRules
import ru.sodovaya.volty.domain.alert.batteryConditions
import ru.sodovaya.volty.domain.alert.startsNewEpisode
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.motionAlertRules
import ru.sodovaya.volty.domain.model.notifies
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.notification.Notifier
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
 * notification at all. Duty and speed are its metrics; they raise a notification
 * here only when the rider switches [NotifyKind.SPEED] / [NotifyKind.DUTY] on —
 * see [evaluateMotion].
 *
 * **Retraction (2026-10-25): duty and speed may now notify.** This KDoc, and the
 * one on [evaluateMotion], used to say they raise no notification here, ever,
 * because a duty excursion past 80 % is ordinary riding and one notification per
 * excursion would train the rider to ignore them. That reasoning is why they are
 * **off by default**; the rider then asked for an opt-in switch per kind
 * (decision 6), and an enabled kind posts one notification per arming episode,
 * exactly like a temperature ([fireLevelled]) — not one per excursion sample.
 *
 * **Notifications are structured, not worded.** The engine hands the notifier an
 * [AlertMessage]; the platform turns it into the app language
 * ([ru.sodovaya.volty.notification.AlertNotificationFormatter]). The English
 * strings that used to be built inline here are now resources, with the same
 * meaning.
 *
 * **Retraction (2026-10-25): temperatures no longer fire on the first sample.**
 * An earlier revision of this class (and of [fire]'s "debounce") notified on
 * the first reading over a threshold, and [debounce] was only ever a minimum
 * interval between notifications, never a confirmation. That was wrong for
 * Begode, whose checksum-less frames let one corrupted frame decode into a
 * plausible "controller 130 °C" at the edge of BLE range. Motor, controller and
 * battery temperatures now pass a [TemperatureGate] first: three agreeing
 * samples, a >20 °C step discarded as corrupt unless it holds three times over.
 * While the gate has nothing confirmed the previous decision stands. Faults and
 * the battery's non-temperature kinds are unchanged and still fire at once.
 */
@OptIn(ExperimentalTime::class)
class AlertEngine(
    private val bmsRepository: BmsRepository,
    private val notifier: Notifier,
    private val clock: () -> Instant = { Clock.System.now() },
    private val calibrationMute: CalibrationMute = CalibrationMute()
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

    /**
     * Per vehicle: the timestamp of the first BMS sample that carried no fault
     * since the last one that did, or absent while a fault is present. A BMS
     * fault episode (rider-approved decision 2) ends only after [startsNewEpisode]
     * says so, so a flickering flag re-arms nothing and cannot notify twice.
     * Measured by the samples' own timestamps, never by [clock].
     */
    private val bmsFaultClearedAt = mutableMapOf<String, Instant>()

    /** The same, for the controller fault list ([evaluateMotion]). */
    private val controllerFaultClearedAt = mutableMapOf<String, Instant>()

    /**
     * Advances one fault stream's episode bookkeeping by one sample and says whether the previous
     * episode is **over** (absent for the shared [startsNewEpisode] gap, by [at] the sample's own
     * timestamp). Evaluated on present samples too, so a fault that comes back after a long
     * silence is a new episode even if no absent sample happened to land after the gap.
     *
     * A fault list that grows or changes while it stays present is the same episode and does not
     * notify again: only presence matters here, never which faults are listed.
     */
    private fun faultEpisodeOver(
        clearedAt: MutableMap<String, Instant>, vehicleId: String, present: Boolean, at: Instant
    ): Boolean {
        val cleared = clearedAt[vehicleId]
        val over = startsNewEpisode(cleared, at)
        if (present) clearedAt.remove(vehicleId) else if (cleared == null) clearedAt[vehicleId] = at
        return over
    }

    /**
     * One [TemperatureGate] per (vehicle, kind). The battery's WARN and HIGH
     * tiers read the same max-sensor value, so they share the one keyed
     * [AlertKind.TEMPERATURE_HIGH]. Cleared by the disconnected-sample returns
     * of [evaluate] and [evaluateMotion], each for the stream it owns.
     */
    private val gates = mutableMapOf<Pair<String, AlertKind>, TemperatureGate>()

    private fun gate(vehicle: Vehicle, kind: AlertKind): TemperatureGate =
        gates.getOrPut(vehicle.id to kind) { TemperatureGate() }

    private fun forgetGates(vararg kinds: AlertKind) {
        gates.keys.removeAll { it.second in kinds }
    }

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
        if (vehicle == null || !data.isConnected) {
            forgetGates(AlertKind.TEMPERATURE_HIGH)
            return
        }
        val cfg = resolveAlertConfig(vehicle.alertConfig, vehicle.chemistry)
        val now = clock()

        // Confirmed, not raw: null means the gate has not (yet) believed this reading, and the
        // temperature conditions are then left out, so their previous decisions stand.
        val temp = if (data.temperatures.isEmpty()) null
            else gate(vehicle, AlertKind.TEMPERATURE_HIGH).confirm(data.temperatures.max())

        // The bands, guards and severities live in ONE place, shared with the dashboard banner's
        // ActiveAlertTracker (rider alerts Task 4, review I-1): see batteryConditions.
        for (c in batteryConditions(data, cfg, temp)) {
            fire(c.kind, vehicle, now,
                enabled = c.enabled,
                triggered = c.triggered,
                recovered = c.recovered,
                severity = c.severity,
                message = AlertMessage(c.kind, vehicle.name, value = c.value)
            )
        }

        if (cfg.chargeCompleteNotify) {
            val isFull = data.soc >= 99.9f && data.hasCurrent && abs(data.current) < 0.1f
            fire(AlertKind.CHARGE_COMPLETE, vehicle, now,
                triggered = isFull,
                recovered = data.soc < 98f,
                severity = AlertSeverity.INFO,
                message = AlertMessage(AlertKind.CHARGE_COMPLETE, vehicle.name)
            )
        }

        evaluateBmsFaults(data, vehicle, now)
    }

    /**
     * BMS faults notify once per **episode** (rider-approved decisions 2 and 5).
     *
     * ANT BMS repeats its fault flags in every frame, so "armed until the list is
     * empty" is not enough: a flag that drops for one frame and returns would
     * re-arm and notify again (after the debounce). The episode rule re-arms only
     * once the list has been empty for the shared [startsNewEpisode] gap, judged
     * by the samples' own timestamps. Gated by [NotifyKind.BMS_FAULT]; always
     * CRITICAL, like a controller fault.
     */
    private fun evaluateBmsFaults(data: BmsData, vehicle: Vehicle, now: Instant) {
        val present = data.bmsFaults.isNotEmpty()
        val over = faultEpisodeOver(bmsFaultClearedAt, vehicle.id, present, data.timestamp)
        fire(AlertKind.BMS_FAULT, vehicle, now,
            enabled = vehicle.notifies(NotifyKind.BMS_FAULT),
            triggered = present,
            recovered = over,
            severity = AlertSeverity.CRITICAL,
            message = AlertMessage(AlertKind.BMS_FAULT, vehicle.name, details = data.bmsFaults)
        )
    }

    /**
     * The motion one-shots (F §2, §3): controller fault, and the two temperature
     * kinds. Same debounce/arm/recover machinery as the battery alerts above —
     * only the source of the readings differs.
     *
     * **Duty and speed notify only when the rider switches them on.** They are
     * the continuous alarm's metrics: a duty excursion past 80 % is ordinary
     * riding and happens repeatedly in a single ride, which is why they are off
     * by default (F §10's train-the-rider-to-ignore-it failure). *Retraction
     * (2026-10-25): an earlier revision of this comment said they raise nothing
     * here at all; the rider asked for an opt-in per-kind switch, so a kind
     * switched on posts one notification per arming episode through
     * [fireLevelled] — never one per excursion sample, which is the failure the
     * default protects against.* Temperature is the opposite shape: slow,
     * near-monotone, and genuinely the "discrete event crossed a line" F §2
     * describes. Each kind's [NotifyKind] switch gates its notification only; the
     * live alarm is [ru.sodovaya.volty.domain.alert.AlarmController]'s and never
     * reads it.
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
        if (vehicle == null || !motion.isConnected) {
            forgetGates(AlertKind.MOTOR_TEMP_HIGH, AlertKind.ESC_TEMP_HIGH)
            return
        }
        val now = clock()

        // Always CRITICAL: a fault is the controller saying it has stopped
        // trusting itself, and there is no rider-set threshold to grade it
        // against. It needs no availability gate of its own either — a fault
        // list can only be non-empty because a controller decoder put something
        // in it, and `isConnected` above is true only when a controller is
        // actually online (MotionAggregator sets it).
        // Once per episode, like a BMS fault (2026-10-25): a VESC rebuilds `faults` from every
        // frame and a checksum-less Begode frame can flip it, so "armed until the list is empty"
        // re-notified on every flicker. The shared 2 s rule judges it by the sample's timestamp.
        val faultPresent = motion.faults.isNotEmpty()
        fire(AlertKind.CONTROLLER_FAULT, vehicle, now,
            enabled = vehicle.notifies(NotifyKind.CONTROLLER_FAULT),
            triggered = faultPresent,
            recovered = faultEpisodeOver(controllerFaultClearedAt, vehicle.id, faultPresent, motion.timestamp),
            severity = AlertSeverity.CRITICAL,
            message = AlertMessage(AlertKind.CONTROLLER_FAULT, vehicle.name, details = motion.faults)
        )

        // Recomputed from THIS sample, every sample — never cached. Every
        // sensor-backed kind is AlertAvailability.Unknown until the first sample
        // arrives and `armedRules` drops Unknown, so a value computed once when
        // the engine starts would arm no temperature alert for the whole ride,
        // and nothing would report it (AlarmController's KDoc states the same
        // obligation for the alarm). It also follows the vehicle: the rules and
        // the availability both come from the `vehicle` this sample arrived with.
        val rules = armedRules(vehicle, motion, vehicle.motionAlertRules)
        val calibrating = calibrationMute.isActive()
        for (rule in rules.rules) when (rule.kind) {
            MotionAlertKind.MOTOR_TEMP -> gate(vehicle, AlertKind.MOTOR_TEMP_HIGH)
                .confirm(motion.motorTempC)?.let { confirmed ->
                    fireLevelled(
                        AlertKind.MOTOR_TEMP_HIGH, rule, vehicle, now,
                        value = confirmed,
                        enabled = vehicle.notifies(NotifyKind.MOTOR_TEMP),
                        message = AlertMessage(AlertKind.MOTOR_TEMP_HIGH, vehicle.name, value = confirmed.latest)
                    )
                }
            MotionAlertKind.ESC_TEMP -> gate(vehicle, AlertKind.ESC_TEMP_HIGH)
                .confirm(motion.escTempC)?.let { confirmed ->
                    fireLevelled(
                        AlertKind.ESC_TEMP_HIGH, rule, vehicle, now,
                        value = confirmed,
                        enabled = vehicle.notifies(NotifyKind.ESC_TEMP),
                        message = AlertMessage(AlertKind.ESC_TEMP_HIGH, vehicle.name, value = confirmed.latest)
                    )
                }
            // Speed and duty are not gated (decision 1: unchanged) - one raw
            // reading is its own min, max and latest. Switched off by default,
            // in which case fireLevelled posts nothing.
            MotionAlertKind.DUTY -> if (calibrating) markNotifiedForCalibration(vehicle, AlertKind.DUTY_HIGH, rule, motion.dutyPercent) else motion.dutyPercent.let { v ->
                fireLevelled(
                    AlertKind.DUTY_HIGH, rule, vehicle, now,
                    value = TemperatureGate.Confirmed(min = v, max = v, latest = v),
                    enabled = vehicle.notifies(NotifyKind.DUTY),
                    message = AlertMessage(AlertKind.DUTY_HIGH, vehicle.name, value = v)
                )
            }
            MotionAlertKind.SPEED -> if (calibrating) markNotifiedForCalibration(vehicle, AlertKind.SPEED_HIGH, rule, motion.speedKmh) else motion.speedKmh.let { v ->
                fireLevelled(
                    AlertKind.SPEED_HIGH, rule, vehicle, now,
                    value = TemperatureGate.Confirmed(min = v, max = v, latest = v),
                    enabled = vehicle.notifies(NotifyKind.SPEED),
                    message = AlertMessage(AlertKind.SPEED_HIGH, vehicle.name, value = v)
                )
            }
        }
    }

    /**
     * A PWM calibration spins the lifted wheel on purpose: duty and speed raise nothing while it runs. A reading
     * over one of the kind's levels is an episode that began during calibration, so the kind is left DISARMED, as if
     * it had already notified: when the wizard ends with the wheel still spinning that is not a "new episode" (no
     * burst at the moment the rider reads the result). It re-arms the normal way, once a sample falls below every
     * level ([fire]'s `recovered`). A reading under every level leaves the arming alone, so a calibration that
     * ends at rest does not swallow the next real crossing.
     */
    private fun markNotifiedForCalibration(vehicle: Vehicle, kind: AlertKind, rule: AlertRule, value: Float) {
        if (rule.engagedLevel(value) > 0) armed[vehicle.id to kind] = false
        peakLevel.remove(vehicle.id to kind)
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
        value: TemperatureGate.Confirmed, enabled: Boolean, message: AlertMessage
    ) {
        val key = vehicle.id to kind
        // Engage needs all three samples over a level (min); re-arming needs all
        // three under every level (max), so one glitch either way is inert.
        val level = rule.engagedLevel(value.min)
        val recovered = rule.engagedLevel(value.max) == 0
        // The peak is cleared exactly when the episode is recovered, so the two
        // cannot drift apart.
        val peak = if (recovered) 0 else maxOf(level, peakLevel[key] ?: 0)
        peakLevel[key] = peak
        fire(kind, vehicle, now,
            enabled = enabled,
            triggered = level > 0,
            recovered = recovered,
            severity = if (peak >= rule.topEnabledLevel) AlertSeverity.CRITICAL else AlertSeverity.WARNING,
            message = message
        )
    }

    private fun fire(
        kind: AlertKind, vehicle: Vehicle, now: Instant,
        enabled: Boolean = true,
        triggered: Boolean, recovered: Boolean,
        severity: AlertSeverity, message: AlertMessage
    ) {
        if (!enabled) {
            // An alert that was disabled while engaged must be forgotten as an
            // active episode. Re-enabling it should require a fresh crossing,
            // not immediately replay the old condition.
            armed.remove(vehicle.id to kind)
            peakLevel.remove(vehicle.id to kind)
            return
        }
        val key = vehicle.id to kind
        var isArmed = armed.getOrPut(key) { true }
        // A sample can both end the previous episode and start the next (a fault episode is
        // judged over by timestamp, so it can be over on the very sample that returns), hence
        // the local is updated too. The other kinds' recovered/triggered bands never overlap.
        if (recovered && !isArmed) { armed[key] = true; isArmed = true }
        if (!triggered || !isArmed) return
        val last = lastFired[key]
        if (last != null && (now - last) < debounce) return
        lastFired[key] = now
        armed[key] = false
        notifier.showAlert(message, severity, alertCounter++)
    }
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
internal val AlertRule.topEnabledLevel: Int get() = levels.indexOfLast { it.enabled } + 1
