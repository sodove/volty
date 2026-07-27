package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BegodeDumpFixture
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.MotionSource
import ru.sodovaya.volty.domain.alert.AlertAvailability
import ru.sodovaya.volty.domain.alert.AlertUnavailableReason
import ru.sodovaya.volty.domain.alert.AlarmDefaults
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.armedRules
import ru.sodovaya.volty.domain.alert.availabilityFor
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Part D Task 4 — the gate: a Begode vehicle may finally carry a CONTROLLER,
 * so the wheel's motion reaches a rider's dashboard and Part F's ШИМ alarm.
 *
 * Everything here drives the production wiring — the real link plan, the real
 * protocol factory, the real motion funnel, the real router and gate — because
 * the three things this task lands only meet each other there:
 *
 *  1. one link owning `controllers = [0]` + `packs = [0, 1]` at one address
 *     (`D §4`, `01-linking §3` archetype 3);
 *  2. the pack's **cell count** reaching `createProtocol`, without which
 *     `inputVoltageV` — and `powerW` built from it — stay 0 and the Ride
 *     dashboard renders a confident "0.0 kW";
 *  3. the **observed duty layer**, without which a wheel whose firmware never
 *     fills the PWM field shows the ШИМ alarm armed and unable to fire.
 *
 * The battery half is asserted alongside on purpose: a wheel that today
 * connects as batteries only must keep working, and the interleave of one
 * controller with two packs over one link is `D §5`'s own requirement.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryBegodeControllerTest {

    private class NoopVehicleRepository : VehicleRepository {
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) {}
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
    }

    /** Every test here owns its repository through [bleRepositoryTest] — see there for why that is not optional. */
    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = NoopVehicleRepository(),
        serviceStart = {},
        serviceStop = {},
        body = body
    )

    /**
     * An ET Max as a rider would configure it after this task: ONE stored pack
     * (the profile's shape — the protocol reports the second branch, and
     * `expandedTo` synthesises its slot) plus the wheel itself as a controller,
     * at the SAME address, because a wheel multiplexes both over one BLE link.
     */
    private fun wheel(cellCount: Int? = 40, withController: Boolean = true) = Vehicle(
        id = "v-wheel",
        name = "ET Max",
        iconKey = "unicycle",
        packs = listOf(
            Pack(index = 0, label = "Branch 1", bmsType = BmsType.BEGODE, bmsAddress = WHEEL, cellCount = cellCount)
        ),
        controllers = if (!withController) emptyList() else listOf(
            Controller(index = 0, label = "Wheel", controllerType = ControllerType.BEGODE, address = WHEEL)
        ),
        topology = PackTopology.PARALLEL,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    /**
     * The session's observe loop in miniature, BOTH halves — the production
     * funnels, routers and gates a [ConnectionSession] drives, against the
     * protocol instance the production factory built for this link.
     */
    private class Wire(repo: KableBmsRepository, val vehicle: Vehicle) {
        val protocol: BegodeProtocol
        private val motion: MotionSource
        private val packFunnel: (Int, ru.sodovaya.volty.domain.model.BmsData, List<SectionState>) -> Unit
        private val motionFunnel: (Int, ru.sodovaya.volty.domain.model.ControllerData) -> Unit
        private val packGate: PackSampleGate
        private val motionGate: MotionSampleGate

        init {
            packFunnel = repo.installLinksForTest(vehicle, vehicle.primaryAddress, BmsType.BEGODE).single()
            motionFunnel = repo.linkMotionFunnelsForTest().single()
            val spec = repo.linkSpecsForTest().single()
            protocol = assertIs(repo.createProtocolForTest(spec, vehicle))
            motion = protocol
            packGate = PackSampleGate(protocol.packCount)
            motionGate = MotionSampleGate(motion.controllerCount)
        }

        fun notify(bytes: ByteArray) {
            protocol.onNotification(bytes)
            routePackSamples(protocol, packGate) { i, bms, sections -> packFunnel(i, bms, sections) }
            routeControllerSamples(motion, motionGate) { i, m -> motionFunnel(i, m) }
        }
    }

    // ----- 1. The link plan: archetype 3 -----

    @Test
    fun `a wheel plans as ONE link owning its controller and both branches at one address`() = repoTest { repo ->
        val v = wheel()

        // Pure plan first: the stored profile's own sources, one address.
        val planned = planLinks(v.packs, v.controllers).single()
        assertEquals(WHEEL, planned.address)
        assertEquals(ProtocolKind.BEGODE, planned.protocolKind, "pack and controller resolve to ONE wire protocol")
        assertEquals(listOf(OwnedSource(0)), planned.ownedControllers)
        assertFalse(planned.isGatewayLink, "a wheel is a plain single-source link, not a multiplexer")

        // Then the effective plan a connection installs, which is what `D §4`
        // describes: the second branch's slot exists because the protocol
        // reports packCount = 2, and the link must own it or globalPackIndex(1)
        // could not translate the session's local index.
        repo.installLinksForTest(v, v.primaryAddress, BmsType.BEGODE)
        assertEquals(1, repo.linkCountForTest(), "one address, one link")
        val spec = repo.linkSpecsForTest().single()
        assertEquals(listOf(OwnedSource(0), OwnedSource(1)), spec.ownedPacks)
        assertEquals(listOf(OwnedSource(0)), spec.ownedControllers)
    }

    @Test
    fun `the wheel's controller and its two branches interleave over the one link`() = repoTest { repo ->
        // `D §5`: one connection yields one controller AND two packs, from the
        // same frames on the same link.
        val wire = Wire(repo, wheel())
        BegodeDumpFixture.chunks().forEach { wire.notify(it) }
        advanceUntilIdle()

        val vd = repo.activeVehicleData.value
        assertEquals(2, vd.packs.size, "both branches")
        assertTrue(vd.packs.all { it.isOnline })
        assertEquals(1, vd.controllers.size, "and the wheel itself, as a controller")
        assertTrue(vd.controllers[0].isOnline)

        val motion = repo.activeMotion.value
        assertTrue(motion.isConnected, "the motion sample reached activeMotion")
        assertEquals(SpeedSource.REPORTED, motion.speedSource, "a wheel reports ground speed")
        assertEquals(2f, motion.dutyPercent, 0f, "the capture's hardware PWM: a balancing wheel spends 2 %")
        assertEquals(20f, motion.motorTempC, 0.01f)
        assertTrue(motion.hasMotorTemp)
    }

    // ----- 2. The cell count reaching createProtocol -----

    @Test
    fun `the profile's cell count reaches the wheel's decoder, so power is a number and not a lie`() = repoTest { repo ->
        // Without this the picker branch alone would ship a dashboard reading a
        // confident "0.0 kW" and "0.0 Wh/km" — not a blank and not a dash.
        val wire = Wire(repo, wheel(cellCount = 40))
        BegodeDumpFixture.chunks().forEach { wire.notify(it) }
        advanceUntilIdle()

        val motion = repo.activeMotion.value
        // 58.88 V on Begode's 67.2 V reference x (40 x 4.2 / 67.2) = 147.20 V.
        assertEquals(147.20f, motion.inputVoltageV, 0.01f, "the 67.2 V scale needs the profile's cell count")
        assertEquals(98.62f, motion.powerW, 0.5f, "power is voltage-derived and follows it")
    }

    @Test
    fun `a wheel whose profile has no cell count publishes no voltage rather than a wrong one`() = repoTest { repo ->
        // The control that makes the test above non-vacuous: the number can
        // only come from the profile, so a profile without one gets nothing.
        // 0 here means UNKNOWN; the raw reading would be 58.88 V on a 168 V
        // wheel, which is worse than an absence because power is built from it.
        val wire = Wire(repo, wheel(cellCount = null))
        BegodeDumpFixture.chunks().forEach { wire.notify(it) }
        advanceUntilIdle()

        val motion = repo.activeMotion.value
        assertTrue(motion.isConnected, "everything else the wheel reports is unaffected")
        assertEquals(0f, motion.inputVoltageV, 0f)
        assertEquals(0f, motion.powerW, 0f)
        assertEquals(2f, motion.dutyPercent, 0f, "…and the duty, which needs no scale, is still there")
    }

    // ----- 3. The observed duty layer -----

    @Test
    fun `a wheel that has never reported PWM cannot arm the ШИМ alarm`() = repoTest { repo ->
        // The hazard this task makes reachable: `truePWM` latches on the first
        // NON-ZERO reading, and before it `dutyPercent` reads 0 —
        // indistinguishable from a genuine 0 %. A firmware that never fills the
        // field would leave Part F's headline alarm displayed as armed and
        // permanently unable to fire (F §10's silent-dead-alarm class).
        val v = wheel()
        val wire = Wire(repo, v)
        wire.notify(liveFrame(voltageRaw = 5888, currentRaw = -350, tempRaw = 2798))
        wire.notify(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 0))
        advanceUntilIdle()

        val motion = repo.activeMotion.value
        assertTrue(motion.isConnected, "precondition: a real sample, not the disconnected placeholder")
        assertEquals(0f, motion.dutyPercent, 0f, "0 is all the field can say — hence the flag")
        assertFalse(motion.hasDuty, "the truePWM latch is still open")

        val availability = availabilityFor(v, motion)
        assertEquals(
            AlertAvailability.Unavailable(
                AlertUnavailableReason.ControllerReportsNoDuty(ControllerType.BEGODE)
            ),
            availability[MotionAlertKind.DUTY],
            "an alarm that cannot fire must not be shown armed"
        )
        assertTrue(
            armedRules(v, motion, AlarmDefaults.all()).rules.none { it.kind == MotionAlertKind.DUTY },
            "the shipped defaults armed a duty alarm against a duty we have never seen"
        )
    }

    @Test
    fun `the ШИМ alarm arms on the first PWM the wheel actually reports`() = repoTest { repo ->
        // The other direction: the guard must not disarm a working wheel. On
        // the ET Max the latch closes inside the first 0x07 frame.
        val v = wheel()
        val wire = Wire(repo, v)
        wire.notify(liveFrame(voltageRaw = 5888, currentRaw = -350, tempRaw = 2798))
        wire.notify(motionFrame(batteryCurrentRaw = 67, motorTempRaw = 20, dutyRaw = 2))
        advanceUntilIdle()

        val motion = repo.activeMotion.value
        assertTrue(motion.hasDuty, "the wheel proved it reports duty")
        assertEquals(
            AlertAvailability.Available,
            availabilityFor(v, motion)[MotionAlertKind.DUTY]
        )
        assertTrue(
            armedRules(v, motion, AlarmDefaults.all()).rules.any { it.kind == MotionAlertKind.DUTY },
            "a wheel that reports duty must arm the alarm its rider needs most"
        )
    }

    @Test
    fun `a wheel with no controller in its profile still plans no controller and shows no motion`() = repoTest { repo ->
        // The constraint this task must not break: a Begode that connects as
        // batteries only today keeps working, and gains nothing it did not ask
        // for. The picker branch is what a rider opts into.
        val v = wheel(withController = false)
        val wire = Wire(repo, v)
        assertTrue(
            repo.linkSpecsForTest().single().ownedControllers.isEmpty(),
            "no controller in the profile, no controller in the plan"
        )
        BegodeDumpFixture.chunks().forEach { wire.notify(it) }
        advanceUntilIdle()

        assertFalse(repo.activeMotion.value.isConnected, "no planned controller, no motion")
        assertEquals(2, repo.activeVehicleData.value.packs.size, "…and the batteries decode exactly as before")
        assertTrue(repo.activeVehicleData.value.packs.all { it.isOnline })
        // The branch's own cell sum from the capture (~148.4 V), which is what
        // a smart-BMS wheel publishes and which owes nothing to any of this.
        assertEquals(
            148.4f,
            assertNotNull(wire.protocol.latestData(0)).voltage,
            0.3f,
            "the branch decode is untouched by any of this"
        )
    }

    // --- Synthetic frame builders (24 bytes, layout as BegodeMotionProtocolTest) ---

    private companion object {
        const val WHEEL = "AA:BB:CC:DD:EE:FF"
    }

    private fun frame(type: Int, subtype: Int, payload: ByteArray): ByteArray {
        require(payload.size == 16) { "payload is frame bytes 2..17" }
        return byteArrayOf(0x55, 0xAA.toByte()) + payload +
            byteArrayOf(type.toByte(), subtype.toByte(), 0x5A, 0x5A, 0x5A, 0x5A)
    }

    /** Live 0x00 frame: voltage at 2..3, phase current at 10..11, MPU temp at 12..13. */
    private fun liveFrame(voltageRaw: Int, currentRaw: Int = 0, tempRaw: Int = 0): ByteArray {
        val p = ByteArray(16)
        p[0] = (voltageRaw shr 8).toByte(); p[1] = voltageRaw.toByte()
        p[8] = (currentRaw shr 8).toByte(); p[9] = currentRaw.toByte()
        p[10] = (tempRaw shr 8).toByte(); p[11] = tempRaw.toByte()
        return frame(0x00, 24, p)
    }

    /** Motion 0x07 frame: battery current at 2..3, motor temperature at 6..7, hardware duty at 8..9. */
    private fun motionFrame(batteryCurrentRaw: Int, motorTempRaw: Int, dutyRaw: Int): ByteArray {
        val p = ByteArray(16)
        p[0] = (batteryCurrentRaw shr 8).toByte(); p[1] = batteryCurrentRaw.toByte()
        p[2] = 0x00; p[3] = 0x01 // the constant the real wheel sends here
        p[4] = (motorTempRaw shr 8).toByte(); p[5] = motorTempRaw.toByte()
        p[6] = (dutyRaw shr 8).toByte(); p[7] = dutyRaw.toByte()
        return frame(0x07, 24, p)
    }
}
