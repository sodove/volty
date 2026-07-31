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
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.repository.GaugePeaks
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
        // Explicit, because both of VehicleRepository's gauge-peak members are abstract:
        // no fake gets a silent default. Nothing in this file rides a learned dial range
        // (G §9.2), and an EMPTY map is the honest answer rather than a missing one --
        // absence in that map means "has learned nothing", which is exactly the case here.
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
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

        /**
         * Every funnel call in ARRIVAL ORDER — `P<n>` a battery sample for
         * branch n, `M<n>` a motion sample for controller n, both local indices.
         *
         * Recorded rather than merely counted because terminal state cannot see
         * ORDER: a decode that routed every battery frame first and every motion
         * frame afterwards leaves the same end state and would satisfy a test
         * called "interleave" without interleaving anything. Same reason the
         * Task 5 funnel test records.
         */
        val calls: MutableList<String> = mutableListOf()
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
            routePackSamples(protocol, packGate) { i, bms, sections ->
                calls += "P$i"
                packFunnel(i, bms, sections)
            }
            routeControllerSamples(motion, motionGate) { i, m ->
                calls += "M$i"
                motionFunnel(i, m)
            }
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
        val chunks = BegodeDumpFixture.chunks()
        val wire = Wire(repo, wheel())
        chunks.forEach { wire.notify(it) }
        advanceUntilIdle()

        // (a) The INTERLEAVE this test is named for, which its terminal
        // assertions below cannot see: a decode that routed every battery frame
        // first and every motion frame afterwards satisfies every one of them.
        val kinds = wire.calls.map { it.first() }
        assertTrue(
            kinds.indexOfFirst { it == 'M' } < kinds.indexOfLast { it == 'P' },
            "motion starts before the battery stream ends"
        )
        assertTrue(
            kinds.indexOfFirst { it == 'P' } < kinds.indexOfLast { it == 'M' },
            "battery starts before the motion stream ends"
        )
        assertTrue(
            kinds.zipWithNext().count { (a, b) -> a != b } >= 20,
            "the two must alternate throughout the capture, not cross once; got ${wire.calls.size} calls"
        )
        assertTrue(
            wire.calls.none { it != "M0" && it != "P0" && it != "P1" },
            "one controller and two branches and nothing else, got ${wire.calls.distinct()}"
        )

        // (b) And no frame was LOST on the way: the count comes from the BYTES,
        // not from the decoder, so a reassembler that consumed fewer frames than
        // the stream contains fails here even when every terminal VALUE is
        // identical — this wheel repeats its frame types every cycle, so losing
        // one changes nothing a value assertion can see. Written as "all but
        // one" for the capture's single unobservable frame — its first branch-1
        // cell frame precedes that branch's telemetry, so `rebuild` publishes
        // nothing for it — which means a SECOND missing frame fails this too.
        //
        // Measured, not assumed: a decoder that waits for more than one frame's
        // worth of bytes before parsing (`current.size < FRAME_SIZE + 4`) fails
        // this test. Note the mutant the Task 5 funnel test's own comment names
        // — discarding the trailing byte in `tryParseAll`'s no-header branch —
        // does NOT fail either count, because this capture never reaches that
        // branch with a straddling `55 AA`. The guard is real; that particular
        // justification for it is not exercised by these bytes.
        val streamBytes = chunks.sumOf { it.size }
        assertEquals(0, streamBytes % BEGODE_FRAME_BYTES, "the capture is a whole number of frames")
        assertEquals(
            streamBytes / BEGODE_FRAME_BYTES - 1,
            wire.calls.size,
            "every frame of the capture but one must reach a funnel exactly once"
        )

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
        //
        // Vacuous with respect to the PROFILE specifically since Task 2: this
        // wheel's own smart BMS derives the identical 40S on its own, and
        // `inputVoltageOrNull` prefers the derived count over the profile's —
        // see `a wheel whose profile has no cell count still gets a real
        // voltage from its own smart BMS` below for that path in isolation.
        // What this test still pins is that a cell count on the profile does
        // not BREAK anything and produces the same right answer, which
        // matters for a wheel with no smart BMS (a dumb wheel never proves a
        // derived count, so the profile is its only route — see
        // `KableBmsRepositoryDumbBegodeTest`).
        val wire = Wire(repo, wheel(cellCount = 40))
        BegodeDumpFixture.chunks().forEach { wire.notify(it) }
        advanceUntilIdle()

        val motion = repo.activeMotion.value
        // 58.88 V on Begode's 67.2 V reference x (40 x 4.2 / 67.2) = 147.20 V.
        assertEquals(147.20f, motion.inputVoltageV, 0.01f, "the 67.2 V scale, from either the profile or the derived count")
        assertEquals(98.62f, motion.powerW, 0.5f, "power is voltage-derived and follows it")
    }

    @Test
    fun `a wheel whose profile has no cell count still gets a real voltage from its own smart BMS`() = repoTest { repo ->
        // Task 2 (2026-07-30 field fix): this used to be the control that made
        // the test above non-vacuous — "the number can only come from the
        // profile, so a profile without one gets nothing" — and THAT premise
        // was the field report's bug. The ET Max's two branches are PARALLEL,
        // so branch 0's own cells prove the pack's series-cell count with no
        // profile involved at all; the wheel that showed no power on a real
        // ride was streaming exactly this data, unused. See
        // `BegodeProtocol.derivedCellCount`. A wheel with no smart BMS still
        // has no recovery route and is covered by
        // `KableBmsRepositoryDumbBegodeTest`'s "no known cell count" cases.
        val wire = Wire(repo, wheel(cellCount = null))
        BegodeDumpFixture.chunks().forEach { wire.notify(it) }
        advanceUntilIdle()

        val motion = repo.activeMotion.value
        assertTrue(motion.isConnected, "everything else the wheel reports is unaffected")
        assertTrue(motion.hasInputVoltage, "the wheel's own cells supply the rail voltage")
        assertEquals(147.2f, motion.inputVoltageV, 0.5f, "40S derived from branch 0's own cells")
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
            // "not reported YET", not "this hardware cannot": the latch may
            // close on the next frame, and on this wheel it does.
            AlertAvailability.Unavailable(AlertUnavailableReason.ControllerHasNotReportedDuty),
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

    @Test
    fun `a battery-only wheel beside a real controller must DROP its motion, not misroute it`() = repoTest { repo ->
        // The case a single-link vehicle cannot expose, and the reason
        // `makeLinkOnMotionSample` drops rather than defaulting to controller 0.
        //
        // A Begode used as a BATTERY (no controller in its profile) beside a
        // VESC controller at another address: two links, ONE VehicleConnection.
        // The wheel's protocol is a MotionSource, so its funnel fires — and its
        // link owns no controller. Dropping is the only correct answer: any
        // fallback index lands on the VESC's slot and OVERWRITES a real
        // controller's speed, duty and temperatures with the wheel's.
        //
        // On a single-link vehicle the same fallback is harmless — submitMotion
        // finds neither a state nor a latent slot and returns without emitting —
        // which is exactly why that case cannot pin this and this one must.
        val v = Vehicle(
            id = "v-wheel-battery-plus-esc",
            name = "Wheel pack + VESC",
            iconKey = "scooter",
            packs = listOf(
                Pack(index = 0, label = "Wheel pack", bmsType = BmsType.BEGODE, bmsAddress = WHEEL, cellCount = 40)
            ),
            controllers = listOf(
                Controller(index = 0, label = "ESC", controllerType = ControllerType.VESC, address = ESC)
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Instant.fromEpochSeconds(0L)
        )
        repo.installLinksForTest(v, v.primaryAddress, BmsType.BEGODE)

        val specs = repo.linkSpecsForTest()
        assertEquals(2, specs.size, "two addresses, two links")
        val wheelLink = specs.indexOfFirst { it.address == WHEEL }
        assertTrue(wheelLink >= 0)
        assertTrue(
            specs[wheelLink].ownedControllers.isEmpty(),
            "precondition: the wheel is a battery here and owns no controller"
        )
        val escLink = specs.indexOfFirst { it.address == ESC }
        assertEquals(listOf(OwnedSource(0)), specs[escLink].ownedControllers)

        // The VESC reports first: real controller telemetry in the shared state.
        val funnels = repo.linkMotionFunnelsForTest()
        funnels[escLink](
            0,
            ControllerData(
                speedKmh = 42f, speedSource = SpeedSource.REPORTED, dutyPercent = 71f,
                escTempC = 55f, isConnected = true
            )
        )
        advanceUntilIdle()
        assertEquals(42f, repo.activeMotion.value.speedKmh, 0.01f, "fixture: the VESC's reading is in place")

        // Now the wheel's own link decodes motion, through the production
        // router, gate and funnel — exactly as its session would.
        val wheelProtocol = assertIs<BegodeProtocol>(repo.createProtocolForTest(specs[wheelLink], v))
        val gate = MotionSampleGate(wheelProtocol.controllerCount)
        wheelProtocol.onNotification(liveFrame(voltageRaw = 5888, currentRaw = -350, tempRaw = 2798))
        val alive = routeControllerSamples(wheelProtocol, gate) { i, m -> funnels[wheelLink](i, m) }
        assertTrue(alive, "the wheel really did decode motion — that is the whole hazard")
        advanceUntilIdle()

        val motion = repo.activeMotion.value
        assertEquals(42f, motion.speedKmh, 0.01f, "the wheel's motion overwrote the VESC's speed")
        assertEquals(71f, motion.dutyPercent, 0.01f, "the wheel's motion overwrote the VESC's duty")
        assertEquals(55f, motion.escTempC, 0.01f, "the wheel's motion overwrote the VESC's ESC temperature")
        val vd = repo.activeVehicleData.value
        assertEquals(1, vd.controllers.size, "the vehicle has exactly one controller and it is the VESC")
        assertEquals(ControllerType.VESC, vd.controllers.single().controller.controllerType)
    }

    // --- Synthetic frame builders (24 bytes, layout as BegodeMotionProtocolTest) ---

    private companion object {
        const val WHEEL = "AA:BB:CC:DD:EE:FF"
        const val ESC = "AA:BB:CC:DD:EE:0C"
        /** Every Begode frame: 0x55 0xAA, 16 payload bytes, type, subtype, 0x5A x4. */
        const val BEGODE_FRAME_BYTES = 24
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
