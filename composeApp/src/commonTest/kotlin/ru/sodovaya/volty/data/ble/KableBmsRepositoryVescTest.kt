package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.AntBmsProtocol
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.GatewaySource
import ru.sodovaya.volty.data.bms.FarDriverProtocol
import ru.sodovaya.volty.data.bms.KellyProtocol
import ru.sodovaya.volty.data.bms.MotionSource
import ru.sodovaya.volty.data.bms.VescGatewayProtocol
import ru.sodovaya.volty.data.bms.VescProtocol
import ru.sodovaya.volty.data.bms.vesc.VescBmsValues
import ru.sodovaya.volty.data.bms.vesc.VescCan
import ru.sodovaya.volty.data.bms.vesc.VescPacket
import ru.sodovaya.volty.data.bms.vesc.VescValues
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SpeedSource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.pickedControllerVehicle
import ru.sodovaya.volty.domain.model.primaryAddress
import ru.sodovaya.volty.domain.model.singlePackVehicle
import ru.sodovaya.volty.domain.model.bmsTypeOrNull
import ru.sodovaya.volty.domain.model.bmsAddressOrNull
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Part B1 Task 4 — the crux: a vehicle's CONTROLLERS are planned into BLE
 * links, a controller link builds a [VescProtocol], and Part A's dormant
 * motion hop (`ConnectionSession.onMotionSample` → funnel → `submitMotion` →
 * `activeMotion`) finally carries a real decode.
 *
 * Everything runs on fakes, like the sibling multi-link / motion tests: the
 * production wiring is installed through [KableBmsRepository.installLinksForTest]
 * and the session's own body — `routeControllerSamples` + the per-link motion
 * funnel — is driven by hand, because [ConnectionSession] needs a real Kable
 * peripheral that commonTest cannot build.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class KableBmsRepositoryVescTest {

    /** Records every write so a test can assert the auto-fills stay silent. */
    private class RecordingVehicleRepository : VehicleRepository {
        val upserts = mutableListOf<Vehicle>()
        override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
        override suspend fun get(id: String): Vehicle? = null
        override suspend fun upsert(vehicle: Vehicle) { upserts += vehicle }
        override suspend fun delete(id: String) {}
        override suspend fun touch(id: String) {}
        // Explicit, because both of VehicleRepository's gauge-peak members are abstract:
        // no fake gets a silent default. Nothing in this file rides a learned dial range
        // (G §9.2), and an EMPTY map is the honest answer rather than a missing one --
        // absence in that map means "has learned nothing", which is exactly the case here.
        override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
        override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
    }

    private val vehicleRepo = RecordingVehicleRepository()
    /** Every test here owns its repository through [bleRepositoryTest] — see there for why that is not optional. */
    private fun repoTest(body: suspend TestScope.(KableBmsRepository) -> Unit) = bleRepositoryTest(
        vehicleRepository = vehicleRepo,
        serviceStart = {},
        serviceStop = {},
        body = body
    )

    /**
     * A lone VESC: ZERO stored packs (legal since Part A) and one controller
     * that backs a derived battery. `Vehicle.bmsType` / `Vehicle.bmsAddress`
     * are `packs.first()` shims and THROW on this vehicle — that is the trap
     * this task had to route around.
     */
    private fun vescOnlyVehicle(providesDerivedBattery: Boolean = true): Vehicle = Vehicle(
        id = "v-vesc",
        name = "Scooter",
        iconKey = "scooter",
        packs = emptyList(),
        controllers = listOf(
            Controller(
                index = 0,
                label = "ESC",
                controllerType = ControllerType.VESC,
                address = CTRL_ADDR,
                motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254),
                providesDerivedBattery = providesDerivedBattery
            )
        ),
        topology = PackTopology.PARALLEL,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    /** The same COMM_GET_VALUES_SETUP fixture VescProtocolTest decodes: ~47 km/h. */
    private fun setupFrame(): ByteArray {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) {
            o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
            o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte()
        }
        o += 47; i16(520); i16(680); i32(-8250); i32(5240); i16(760); i32(12000); i32(13056)
        i16(782); i16(840); i32(154000); i32(21000); i32(9800000); i32(1200000)
        i32(12400000); i32(1284600000); i32(0); o += 0; o += 11
        return VescPacket.frame(o.toByteArray())
    }

    // ----- 1. A controller-only vehicle must connect -----

    @Test
    fun `controller-only vehicle plans a controller link and does not touch packs first`() = repoTest { repo ->
        val v = vescOnlyVehicle()

        // The trap, pinned. The throwing `packs.first()` shims that used to sit
        // on Vehicle are gone (G1 task 2) — what replaced them must report
        // "no pack" rather than blow up, and primaryAddress must still route
        // through the controller.
        assertNull(v.bmsAddressOrNull, "a zero-pack vehicle has no pack address")
        assertNull(v.bmsTypeOrNull, "a zero-pack vehicle has no pack type")
        assertEquals(CTRL_ADDR, v.primaryAddress, "primaryAddress is the safe route")

        // connect() must plan the vehicle THROUGH primaryAddress. No BLE stack
        // exists here, so the link cannot come up — but planning happens before
        // any radio work, and a route through packs.first() would have blown up
        // long before the link list was installed.
        val result = repo.connect(v)
        advanceUntilIdle()
        assertTrue(result.isFailure, "no BLE in a unit test — the link cannot come up")

        assertEquals(1, repo.linkCountForTest(), "the lone controller raises exactly one link")
        val spec = repo.linkSpecsForTest().single()
        assertEquals(CTRL_ADDR, spec.address)
        assertEquals(ProtocolKind.VESC, spec.protocolKind)
        assertEquals(listOf(OwnedSource(0)), spec.ownedControllers)
    }

    // ----- G1 Task 5: the picker's gate is DERIVED from this factory -----

    /** A controller-only vehicle of any kind, for probing the factory. */
    private fun controllerOnly(type: ControllerType): Vehicle = Vehicle(
        id = "v-${type.name.lowercase()}",
        name = "probe",
        iconKey = "generic",
        packs = emptyList(),
        controllers = listOf(
            Controller(
                index = 0, label = "ESC", controllerType = type,
                address = CTRL_ADDR, providesDerivedBattery = true
            )
        ),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    /**
     * THE test that keeps the two from drifting.
     *
     * `unsupportedControllerReason` in the picker used to *list* which
     * controller kinds work — presentation-layer code encoding a data-layer
     * fact. The failure mode was silent and delayed: Part D adds a FarDriver
     * protocol here, nothing in the picker fails to compile, and the sheet goes
     * on refusing a controller that works.
     *
     * The gate now derives its answer from [controllerMotionProtocol], the same
     * function this factory builds every controller link from. This test drives
     * the REAL factory for every [ControllerType] and asserts the two agree —
     * so it stays green when a protocol is added (both sides move together) and
     * goes red the moment anyone reintroduces a separate list.
     *
     * **Both directions are universal again** (Part D Task 4, 2026-07-27). Task
     * 2 had to carve out one deliberate exception — [BegodeProtocol] became a
     * [MotionSource] while [controllerMotionProtocol] still had no BEGODE
     * branch, so the battery FALLBACK handed back something carrying motion
     * behind a picker that refused it. Task 4 gave the kind its branch, and the
     * exception came out with it. A type drifting into that state now fails
     * here again, which is the whole point of the test.
     */
    @Test
    fun `the picker's gate agrees with the real connect factory for every controller type`() = repoTest { repo ->
        ControllerType.entries.forEach { type ->
            val v = controllerOnly(type)
            val spec = planLinks(v.packs, v.controllers).single()
            // Exactly what doConnect sees: build through the factory, and treat
            // a throw as "cannot connect" — doConnect catches it and returns
            // Result.failure rather than propagating.
            val built = runCatching { repo.createProtocolForTest(spec, v) }.getOrNull()
            if (controllerMotionSupported(type)) {
                assertTrue(
                    built is MotionSource,
                    "$type: the picker offers a controller the connect factory cannot decode motion from"
                )
            } else {
                assertTrue(
                    built !is MotionSource,
                    "$type: the connect factory decodes its motion but the picker refuses it — " +
                        "give the kind a branch in controllerMotionProtocol"
                )
            }
        }
    }

    /** Guards the test above from passing vacuously with everything refused. */
    @Test
    fun `all implemented controller kinds decode motion today`() {
        assertEquals(
            listOf(
                ControllerType.VESC,
                ControllerType.FARDRIVER,
                ControllerType.KELLY,
                ControllerType.BEGODE,
                ControllerType.NINEBOT,
                ControllerType.NINEBOT_LEGACY,
                ControllerType.KINGSONG,
                ControllerType.INMOTION,
                ControllerType.VETERAN,
                ControllerType.NOSFET
            ),
            ControllerType.entries.filter { controllerMotionSupported(it) },
            "Every ported wheel must be wired through the same shared coverage decision"
        )
    }

    @Test
    fun `a FarDriver controller-only vehicle builds read-only motion protocol and derived pack`() = repoTest { repo ->
        val v = controllerOnly(ControllerType.FARDRIVER)

        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val spec = repo.linkSpecsForTest().single()
        assertEquals(ProtocolKind.FARDRIVER, spec.protocolKind)
        val protocol = assertIs<FarDriverProtocol>(repo.createProtocolForTest(spec, v))
        assertIs<MotionSource>(protocol)
        assertEquals(1, protocol.packCount)
        assertTrue(protocol.handshakeCommands().isEmpty())
        assertTrue(protocol.pollCommands().isEmpty())
    }

    /**
     * The case a "types that throw" list would have missed, and the reason the
     * gate's criterion is `is MotionSource` rather than "did not throw":
     * `ControllerType.BEGODE.protocolKind()` is `ProtocolKind.BEGODE`, which
     * `toBmsType()` maps to a REAL `BmsType.BEGODE`, so the factory reaches its
     * battery fallback and nothing fails. Between Task 2 and Task 4 that made
     * Begode the one kind whose decoder carried motion behind a picker that
     * refused it — conservative, but a state no *list* of throwing types could
     * even have described.
     *
     * **Rewritten twice**: by Task 2, when [BegodeProtocol] became a
     * [MotionSource], and by Task 4, which is what this now pins — the branch
     * exists, the picker offers it, and the protocol reaching a connection is
     * the wheel's own decoder rather than the battery fallback's.
     */
    @Test
    fun `a Begode controller decodes motion and the picker now offers it`() = repoTest { repo ->
        val v = controllerOnly(ControllerType.BEGODE)
        val spec = planLinks(v.packs, v.controllers).single()

        val protocol = repo.createProtocolForTest(spec, v)
        assertIs<BegodeProtocol>(protocol, "the wheel's own decoder, not a stand-in")
        val motion = assertIs<MotionSource>(protocol, "a wheel IS a controller over its battery link")
        assertEquals(1, motion.controllerCount)
        assertTrue(
            controllerMotionSupported(ControllerType.BEGODE),
            "controllerMotionProtocol has a BEGODE branch since Part D Task 4"
        )
    }

    // ----- 2. A VESC link builds a VescProtocol that is a MotionSource -----

    @Test
    fun `a vesc link builds a VescProtocol that is a MotionSource`() = repoTest { repo ->
        val v = vescOnlyVehicle(providesDerivedBattery = true)
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val spec = repo.linkSpecsForTest().single()
        val protocol = repo.createProtocolForTest(spec, v)

        assertIs<VescProtocol>(protocol)
        val motion = assertIs<MotionSource>(protocol)
        assertEquals(1, motion.controllerCount)
        assertEquals(1, protocol.packCount, "providesDerivedBattery ⇒ the controller backs one pack")
        // The controller's own motor config must reach the protocol: with no
        // wheel diameter the GET_VALUES fallback could not derive a speed.
        assertEquals(VescProtocol.NUS_SERVICE, protocol.uuids.serviceUuid)
    }

    @Test
    fun `a Kelly controller-only vehicle plans a derived pack and builds its motion protocol`() = repoTest { repo ->
        val v = controllerOnly(ControllerType.KELLY)

        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val spec = repo.linkSpecsForTest().single()
        assertEquals(ProtocolKind.KELLY, spec.protocolKind)
        assertEquals(listOf(OwnedSource(0)), spec.ownedPacks, "a controller-only Kelly has one derived pack slot")
        val protocol = assertIs<KellyProtocol>(repo.createProtocolForTest(spec, v))
        assertIs<MotionSource>(protocol)
        assertEquals(1, protocol.packCount)
    }

    @Test
    fun `a Kelly controller beside a smart BMS does not create a duplicate derived pack`() = repoTest { repo ->
        val v = Vehicle(
            id = "v-kelly-with-bms",
            name = "Scooter",
            iconKey = "scooter",
            packs = listOf(Pack(index = 0, label = "Main", bmsType = BmsType.JK_BMS, bmsAddress = ADDR)),
            controllers = listOf(
                Controller(
                    index = 0, label = "ESC", controllerType = ControllerType.KELLY,
                    address = CTRL_ADDR, motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254),
                    providesDerivedBattery = false
                )
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Instant.fromEpochSeconds(0L)
        )
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val controllerSpec = repo.linkSpecsForTest().single { it.address == CTRL_ADDR }
        assertTrue(controllerSpec.ownedPacks.isEmpty(), "the smart BMS already owns the only pack")
        val protocol = assertIs<KellyProtocol>(repo.createProtocolForTest(controllerSpec, v))
        assertEquals(0, protocol.packCount, "the Kelly must not publish a second derived copy of the smart BMS")
    }

    @Test
    fun `a controller beside a real BMS builds a VescProtocol with no pack`() = repoTest { repo ->
        // "Backs no battery" only means something when a real battery source
        // exists ELSEWHERE — a zero-pack vehicle with the flag off is a
        // different scenario (see the next test): there the fallback must
        // derive a battery regardless of the flag.
        val v = Vehicle(
            id = "v-vesc-with-bms",
            name = "Scooter",
            iconKey = "scooter",
            packs = listOf(Pack(index = 0, label = "Main", bmsType = BmsType.JK_BMS, bmsAddress = ADDR)),
            controllers = listOf(
                Controller(
                    index = 0, label = "ESC", controllerType = ControllerType.VESC,
                    address = CTRL_ADDR, motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254),
                    providesDerivedBattery = false
                )
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Instant.fromEpochSeconds(0L)
        )
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val controllerSpec = repo.linkSpecsForTest().single { it.address == CTRL_ADDR }
        val protocol = repo.createProtocolForTest(controllerSpec, v)
        assertIs<VescProtocol>(protocol)
        assertEquals(0, protocol.packCount)
        assertTrue(controllerSpec.ownedPacks.isEmpty(), "a real BMS covers the battery ⇒ the link owns no pack slot")
    }

    /**
     * Pins the dead-elvis bug directly: `controller` is never null for a
     * planned VESC link (its spec's owned controller is always found in
     * `vehicle.controllers`), so a plain `?:` on its non-null
     * `providesDerivedBattery` could never fall through to the "no packs at
     * all" fallback. A controller-only vehicle built with that flag at its
     * own default (false) must still derive a battery — there is nowhere
     * else for one to come from.
     */
    @Test
    fun `a controller-only vehicle derives a battery even when its own flag is off`() = repoTest { repo ->
        val v = vescOnlyVehicle(providesDerivedBattery = false)
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val spec = repo.linkSpecsForTest().single()
        val protocol = repo.createProtocolForTest(spec, v)
        assertIs<VescProtocol>(protocol)
        assertEquals(1, protocol.packCount, "no packs anywhere ⇒ the controller must derive one regardless of its flag")
        assertEquals(listOf(OwnedSource(0)), spec.ownedPacks, "the derived slot must still be planned")
    }

    // ----- 3. Motion from a VESC link reaches activeMotion -----

    @Test
    fun `motion from a vesc link reaches activeMotion`() = repoTest { repo ->
        val v = vescOnlyVehicle()
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        assertEquals(SpeedSource.NONE, repo.activeMotion.value.speedSource, "nothing decoded yet")

        val spec = repo.linkSpecsForTest().single()
        val protocol = repo.createProtocolForTest(spec, v)
        val motionFunnel = repo.linkMotionFunnelsForTest().single()

        // Exactly what ConnectionSession's observe loop does: feed the raw
        // notification, then route the protocol's controllers through the gate
        // into this link's motion funnel.
        protocol.onNotification(setupFrame())
        val alive = routeControllerSamples(
            protocol = protocol as MotionSource,
            gate = MotionSampleGate(protocol.controllerCount)
        ) { controllerIndex, motion -> motionFunnel(controllerIndex, motion) }
        assertTrue(alive, "the decoded frame must count as motion liveness")
        advanceUntilIdle()

        assertEquals(SpeedSource.REPORTED, repo.activeMotion.value.speedSource)
        assertTrue(
            abs(repo.activeMotion.value.speedKmh - 47.0f) < 0.05f,
            "expected ~47 km/h, was ${repo.activeMotion.value.speedKmh}"
        )

        // The same submit surfaced the controller on the vehicle-level snapshot.
        val vd = repo.activeVehicleData.value
        assertEquals(1, vd.controllers.size)
        assertTrue(vd.controllers[0].isOnline)
        assertEquals(SpeedSource.REPORTED, vd.motion.speedSource)
    }

    // ----- 3b. The derived pack slot a controller link backs -----

    /**
     * `providesDerivedBattery` makes `VescProtocol.packCount` 1, so the SESSION
     * will call the battery route with local pack index 0 — which resolves
     * through `LinkSpec.globalPackIndex(0)`. If the link owned no pack slot
     * that lookup throws IndexOutOfBounds inside the observe loop, killing it:
     * the link goes silent, the watchdog fires and the vehicle reconnects
     * forever. This pins the slot AND that a sample actually lands in it.
     */
    @Test
    fun `a derived-battery VESC link owns pack 0 and its sample materialises the latent slot`() = repoTest { repo ->
        val v = vescOnlyVehicle(providesDerivedBattery = true)
        val funnels = repo.installLinksForTest(v, v.primaryAddress, type = null)

        assertEquals(
            listOf(OwnedSource(0)),
            repo.linkSpecsForTest().single().ownedPacks,
            "packCount = 1 ⇒ the link must own exactly the slot globalPackIndex(0) resolves"
        )

        // A motion sample emits a real vehicle snapshot without touching the
        // battery side — proof the derived slot is LATENT (invisible until it
        // reports), not an eagerly published permanently-offline phantom.
        repo.linkMotionFunnelsForTest().single()(
            0, ControllerData(speedKmh = 12f, speedSource = SpeedSource.REPORTED, isConnected = true)
        )
        advanceUntilIdle()
        assertTrue(
            repo.activeVehicleData.value.packs.isEmpty(),
            "the derived slot must stay latent until it reports"
        )

        funnels.single()(0, BmsData(voltage = 78.2f, current = -8.85f, isConnected = true), emptyList())
        advanceUntilIdle()

        val snap = repo.activeVehicleData.value
        assertEquals(1, snap.packs.size, "the derived slot must materialise on its first sample")
        assertEquals(0, snap.packs[0].pack.index)
        assertTrue(snap.packs[0].isOnline)
        assertEquals(78.2f, snap.packs[0].data.voltage, absoluteTolerance = 0.01f)
        assertEquals(78.2f, repo.activeData.value.voltage, absoluteTolerance = 0.01f)
    }

    /**
     * The index-collision half of the same mechanism, plus the guard that keeps
     * a derived slot out of the database: persisted, it would sit at the
     * CONTROLLER's address with a BmsType that can never resolve to the
     * controller's protocol kind — and the next connect's `planLinks` would
     * reject the vehicle outright ("conflicting protocol kinds").
     */
    @Test
    fun `a BMS beside a derived-battery VESC numbers the derived slot 1 and never persists it`() = repoTest { repo ->
        val v = Vehicle(
            id = "v-mixed-derived",
            name = "Scooter",
            iconKey = "scooter",
            packs = listOf(Pack(index = 0, label = "Main", bmsType = BmsType.JK_BMS, bmsAddress = ADDR)),
            controllers = listOf(
                Controller(
                    index = 0, label = "ESC", controllerType = ControllerType.VESC,
                    address = CTRL_ADDR, providesDerivedBattery = true
                )
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Instant.fromEpochSeconds(0L)
        )
        val funnels = repo.installLinksForTest(v, v.primaryAddress, type = null)
        val specs = repo.linkSpecsForTest()

        val battery = specs.single { it.address == ADDR }
        val controller = specs.single { it.address == CTRL_ADDR }
        assertEquals(listOf(OwnedSource(0)), battery.ownedPacks)
        assertEquals(
            listOf(OwnedSource(1)),
            controller.ownedPacks,
            "the derived slot is numbered AFTER every stored/expanded index — never a collision"
        )

        funnels[specs.indexOf(battery)](
            0, BmsData(voltage = 78.0f, current = 1.0f, isConnected = true), emptyList()
        )
        funnels[specs.indexOf(controller)](
            0, BmsData(voltage = 78.2f, current = -8.85f, isConnected = true), emptyList()
        )
        advanceUntilIdle()

        val snap = repo.activeVehicleData.value
        assertEquals(listOf(0, 1), snap.packs.map { it.pack.index }, "both slots reachable, distinct indices")
        assertTrue(snap.packs.all { it.isOnline })

        // The pack auto-fill sees 2 discovered slots against 1 stored — it is
        // genuinely reached — and must still write nothing.
        assertEquals(
            emptyList(),
            vehicleRepo.upserts,
            "a derived slot is runtime telemetry, not discovered hardware — it must never be persisted"
        )
    }

    /**
     * The fourth `packs.first()` trap of the same class, in
     * `maybePersistCellCount`: cell count lives on a Pack, and there is none.
     * That collector runs on the repo's SupervisorJob scope with no exception
     * handler, so a throw there kills it silently (and is app-fatal on
     * Android). The observable consequence is the write at the end of the
     * method: it happens iff the collector survived the shim.
     */
    @Test
    fun `the cell-count auto-fill survives a zero-pack vehicle`() = repoTest { repo ->
        val v = vescOnlyVehicle()
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        // Three consecutive samples with the same cell count is what the
        // auto-fill demands before it trusts a reading. This is a VESC-derived
        // pack (no real BmsProtocol behind it — primaryPackProtocol is not a
        // BegodeProtocol here), so Task 3's decoder-confirmation route never
        // applies: stability alone, exactly as before Task 3.
        repeat(3) { i ->
            repo.emitActiveDataForTest(
                BmsData(
                    voltage = 78.2f + i,
                    cellVoltages = List(20) { 3.91f },
                    isConnected = true
                )
            )
            advanceUntilIdle()
        }

        assertEquals(
            1,
            vehicleRepo.upserts.size,
            "the collector must reach its write — 0 upserts means it died on packs.first()"
        )
    }

    // ----- 4. A battery-only vehicle plans exactly as before -----

    @Test
    fun `a battery-only vehicle plans exactly as before`() = repoTest { repo ->
        val v = singlePackVehicle(
            id = "v-ant", name = "Rig", iconKey = "battery",
            bmsType = BmsType.ANT_BMS, bmsAddress = ADDR,
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0L)
        )
        repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)

        val spec = repo.linkSpecsForTest().single()
        assertEquals(
            LinkSpec(
                address = ADDR,
                protocolKind = ProtocolKind.ANT,
                ownedPacks = listOf(OwnedSource(0)),
                ownedControllers = emptyList()
            ),
            spec
        )
        assertIs<AntBmsProtocol>(repo.createProtocolForTest(spec, v))
    }

    @Test
    fun `a Begode still owns both branches through one address`() = repoTest { repo ->
        val v = singlePackVehicle(
            id = "v-begode", name = "Wheel", iconKey = "unicycle",
            bmsType = BmsType.BEGODE, bmsAddress = ADDR,
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0L)
        )
        repo.installLinksForTest(v, v.primaryAddress, v.packs.first().bmsType)

        val spec = repo.linkSpecsForTest().single()
        assertEquals(ProtocolKind.BEGODE, spec.protocolKind)
        assertEquals(listOf(OwnedSource(0), OwnedSource(1)), spec.ownedPacks)
        assertIs<BegodeProtocol>(repo.createProtocolForTest(spec, v))
    }

    @Test
    fun `a BMS plus a VESC at two addresses raise one link each`() = repoTest { repo ->
        val v = Vehicle(
            id = "v-mixed",
            name = "Mixed",
            iconKey = "scooter",
            packs = listOf(Pack(index = 0, label = "Main", bmsType = BmsType.JK_BMS, bmsAddress = ADDR)),
            controllers = listOf(
                Controller(
                    index = 0, label = "ESC", controllerType = ControllerType.VESC,
                    address = CTRL_ADDR, providesDerivedBattery = false
                )
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Instant.fromEpochSeconds(0L)
        )
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val specs = repo.linkSpecsForTest()
        assertEquals(2, specs.size)
        val battery = specs.single { it.address == ADDR }
        assertEquals(ProtocolKind.JK, battery.protocolKind)
        assertEquals(listOf(OwnedSource(0)), battery.ownedPacks)
        assertTrue(battery.ownedControllers.isEmpty())

        val controller = specs.single { it.address == CTRL_ADDR }
        assertEquals(ProtocolKind.VESC, controller.protocolKind)
        assertEquals(listOf(OwnedSource(0)), controller.ownedControllers)
        assertTrue(controller.ownedPacks.isEmpty(), "no derived battery — no pack slot")
    }

    // ----- 5. Part C: the gateway link resolves to the multiplexer -----

    /**
     * The product owner's scooter: ONE head-unit address carrying two CAN uBoxes
     * and the ANT battery the head unit hosts. `planLinks` folds it into one
     * link (Part C task 3); this is where that link finally becomes a protocol.
     */
    private fun gatewayScooter(): Vehicle = Vehicle(
        id = "v-gateway",
        name = "Scooter",
        iconKey = "scooter",
        packs = listOf(
            Pack(index = 0, label = "ANT", bmsType = BmsType.VESC_BMS, bmsAddress = CTRL_ADDR)
        ),
        controllers = listOf(
            Controller(index = 0, label = "Front", controllerType = ControllerType.VESC,
                address = CTRL_ADDR, canId = 41),
            // Only the REAR uBox is given wheel geometry, so a factory that
            // handed every controller the first one's config would be visible.
            Controller(index = 1, label = "Rear", controllerType = ControllerType.VESC,
                address = CTRL_ADDR, canId = 42,
                motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254))
        ),
        topology = PackTopology.PARALLEL,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    /** `COMM_GET_VALUES` (4), 53-byte body — the per-unit frame a uBox answers with. */
    private fun valuesFrame(): ByteArray {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) {
            o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
            o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte()
        }
        o += 4; i16(400); i16(680); i32(-8250); i32(3000); i32(0); i32(0); i16(500)
        i32(12000); i16(782); i32(154000); i32(21000); i32(9800000); i32(1200000)
        i32(0); i32(0); o += 0
        return VescPacket.frame(o.toByteArray())
    }

    @Test
    fun `a gateway link builds the multiplexer instead of a plain VescProtocol`() = repoTest { repo ->
        val v = gatewayScooter()
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val spec = repo.linkSpecsForTest().single()
        assertEquals(ProtocolKind.VESC, spec.protocolKind, "the LINK still speaks one wire protocol")
        assertTrue(spec.isGatewayLink)

        val protocol = repo.createProtocolForTest(spec, v)
        val gateway = assertIs<VescGatewayProtocol>(protocol)
        assertEquals(2, gateway.controllerCount, "both uBoxes ride this one link")
        assertEquals(1, gateway.packCount, "and so does the hosted battery")
    }

    /**
     * The regression this task had to fix. `effectiveLinkSpecs` rebuilt every
     * owned pack as a bare `OwnedSource(index)`, silently dropping `canId` and
     * `kind`. Nothing noticed while no pack could carry either — but the hosted
     * VESC-BMS is exactly such a source, and without its tag
     * [LinkSpec.isGatewayLink] answers differently on the spec the session gets
     * than on the one `planLinkPacks` sized the pack list from.
     */
    @Test
    fun `effectiveLinkSpecs keeps the hosted battery's kind through the pack resize`() = repoTest { repo ->
        val v = gatewayScooter()
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        assertEquals(
            planLinks(v.packs, v.controllers).single().ownedPacks,
            repo.linkSpecsForTest().single().ownedPacks,
            "the resize must hand the session the SAME owned sources planLinks built"
        )
        assertEquals(
            listOf(OwnedSource(globalIndex = 0, canId = null, kind = ProtocolKind.VESC_BMS)),
            repo.linkSpecsForTest().single().ownedPacks
        )
    }

    /**
     * The case where the dropped tag was not merely cosmetic: a head unit that
     * hosts a battery but forwards nothing (one directly-attached controller,
     * no CAN ids). The hosted pack's `kind` is then the ONLY thing that makes
     * this a gateway — strip it and the link builds a plain [VescProtocol] that
     * never sends `BMS_GET_VALUES`, so the battery is simply never read, while
     * `planLinkPacks` had already sized the pack list from a protocol that said
     * it would be.
     */
    @Test
    fun `a head unit hosting a battery is a gateway even with no CAN ids`() = repoTest { repo ->
        val v = Vehicle(
            id = "v-hosted-only",
            name = "Scooter",
            iconKey = "scooter",
            packs = listOf(
                Pack(index = 0, label = "ANT", bmsType = BmsType.VESC_BMS, bmsAddress = CTRL_ADDR)
            ),
            controllers = listOf(
                Controller(index = 0, label = "ESC", controllerType = ControllerType.VESC,
                    address = CTRL_ADDR)
            ),
            topology = PackTopology.PARALLEL,
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Instant.fromEpochSeconds(0L)
        )
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val spec = repo.linkSpecsForTest().single()
        assertTrue(spec.isGatewayLink, "the hosted battery's kind is the only trigger here")
        val gateway = assertIs<VescGatewayProtocol>(repo.createProtocolForTest(spec, v))
        assertEquals(1, gateway.packCount, "and the hosted battery is what it polls")
        assertEquals(1, gateway.controllerCount)
    }

    /** A plain, non-gateway VESC link must be untouched by any of this. */
    @Test
    fun `a lone VESC link is not a gateway and still builds VescProtocol`() = repoTest { repo ->
        val v = vescOnlyVehicle(providesDerivedBattery = true)
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val spec = repo.linkSpecsForTest().single()
        assertFalse(
            spec.isGatewayLink,
            "one controller + its DERIVED pack slot is two owned sources but still one source of truth"
        )
        assertIs<VescProtocol>(repo.createProtocolForTest(spec, v))
    }

    /**
     * Each controller's own wheel geometry has to reach the multiplexer, or the
     * fallback speed a `GET_VALUES` frame derives is computed from the wrong
     * wheel — a wrong number that looks perfectly plausible.
     */
    @Test
    fun `every controller's own motor config reaches the gateway`() = repoTest { repo ->
        val v = gatewayScooter()
        repo.installLinksForTest(v, v.primaryAddress, type = null)
        val gateway = assertIs<VescGatewayProtocol>(
            repo.createProtocolForTest(repo.linkSpecsForTest().single(), v)
        )

        // Answer inline: this test is about geometry, not about the loop's
        // serialisation (VescGatewayProtocolTest owns that).
        //
        // The inner opcode has to be read where it actually is. This link owns
        // a HOSTED battery, whose BMS_GET_VALUES request is a bare one-byte
        // payload — indexing [2] on it threw, and `exchange`'s write-failure
        // catch swallowed the IOOBE, so the test was quietly driving the
        // failure path once per cycle instead of the silent-source one.
        val loop = launch {
            gateway.runPollLoop { frame ->
                val len = frame[1].toInt() and 0xFF
                val payload = frame.copyOfRange(2, 2 + len)
                val inner = if ((payload[0].toInt() and 0xFF) == VescCan.OPCODE_FORWARD_CAN) {
                    payload[2].toInt() and 0xFF
                } else {
                    payload[0].toInt() and 0xFF
                }
                if (inner == VescValues.OPCODE_GET_VALUES) gateway.onNotification(valuesFrame())
            }
        }
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(
            SpeedSource.NONE,
            assertNotNull(gateway.latestMotion(0)).speedSource,
            "the front uBox has no wheel configured, so its speed stays unknown"
        )
        val rear = assertNotNull(gateway.latestMotion(1))
        assertEquals(SpeedSource.DERIVED, rear.speedSource, "the rear uBox's own wheel is configured")
        assertTrue(rear.speedKmh > 30f, "and its geometry is the one that was used")
        loop.cancel()
    }

    // ----- 6. `I` Task 5: adding a CAN controller must not delete the battery -----

    /**
     * **The rider's own vehicle, in the two states their 2026-07-30 ride went
     * through.** They picked the head unit as a Controller — which is
     * [pickedControllerVehicle]'s controller shape: zero packs, one controller
     * backing a derived battery — saw no battery, and then added the main uBox
     * over CAN forwarding to fix it.
     *
     * [withCanController] is that add, in the form the composer produces it:
     * `VehicleDraft.derivedBatteryDefault` is `packs.isEmpty()`, so on a
     * pack-less vehicle BOTH controllers are saved with
     * `providesDerivedBattery = true`.
     */
    private fun headUnitVehicle(): Vehicle = pickedControllerVehicle(
        id = "v-head-unit",
        name = "Scooter",
        iconKey = "scooter",
        controllerType = ControllerType.VESC,
        address = CTRL_ADDR,
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0L)
    )

    private fun Vehicle.withCanController(): Vehicle = copy(
        controllers = controllers + Controller(
            index = 1,
            label = "uBox",
            controllerType = ControllerType.VESC,
            address = CTRL_ADDR,
            canId = U_BOX,
            motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254),
            providesDerivedBattery = true
        )
    )

    /** `COMM_BMS_GET_VALUES` (96) — what the head unit's ANT bridge answers with. */
    private fun bmsFrame(): ByteArray {
        val o = mutableListOf<Byte>()
        fun i16(v: Int) { o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte() }
        fun i32(v: Int) {
            o += ((v shr 24) and 0xFF).toByte(); o += ((v shr 16) and 0xFF).toByte()
            o += ((v shr 8) and 0xFF).toByte(); o += (v and 0xFF).toByte()
        }
        o += 96
        i32(75_500_000); i32(0); i32(-12_500_000); i32(-12_500_000)   // v_tot 75.5 V, i_in -12.5 A
        i32(18_200); i32(1_374_100)
        o += 2; i16(4_012); i16(3_998); o += 0; o += 1                // 2 cells + balancing
        o += 1; i16(2_350)                                            // 1 temperature sensor
        i16(3_120); i16(2_880); i16(0); i16(2_410)
        i16(812); i16(990)                                            // soc 0.812
        o += 10                                                       // can_id — NOT the 0xFF sentinel
        return VescPacket.frame(o.toByteArray())
    }

    /**
     * **The literal mechanism behind *"это ничем не помогло"*.** Adding the CAN
     * controller flips the link to [LinkSpec.isGatewayLink], and the gateway arm
     * of [controllerMotionProtocol] used to rebuild the multiplexer's pack list
     * from `LinkSpec.ownedPacks` alone — never answering `deriveBattery`. So
     * `packCount` went 1 → 0, `planLinkPacks` allocated no slot, and the rider's
     * attempt to FIX the missing battery is what removed its slot entirely.
     *
     * Both states are asserted in one test on purpose: "the gateway has a pack
     * slot" on its own would pass on a build where the picker's own vehicle had
     * none either, and the defect is the DIFFERENCE between the two.
     */
    @Test
    fun `adding a CAN controller must not delete the vehicle's battery`() = repoTest { repo ->
        val before = headUnitVehicle()
        repo.installLinksForTest(before, before.primaryAddress, type = null)
        val plainSpec = repo.linkSpecsForTest().single()
        assertFalse(plainSpec.isGatewayLink, "one controller, no CAN id: still a plain VESC link")
        assertEquals(
            listOf(OwnedSource(0)), plainSpec.ownedPacks,
            "the picker's own vehicle has a derived battery slot to begin with"
        )
        assertEquals(1, repo.createProtocolForTest(plainSpec, before).packCount)

        val after = before.withCanController()
        repo.installLinksForTest(after, after.primaryAddress, type = null)

        val spec = repo.linkSpecsForTest().single()
        assertTrue(spec.isGatewayLink, "the CAN controller is what makes this a gateway — that IS the mechanism")
        assertEquals(
            listOf(OwnedSource(0)), spec.ownedPacks,
            "and the derived slot must survive it: the rider added a controller, not removed a battery"
        )
        val gateway = assertIs<VescGatewayProtocol>(repo.createProtocolForTest(spec, after))
        assertEquals(2, gateway.controllerCount, "both the head unit and the uBox ride this link")
        // Exactly one, not merely "at least one": the protocol that SIZES the
        // pack list and the protocol the session speaks are built from two
        // different specs (planned, then effective), and a derived slot that is
        // appended on BOTH passes gives the session a phantom second battery it
        // then polls for every cycle. `>= 1` would not see it.
        assertEquals(
            1, gateway.packCount,
            "one battery: the slot the repository used to bail out on at 0, and no phantom beside it"
        )
    }

    /**
     * The other half: opcode 96 was **never sent at all** on that vehicle,
     * because [VescGatewayProtocol]'s only sender is built per owned pack and a
     * picker-built vehicle owns none. The head unit's ANT bridge was working and
     * emulating correctly the whole time — nothing ever asked it anything.
     *
     * Driven end to end from the stored vehicle: the link plan, the production
     * protocol factory, the real poll loop, and the real routing helper into the
     * link's own pack funnel. The assertion that the opcode reached the wire and
     * the assertion that its reply reached the Battery screen are both here,
     * because either alone is satisfiable without the other.
     */
    @Test
    fun `a gateway that hosts a BMS is finally asked for it`() = repoTest { repo ->
        val v = headUnitVehicle().withCanController()
        val funnels = repo.installLinksForTest(v, v.primaryAddress, type = null)
        val spec = repo.linkSpecsForTest().single()
        val gateway = assertIs<VescGatewayProtocol>(repo.createProtocolForTest(spec, v))

        val sent = mutableListOf<Pair<Int?, Int>>()
        val loop = launch {
            gateway.runPollLoop { frame ->
                val len = frame[1].toInt() and 0xFF
                val payload = frame.copyOfRange(2, 2 + len)
                val forwarded = (payload[0].toInt() and 0xFF) == VescCan.OPCODE_FORWARD_CAN
                val inner = if (forwarded) payload[2].toInt() and 0xFF else payload[0].toInt() and 0xFF
                sent += (if (forwarded) payload[1].toInt() and 0xFF else null) to inner
                // The head unit answers for its BMS and for nothing else — its
                // own firmware falls through to `default: break;` on both
                // GET_VALUES opcodes, and the uBox is not scripted here at all,
                // so nothing but opcode 96 can produce the battery below.
                if (inner == VescBmsValues.OPCODE_BMS_GET_VALUES && !forwarded) {
                    gateway.onNotification(bmsFrame())
                }
            }
        }
        advanceTimeBy(5_000)
        runCurrent()
        // Cancelled BEFORE the advanceUntilIdle below: the poll loop delays
        // forever, so idling a scheduler it is still running on advances virtual
        // time without bound and wedges the build instead of failing it.
        loop.cancel()

        assertTrue(
            sent.contains(null to VescBmsValues.OPCODE_BMS_GET_VALUES),
            "the head unit hosts the BMS, so the ask is UNWRAPPED — and it has to be made: $sent"
        )
        val battery = assertNotNull(
            gateway.latestData(0),
            "…and its reply is the vehicle's battery"
        )
        assertEquals(75.5f, battery.voltage, absoluteTolerance = 0.01f)

        // Through the production routing helper into the link's own funnel, so
        // the latent slot materialises exactly as it does on a real connection.
        routePackSamples(gateway, PackSampleGate(gateway.packCount)) { local, data, sections ->
            funnels.single()(local, data, sections)
        }
        advanceUntilIdle()

        val snap = repo.activeVehicleData.value
        assertEquals(1, snap.packs.size, "the derived slot materialises on its first real reading")
        assertEquals(75.5f, snap.packs.single().data.voltage, absoluteTolerance = 0.01f)
        assertTrue(snap.packs.single().isOnline)
    }

    /**
     * **The two-pass fixed point**, asserted directly on the one function that
     * answers it, because the two passes are built from two different specs and
     * an answer that grows between them gives the session a phantom battery it
     * then polls for every cycle.
     *
     * Pass 1 (`planLinkPacks`) asks it of the PLANNED spec, whose owned packs
     * are the profile's stored ones, purely to size the pack list. Pass 2 asks
     * it of the EFFECTIVE spec, by which time the derived slot exists and is
     * numbered — and it must be RECOGNISED there, not counted a second time.
     *
     * Both specs are the rider's own, taken from the real planner and the real
     * repository rather than hand-built, because the recognition rule reads
     * `OwnedSource.canId`/`kind` and those tags are exactly what
     * `effectiveLinkSpecs` is responsible for carrying through the resize.
     *
     * **`derived` on the pass-2 slot is what the fallback hangs off**, and it is
     * asserted here rather than end-to-end for a stated reason: the warm-up and
     * staleness rules are measured in seconds on the protocol's own clock, which
     * `controllerMotionProtocol` does not thread through, so a
     * repository-built protocol runs them against the REAL clock and no
     * virtual-time test can spend them. `VescGatewayProtocolTest` owns the
     * behaviour; this owns the wiring that reaches it.
     */
    @Test
    fun `the derived slot is recognised again on the spec the session is built from`() = repoTest { repo ->
        val v = headUnitVehicle().withCanController()
        val planned = planLinks(v.packs, v.controllers).single()
        assertEquals(
            listOf(GatewaySource(globalIndex = -1, derived = true)),
            vescGatewayPacks(planned, deriveBattery = true),
            "pass 1 has nothing to number the slot with yet, but must still COUNT it"
        )
        assertEquals(
            emptyList(),
            vescGatewayPacks(planned, deriveBattery = false),
            "and a link whose battery a real BMS covers gets no slot at all"
        )

        repo.installLinksForTest(v, v.primaryAddress, type = null)
        assertEquals(
            listOf(GatewaySource(globalIndex = 0, derived = true)),
            vescGatewayPacks(repo.linkSpecsForTest().single(), deriveBattery = true),
            "pass 2 must recognise the slot it asked for — as DERIVED, which is what gives it a " +
                "fallback at all — and must not append a second one beside it"
        )
    }

    /**
     * The one shape where [vescGatewayPacks] deliberately does not honour
     * `deriveBattery`: a gateway that already hosts a battery. A second slot
     * there would send a byte-identical `COMM_BMS_GET_VALUES` to the same node —
     * and, being an `expandedTo` slot beside a stored pack rather than a derived
     * one, `maybePersistPacks` would write it into the profile and the next
     * connect would expand again. Reachable only by a rider forcing the toggle
     * on; pinned so the choice is visible rather than assumed.
     */
    @Test
    fun `a gateway that already hosts a battery does not grow a second slot`() = repoTest { repo ->
        val v = gatewayScooter().let { g ->
            g.copy(controllers = g.controllers.map { it.copy(providesDerivedBattery = true) })
        }
        repo.installLinksForTest(v, v.primaryAddress, type = null)

        val spec = repo.linkSpecsForTest().single()
        assertEquals(
            listOf(OwnedSource(globalIndex = 0, canId = null, kind = ProtocolKind.VESC_BMS)),
            spec.ownedPacks,
            "the hosted ANT battery, and nothing beside it"
        )
        assertEquals(1, assertIs<VescGatewayProtocol>(repo.createProtocolForTest(spec, v)).packCount)
    }

    private companion object {
        const val ADDR = "AA:BB:CC:DD:EE:01"
        const val CTRL_ADDR = "AA:BB:CC:DD:EE:0C"

        /** The CAN id of the rider's uBox, behind the head unit. */
        const val U_BOX = 24
    }
}
