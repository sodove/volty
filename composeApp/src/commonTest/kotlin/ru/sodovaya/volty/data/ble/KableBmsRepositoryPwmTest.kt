package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DutySource
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.pwm.PwmCalibrationLearner
import ru.sodovaya.volty.domain.pwm.AlarmOnset
import ru.sodovaya.volty.domain.pwm.PwmEvidence
import ru.sodovaya.volty.domain.pwm.PwmMemory
import ru.sodovaya.volty.domain.pwm.VehiclePwmStore
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The repository's PWM wiring (load / install / ordered saves / reset), end to end through the production
 * sample funnel, against a fake store that can fail and block. Asserts on what the store was ASKED to do.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryPwmTest {

    private class NoVehicles : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    /** Behaves like the real store for R3 (monotone evidence, calibration kept) and logs every operation in order. */
    private class FakeStore(
        seeded: Map<String, PwmMemory> = emptyMap(),
        val failLoad: Boolean = false,
        var failSaves: Int = 0,
        /** While set, every save suspends on it AFTER being recorded as started. */
        var saveGate: CompletableDeferred<Unit>? = null,
    ) : VehiclePwmStore {
        val rows = seeded.toMutableMap()
        val log = mutableListOf<String>()
        val saves = mutableListOf<PwmMemory>()
        override suspend fun load(vehicleId: String): PwmMemory {
            log += "load"
            if (failLoad) throw IllegalStateException("disk error")
            return rows[vehicleId] ?: PwmMemory.EMPTY
        }
        override suspend fun save(vehicleId: String, memory: PwmMemory) {
            log += "save-start"
            saveGate?.await()
            if (failSaves > 0) { failSaves--; log += "save-failed"; throw IllegalStateException("disk full") }
            val old = rows[vehicleId] ?: PwmMemory.EMPTY
            rows[vehicleId] = PwmMemory(memory.calibration ?: old.calibration, PwmEvidence.monotone(old.evidence, memory.evidence))
            saves += memory
            log += "save"
        }
        override suspend fun resetCalibration(vehicleId: String) {
            log += "resetCalibration"
            rows[vehicleId]?.let { rows[vehicleId] = it.copy(calibration = null) }
        }
        override suspend fun reset(vehicleId: String) { rows.remove(vehicleId) }
    }

    private fun repoTest(store: FakeStore, body: suspend TestScope.(KableBmsRepository) -> Unit) =
        bleRepositoryTest(vehicleRepository = NoVehicles(), pwmStore = store, body = body)

    private fun wheel(id: String = "v-pwm"): Vehicle = Vehicle(
        id = id, name = "T4", iconKey = "unicycle",
        packs = listOf(Pack(index = 0, label = "Battery", bmsType = BmsType.JK_BMS, bmsAddress = "AA:00")),
        controllers = listOf(Controller(index = 0, label = "ESC", controllerType = ControllerType.BEGODE, address = "AA:01")),
        topology = PackTopology.PARALLEL, chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0L),
    )

    private val proven = PwmMemory(null, PwmEvidence.NEVER_REPORTED)
    private val measured = PwmCalibrationLearner.fromOnsets(listOf(AlarmOnset(68.4f, 96.8f, null, 0L)), 80f)!!

    /** One wheel connection driven through the real funnel, a motion sample every 250 ms of an injected clock. */
    private inner class Ride(val repo: KableBmsRepository, val idle: () -> Unit, val vehicle: Vehicle = wheel()) {
        var nowMs = 50_000_000L
        init { repo.orchestratorClockForTest = { Instant.fromEpochMilliseconds(nowMs) } }
        fun connect() { repo.installLinksForTest(vehicle, vehicle.primaryAddress, vehicle.packs.first().bmsType) }
        fun send(speed: Float, alarm: Boolean? = null, duty: Float = 0f) {
            nowMs += 250
            val ch = assertNotNull(repo.sampleFunnelChannelForTest())
            val d = ControllerData(
                speedKmh = speed, speedSource = SpeedSource.REPORTED, dutyPercent = duty, hasDuty = true,
                wheelPwmAlarm = alarm, inputVoltageV = 96f, hasInputVoltage = true, isConnected = true,
                timestamp = Instant.fromEpochMilliseconds(nowMs),
            )
            assertTrue(ch.trySend(MotionSample(0, d, SampleSessionOwnership { true })).isSuccess)
            idle()
        }
        fun cruise(seconds: Int, speed: Float = 30f) = repeat(seconds * 4) { send(speed) }
        /** A rising edge of the alarm at [speed]: one calibration onset. */
        fun onset(speed: Float) { send(speed - 2f, alarm = false); send(speed, alarm = true) }
    }

    @Test
    fun `a failed load never saves anything, not on a change and not on disconnect`() {
        val store = FakeStore(mapOf("v-pwm" to PwmMemory(measured, PwmEvidence.REPORTS)), failLoad = true)
        repoTest(store) { repo ->
            val ride = Ride(repo, { advanceUntilIdle() }); ride.connect()
            ride.cruise(8); ride.onset(68f); ride.cruise(1)
            assertEquals(listOf("load"), store.log, "the load was attempted once, and nothing else was ever asked of the store")
            assertFalse(assertNotNull(repo.vehicleConnectionForTest()?.pwm).memoryReady)
            repo.disconnect(); advanceUntilIdle()
            assertEquals(listOf("load"), store.log)
        }
    }

    @Test
    fun `an install from a connection that no longer owns the pipeline is ignored`() {
        val store = FakeStore(mapOf("v-pwm" to PwmMemory(measured, PwmEvidence.REPORTS)))
        repoTest(store) { repo ->
            val ride = Ride(repo, { advanceUntilIdle() })
            ride.connect()
            val first = assertNotNull(repo.vehicleConnectionForTest())
            // Supersede it BEFORE its first sample can start (and finish) a load.
            ride.connect()
            val second = assertNotNull(repo.vehicleConnectionForTest())
            assertTrue(first !== second)
            // Drive the superseded estimator's hook directly: its first apply starts the load.
            first.submitMotion(0, ControllerData(speedKmh = 30f, speedSource = SpeedSource.REPORTED, isConnected = true))
            advanceUntilIdle()
            assertFalse(assertNotNull(first.pwm).memoryReady, "the loser must not adopt the memory")
            ride.send(30f)
            assertTrue(assertNotNull(second.pwm).memoryReady, "the owner does")
            assertEquals(PwmEvidence.REPORTS, second.pwm!!.evidence)
        }
    }

    @Test
    fun `a change is saved promptly, and what was seen moving at a constant zero is persisted as NEVER_REPORTED`() {
        val store = FakeStore()
        repoTest(store) { repo ->
            val ride = Ride(repo, { advanceUntilIdle() }); ride.connect()
            ride.cruise(4)
            assertTrue(store.saves.isEmpty(), "nothing earned yet")
            ride.cruise(3)
            assertEquals(PwmEvidence.NEVER_REPORTED, store.saves.single().evidence, "saved the moment it was proven, not at disconnect")
        }
    }

    @Test
    fun `a failed save is retried later and again on disconnect`() {
        val store = FakeStore(mapOf("v-pwm" to proven), failSaves = 1)
        repoTest(store) { repo ->
            val ride = Ride(repo, { advanceUntilIdle() }); ride.connect()
            ride.cruise(1)
            ride.onset(68f)
            assertEquals(listOf("load", "save-start", "save-failed"), store.log)
            ride.send(68f); ride.send(68f)
            assertEquals(3, store.log.size, "no hot retry on every sample")
            repeat(130) { ride.send(60f) } // > 30 s of the injected clock
            assertEquals(1, store.saves.size, "retried once the back-off passed")
            assertEquals(1, store.saves.single().calibration!!.onsets.size)
        }
    }

    @Test
    fun `a failed save that is still unsaved is written by the disconnect`() {
        val store = FakeStore(mapOf("v-pwm" to proven), failSaves = 1)
        repoTest(store) { repo ->
            val ride = Ride(repo, { advanceUntilIdle() }); ride.connect()
            ride.cruise(1); ride.onset(68f)
            assertTrue(store.saves.isEmpty())
            repo.disconnect(); advanceUntilIdle()
            assertEquals(1, store.saves.size, "the disconnect writes the unsaved calibration")
            assertEquals(1, store.saves.single().calibration!!.onsets.size)
        }
    }

    @Test
    fun `resetPwmCalibration clears the live estimator and the row, and keeps the evidence`() {
        val store = FakeStore(mapOf("v-pwm" to PwmMemory(measured, PwmEvidence.NEVER_REPORTED)))
        repoTest(store) { repo ->
            val ride = Ride(repo, { advanceUntilIdle() }); ride.connect()
            ride.send(60f); ride.send(60f)
            assertEquals(DutySource.ESTIMATED, repo.activeMotion.value.dutySource, "precondition: estimating")
            repo.resetPwmCalibration("v-pwm"); advanceUntilIdle()
            ride.send(60f)
            assertFalse(repo.activeMotion.value.hasDuty, "no model left, no estimate")
            assertNull(store.rows["v-pwm"]!!.calibration)
            assertEquals(PwmEvidence.NEVER_REPORTED, store.rows["v-pwm"]!!.evidence)
        }
    }

    @Test
    fun `a save captured before a reset cannot write the calibration back after it`() {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore(mapOf("v-pwm" to proven), saveGate = gate)
        repoTest(store) { repo ->
            val ride = Ride(repo, { advanceUntilIdle() }); ride.connect()
            ride.cruise(1); ride.onset(68f)
            assertEquals(listOf("load", "save-start"), store.log, "the prompt save is in flight, holding the ordered path")
            val reset = launchReset(repo)
            advanceUntilIdle()
            assertEquals(listOf("load", "save-start"), store.log, "the reset waits behind the in-flight save")
            gate.complete(Unit); store.saveGate = null
            advanceUntilIdle()
            assertTrue(reset.isCompleted)
            assertEquals(listOf("load", "save-start", "save", "resetCalibration"), store.log, "reset strictly after the save")
            assertNull(store.rows["v-pwm"]!!.calibration, "the stale capture did not come back")
        }
    }

    @Test
    fun `saves land in order, so the newest state is written last`() {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore(mapOf("v-pwm" to proven), saveGate = gate)
        repoTest(store) { repo ->
            val ride = Ride(repo, { advanceUntilIdle() }); ride.connect()
            ride.cruise(1); ride.onset(68f)       // first save blocks in the store with one onset
            ride.onset(69f)                        // a newer change while it is in flight
            gate.complete(Unit); store.saveGate = null
            advanceUntilIdle()
            assertTrue(store.saves.isNotEmpty())
            assertEquals(2, store.saves.last().calibration!!.onsets.size, "the last write carries the newest onset")
            assertEquals(2, store.rows["v-pwm"]!!.calibration!!.onsets.size)
            assertTrue(
                store.saves.map { it.calibration?.onsets?.size ?: 0 }.zipWithNext().all { (a, b) -> a <= b },
                "never an older state after a newer one: ${store.saves.map { it.calibration?.onsets?.size }}"
            )
        }
    }

    @Test
    fun `a new connection's load waits for the previous connection's pending save`() {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore(mapOf("v-pwm" to proven), saveGate = gate)
        repoTest(store) { repo ->
            val ride = Ride(repo, { advanceUntilIdle() }); ride.connect()
            ride.cruise(1); ride.onset(68f)
            assertEquals(listOf("load", "save-start"), store.log)
            ride.connect()          // the next connection of the same vehicle
            ride.send(30f)
            assertEquals(listOf("load", "save-start"), store.log, "its load is queued behind the pending save")
            gate.complete(Unit); store.saveGate = null
            advanceUntilIdle()
            assertEquals(listOf("load", "save-start", "save", "load"), store.log)
            // And it read what the previous connection wrote.
            ride.send(30f)
            assertEquals(1, assertNotNull(repo.vehicleConnectionForTest()?.pwm).memory().calibration?.onsets?.size)
        }
    }

    private val wizard = PwmCalibrationLearner.fromFreeSpin(81.7f, 96.5f)!!

    @Test
    fun `applyPwmCalibration installs the calibration live and writes the row, keeping the evidence`() {
        val store = FakeStore(mapOf("v-pwm" to proven))
        repoTest(store) { repo ->
            val ride = Ride(repo, { advanceUntilIdle() }); ride.connect()
            ride.send(60f); ride.send(60f)
            assertFalse(repo.activeMotion.value.hasDuty, "precondition: nothing to estimate from")
            repo.applyPwmCalibration("v-pwm", wizard); advanceUntilIdle()
            ride.send(60f)
            assertEquals(DutySource.ESTIMATED, repo.activeMotion.value.dutySource, "the live estimator uses it at once")
            assertEquals(wizard, store.rows["v-pwm"]!!.calibration)
            assertEquals(PwmEvidence.NEVER_REPORTED, store.rows["v-pwm"]!!.evidence)
            assertEquals(wizard, assertNotNull(repo.vehicleConnectionForTest()?.pwm).memory().calibration)
        }
    }

    @Test
    fun `applyPwmCalibration for a vehicle that is not connected writes the row only`() {
        val store = FakeStore(mapOf("other" to proven))
        repoTest(store) { repo ->
            repo.applyPwmCalibration("other", wizard); advanceUntilIdle()
            assertEquals(wizard, store.rows["other"]!!.calibration)
            assertEquals(PwmEvidence.NEVER_REPORTED, store.rows["other"]!!.evidence, "an UNKNOWN save never lowers it (R3)")
            assertEquals(listOf("save-start", "save"), store.log)
        }
    }

    @Test
    fun `applyPwmCalibration queues behind an in-flight save and is the last write`() {
        val gate = CompletableDeferred<Unit>()
        val store = FakeStore(mapOf("v-pwm" to proven), saveGate = gate)
        repoTest(store) { repo ->
            val ride = Ride(repo, { advanceUntilIdle() }); ride.connect()
            ride.cruise(1); ride.onset(68f)
            assertEquals(listOf("load", "save-start"), store.log)
            val apply = launch { repo.applyPwmCalibration("v-pwm", wizard) }
            advanceUntilIdle()
            assertEquals(listOf("load", "save-start"), store.log, "waits behind the in-flight save")
            gate.complete(Unit); store.saveGate = null
            advanceUntilIdle()
            assertTrue(apply.isCompleted)
            assertEquals(wizard, store.rows["v-pwm"]!!.calibration, "the wizard's write is the last one")
        }
    }

    @Test
    fun `applyPwmCalibration never persists a guest`() {
        val store = FakeStore()
        repoTest(store) { repo ->
            repo.applyPwmCalibration("guest:AA", wizard); advanceUntilIdle()
            assertTrue(store.log.isEmpty())
        }
    }

    @Test
    fun `pwmMemory reads the row when not connected and prefers the live estimator when connected`() {
        val store = FakeStore(mapOf("v-pwm" to PwmMemory(measured, PwmEvidence.NEVER_REPORTED)))
        repoTest(store) { repo ->
            assertEquals(PwmMemory(measured, PwmEvidence.NEVER_REPORTED), repo.pwmMemory("v-pwm"))
            assertEquals(PwmMemory.EMPTY, repo.pwmMemory("nobody"))
            val ride = Ride(repo, { advanceUntilIdle() }); ride.connect()
            ride.send(60f); ride.send(60f)
            repo.applyPwmCalibration("v-pwm", wizard); advanceUntilIdle()
            assertEquals(wizard, repo.pwmMemory("v-pwm").calibration)
        }
    }

    @Test
    fun `pwmMemory with an unreadable row is empty information, never a crash`() {
        val store = FakeStore(failLoad = true)
        repoTest(store) { repo -> assertEquals(PwmMemory.EMPTY, repo.pwmMemory("v-pwm")) }
    }

    private fun TestScope.launchReset(repo: KableBmsRepository) =
        launch { repo.resetPwmCalibration("v-pwm") }
}
