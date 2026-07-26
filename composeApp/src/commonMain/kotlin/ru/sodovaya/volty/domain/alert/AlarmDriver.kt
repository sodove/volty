package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.motionAlertRules
import ru.sodovaya.volty.domain.repository.BmsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *     [ControllerData.isConnected] false whenever a *reset path* runs —
 *     `disconnect()`, a demo teardown, switching to another device — and
 *     [AlarmController.update] silences and forgets on it;
 *  2. **[ConnectionState].** Mechanism 1 is a *push*, and it needs somebody to
 *     push. **A dropped link does not push.** `KableBmsRepository.onLinkDrop`
 *     marks the link reconnecting and starts an unbounded retry loop without
 *     touching `activeMotion`, which is written only from `onVehicleData` — i.e.
 *     only when a sample is actually submitted. So a link that dies mid-alarm
 *     leaves the last hot reading sitting in the StateFlow **indefinitely**, and
 *     mechanism 1 cannot fire because the readings that would clear it are
 *     exactly the ones that stopped arriving. Anything other than
 *     [ConnectionState.Connected] therefore silences on its own; see
 *     [canDeliverSamples] for why that set is exactly right.
 *
 * Service **destruction** is not on this list, and cannot be: cancelling the
 * scope stops the collector rather than the speaker. That is `onDestroy`'s job
 * (`AudibleAlarm.release()`), and it is the reason `release` is not on
 * [AlarmOutput].
 *
 * ### One gap the two mechanisms do NOT close, recorded rather than papered over
 *
 * On a vehicle with **both** a BMS and a controller, where the *controller* link
 * dies while the BMS keeps delivering: the vehicle-level state stays
 * [ConnectionState.Connected] — correctly, a link really is up — so mechanism 2
 * must not fire, and mechanism 1 cannot, because
 * `VehicleConnection.submit` sweeps only `states` (packs) for staleness and
 * `submitMotion` only `ctrlStates` (controllers). Nothing marks a controller
 * stale from the battery path, so its `isOnline` stays true, and
 * [ru.sodovaya.volty.domain.stats.MotionAggregator] keeps folding the last hot
 * reading with `isConnected = true`. The alarm would go on sounding on a duty
 * number nobody is measuring any more.
 *
 * The fix belongs in `VehicleConnection` — a cross-kind staleness sweep, or a
 * clock-driven one — and is deliberately **not** made here: a second staleness
 * rule living in the alarm would be a second answer to a question that already
 * has an owner. Recorded as a follow-up; do not close it by adding a timeout to
 * this class.
 *
 * What *is* here is the survivability half — [silence], the live notification's
 * "Заглушить" action — so a rider caught by that gap can stop the noise without
 * ending the ride, and [isSilenced], so they can see that they did.
 * [AlarmSilencer] documents what it means and what it costs.
 */
class AlarmDriver(
    private val repository: BmsRepository,
    private val modalities: Flow<AlarmModalities>,
    private val alarm: AlarmOutput,
    private val controller: AlarmController = AlarmController(),
    private val silencer: AlarmSilencer = AlarmSilencer()
) {

    /** Which stream produced a tick. See the class doc for why all four share one collector. */
    private sealed interface Tick {
        data class Sample(val motion: ControllerData, val vehicle: Vehicle?) : Tick
        data class Switches(val modalities: AlarmModalities) : Tick
        data class Link(val state: ConnectionState) : Tick
        data object Silence : Tick
    }

    /**
     * The rider's silence requests, as a stream rather than a flag.
     *
     * It arrives from a `BroadcastReceiver` on Android's main thread while the
     * collector is running on `Dispatchers.Default`, and [AlarmSilencer] is as
     * unsynchronised as [AlarmController] is. Routing the request through the one
     * collector is what keeps that safe — the same reason the class doc gives for
     * merging rather than launching a second collector.
     *
     * Conflating: one buffered request, oldest dropped. Two presses a millisecond
     * apart are one silence, and there is nothing to gain from queueing the
     * second. `replay = 0` means a press before [start] is dropped, which is
     * right — nothing was sounding to silence.
     */
    private val silenceRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _isSilenced = MutableStateFlow(false)

    /**
     * **Is a rider's silence in force right now?** — so the live notification can
     * say so.
     *
     * [AlarmSilencer]'s own documentation concedes that on the frozen-link case
     * the button exists for, the suppression can last the rest of the ride: the
     * level never returns to 0, so it never lifts. The only cue the rider gets is
     * that the alarm never sounds, which is indistinguishable from *nothing is
     * wrong* — a rider who silences a stuck duty alarm at km 5 and arrives at km
     * 40 has ridden 35 km with no duty alarm and no way to know it.
     *
     * This is that cue. A `StateFlow` rather than [AlarmSilencer.isSilenced]
     * read directly, because the silencer is as unsynchronised as
     * [AlarmController] and is mutated only on the one collector; this is
     * published from that same collector and is safe to read from anywhere.
     *
     * Deliberately **not** a timeout. A suppression that outlives the danger is
     * its own safety bug, and the trade was settled in [AlarmSilencer]: making it
     * visible is the answer, not making it expire.
     */
    val isSilenced: StateFlow<Boolean> = _isSilenced.asStateFlow()

    /**
     * "Заглушить" — stop the sounding alarm without ending the ride.
     *
     * Safe to call from any thread: it only offers a request to the collector,
     * which is where the decision is made. See [AlarmSilencer] for what silence
     * means over time and for the window it opens.
     */
    fun silence() { silenceRequests.tryEmit(Unit) }

    /**
     * The one way anything reaches the speaker: gate, play, publish.
     *
     * [AlarmSilencer.gate] both suppresses **and re-arms** — it clears itself on
     * a level-0 state — so [isSilenced] can only be read honestly *after* the
     * gate has run. Every path that sounds the alarm goes through here so that a
     * silence lifting can never be a change the notification misses.
     */
    private fun publish(state: AlarmState) {
        alarm.update(silencer.gate(state))
        _isSilenced.value = silencer.isSilenced
    }

    /** Start the one collector. Cancelled with [scope]; the caller still owes the speaker a teardown. */
    fun start(scope: CoroutineScope) {
        val samples = repository.activeMotion
            .combine(repository.activeVehicle) { motion, vehicle -> Tick.Sample(motion, vehicle) }
        val switches = modalities.map { Tick.Switches(it) }
        val link = repository.connectionState.map { Tick.Link(it) }
        val silences = silenceRequests.map { Tick.Silence }
        scope.launch {
            merge(samples, switches, link, silences).collect { tick ->
                when (tick) {
                    is Tick.Sample -> onSample(tick.motion, tick.vehicle)
                    is Tick.Switches -> alarm.setModalities(tick.modalities)
                    is Tick.Link -> onLink(tick.state)
                    is Tick.Silence -> onSilence()
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
        // Through the silencer on the way out, never into the engine: a silence
        // must not disturb hysteresis, so the engine goes on grading the ride and
        // only what reaches the speaker is suppressed. That is also what lets the
        // silence lift by itself the moment the engine says level 0.
        publish(controller.update(motion, rules))
    }

    /**
     * The liveness belt described in the class doc: anything but
     * [ConnectionState.Connected] silences, immediately and unconditionally.
     *
     * Silencing costs nothing that has to be paid back. Attack is immediate and
     * only release is damped, so a link that comes back sounds again on its very
     * first reading — the alarm is silenced, not disarmed.
     */
    private fun onLink(state: ConnectionState) {
        if (state.canDeliverSamples) return
        controller.reset()
        // The reset state is level 0, so this is also where a rider's silence is
        // lifted: a link the app *can* see drop re-arms the alarm rather than
        // leaving it suppressed into the next stretch of the ride.
        publish(controller.state)
    }

    /**
     * The rider pressed "Заглушить" on the live notification.
     *
     * The current engine state is pushed through the silencer at once rather than
     * waiting for the next sample, so the speaker stops on the press. On a frozen
     * link — the case this exists for — there is no next sample, so waiting would
     * mean never.
     */
    private fun onSilence() {
        silencer.silence()
        publish(controller.state)
    }
}

/**
 * Whether this state leaves any possibility of a telemetry sample arriving.
 * **Only [ConnectionState.Connected] does.**
 *
 * That reads like a blunt rule and it is in fact the precise one, because of what
 * the vehicle-level fold actually is. `KableBmsRepository.refoldConnectionStateLocked`
 * tests **`any link ONLINE` first**, so `Connected` is the answer whenever a
 * single link of any number is up. Every other state is therefore reachable *only
 * when nothing at all is online*:
 *
 *  - `Connecting` — some link is dialling and none is up;
 *  - `Reconnecting` — some link is retrying and none is up. This is the dangerous
 *    one: `onLinkDrop` starts an unbounded `while (isActive)` retry loop that
 *    never gives up, so the state can sit here for the rest of the ride while
 *    `activeMotion` still holds the last hot reading;
 *  - `Scanning` — the picker only writes it when the state is *not* already
 *    Connected/Connecting/Reconnecting, so it too implies nothing is up;
 *  - `Idle`, `Disconnected`, `Failed` — self-evidently nothing.
 *
 * An earlier version of this file excluded `Reconnecting` on the belief that a
 * multi-link vehicle could read Reconnecting while a surviving link kept
 * delivering duty. **That state is unreachable** — such a vehicle reads
 * `Connected` — and the exclusion left exactly the hole the belt exists to close:
 * a rider at 95 % duty whose link drops would have kept the tone in their pocket
 * until the link returned or they stopped and hit disconnect.
 *
 * Exhaustive with no `else`, one arm per state, so a new [ConnectionState] has to
 * answer the question rather than inherit an answer.
 */
private val ConnectionState.canDeliverSamples: Boolean
    get() = when (this) {
        is ConnectionState.Connected -> true
        is ConnectionState.Idle -> false
        is ConnectionState.Scanning -> false
        is ConnectionState.Connecting -> false
        is ConnectionState.Disconnected -> false
        is ConnectionState.Reconnecting -> false
        is ConnectionState.Failed -> false
    }
