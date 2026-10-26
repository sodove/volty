package ru.sodovaya.volty.presentation.vehicle.pwm

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
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
        override suspend fun applyPwmCalibration(vehicleId: String, calibration: PwmCalibration) {
            applyGate?.await()
            if (failApply) throw IllegalStateException("disk full")
            applied += vehicleId to calibration
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

    private class Vehicles(private val v: Vehicle) : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = MutableStateFlow(listOf(v))
        override suspend fun get(id: String): Vehicle? = v.takeIf { it.id == id }
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

    private fun TestScope.rig(): Rig {
        val bms = FakeBms()
        lateinit var rig: Rig
        val component = DefaultPwmCalibrationComponent(
            DefaultComponentContext(LifecycleRegistry(Lifecycle.State.RESUMED)), backgroundScope, "t4",
            Vehicles(t4), bms, onBackRequested = { rig.backs++ }, ioContext = StandardTestDispatcher(testScheduler),
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
        r.send(60f, false); runCurrent(); r.send(68.4f, true); runCurrent()
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
        r.component.onStart(); r.send(60f, false); runCurrent(); r.send(68.4f, true); runCurrent()
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
        r.component.onStart(); r.send(60f, false); runCurrent(); r.send(68.4f, true); runCurrent()
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
        r.component.onStart(); r.send(60f, false); runCurrent(); r.send(68.4f, true); runCurrent()
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
        r.component.onStart(); r.send(60f, false); runCurrent(); r.send(68.4f, true); runCurrent()
        r.component.onFinish()
        r.bms.applyGate = CompletableDeferred()
        r.component.onSave(); runCurrent(); r.component.onSave(); runCurrent()
        r.bms.applyGate!!.complete(Unit); runCurrent()
        assertEquals(1, r.bms.applied.size)
    }
}
