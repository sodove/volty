package ru.sodovaya.volty.presentation.graph

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DemoProfile
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.stats.MovingAvg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * **The Graph screen's "Used" figure, and specifically its SIGN.**
 *
 * `I` Task 8 moved the trapezoid out of this component into
 * [ru.sodovaya.volty.domain.stats.RideEnergy] so the Ride dashboard could
 * integrate the same quantity for a wheel that keeps no watt-hour counters.
 * That refactor had nothing at all pinning it: there was no test on this
 * component, and the arithmetic it moved is the one thing the field report says
 * was *already correct*.
 *
 * The negation is the part that must stay HERE and must not follow the
 * arithmetic into the shared object. `BmsData` is charge-positive
 * (`+ = charging`), so a discharging pack integrates NEGATIVE and is flipped so
 * the readout reads consumption-positive. The other caller's samples are
 * `ControllerData`, whose `powerW` carries VESC's opposite convention and is
 * therefore *not* flipped — see `RideEnergyTest`, which asserts the same
 * function's output for both.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class GraphComponentUsedTest {

    private class FakeBmsRepo : BmsRepository {
        override val activeVehicleData = MutableStateFlow(VehicleData())
        override val activeData = MutableStateFlow(BmsData())
        override val activeMotion = MutableStateFlow(ControllerData())
        override val activeVehicle = MutableStateFlow<Vehicle?>(null)
        override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override fun scanAll(): Flow<DiscoveredDevice> = emptyFlow()
        override suspend fun connect(vehicle: Vehicle): Result<Unit> = Result.success(Unit)
        override suspend fun connectGuest(address: String, type: BmsType): Result<Unit> = Result.success(Unit)
        override suspend fun connectDemo(profile: DemoProfile): Result<Unit> = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun disconnectLink(address: String) {}

        /** The retained window the component re-reads; hot, so a test can move it. */
        val window = MutableStateFlow<List<BmsData>>(emptyList())
        override fun samples(window: Duration): Flow<List<BmsData>> = this.window
        override fun motionSamples(window: Duration): Flow<List<ControllerData>> = flowOf(emptyList())
        override fun movingAverage(window: Duration): Flow<MovingAvg> = emptyFlow()
        override suspend fun onAppResumed() {}
    }

    private fun component(repo: FakeBmsRepo) = DefaultGraphComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        bmsRepository = repo,
        onBackRequested = {}
    )

    private fun at(seconds: Long) = Instant.fromEpochSeconds(seconds)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /**
     * A minute at 600 W of DISCHARGE — `power = -600f` in the battery
     * convention — reads as **+10 Wh used**.
     *
     * Drop the negation and this is -10 Wh, which is what the shared integrator
     * returns and what the Ride dashboard's caller keeps.
     */
    @Test
    fun a_discharging_minute_reads_as_positive_watt_hours_used() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.window.value = listOf(
            BmsData(power = -600f, timestamp = at(0)),
            BmsData(power = -600f, timestamp = at(60))
        )
        advanceUntilIdle()

        assertEquals(GraphMetric.POWER, c.state.value.metric, "precondition: POWER is the default")
        assertEquals(10f, requireNotNull(c.state.value.used), 0.001f)
    }

    /**
     * The other direction, so "always positive" is not a passing implementation:
     * a minute of CHARGING subtracts, making "Used" a net figure.
     */
    @Test
    fun a_charging_minute_subtracts_so_used_is_a_net_figure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.window.value = listOf(
            BmsData(power = 600f, timestamp = at(0)),
            BmsData(power = 600f, timestamp = at(60))
        )
        advanceUntilIdle()

        assertEquals(-10f, requireNotNull(c.state.value.used), 0.001f)
    }

    /**
     * The trapezoid, and that it is over the samples' own timestamps: a ramp
     * from 0 to -1200 W over an hour is 600 Wh, not 0 (left rectangle) and not
     * 1200 (right).
     */
    @Test
    fun the_window_is_integrated_trapezoidally_over_the_samples_own_timestamps() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.window.value = listOf(
            BmsData(power = 0f, timestamp = at(0)),
            BmsData(power = -1200f, timestamp = at(3600))
        )
        advanceUntilIdle()

        assertEquals(600f, requireNotNull(c.state.value.used), 0.001f)
    }

    /**
     * A window too short to span an interval reports 0, not a stale figure — and
     * the metrics that are not rates have no integral at all.
     */
    @Test
    fun a_window_with_no_interval_and_a_non_rate_metric_both_report_zero() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.window.value = listOf(BmsData(power = -600f, timestamp = at(0)))
        advanceUntilIdle()
        assertNull(c.state.value.used, "one sample spans no measurable interval")

        c.onMetricSelected(GraphMetric.SOC)
        repo.window.value = listOf(
            BmsData(soc = 80f, power = -600f, timestamp = at(0)),
            BmsData(soc = 70f, power = -600f, timestamp = at(3600))
        )
        advanceUntilIdle()
        assertNull(c.state.value.used, "a state of charge has no watt-hours to accumulate")
    }

    @Test
    fun unavailable_power_is_omitted_from_the_trace_and_every_derived_stat() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.window.value = listOf(
            // Deliberately incoherent: no producer emits 900 W while denying that
            // power, which makes this a contract test rather than a decoder test.
            BmsData(power = -900f, hasPower = false, timestamp = at(0)),
            BmsData(power = 0f, hasPower = true, timestamp = at(60)),
            BmsData(power = -600f, hasPower = true, timestamp = at(120))
        )
        advanceUntilIdle()

        assertEquals(listOf(0f, 600f), c.state.value.values, "unknown power must make no zero-valued trace point")
        assertEquals(300f, requireNotNull(c.state.value.avg), 0.001f)
        assertEquals(600f, requireNotNull(c.state.value.peak), 0.001f)
        assertEquals(5f, requireNotNull(c.state.value.used), 0.001f)
    }

    @Test
    fun unavailable_current_is_omitted_while_a_measured_zero_remains_a_graph_sample() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()
        c.onMetricSelected(GraphMetric.CURRENT)

        repo.window.value = listOf(
            BmsData(current = -99f, hasCurrent = false, timestamp = at(0)),
            BmsData(current = 0f, hasCurrent = true, timestamp = at(60)),
            BmsData(current = -6f, hasCurrent = true, timestamp = at(3660))
        )
        advanceUntilIdle()

        assertEquals(listOf(0f, 6f), c.state.value.values)
        assertEquals(3f, requireNotNull(c.state.value.avg), 0.001f)
        assertEquals(6f, requireNotNull(c.state.value.peak), 0.001f)
        assertEquals(3f, requireNotNull(c.state.value.used), 0.001f)
    }

    @Test
    fun all_unavailable_power_is_an_explicit_unknown_graph_state_not_a_zero_reading() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        repo.window.value = listOf(
            BmsData(power = -900f, hasPower = false, timestamp = at(0)),
            BmsData(power = -700f, hasPower = false, timestamp = at(60))
        )
        advanceUntilIdle()

        val unknown = c.state.value
        assertEquals(emptyList(), unknown.values)
        assertNull(unknown.nowValue)
        assertNull(unknown.avg)
        assertNull(unknown.peak)
        assertNull(unknown.min)
        assertNull(unknown.used)

        repo.window.value = listOf(
            BmsData(power = 0f, hasPower = true, timestamp = at(0)),
            BmsData(power = 0f, hasPower = true, timestamp = at(60))
        )
        advanceUntilIdle()

        val zero = c.state.value
        assertEquals(listOf(0f, 0f), zero.values)
        assertEquals(0f, zero.nowValue)
        assertEquals(0f, zero.avg)
        assertEquals(0f, zero.peak)
        assertEquals(0f, zero.min)
        assertEquals(0f, zero.used)
    }

    @Test
    fun empty_or_unknown_state_metrics_are_not_plotted_as_zero() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        advanceUntilIdle()

        c.onMetricSelected(GraphMetric.SOC)
        repo.window.value = listOf(
            BmsData(soc = 76f, socKnown = false, timestamp = at(0)),
            BmsData(soc = 0f, socKnown = false, timestamp = at(60))
        )
        advanceUntilIdle()
        assertEquals(emptyList(), c.state.value.values)
        assertNull(c.state.value.nowValue)

        c.onMetricSelected(GraphMetric.TEMPERATURE)
        repo.window.value = listOf(BmsData(temperatures = emptyList(), timestamp = at(0)))
        advanceUntilIdle()
        assertEquals(emptyList(), c.state.value.values)
        assertNull(c.state.value.nowValue)

        c.onMetricSelected(GraphMetric.VOLTAGE)
        repo.window.value = listOf(BmsData(voltage = 0f, timestamp = at(0)))
        advanceUntilIdle()
        assertEquals(emptyList(), c.state.value.values)
        assertNull(c.state.value.nowValue)
    }

    @Test
    fun disconnected_initial_state_has_no_power_or_current_sample() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeBmsRepo()
        val c = component(repo)
        assertEquals(emptyList(), c.state.value.values)
        assertNull(c.state.value.nowValue)

        c.onMetricSelected(GraphMetric.CURRENT)
        assertNull(c.state.value.nowValue)
    }
}
