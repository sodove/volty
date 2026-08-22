package ru.sodovaya.volty.domain.usecase

import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.alert.AlertLevel
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.model.AlertConfig
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.stats.MovingAvg
import ru.sodovaya.volty.domain.stats.PackAggregator
import ru.sodovaya.volty.notification.LiveSummary
import ru.sodovaya.volty.notification.Notifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class AlertEngineTest {

    private class TestNotifier : Notifier {
        val live = mutableListOf<LiveSummary>()
        val alerts = mutableListOf<Triple<String, String, AlertSeverity>>()
        override fun showLive(summary: LiveSummary) { live += summary }
        override fun cancelLive() {}
        override fun showAlert(title: String, text: String, severity: AlertSeverity, alertId: Int) {
            alerts += Triple(title, text, severity)
        }
    }

    private class StubBmsRepository : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle) = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType) = Result.success(Unit)
        override suspend fun connectDemo(profile: DemoProfile) = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun disconnectLink(address: String) {}
        override fun samples(window: Duration): Flow<List<BmsData>> = emptyFlow()
        override fun motionSamples(window: Duration): Flow<List<ControllerData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> =
            flowOf(MovingAvg(0f, 0f, window))
        override suspend fun onAppResumed() {}
    }

    private fun vehicleWith(
        alertConfig: AlertConfig = AlertConfig(),
        chemistry: Chemistry = Chemistry.LI_ION_NMC
    ) = singlePackVehicle(
        id = "v1",
        name = "Test",
        iconKey = "generic",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA",
        chemistry = chemistry,
        alertConfig = alertConfig,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    private fun bmsData(
        cells: List<Float> = listOf(3.7f, 3.7f),
        temps: List<Float> = listOf(25f),
        soc: Float = 80f,
        current: Float = -2f,
        ts: Instant = Instant.fromEpochSeconds(0L)
    ) = BmsData(
        voltage = cells.sum(),
        current = current,
        power = current * cells.sum(),
        soc = soc,
        cellVoltages = cells,
        temperatures = temps,
        isConnected = true,
        timestamp = ts
    )

    private fun aggregateSoc(topology: PackTopology, vararg samples: BmsData): BmsData =
        PackAggregator.aggregate(
            samples.mapIndexed { index, sample ->
                PackState(
                    pack = Pack(
                        index = index,
                        label = "P$index",
                        bmsType = BmsType.JK_BMS,
                        bmsAddress = "AA:0$index"
                    ),
                    data = sample,
                    isOnline = true
                )
            },
            topology
        )

    private fun fakeClockProgressing(): () -> Instant {
        var nowEpoch = 1_000_000L
        return {
            val r = Instant.fromEpochSeconds(nowEpoch)
            nowEpoch += 10  // 10-second jumps so debounce never blocks subsequent fires
            r
        }
    }

    @Test
    fun `cell high triggers critical alert`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        // Use close-together cell values so CELL_DELTA (200 mV default) doesn't fire too.
        engine.evaluateForTest(bmsData(cells = listOf(4.21f, 4.25f)), v)
        assertEquals(1, notifier.alerts.size)
        val (title, _, severity) = notifier.alerts.first()
        assertEquals("Cell voltage high", title)
        assertEquals(AlertSeverity.CRITICAL, severity)
    }

    @Test
    fun `cell high does not fire twice without recovery (hysteresis)`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        engine.evaluateForTest(bmsData(cells = listOf(4.21f, 4.25f)), v)
        engine.evaluateForTest(bmsData(cells = listOf(4.21f, 4.26f)), v)
        engine.evaluateForTest(bmsData(cells = listOf(4.21f, 4.27f)), v)
        assertEquals(1, notifier.alerts.size)
    }

    @Test
    fun `cell high re-arms after recovery and fires again`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        engine.evaluateForTest(bmsData(cells = listOf(4.21f, 4.25f)), v) // fires
        engine.evaluateForTest(bmsData(cells = listOf(4.05f, 4.10f)), v) // recovered (max < 4.20 - 0.05)
        engine.evaluateForTest(bmsData(cells = listOf(4.21f, 4.25f)), v) // fires again
        assertEquals(2, notifier.alerts.size)
    }

    @Test
    fun `debounce blocks rapid re-fire within 3 seconds`() {
        val notifier = TestNotifier()
        val frozenClock: () -> Instant = { Instant.fromEpochSeconds(1_000_000L) }
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = frozenClock)
        val v = vehicleWith()
        engine.evaluateForTest(bmsData(cells = listOf(4.21f, 4.25f)), v) // fires
        engine.evaluateForTest(bmsData(cells = listOf(4.05f, 4.10f)), v) // recover so armed=true
        engine.evaluateForTest(bmsData(cells = listOf(4.21f, 4.25f)), v) // armed but within debounce -> blocked
        assertEquals(1, notifier.alerts.size)
    }

    @Test
    fun `temperature high triggers critical`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        engine.evaluateForTest(bmsData(temps = listOf(65f)), v)
        assertEquals(1, notifier.alerts.size)
        assertEquals(AlertSeverity.CRITICAL, notifier.alerts.first().third)
    }

    @Test
    fun `temperature warning fires in band below critical`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        // 54°C is above warn (50) but below high (60) -> a single WARNING.
        engine.evaluateForTest(bmsData(temps = listOf(54f)), v)
        assertEquals(1, notifier.alerts.size)
        val (title, _, severity) = notifier.alerts.first()
        assertEquals("Temperature warning", title)
        assertEquals(AlertSeverity.WARNING, severity)
    }

    @Test
    fun `temperature uses hottest sensor not first`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        // First sensor is cool, a later one is hot -> warn must still fire on max.
        engine.evaluateForTest(bmsData(temps = listOf(25f, 30f, 54f)), v)
        assertEquals(1, notifier.alerts.size)
        assertEquals(AlertSeverity.WARNING, notifier.alerts.first().third)
    }

    @Test
    fun `temperature escalates warn then critical as it rises`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        engine.evaluateForTest(bmsData(temps = listOf(54f)), v) // WARNING
        engine.evaluateForTest(bmsData(temps = listOf(65f)), v) // CRITICAL (warn no longer in band)
        assertEquals(2, notifier.alerts.size)
        assertEquals(AlertSeverity.WARNING, notifier.alerts[0].third)
        assertEquals(AlertSeverity.CRITICAL, notifier.alerts[1].third)
    }

    @Test
    fun `temperature high at 65 does not also fire warning`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        // Jumping straight into the critical range must not emit a warn too.
        engine.evaluateForTest(bmsData(temps = listOf(65f)), v)
        assertEquals(1, notifier.alerts.size)
        assertEquals(AlertSeverity.CRITICAL, notifier.alerts.first().third)
    }

    @Test
    fun `soc low triggers warning`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        engine.evaluateForTest(bmsData(soc = 10f), v)
        assertEquals(1, notifier.alerts.size)
        assertEquals(AlertSeverity.WARNING, notifier.alerts.first().third)
    }

    @Test
    fun `unknown soc fires no soc alerts even at zero percent`() {
        // A dumb Begode with no cell count: soc = 0 means "unknown", not
        // "empty". Neither SOC_LOW nor SOC_CUTOFF may fire on it.
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith(alertConfig = AlertConfig(socCutoffPercent = 5))
        engine.evaluateForTest(bmsData(soc = 0f).copy(socKnown = false), v)
        assertEquals(0, notifier.alerts.size)
    }

    @Test
    fun `an all-unknown aggregate raises no low-charge alarm`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith(alertConfig = AlertConfig(socCutoffPercent = 5))
        val aggregate = aggregateSoc(
            PackTopology.PARALLEL,
            bmsData(soc = 31f).copy(socKnown = false),
            bmsData(soc = 67f).copy(socKnown = false)
        )
        assertFalse(aggregate.socKnown)

        engine.evaluateForTest(aggregate, v)

        assertEquals(0, notifier.alerts.size)
    }

    @Test
    fun `an eighty-percent known aggregate raises no low-charge alarm`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith(alertConfig = AlertConfig(socLowPercent = 15, socCutoffPercent = 5))
        val aggregate = aggregateSoc(
            PackTopology.PARALLEL,
            bmsData(soc = 80f).copy(charge = 16f, capacity = 20f),
            bmsData(soc = 37f).copy(charge = 7.4f, capacity = 20f, socKnown = false)
        )
        assertEquals(80f, aggregate.soc, absoluteTolerance = 0.001f)
        assertTrue(aggregate.socKnown)

        engine.evaluateForTest(aggregate, v)

        assertEquals(0, notifier.alerts.size)
    }

    @Test
    fun `unknown soc does not silence cell voltage alerts on the same sample`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith(alertConfig = AlertConfig(socCutoffPercent = 5))
        // Close-together cells so CELL_DELTA does not fire too.
        engine.evaluateForTest(bmsData(cells = listOf(4.21f, 4.25f), soc = 0f).copy(socKnown = false), v)
        assertEquals(1, notifier.alerts.size)
        assertEquals("Cell voltage high", notifier.alerts.first().first)
    }

    @Test
    fun `unknown soc does not silence temperature alerts on the same sample`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith(alertConfig = AlertConfig(socCutoffPercent = 5))
        engine.evaluateForTest(bmsData(temps = listOf(65f), soc = 0f).copy(socKnown = false), v)
        assertEquals(1, notifier.alerts.size)
        assertEquals("Temperature high", notifier.alerts.first().first)
    }

    @Test
    fun `a genuine known zero percent still fires both soc alerts`() {
        // The case the socKnown gate must NOT break: a coulomb-counting BMS
        // reporting a truly flat pack. socKnown defaults to true on its samples.
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith(alertConfig = AlertConfig(socCutoffPercent = 5))
        engine.evaluateForTest(bmsData(soc = 0f), v)
        assertEquals(2, notifier.alerts.size)
        assertEquals(
            listOf("Battery low", "Discharge cutoff"),
            notifier.alerts.map { it.first }
        )
    }

    @Test
    fun `charge complete fires when SOC 100 and current near zero`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        engine.evaluateForTest(bmsData(soc = 100f, current = 0.05f), v)
        assertEquals(1, notifier.alerts.size)
        assertEquals("Charge complete", notifier.alerts.first().first)
        assertEquals(AlertSeverity.INFO, notifier.alerts.first().third)
    }

    @Test
    fun `charge complete respects the battery notification setting`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith(alertConfig = AlertConfig(chargeCompleteNotify = false))

        engine.evaluateForTest(bmsData(soc = 100f, current = 0.05f), v)

        assertEquals(0, notifier.alerts.size)
    }

    @Test
    fun `each battery alert can be disabled independently`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith(
            alertConfig = AlertConfig(
                cellDeltaMv = 100,
                cellDeltaEnabled = false,
                temperatureHighC = 50f,
                temperatureHighEnabled = false,
                socLowPercent = 15,
                socLowEnabled = false,
                chargeCompleteNotify = false,
            )
        )

        engine.evaluateForTest(
            bmsData(
                cells = listOf(3.7f, 4.0f),
                temps = listOf(65f),
                soc = 5f,
            ),
            v,
        )

        assertEquals(0, notifier.alerts.size)
    }

    @Test
    fun `battery conditions stay quiet when their measurements are unknown`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith(
            alertConfig = AlertConfig(
                cellHighV = 4.1f,
                cellLowV = 3.1f,
                cellDeltaMv = 100,
                temperatureWarnC = 40f,
                temperatureHighC = 50f,
                socLowPercent = 20,
                socCutoffPercent = 5,
                chargeCompleteNotify = false
            )
        )
        val unknown = BmsData(
            voltage = 84f,
            current = 42f,
            power = 4200f,
            hasPower = false,
            soc = 0f,
            socKnown = false,
            cellVoltages = emptyList(),
            temperatures = emptyList(),
            isConnected = true
        )

        engine.evaluateForTest(unknown, v)

        assertEquals(0, notifier.alerts.size)
    }

    @Test
    fun `charge complete requires a measured current rather than a zero placeholder`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        // A voltage-estimated pack can look full while its missing current field
        // is still the default zero. The non-zero raw value makes this fixture
        // deliberately incoherent and catches consumers that read the number
        // without its evidence flag.
        engine.evaluateForTest(
            bmsData(soc = 100f, current = 42f).copy(hasCurrent = false),
            v
        )

        assertEquals(0, notifier.alerts.size)
    }

    @Test
    fun `disconnected data produces no alerts`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        engine.evaluateForTest(BmsData(cellVoltages = listOf(4.5f), isConnected = false), v)
        assertEquals(0, notifier.alerts.size)
    }

    @Test
    fun `chemistry-aware threshold — LiFePO4 fires above 3 point 65V instead of 4 point 2V`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith(chemistry = Chemistry.LIFEPO4)
        // 3.70 V is fine for Li-ion (< 4.20) but HIGH for LiFePO4 (> 3.65)
        engine.evaluateForTest(bmsData(cells = listOf(3.7f, 3.7f)), v)
        assertEquals(1, notifier.alerts.size)
    }

    // ======================================================================
    // Motion one-shots (Task 6)
    // ======================================================================

    /**
     * A vehicle with one controller, so motion telemetry exists at all. [rules]
     * null means "never configured", which resolves to [ru.sodovaya.volty.domain.alert.AlarmDefaults]
     * (duty 80/90, ESC 90 °C, motor 110 °C, speed off).
     */
    private fun motionVehicle(
        rules: List<AlertRule>? = null,
        controllerType: ControllerType = ControllerType.VESC
    ) = Vehicle(
        id = "v1",
        name = "Test",
        iconKey = "generic",
        packs = emptyList(),
        controllers = listOf(Controller(index = 0, label = "ESC", controllerType = controllerType, address = "AA")),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L),
        motionAlerts = rules
    )

    /**
     * A live motion sample. Both temperatures default to a cold 20 °C — well
     * under every default threshold — so a test that raises one reading is the
     * only thing that can fire, and `escTempC` stays above the "no sensor"
     * sentinel so [ControllerData.hasEscTemp] is true unless a test says
     * otherwise.
     */
    private fun motion(
        motorTempC: Float = 20f,
        escTempC: Float = 20f,
        hasMotorTemp: Boolean = true,
        faults: List<String> = emptyList(),
        dutyPercent: Float = 0f,
        speedKmh: Float = 0f,
        isConnected: Boolean = true
    ) = ControllerData(
        speedKmh = speedKmh,
        speedSource = SpeedSource.REPORTED,
        dutyPercent = dutyPercent,
        escTempC = escTempC,
        motorTempC = motorTempC,
        hasMotorTemp = hasMotorTemp,
        faults = faults,
        isConnected = isConnected
    )

    /** One rule for [kind], thresholds ascending, all enabled. */
    private fun rule(kind: MotionAlertKind, vararg thresholds: Float) =
        AlertRule(kind, thresholds.map { AlertLevel(it) })

    /**
     * A clock that advances [stepSeconds] per read. `evaluateMotionForTest`
     * reads it exactly once per call, so sample *i* happens at *i × step*, and a
     * step of 1 s puts the 3 s debounce three samples wide.
     */
    private fun fakeClockStepping(stepSeconds: Long): () -> Instant {
        var nowEpoch = 1_000_000L
        return {
            val r = Instant.fromEpochSeconds(nowEpoch)
            nowEpoch += stepSeconds
            r
        }
    }

    // ------------------------------------------------------------- fault

    @Test
    fun `a controller fault fires a critical one-shot naming the fault`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        engine.evaluateMotionForTest(motion(faults = listOf("OVER_VOLTAGE")), motionVehicle())
        assertEquals(1, notifier.alerts.size)
        val (title, text, severity) = notifier.alerts.first()
        assertEquals("Controller fault", title)
        assertEquals("OVER_VOLTAGE on Test", text, "the fault is named, not merely counted")
        assertEquals(AlertSeverity.CRITICAL, severity, "a fault has no levels and is never less than critical")
    }

    @Test
    fun `every fault the controllers report is named, not just the first`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        engine.evaluateMotionForTest(
            motion(faults = listOf("ESC0: OVER_TEMP_FET", "ESC1: DRV")),
            motionVehicle()
        )
        assertEquals("ESC0: OVER_TEMP_FET, ESC1: DRV on Test", notifier.alerts.single().second)
    }

    @Test
    fun `a persisting fault does not re-notify on every sample`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = motionVehicle()
        repeat(3) { engine.evaluateMotionForTest(motion(faults = listOf("DRV")), v) }
        assertEquals(1, notifier.alerts.size, "one notification per arming episode, not one per sample")
    }

    @Test
    fun `a fault re-arms once it clears and notifies again if it returns`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = motionVehicle()
        engine.evaluateMotionForTest(motion(faults = listOf("DRV")), v)
        engine.evaluateMotionForTest(motion(faults = emptyList()), v)
        engine.evaluateMotionForTest(motion(faults = listOf("DRV")), v)
        assertEquals(2, notifier.alerts.size)
    }

    // ------------------------------------------------------- levelled temps

    @Test
    fun `motor temperature past the rider's only step fires a critical one-shot`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        // Defaults: motor 110 °C, one step. 115 is past it; the ESC's 20 °C is not.
        engine.evaluateMotionForTest(motion(motorTempC = 115f), motionVehicle())
        assertEquals(1, notifier.alerts.size)
        val (title, text, severity) = notifier.alerts.first()
        assertEquals("Motor temperature high", title)
        assertEquals("Motor 115°C on Test", text)
        assertEquals(
            AlertSeverity.CRITICAL, severity,
            "the rider's most urgent step is critical even when it is their only step"
        )
    }

    @Test
    fun `esc temperature thresholds against the ESC reading and names it`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        // ESC 95 °C is past its 90 °C default; the motor's 20 °C is nowhere near 110.
        engine.evaluateMotionForTest(motion(escTempC = 95f), motionVehicle())
        assertEquals(1, notifier.alerts.size)
        assertEquals("Controller temperature high", notifier.alerts.first().first)
        assertEquals("ESC 95°C on Test", notifier.alerts.first().second)
    }

    @Test
    fun `a climb through three levels posts one notification, not one per level`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = motionVehicle(rules = listOf(rule(MotionAlertKind.MOTOR_TEMP, 100f, 110f, 120f)))
        engine.evaluateMotionForTest(motion(motorTempC = 105f), v) // level 1
        engine.evaluateMotionForTest(motion(motorTempC = 115f), v) // level 2
        engine.evaluateMotionForTest(motion(motorTempC = 125f), v) // level 3
        assertEquals(
            1, notifier.alerts.size,
            "three rider-defined levels are one alert, not three — F §10's alarm fatigue"
        )
    }

    @Test
    fun `a levelled alert re-arms only after the reading falls below every level`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = motionVehicle(rules = listOf(rule(MotionAlertKind.MOTOR_TEMP, 100f, 120f)))
        engine.evaluateMotionForTest(motion(motorTempC = 125f), v) // fires at level 2
        engine.evaluateMotionForTest(motion(motorTempC = 105f), v) // down to level 1 — still engaged
        engine.evaluateMotionForTest(motion(motorTempC = 125f), v) // back up — no new episode
        assertEquals(1, notifier.alerts.size, "dropping a level is not a recovery")
        engine.evaluateMotionForTest(motion(motorTempC = 90f), v)  // below every level — recovered
        engine.evaluateMotionForTest(motion(motorTempC = 105f), v)
        assertEquals(2, notifier.alerts.size, "and falling below every level does re-arm it")
    }

    // There is no separate "step 1 of 3 notifies as a WARNING" test: the first
    // assertion of the test below makes exactly that claim, on the same 105 °C
    // reading against the same three-step rule, and is killed by the same
    // mutation (severity always CRITICAL). Two tests, one claim.

    @Test
    fun `the severity is the highest level the episode reached, not the level at the moment it fires`() {
        val notifier = TestNotifier()
        // 1 s per sample, so the 3 s debounce spans exactly three samples.
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockStepping(1))
        val v = motionVehicle(rules = listOf(rule(MotionAlertKind.MOTOR_TEMP, 100f, 110f, 120f)))
        engine.evaluateMotionForTest(motion(motorTempC = 105f), v) // t=0 fires at level 1
        assertEquals(AlertSeverity.WARNING, notifier.alerts.single().third, "the first episode was only step 1")
        engine.evaluateMotionForTest(motion(motorTempC = 90f), v)  // t=1 recovers, arming the next episode
        engine.evaluateMotionForTest(motion(motorTempC = 125f), v) // t=2 level 3, but 2 s < the 3 s debounce
        assertEquals(1, notifier.alerts.size, "still held back by the debounce")
        engine.evaluateMotionForTest(motion(motorTempC = 105f), v) // t=3 back to level 1, debounce expired
        assertEquals(2, notifier.alerts.size)
        assertEquals(
            AlertSeverity.CRITICAL, notifier.alerts[1].third,
            "the episode touched step 3 while the debounce held the fire back, and the rider is owed that"
        )
    }

    @Test
    fun `a new episode is graded on its own peak, not the previous episode's`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = motionVehicle(rules = listOf(rule(MotionAlertKind.MOTOR_TEMP, 100f, 110f, 120f)))
        engine.evaluateMotionForTest(motion(motorTempC = 125f), v) // episode 1 reaches step 3
        assertEquals(AlertSeverity.CRITICAL, notifier.alerts.single().third)
        engine.evaluateMotionForTest(motion(motorTempC = 90f), v)  // recovers, ending the episode
        engine.evaluateMotionForTest(motion(motorTempC = 105f), v) // episode 2 only reaches step 1
        assertEquals(
            AlertSeverity.WARNING, notifier.alerts[1].third,
            "the peak is per episode — yesterday's step 3 must not make today's step 1 critical"
        )
    }

    @Test
    fun `a muted level is skipped and does not fire on its own threshold`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = motionVehicle(
            rules = listOf(
                AlertRule(
                    MotionAlertKind.MOTOR_TEMP,
                    listOf(AlertLevel(100f, enabled = false), AlertLevel(120f))
                )
            )
        )
        engine.evaluateMotionForTest(motion(motorTempC = 110f), v)
        assertEquals(0, notifier.alerts.size, "the rider muted that step; passing it is not an event")
    }

    @Test
    fun `a muted top step leaves the highest step that can still sound as the critical one`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = motionVehicle(
            rules = listOf(
                AlertRule(
                    MotionAlertKind.MOTOR_TEMP,
                    listOf(AlertLevel(100f), AlertLevel(120f, enabled = false))
                )
            )
        )
        engine.evaluateMotionForTest(motion(motorTempC = 105f), v)
        assertEquals(
            AlertSeverity.CRITICAL, notifier.alerts.single().third,
            "with the step above muted, step 1 IS the rider's most urgent step"
        )
    }

    // ------------------------------------------------------------ gating

    @Test
    fun `no motor thermistor means no motor temperature alert, however hot the field reads`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        // 200 °C in a field the hardware does not measure is not evidence of
        // anything (F §10: an unavailable alert must be impossible to arm).
        engine.evaluateMotionForTest(motion(motorTempC = 200f, hasMotorTemp = false), motionVehicle())
        assertEquals(0, notifier.alerts.size)
    }

    // There is deliberately NO `no ESC sensor means no ESC temperature alert`
    // test. `ControllerData.hasEscTemp` is derived from `escTempC` itself
    // (`> -50 °C`), so "no sensor" and "a reading past the 90 °C step" cannot
    // both hold in one sample: the only fixture the gate can be tested with is
    // one whose reading is far below every threshold, and such a test passes
    // with the whole availability gate deleted. It would assert nothing. The
    // ESC gate is covered where it is observable, in `MotionAlertAvailabilityTest`.

    /**
     * **A contract statement, not a proven assertion.** Nothing that exists can
     * be mutated to fail it: a rule with no levels reaches no threshold, so it
     * is silent whether or not `armedRules` drops it — the `!rule.isOff` filter
     * is unobservable from this engine. It is kept because the alternative
     * implementation it forbids is real and has been written before: resolving
     * an empty level list back to [ru.sodovaya.volty.domain.alert.AlarmDefaults],
     * which would override the one and only way a rider can say "off".
     */
    @Test
    fun `a rule the rider switched off raises nothing`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = motionVehicle(rules = listOf(AlertRule(MotionAlertKind.MOTOR_TEMP, emptyList())))
        engine.evaluateMotionForTest(motion(motorTempC = 200f), v)
        assertEquals(0, notifier.alerts.size)
    }

    @Test
    fun `duty and speed raise no notification however high they go`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        // Both armed and both far past their steps. They belong to the
        // continuous alarm (F §2); a notification per excursion would be dozens
        // per ride.
        val v = motionVehicle(
            rules = listOf(rule(MotionAlertKind.DUTY, 80f, 90f), rule(MotionAlertKind.SPEED, 30f))
        )
        engine.evaluateMotionForTest(motion(dutyPercent = 100f, speedKmh = 90f), v)
        assertEquals(0, notifier.alerts.size)
    }

    // ------------------------------------------------------- disconnection

    @Test
    fun `a disconnected sample fires nothing, whatever it still carries`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        // A STALE sample, not the zero-filled placeholder: a fault list and a
        // 200 °C motor would both fire if the guard were missing, so this can
        // actually detect the guard's absence.
        engine.evaluateMotionForTest(
            motion(motorTempC = 200f, faults = listOf("DRV"), isConnected = false),
            motionVehicle()
        )
        assertEquals(0, notifier.alerts.size)
    }

    @Test
    fun `a gap does not re-arm a fault that never cleared`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = motionVehicle()
        engine.evaluateMotionForTest(motion(faults = listOf("DRV")), v)
        assertEquals(1, notifier.alerts.size, "faulted, and notified once")
        // The link drops. `activeMotion` falls back to a zero-filled placeholder
        // whose empty fault list looks exactly like a recovery — and is not one.
        // CONTROLLER_FAULT has no availability gate to hide behind, so the
        // `isConnected` guard is the only thing standing here.
        engine.evaluateMotionForTest(ControllerData(), v)
        engine.evaluateMotionForTest(motion(faults = listOf("DRV")), v)
        assertEquals(
            1, notifier.alerts.size,
            "the fault never cleared; a dropout is not a recovery and must not re-notify"
        )
    }

    // The same claim for the *levelled* kinds has no test, deliberately: a
    // disconnected placeholder is stopped twice over — by the `isConnected`
    // guard, and by `availabilityFor`, which discards a disconnected sample as
    // evidence so every temperature kind resolves to Unknown and is dropped.
    // Neither guard alone can be mutated away to make such a test fail, so it
    // would assert only that two independent mechanisms are both present.
    // CONTROLLER_FAULT has only the first of them, which is why the test above
    // is written on the fault path.

    @Test
    fun `a gap does not consume an arming a real recovery had already earned`() {
        // The other direction of the same rule: a dropout must not leave a kind
        // permanently *silent* either. Muting everything for the duration of a
        // gap — a plausible reading of "ignore what happened while offline" —
        // would swallow the first real sample after it.
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = motionVehicle()
        engine.evaluateMotionForTest(motion(faults = listOf("DRV")), v)   // fires
        engine.evaluateMotionForTest(motion(faults = emptyList()), v)     // genuinely cleared — re-armed
        engine.evaluateMotionForTest(motion(faults = listOf("DRV"), isConnected = false), v) // gap
        engine.evaluateMotionForTest(motion(faults = listOf("DRV")), v)   // faulted again, on a real sample
        assertEquals(
            2, notifier.alerts.size,
            "a gap must leave the arming alone — muting through one would silence the next real episode"
        )
    }

    // -------------------------------------------------------- flow wiring

    /**
     * Two claims at once, and they need the same fixture:
     *
     *  1. motion samples reach the engine at all — there is a second collector;
     *  2. battery alerts are **not** re-evaluated on motion samples. `activeMotion`
     *     ticks far more often than a BMS frame, so folding it into the battery
     *     `combine` would let a motion sample release a battery alert the
     *     debounce was still holding — i.e. adding a controller to a vehicle
     *     would change when its cell alerts fire. The clock steps 1 s per
     *     evaluation, so the CELL_HIGH blocked at t=2 s becomes eligible at
     *     t≥3 s, which is exactly when the motion sample lands.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `motion samples drive the motion alerts without re-evaluating the battery ones`() = runTest {
        val repo = StubBmsRepository()
        val notifier = TestNotifier()
        val engine = AlertEngine(repo, notifier, clock = fakeClockStepping(1))
        // backgroundScope: the collectors never complete, and runTest cancels it
        // rather than waiting for them.
        engine.start(backgroundScope)

        repo.activeVehicle.value = motionVehicle()
        runCurrent()
        repo.activeData.value = bmsData(cells = listOf(4.21f, 4.25f))   // t=0 CELL_HIGH fires
        runCurrent()
        repo.activeData.value = bmsData(cells = listOf(4.05f, 4.10f))   // t=1 recovers, re-arms
        runCurrent()
        repo.activeData.value = bmsData(cells = listOf(4.21f, 4.26f))   // t=2 blocked by the 3 s debounce
        runCurrent()
        assertEquals(1, notifier.alerts.size, "one cell alert so far, the second held by the debounce")

        repo.activeMotion.value = motion(faults = listOf("DRV"))        // t=3
        runCurrent()

        assertTrue(
            notifier.alerts.any { it.first == "Controller fault" },
            "the motion stream is collected at all"
        )
        assertEquals(
            1, notifier.alerts.count { it.first == "Cell voltage high" },
            "and a motion sample does not re-evaluate the battery alerts"
        )
    }
}
