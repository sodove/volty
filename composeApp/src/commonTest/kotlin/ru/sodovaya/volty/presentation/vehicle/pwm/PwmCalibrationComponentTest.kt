package ru.sodovaya.volty.presentation.vehicle.pwm

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.start
import com.arkivanov.essenty.lifecycle.stop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.pwm.PwmBasis
import ru.sodovaya.volty.domain.pwm.PwmCalibration
import ru.sodovaya.volty.domain.pwm.PwmEvidence
import ru.sodovaya.volty.domain.pwm.PwmCalibrationSource
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.stats.MovingAvg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class PwmCalibrationComponentTest {
    private val t4 = Vehicle(
        id = "t4", name = "T4", iconKey = "unicycle",
        packs = listOf(Pack(0, "Wheel pack", BmsType.BEGODE, "WW")),
        controllers = listOf(Controller(0, "Wheel", ControllerType.BEGODE, "WW")),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0),
        pwmAlarmThresholdPercent = 80,
    )

    private class FakeBms : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val applied = mutableListOf<Pair<String, PwmCalibration>>()
        var applyGate: CompletableDeferred<Unit>? = null
        var failApply = false
        /** The wheel's evidence as the repository reports it, live. */
        val evidence = MutableStateFlow(PwmEvidence.NEVER_REPORTED)
        override fun pwmEvidence(vehicleId: String): Flow<PwmEvidence> = evidence
        var refuse = false
        override suspend fun applyPwmCalibration(vehicleId: String, calibration: PwmCalibration): Result<Unit> {
            applyGate?.await()
            if (failApply) throw IllegalStateException("disk full")
            if (refuse) return Result.failure(IllegalStateException("evidence"))
            applied += vehicleId to calibration
            return Result.success(Unit)
        }
        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> = Result.success(Unit)
        override suspend fun connectDemo(profile: DemoProfile): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun disconnectLink(address: String) {}
        override fun samples(window: Duration): Flow<List<BmsData>> = flowOf(emptyList())
        override fun motionSamples(window: Duration): Flow<List<ControllerData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}
    }

    private class Vehicles(private val v: Vehicle, var gate: CompletableDeferred<Unit>? = null, var missing: Boolean = false) : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = MutableStateFlow(listOf(v))
        override suspend fun get(id: String): Vehicle? {
            gate?.await()
            return v.takeIf { it.id == id && !missing }
        }
        override suspend fun upsert(vehicle: Vehicle) = error("the wizard never writes the vehicle")
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    private class Rig(val bms: FakeBms, val component: PwmCalibrationComponent) {
        var backs = 0
        var t = 0L
        fun send(kmh: Float, alarm: Boolean?, v: Float = 96.8f) {
            t += 100
            bms.activeMotion.value = ControllerData(
                speedKmh = kmh, speedSource = SpeedSource.REPORTED, wheelPwmAlarm = alarm,
                inputVoltageV = v, hasInputVoltage = true, isConnected = true, timestamp = Instant.fromEpochMilliseconds(t),
            )
        }
    }

    private fun TestScope.rig(vehicle: Vehicle = t4, vehicles: Vehicles = Vehicles(vehicle), evidence: PwmEvidence = PwmEvidence.NEVER_REPORTED, mute: ru.sodovaya.volty.domain.alert.CalibrationMute = ru.sodovaya.volty.domain.alert.CalibrationMute(), lifecycle: LifecycleRegistry = LifecycleRegistry(Lifecycle.State.RESUMED)): Rig {
        val bms = FakeBms().also { it.evidence.value = evidence }
        lateinit var rig: Rig
        val component = DefaultPwmCalibrationComponent(
            DefaultComponentContext(lifecycle), backgroundScope, "t4",
            vehicles, bms, onBackRequested = { rig.backs++ }, ioContext = StandardTestDispatcher(testScheduler), calibrationMute = mute,
        )
        rig = Rig(bms, component)
        return rig
    }

    private fun connect(r: Rig) {
        r.bms.activeVehicle.value = t4
        r.bms.connectionState.value = ConnectionState.Connected(t4)
    }

    @Test fun samplesCarryOnlyWhatTheLinkEarned() {
        // Incoherent on purpose: a speed with no source, a voltage the link says it never measured.
        val m = ControllerData(
            speedKmh = 50f, speedSource = SpeedSource.NONE, inputVoltageV = 96f, hasInputVoltage = false,
            wheelPwmAlarm = true, timestamp = Instant.fromEpochMilliseconds(5),
        )
        val s = wizardSampleOf(m, VehicleData())
        assertTrue(s.speedKmh.isNaN(), "an unknown speed is not a 50")
        assertNull(s.packV, "an unmeasured voltage is not a 96")
        assertEquals(true, s.alarm); assertEquals(5L, s.atMillis)
    }

    @Test fun usesTheVehiclesOwnThreshold() = runTest {
        val r = rig(); runCurrent()
        assertEquals(80, r.component.state.value.wizard.thresholdPercent)
        assertEquals("T4", r.component.state.value.vehicleName)
    }

    @Test fun startingWithoutALinkSaysSoAndStaysInIntro() = runTest {
        val r = rig(); runCurrent()
        r.component.onStart()
        assertEquals(PwmWizardStep.INTRO, r.component.state.value.wizard.step)
        assertEquals(PwmWizardNotice.NOT_CONNECTED, r.component.state.value.wizard.notice)
    }

    @Test fun aRunFromTheLiveStreamIsCapturedAndSavedThroughTheRepositoryAsWizard() = runTest {
        val r = rig(); connect(r); r.send(0f, false); runCurrent()
        assertTrue(r.component.state.value.connected)
        r.component.onStart()
        assertEquals(PwmWizardStep.THRESHOLD, r.component.state.value.wizard.step)
        r.send(66f, false); runCurrent(); r.send(68.4f, true); runCurrent()
        assertEquals(1, r.component.state.value.wizard.onsets.size)
        r.component.onFinish()
        val result = assertNotNull(r.component.state.value.wizard.result)
        r.component.onSave(); runCurrent()
        val (id, cal) = r.bms.applied.single()
        assertEquals("t4", id)
        assertEquals(result.calibration, cal)
        assertEquals(PwmCalibrationSource.WIZARD, cal.source); assertEquals(PwmBasis.ONSET, cal.basis)
        assertEquals(1, r.backs, "leaves after the write")
    }

    @Test fun cancelWritesNothingAndLeaves() = runTest {
        val r = rig(); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); r.send(66f, false); runCurrent(); r.send(68.4f, true); runCurrent()
        r.component.onFinish()
        r.component.onCancel(); runCurrent()
        assertTrue(r.bms.applied.isEmpty())
        assertEquals(1, r.backs)
    }

    @Test fun savingWithNoResultDoesNothing() = runTest {
        val r = rig(); connect(r); runCurrent()
        r.component.onSave(); runCurrent()
        assertTrue(r.bms.applied.isEmpty()); assertEquals(0, r.backs)
    }

    @Test fun aDisconnectMidRunReturnsToIntroWithTheReason() = runTest {
        val r = rig(); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); r.send(66f, false); runCurrent(); r.send(68.4f, true); runCurrent()
        assertEquals(1, r.component.state.value.wizard.onsets.size)
        r.bms.connectionState.value = ConnectionState.Idle; runCurrent()
        val w = r.component.state.value.wizard
        assertEquals(PwmWizardStep.INTRO, w.step)
        assertEquals(PwmWizardNotice.DISCONNECTED, w.notice)
        assertTrue(w.onsets.isEmpty())
        assertFalse(r.component.state.value.connected)
    }

    @Test fun aSaveThatFailsKeepsTheResultAndCanBeRetried() = runTest {
        val r = rig(); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); r.send(66f, false); runCurrent(); r.send(68.4f, true); runCurrent()
        r.component.onFinish()
        r.bms.failApply = true
        r.component.onSave(); runCurrent()
        assertTrue(r.component.state.value.saveFailed); assertFalse(r.component.state.value.saving)
        assertNotNull(r.component.state.value.wizard.result); assertEquals(0, r.backs)
        r.bms.failApply = false
        r.component.onSave(); runCurrent()
        assertEquals(1, r.bms.applied.size); assertEquals(1, r.backs)
    }

    @Test fun aSecondSaveTapWhileTheFirstIsInFlightWritesOnce() = runTest {
        val r = rig(); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); r.send(66f, false); runCurrent(); r.send(68.4f, true); runCurrent()
        r.component.onFinish()
        r.bms.applyGate = CompletableDeferred()
        r.component.onSave(); runCurrent(); r.component.onSave(); runCurrent()
        r.bms.applyGate!!.complete(Unit); runCurrent()
        assertEquals(1, r.bms.applied.size)
    }

    @Test fun evidenceFlippingToReportsMidRunBlocksTheWizardAndSaveIsImpossible() = runTest {
        val r = rig(); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); r.send(66f, false); runCurrent(); r.send(68.4f, true); runCurrent()
        r.component.onFinish()
        assertEquals(PwmWizardStep.RESULT, r.component.state.value.wizard.step)
        r.bms.evidence.value = PwmEvidence.REPORTS; runCurrent()
        val w = r.component.state.value.wizard
        assertEquals(PwmWizardStep.BLOCKED, w.step)
        assertEquals(PwmWizardBlock.REPORTS, w.blockReason)
        assertNull(w.result); assertTrue(w.onsets.isEmpty())
        r.component.onSave(); runCurrent()
        assertTrue(r.bms.applied.isEmpty(), "Save is impossible once blocked")
        r.component.onStart(); r.component.onRestart()
        assertEquals(PwmWizardStep.BLOCKED, r.component.state.value.wizard.step, "terminal")
    }

    @Test fun aRestoredConfigOpenedOnAWheelThatReportsOrIsNotProvenIsBlocked() = runTest {
        for ((ev, reason) in listOf(PwmEvidence.REPORTS to PwmWizardBlock.REPORTS, PwmEvidence.UNKNOWN to PwmWizardBlock.NOT_PROVEN)) {
            val r = rig(evidence = ev); connect(r); r.send(0f, false); runCurrent()
            assertEquals(PwmWizardStep.BLOCKED, r.component.state.value.wizard.step, "$ev")
            assertEquals(reason, r.component.state.value.wizard.blockReason)
            r.component.onStart()
            assertEquals(PwmWizardStep.BLOCKED, r.component.state.value.wizard.step)
        }
    }

    @Test fun startDoesNothingBeforeTheVehicleHasLoaded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = rig(vehicles = Vehicles(t4, gate)); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart()
        assertEquals(PwmWizardStep.INTRO, r.component.state.value.wizard.step, "no run can start against the default threshold")
        assertEquals(PwmWizardNotice.LOADING, r.component.state.value.wizard.notice)
        gate.complete(Unit); runCurrent()
        r.component.onStart()
        assertEquals(PwmWizardStep.THRESHOLD, r.component.state.value.wizard.step)
    }

    @Test fun aNinetyPercentVehicleIsFittedAgainstNinetyNotTheDefault() = runTest {
        val r = rig(vehicle = t4.copy(pwmAlarmThresholdPercent = 90)); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); r.send(66f, false); runCurrent(); r.send(68.4f, true); runCurrent()
        r.component.onFinish()
        val k = assertNotNull(r.component.state.value.wizard.result).calibration.k
        assertEquals(0.9f * 96.8f / 66f, k, 1e-4f, "the onset is the sample before the edge: 66 km/h")
        assertTrue(kotlin.math.abs(k - 0.8f * 96.8f / 66f) > 0.05f, "not the 80 % fit")
    }

    @Test fun aVehicleThatCannotBeReadBlocksInsteadOfRunningOnTheDefault() = runTest {
        val r = rig(vehicles = Vehicles(t4, missing = true)); connect(r); r.send(0f, false); runCurrent()
        assertEquals(PwmWizardStep.BLOCKED, r.component.state.value.wizard.step)
        assertEquals(PwmWizardBlock.LOAD_FAILED, r.component.state.value.wizard.blockReason)
    }

    @Test fun aSaveTheRepositoryRefusesKeepsTheResultAndReportsFailure() = runTest {
        val r = rig(); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); r.send(66f, false); runCurrent(); r.send(68.4f, true); runCurrent()
        r.component.onFinish()
        r.bms.refuse = true
        r.component.onSave(); runCurrent()
        assertTrue(r.component.state.value.saveFailed)
        assertEquals(0, r.backs)
    }

    @Test fun theDutyAndSpeedAlarmsAreMutedFromStartUntilTheResultAndCancelAndBlockedAndDestroyRelease() = runTest {
        val mute = ru.sodovaya.volty.domain.alert.CalibrationMute()
        val r = rig(mute = mute); connect(r); r.send(0f, false); runCurrent()
        assertFalse(mute.isActive(), "INTRO: the rider is not spinning anything yet")
        r.component.onStart(); runCurrent()
        assertTrue(mute.isActive(), "a run started")
        r.send(66f, false); runCurrent(); r.send(68.4f, true); runCurrent()
        assertTrue(mute.isActive())
        r.component.onFinish(); runCurrent()
        assertEquals(PwmWizardStep.RESULT, r.component.state.value.wizard.step)
        assertFalse(mute.isActive(), "RESULT releases")
        r.component.onCancel(); runCurrent()
        assertFalse(mute.isActive())
    }

    @Test fun aBlockedWizardReleasesTheMute() = runTest {
        val mute = ru.sodovaya.volty.domain.alert.CalibrationMute()
        val r = rig(mute = mute); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); runCurrent()
        assertTrue(mute.isActive())
        r.bms.evidence.value = PwmEvidence.REPORTS; runCurrent()
        assertEquals(PwmWizardStep.BLOCKED, r.component.state.value.wizard.step)
        assertFalse(mute.isActive())
    }

    @Test fun leavingTheScreenMidRunReleasesTheMute() = runTest {
        val mute = ru.sodovaya.volty.domain.alert.CalibrationMute()
        val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED)
        val r = rig(mute = mute, lifecycle = lifecycle); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); runCurrent()
        assertTrue(mute.isActive())
        lifecycle.destroy()
        assertFalse(mute.isActive(), "back or process teardown mid-run must not leave the alarms muted")
    }

    @Test fun liveSamplesDoNotRenewTheMuteSoAForgottenRunTimesOut() = runTest {
        var now = 0L
        val mute = ru.sodovaya.volty.domain.alert.CalibrationMute { now }
        val r = rig(mute = mute); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); runCurrent()
        assertTrue(mute.isActive())
        // The rider put the wheel down and rode off: speeds keep arriving, nothing in the wizard progresses.
        repeat(5) { i ->
            now += ru.sodovaya.volty.domain.alert.CalibrationMute.TIME_BOX_MS / 4
            r.send(20f + i, false); runCurrent()
        }
        assertEquals(PwmWizardStep.THRESHOLD, r.component.state.value.wizard.step)
        assertFalse(mute.isActive(), "telemetry alone must not keep the alarms muted past the time box")
    }

    @Test fun anOnsetCapturedRenewsTheMute() = runTest {
        var now = 0L
        val mute = ru.sodovaya.volty.domain.alert.CalibrationMute { now }
        val r = rig(mute = mute); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); runCurrent()
        now += ru.sodovaya.volty.domain.alert.CalibrationMute.TIME_BOX_MS - 1000
        r.send(66f, false); runCurrent(); r.send(68.4f, true); runCurrent()
        assertEquals(1, r.component.state.value.wizard.onsets.size)
        now += ru.sodovaya.volty.domain.alert.CalibrationMute.TIME_BOX_MS - 1000
        assertTrue(mute.isActive(), "progress renewed the box")
    }

    @Test fun theWizardLeavingTheScreenReleasesTheMuteAndReturningRenewsIt() = runTest {
        val mute = ru.sodovaya.volty.domain.alert.CalibrationMute()
        val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED)
        val r = rig(mute = mute, lifecycle = lifecycle); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); runCurrent()
        assertTrue(mute.isActive())
        lifecycle.stop()
        assertFalse(mute.isActive(), "backgrounded or covered by another screen: not muting")
        lifecycle.start()
        assertTrue(mute.isActive(), "back on screen in a spinning step")
    }

    @Test fun cancelMidRunReleasesTheMuteItself() = runTest {
        val mute = ru.sodovaya.volty.domain.alert.CalibrationMute()
        val r = rig(mute = mute); connect(r); r.send(0f, false); runCurrent()
        r.component.onStart(); runCurrent()
        assertTrue(mute.isActive())
        r.component.onCancel()
        assertFalse(mute.isActive(), "released before navigating, not left to doOnDestroy")
    }
}
