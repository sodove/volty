package com.volty.app.domain.usecase

import com.volty.app.data.stats.MovingAvg
import com.volty.app.domain.model.AlertConfig
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.Chemistry
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.domain.repository.DiscoveredDevice
import com.volty.app.notification.LiveSummary
import com.volty.app.notification.Notifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
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
        override val activeData = MutableStateFlow(BmsData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle) = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType) = Result.success(Unit)
        override suspend fun disconnect() {}
        override fun samples(window: Duration): Flow<List<BmsData>> = emptyFlow()
        override fun movingAverage(window: Duration): StateFlow<MovingAvg> =
            MutableStateFlow(MovingAvg(0f, 0f, window)).asStateFlow()
    }

    private fun vehicleWith(
        alertConfig: AlertConfig = AlertConfig(),
        chemistry: Chemistry = Chemistry.LI_ION_NMC
    ) = Vehicle(
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
    fun `soc low triggers warning`() {
        val notifier = TestNotifier()
        val engine = AlertEngine(StubBmsRepository(), notifier, clock = fakeClockProgressing())
        val v = vehicleWith()
        engine.evaluateForTest(bmsData(soc = 10f), v)
        assertEquals(1, notifier.alerts.size)
        assertEquals(AlertSeverity.WARNING, notifier.alerts.first().third)
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
}
