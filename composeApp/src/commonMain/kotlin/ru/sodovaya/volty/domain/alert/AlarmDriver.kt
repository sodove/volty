package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.motionAlertRules
import ru.sodovaya.volty.domain.repository.BmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The speaker as the driver needs to see it — [AudibleAlarm]'s two live-alarm
 * entry points and nothing else.
 *
 * `AudibleAlarm` is an `expect class` whose only `actual` needs an Android
 * `Context`, so a driver that named it directly could not be unit-tested: this
 * repo has one test source set (`commonTest`) and it compiles against the
 * Android actual. This interface is the seam that keeps the decision-making in
 * `commonMain` where a test can drive it, exactly as `AlarmSignalPlanner` is the
 * seam under `AudibleAlarm`'s platform half.
 *
 * `preview`, `stop` and `release` are deliberately **not** here. They are
 * lifecycle operations owned by whoever constructed the alarm — the settings
 * screen for `preview`, the foreground service's `onDestroy` for `release` — and
 * putting them on the driver's port would invite a collector to call them.
 */
interface AlarmOutput {
    /** See `AudibleAlarm.update`: cheap and idempotent, safe on every sample. */
    fun update(state: AlarmState)

    /** See `AudibleAlarm.setModalities`: takes effect on anything already playing. */
    fun setModalities(modalities: AlarmModalities)
}

/**
 * Turns the live telemetry streams into a sounding alarm (F §5) — the piece that
 * makes Task 5's engine and Task 7's speaker into a feature. Everything before it
 * is inert: the controller grades danger nobody hears, and the alarm can make a
 * noise nobody asks it for.
 *
 * Android's `MonitoringService` owns one of these. The service is where it has to
 * live, because the foreground service is the only thing that keeps the BLE link
 * alive with the screen off, and an alarm that only sounds while the rider is
 * looking at the dashboard warns them of nothing.
 *
 * ### Every sample, never sampled
 *
 * The live notification in the same service is `sample(2.seconds)`-throttled,
 * which is right for a notification and wrong for this: a duty spike is a
 * fraction of a second of warning and a two-second delay would spend most of it.
 * This drives off `activeMotion` directly.
 *
 * ### The rules are recomputed on every sample, and that is load-bearing
 *
 * [AlarmController]'s KDoc states the obligation and what breaks when it is
 * missed. Availability for the sensor-backed kinds is
 * [AlertAvailability.Unknown] until a live sample proves the sensor exists, and
 * [armedRules] drops Unknown, so **rules computed once when the service starts
 * arm no temperature alert for the whole ride** — silently, because the only
 * symptom is an alarm that never sounds. [onSample] therefore re-derives them
 * from the sample and the vehicle it arrived with, every time. The same recompute
 * covers a mid-ride [ru.sodovaya.volty.domain.model.SpeedSource] change (a GPS
 * fix arriving), a rider editing their thresholds, and the active vehicle being
 * swapped underneath.
 *
 * ### One collector
 *
 * [AlarmController] is mutable (hysteresis state) and explicitly not thread-safe,
 * and this runs on `Dispatchers.Default` — a multi-threaded dispatcher. Task 6's
 * review found the concrete harm on exactly this shape: two `scope.launch`
 * collectors sharing unsynchronised maps produced corrupted state in 10/10 runs.
 * So the three triggers are [merge]d into a **single** `launch`. A flow never
 * emits concurrently to one collector, so every mutation of [controller] happens
 * in one sequence with proper happens-before between ticks. **Do not add a second
 * `scope.launch` here.**
 *
 * They are merged rather than `combine`d because they are independent events, not
 * a joint state: a modality switch must reach the speaker at once, and combining
 * it into the sample stream would make it wait for the next telemetry frame — on
 * a parked vehicle, forever.
 *
 * ### Silence is guaranteed twice over
 *
 * A tone that outlives the ride is the worst defect this feature can have, so it
 * is prevented by two independent mechanisms:
 *
 *  1. **the sample itself.** `activeMotion` emits a placeholder with
 *     [ControllerData.isConnected] false whenever nothing is connected, and
 *     [AlarmController.update] silences and forgets on it. This is the path that
 *     runs for an ordinary link drop, and it re-arms on the first fresh sample;
 *  2. **[ConnectionState].** Mechanism 1 is a *push*: it needs the repository to
 *     emit. A link that dies without a final emission would leave the last hot
 *     sample standing and the tone with it — an alarm that nothing can clear,
 *     because clearing it needs the readings that just stopped arriving. A
 *     connection state of [ConnectionState.Idle], [ConnectionState.Disconnected]
 *     or [ConnectionState.Failed] is an independent statement that no link can be
 *     delivering anything, and it silences on its own.
 *
 * Service **destruction** is not on this list, and cannot be: cancelling the
 * scope stops the collector rather than the speaker. That is `onDestroy`'s job
 * (`AudibleAlarm.release()`), and it is the reason `release` is not on
 * [AlarmOutput].
 */
class AlarmDriver(
    private val repository: BmsRepository,
    private val modalities: Flow<AlarmModalities>,
    private val alarm: AlarmOutput,
    private val controller: AlarmController = AlarmController()
) {

    /** Which stream produced a tick. See the class doc for why all three share one collector. */
    private sealed interface Tick {
        data class Sample(val motion: ControllerData, val vehicle: Vehicle?) : Tick
        data class Switches(val modalities: AlarmModalities) : Tick
        data class Link(val state: ConnectionState) : Tick
    }

    /** Start the one collector. Cancelled with [scope]; the caller still owes the speaker a teardown. */
    fun start(scope: CoroutineScope) {
        val samples = repository.activeMotion
            .combine(repository.activeVehicle) { motion, vehicle -> Tick.Sample(motion, vehicle) }
        val switches = modalities.map { Tick.Switches(it) }
        val link = repository.connectionState.map { Tick.Link(it) }
        scope.launch {
            merge(samples, switches, link).collect { tick ->
                when (tick) {
                    is Tick.Sample -> onSample(tick.motion, tick.vehicle)
                    is Tick.Switches -> alarm.setModalities(tick.modalities)
                    is Tick.Link -> onLink(tick.state)
                }
            }
        }
    }

    /**
     * One telemetry sample: re-gate, grade, sound.
     *
     * With no active vehicle there is no configuration and no availability, so
     * nothing may arm — [ArmedRules.NONE] rather than an early return, because
     * returning would leave a previously sounding alarm playing against a vehicle
     * that is no longer there. Feeding the sample through with nothing armed
     * silences it *and* clears the held steps, which is the same end state a
     * disconnect reaches.
     */
    private fun onSample(motion: ControllerData, vehicle: Vehicle?) {
        val rules =
            if (vehicle == null) ArmedRules.NONE
            else armedRules(vehicle, motion, vehicle.motionAlertRules)
        alarm.update(controller.update(motion, rules))
    }

    /**
     * The liveness belt described in the class doc. Silences only on the states
     * that mean *nothing at all is connected*, and re-arms on the next sample.
     *
     * [ConnectionState.Reconnecting] and [ConnectionState.Connecting] are
     * deliberately not among them, and this is not caution for its own sake: the
     * vehicle-level state is a fold over every link, so a multi-link vehicle whose
     * *battery* link dropped reads Reconnecting while the controller link is still
     * delivering duty. Silencing there would cut a live alarm off at 95 % duty
     * because an unrelated BMS blinked. The already-connected links keep supplying
     * samples, and [ru.sodovaya.volty.domain.stats.MotionAggregator] folds only the
     * controllers that are actually online, so mechanism 1 is both sufficient and
     * more precise while any link survives.
     */
    private fun onLink(state: ConnectionState) {
        if (state.canDeliverSamples) return
        controller.reset()
        alarm.update(controller.state)
    }
}

/**
 * Whether this state leaves any possibility of a telemetry sample arriving.
 *
 * Exhaustive with no `else` so a new [ConnectionState] has to answer the question
 * rather than inherit an answer — and note the two directions are not equally
 * costly. A new state wrongly marked `true` merely leans on the disconnected
 * placeholder (mechanism 1); wrongly marked `false` it silences a live alarm.
 */
private val ConnectionState.canDeliverSamples: Boolean
    get() = when (this) {
        // Nothing is connected and nothing is being attempted.
        is ConnectionState.Idle -> false
        is ConnectionState.Disconnected -> false
        // The repository has given up; no further sample is coming.
        is ConnectionState.Failed -> false
        // A scan runs with nothing connected, but it is also reachable from the
        // picker mid-ride; the placeholder covers the former and silencing here
        // would break the latter.
        is ConnectionState.Scanning -> true
        is ConnectionState.Connecting -> true
        is ConnectionState.Connected -> true
        // One link of possibly several is retrying — see [AlarmDriver.onLink].
        is ConnectionState.Reconnecting -> true
    }
