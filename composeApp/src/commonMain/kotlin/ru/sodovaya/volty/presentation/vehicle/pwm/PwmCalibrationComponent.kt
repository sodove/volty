package ru.sodovaya.volty.presentation.vehicle.pwm

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnStart
import com.arkivanov.essenty.lifecycle.doOnStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.alert.CalibrationMute
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DEFAULT_PWM_ALARM_THRESHOLD_PERCENT
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.pwm.PwmEvidence
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime

/** What the calibration screen renders: the machine, plus what the machine does not own. */
data class PwmCalibrationUi(
    val wizard: PwmWizardState,
    /** The app's active vehicle is THIS vehicle and its link is up. */
    val connected: Boolean = false,
    val vehicleName: String? = null,
    val saving: Boolean = false,
    /** The save threw: the result is still on screen and Save can be tried again. */
    val saveFailed: Boolean = false,
)

interface PwmCalibrationComponent {
    val state: StateFlow<PwmCalibrationUi>
    fun onStart()
    fun onRunAgain()
    fun onToPlateau()
    fun onFinish()
    fun onRestart()

    /** Saves the result as the vehicle's calibration (source WIZARD), then leaves. */
    fun onSave()

    /** Leaves without writing anything. */
    fun onCancel()
}

/**
 * One live reading for the wizard, from the vehicle-level motion fold and the pack aggregate. Voltage follows the
 * estimator's own rule (pack aggregate when connected and > 0, else the controller's MEASURED input voltage), and
 * a speed the link has not earned ([ControllerData.speedKnown] false) is NaN, which the machine ignores: a
 * placeholder 0 must never look like a reading.
 */
@OptIn(ExperimentalTime::class)
fun wizardSampleOf(motion: ControllerData, data: VehicleData): WizardSample {
    val agg = data.aggregate
    val packV = if (agg.isConnected && agg.voltage.isFinite() && agg.voltage > 0f) agg.voltage
    else motion.inputVoltageV.takeIf { motion.hasInputVoltage && it.isFinite() && it > 0f }
    val speed = if (motion.speedKnown && motion.speedKmh.isFinite()) motion.speedKmh else Float.NaN
    return WizardSample(motion.timestamp.toEpochMilliseconds(), speed, packV, motion.wheelPwmAlarm, motion.wheelPwmAlarmSinceMillis)
}

/**
 * The calibration wizard for one saved vehicle. A thin shell around [PwmWizardState]: it feeds live samples in,
 * forwards the rider's taps, and on Save writes through [BmsRepository.applyPwmCalibration] — the repository's one
 * ordered PWM path, which keeps the live estimator and the row consistent — never the store directly.
 *
 * Read-only on the device: the wizard only listens to the stream the link already carries.
 *
 * The store call runs on [ioContext] (off Main). Cancel, and the system back, write nothing.
 */
class DefaultPwmCalibrationComponent(
    componentContext: ComponentContext,
    parentScope: CoroutineScope,
    private val vehicleId: String,
    private val vehicles: VehicleRepository,
    private val bms: BmsRepository,
    private val onBackRequested: () -> Unit,
    private val ioContext: CoroutineContext = Dispatchers.Default,
    private val calibrationMute: CalibrationMute,
) : PwmCalibrationComponent, ComponentContext by componentContext {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)

    // Not ready until the vehicle's own threshold is read AND the wheel is proven not to report PWM: Start does nothing before.
    private val _state = MutableStateFlow(PwmCalibrationUi(PwmWizardState(DEFAULT_PWM_ALARM_THRESHOLD_PERCENT, ready = false)))

    private var loadedThreshold: Int? = null
    private var evidenceProven = false

    /** Opens the wizard once both gates are known, and only while it is still waiting in INTRO. */
    private fun openIfReady() = _state.update { ui ->
        val t = loadedThreshold
        val w = ui.wizard
        if (t != null && evidenceProven && !w.ready && w.step == PwmWizardStep.INTRO) ui.copy(wizard = w.copy(thresholdPercent = t, ready = true))
        else ui
    }

    private fun block(reason: PwmWizardBlock) = _state.update { ui -> ui.copy(wizard = ui.wizard.block(reason), saving = false) }
    override val state: StateFlow<PwmCalibrationUi> = _state.asStateFlow()

    init {
        lifecycle.doOnDestroy { lifetime.cancel(); calibrationMute.end() }
        // The mute follows what the rider can SEE and DO, never the telemetry: live samples rewrite the state on every
        // frame, so renewing on them would let a wizard left open keep the alarms off for a whole ride (review of
        // Task 3). It is renewed only by rider actions (start / run again / to plateau, see [renewMute]) and by
        // wizard progress (the step, an onset captured, a run captured) - and by nothing else - so the 3-minute time
        // box really does end a forgotten run.
        // The wizard stops being visible (app backgrounded, another screen pushed): the mute is released at once.
        // On returning it is renewed only if the machine is still in a spinning step - the rider is looking at the
        // run again; a forgotten run is still bounded by the time box.
        lifecycle.doOnStop { calibrationMute.end() }
        lifecycle.doOnStart { renewMute() }
        scope.launch {
            _state.map { Triple(it.wizard.step, it.wizard.onsets.size, it.wizard.runCaptured) }
                .distinctUntilChanged()
                .collect { renewMute() }
        }
        scope.launch {
            val v = try { vehicles.get(vehicleId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            _state.update { it.copy(vehicleName = v?.name) }
            if (v == null) block(PwmWizardBlock.LOAD_FAILED)
            else { loadedThreshold = v.pwmAlarmThresholdPercent; openIfReady() }
        }
        // The rider rule, enforced HERE and not only at the editor's tap: a restored back stack can open this screen
        // on any wheel, and a wheel can latch REPORTS while it is open. Anything but NEVER_REPORTED closes it for good.
        scope.launch {
            val failure = try {
                bms.pwmEvidence(vehicleId).collect { ev ->
                    when (ev) {
                        PwmEvidence.NEVER_REPORTED -> { evidenceProven = true; openIfReady() }
                        PwmEvidence.REPORTS -> block(PwmWizardBlock.REPORTS)
                        PwmEvidence.UNKNOWN -> block(PwmWizardBlock.NOT_PROVEN)
                    }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (failure != null) block(PwmWizardBlock.LOAD_FAILED)
        }
        scope.launch {
            combine(bms.activeVehicle, bms.connectionState, bms.activeMotion, bms.activeVehicleData) { active, st, motion, data ->
                val connected = active?.id == vehicleId && st is ConnectionState.Connected
                connected to if (connected) wizardSampleOf(motion, data) else null
            }.distinctUntilChanged().collect { (connected, sample) ->
                _state.update { ui ->
                    val machine = if (sample != null) ui.wizard.onSample(sample) else ui.wizard.onDisconnected()
                    ui.copy(connected = connected, wizard = machine)
                }
            }
        }
    }

    private fun machine(f: (PwmWizardState, Boolean) -> PwmWizardState) {
        _state.update { ui -> ui.copy(wizard = f(ui.wizard, ui.connected)) }
        renewMute()
    }

    /** Spinning step (THRESHOLD / PLATEAU) and visible: mute duty and speed alarms for another time box; else release. */
    private fun renewMute() {
        val step = _state.value.wizard.step
        if (step == PwmWizardStep.THRESHOLD || step == PwmWizardStep.PLATEAU) calibrationMute.begin()
        else calibrationMute.end()
    }

    override fun onStart() = machine { w, connected -> w.start(connected) }
    override fun onRunAgain() = machine { w, _ -> w.runAgain() }
    override fun onToPlateau() = machine { w, _ -> w.toPlateau() }
    override fun onFinish() = machine { w, _ -> w.finish() }
    override fun onRestart() = machine { w, _ -> w.restart() }

    override fun onSave() {
        val w = _state.value.wizard
        if (w.step != PwmWizardStep.RESULT) return // BLOCKED has no result; nothing else saves
        val calibration = w.result?.calibration ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, saveFailed = false) }
        scope.launch {
            // The repository re-checks the evidence and refuses (a failure) for a wheel that is not NEVER_REPORTED.
            val outcome = try {
                withContext(ioContext) { bms.applyPwmCalibration(vehicleId, calibration) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (outcome.isFailure) {
                _state.update { it.copy(saving = false, saveFailed = true) }
                return@launch
            }
            _state.update { it.copy(saving = false) }
            onBackRequested()
        }
    }

    override fun onCancel() {
        calibrationMute.end() // explicit: do not rely on the navigation pop reaching doOnDestroy
        onBackRequested()
    }
}
