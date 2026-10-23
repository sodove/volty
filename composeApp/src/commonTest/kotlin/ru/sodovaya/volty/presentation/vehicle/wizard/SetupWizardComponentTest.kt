package ru.sodovaya.volty.presentation.vehicle.wizard

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.navigate
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.CanDiscovery
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.domain.stats.PackAggregator
import ru.sodovaya.volty.presentation.picker.ScannedAdd
import ru.sodovaya.volty.presentation.root.Config
import ru.sodovaya.volty.presentation.root.CreateVehicleEntry
import ru.sodovaya.volty.presentation.root.RootComponent
import ru.sodovaya.volty.presentation.root.configForCreateVehicle
import ru.sodovaya.volty.presentation.root.guardComposerDestruction
import ru.sodovaya.volty.presentation.root.leaveSetupWizard
import ru.sodovaya.volty.presentation.root.stackAfterGoTo
import ru.sodovaya.volty.presentation.root.stackAfterWizardSkip
import ru.sodovaya.volty.presentation.root.stackAfterWizardSkip
import ru.sodovaya.volty.presentation.vehicle.CanCandidateKind
import ru.sodovaya.volty.presentation.vehicle.CanScanState
import ru.sodovaya.volty.presentation.vehicle.DerivedBatteryChoice
import ru.sodovaya.volty.presentation.vehicle.VehicleDraft
import ru.sodovaya.volty.presentation.vehicle.addController
import ru.sodovaya.volty.presentation.vehicle.addPack
import ru.sodovaya.volty.presentation.vehicle.updateController
import ru.sodovaya.volty.presentation.vehicle.persistableValues
import ru.sodovaya.volty.presentation.vehicle.persistableValues
import ru.sodovaya.volty.presentation.vehicle.validate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class, DelicateDecomposeApi::class)
class SetupWizardComponentTest {

    private val createdAt = Instant.parse("2026-08-02T10:00:00Z")

    private fun device(
        address: String,
        name: String,
        bmsType: BmsType? = null,
        controllerType: ControllerType? = null,
        rssi: Int = -54
    ) = DiscoveredDevice(
        address = address,
        name = name,
        rssi = rssi,
        bmsType = bmsType,
        controllerType = controllerType
    )

    private fun component(
        saved: MutableList<Vehicle> = mutableListOf(),
        scan: Flow<DiscoveredDevice> = emptyFlow(),
        initialDraft: VehicleDraft = VehicleDraft(),
        initialName: String = "",
        persist: suspend (Vehicle) -> Unit = { saved += it },
        cancelled: () -> Unit = {},
        canDiscovery: CanDiscovery? = null,
        liveAddresses: Flow<Set<String>> = flowOf(emptySet()),
        skipped: MutableList<WizardHandoff> = mutableListOf()
    ) = DefaultSetupWizardComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        initialDraft = initialDraft,
        initialName = initialName,
        scanAll = { scan },
        canDiscovery = canDiscovery,
        liveAddresses = liveAddresses,
        saveVehicle = persist,
        newVehicleId = { "v-new" },
        now = { createdAt },
        onSkipToEditor = { skipped += it },
        onCancelled = cancelled,
        onShowVehicleList = {},
        onConnected = {}
    )

    private class FakeCanDiscovery(
        private val result: Result<List<Int>> = Result.success(emptyList())
    ) : CanDiscovery {
        val calls = mutableListOf<String>()

        override suspend fun discoverCanIds(address: String): Result<List<Int>> {
            calls += address
            return result
        }
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the four decisions are ordered and review is unreachable without a source`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component()
        assertIs<SetupWizardComponent.Child.WhatAreWeBuilding>(c.stack.value.active.instance)

        val what = (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding).component
        what.onNext()
        assertIs<SetupWizardComponent.Child.Controllers>(c.stack.value.active.instance)

        val controllers = (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onNoController()
        assertIs<SetupWizardComponent.Child.Battery>(c.stack.value.active.instance)

        val battery = (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        battery.onNoBattery()
        assertIs<SetupWizardComponent.Child.Battery>(c.stack.value.active.instance)
        assertTrue(c.state.value.advanceBlocked, "an empty draft cannot reach a Save screen")

        battery.onAddScannedDevice(
            device("AN:01", "ANT", bmsType = BmsType.ANT_BMS),
            ScannedAdd.BATTERY
        )
        battery.onNext()
        assertIs<SetupWizardComponent.Child.Review>(c.stack.value.active.instance)
    }

    @Test
    fun `archetypes set presentation defaults`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val expected = mapOf(
            VehicleArchetype.WHEEL to ("unicycle" to SecondaryGauge.DUTY),
            VehicleArchetype.SCOOTER to ("scooter" to SecondaryGauge.BATTERY),
            VehicleArchetype.BICYCLE to ("ebike" to SecondaryGauge.BATTERY),
            VehicleArchetype.CUSTOM to ("generic" to SecondaryGauge.DUTY)
        )

        expected.forEach { (archetype, defaults) ->
            val c = component()
            val what =
                (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding).component
            what.onArchetypeSelected(archetype)

            assertEquals(archetype, c.state.value.archetype)
            assertEquals(defaults.first, c.state.value.iconKey, "$archetype icon")
            assertEquals(defaults.second, c.state.value.secondaryGauge, "$archetype gauge")
        }
    }

    @Test
    fun `each scan stage offers only the one action its question asks for`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wheel = device(
            address = "BG:01",
            name = "Begode",
            bmsType = BmsType.BEGODE,
            controllerType = ControllerType.BEGODE
        )
        val c = component(scan = flowOf(wheel))
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        advanceUntilIdle()

        val controllers =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        assertEquals(listOf(wheel), controllers.scanRows.map { it.device })
        assertEquals(listOf(ScannedAdd.CONTROLLER), controllers.scanRows.single().additions)

        controllers.onNoController()
        val batteries =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        batteries.onChooseExit(BatteryExit.DEVICE_IS_BOTH)
        advanceUntilIdle()
        assertEquals(listOf(wheel), batteries.scanRows.map { it.device })
        assertEquals(listOf(ScannedAdd.WHEEL), batteries.scanRows.single().additions)

        val row = batteries.scanRows.single()
        batteries.onAddScannedDevice(row.device, row.additions.single())
        assertEquals(listOf("BG:01"), c.state.value.draft.controllers.map { it.address })
        assertEquals(listOf("BG:01"), c.state.value.draft.packs.map { it.address })
    }

    @Test
    fun `a Leaperkim wheel scan creates both battery branches`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wheel = device(
            address = "LK:01",
            name = "Veteran",
            bmsType = BmsType.LEAPERKIM
        )
        val c = component(scan = flowOf(wheel))
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        advanceUntilIdle()

        val controllers =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onNoController()
        val batteries =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        batteries.onAddScannedDevice(wheel, ScannedAdd.WHEEL)

        assertEquals(listOf(ControllerType.VETERAN), c.state.value.draft.controllers.map { it.controllerType })
        assertEquals(2, c.state.value.draft.packs.size)
        assertTrue(c.state.value.draft.packs.all { it.bmsType == BmsType.LEAPERKIM })
    }

    @Test
    fun `back and forward keep the exact parent owned draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component()
        val what = (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding).component
        what.onNext()
        val controllers = (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onAddScannedDevice(
            device("VE:01", "VESC", controllerType = ControllerType.VESC),
            ScannedAdd.CONTROLLER
        )
        val exactDraft = c.state.value.draft

        controllers.onNext()
        val battery = (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        battery.onBack()
        assertEquals(exactDraft, c.state.value.draft)

        val returnedControllers =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        returnedControllers.onNext()
        assertEquals(exactDraft, c.state.value.draft)
        assertIs<SetupWizardComponent.Child.Battery>(c.stack.value.active.instance)
    }

    @Test
    fun `leaving controller search stops its live scan`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component(scan = flowOf(device("VE:01", "VESC", controllerType = ControllerType.VESC)))
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding).component.onNext()
        advanceUntilIdle()
        assertTrue(c.state.value.scanning)
        assertEquals(1, c.state.value.scannedDevices.size)

        (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component.onBack()

        assertIs<SetupWizardComponent.Child.WhatAreWeBuilding>(c.stack.value.active.instance)
        assertFalse(c.state.value.scanning)
        assertEquals(emptyList(), c.state.value.scannedDevices)
    }

    @Test
    fun `skipping battery after adding a controller leaves a valid draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component()
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding).component.onNext()
        val controllers = (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onAddScannedDevice(
            device("VE:01", "VESC", controllerType = ControllerType.VESC),
            ScannedAdd.CONTROLLER
        )
        controllers.onNext()

        (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component.onNoBattery()

        assertIs<SetupWizardComponent.Child.Review>(c.stack.value.active.instance)
        assertEquals(1, c.state.value.draft.controllers.size)
        assertEquals(emptyList(), c.state.value.draft.packs)
        assertFalse(validate(c.state.value.draft).any { it.blocking })
    }

    @Test
    fun `no battery explicitly disables derived battery and removes physical packs`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val initial = VehicleDraft()
            .addController(ControllerType.VESC, "VE:01", "VESC")
            .addPack(BmsType.ANT_BMS, "AN:01", "ANT")
        val c = component(initialDraft = initial)
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers)
            .component.onNext()

        (c.stack.value.active.instance as SetupWizardComponent.Child.Battery)
            .component.onNoBattery()

        assertIs<SetupWizardComponent.Child.Review>(c.stack.value.active.instance)
        assertEquals(emptyList(), c.state.value.draft.packs)
        assertFalse(
            c.state.value.draft.toControllers().single().providesDerivedBattery,
            "no battery must be structurally different from derive-from-controller"
        )
    }

    @Test
    fun `wizard source configuration can complete ANT and gateway uBox topology`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val can = FakeCanDiscovery(Result.success(listOf(21)))
        val live = MutableStateFlow(setOf("HU:01"))
        val c = component(canDiscovery = can, liveAddresses = live)
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        advanceUntilIdle()
        val controllers =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component

        // The detector is only a hint: correct the head unit before treating it
        // as the VESC gateway whose live link can answer PING_CAN.
        controllers.onAddScannedDevice(
            device("HU:01", "nyxdash", controllerType = ControllerType.KELLY),
            ScannedAdd.CONTROLLER
        )
        val gatewayKey = c.state.value.draft.controllers.single().key
        controllers.onControllerTypeChanged(gatewayKey, ControllerType.VESC)
        controllers.onControllerCanIdChanged(gatewayKey, 7)
        assertEquals(7, c.state.value.draft.controllers.single().canId)
        controllers.onControllerCanIdChanged(gatewayKey, null)
        controllers.onControllerPolePairsChanged(gatewayKey, 15)
        controllers.onControllerWheelDiameterChanged(gatewayKey, 600)
        controllers.onControllerGearRatioChanged(gatewayKey, 1f)

        controllers.onDiscoverCanDevices()
        advanceUntilIdle()

        assertEquals(listOf("HU:01"), can.calls)
        assertIs<CanScanState.Found>(c.state.value.canScan)
        val uBox = c.state.value.canCandidates.single {
            it.kind == CanCandidateKind.NODE && it.canId == 21
        }
        controllers.onAddCanCandidate(uBox, asBattery = false)
        val storedUBox = c.state.value.draft.controllers.single { it.canId == 21 }
        assertEquals(15, storedUBox.motor.polePairs)
        assertEquals(600, storedUBox.motor.wheelDiameterMm)
        assertEquals(1f, storedUBox.motor.gearRatio)

        controllers.onNext()
        val battery =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        battery.onUseSeparateBms(device("AN:01", "ANT", bmsType = BmsType.JK_BMS))
        val antKey = c.state.value.draft.packs.single().key
        battery.onPackTypeChanged(antKey, BmsType.ANT_BMS)

        // Mistaken rows can be removed even while the wizard is still being
        // assembled; they are not persisted merely because they were tapped.
        battery.onUseSeparateBms(device("WRONG:01", "Wrong", bmsType = BmsType.JK_BMS))
        val mistakenKey = c.state.value.draft.packs.single { it.address == "WRONG:01" }.key
        battery.onRemovePack(mistakenKey)

        assertEquals(BmsType.ANT_BMS, c.state.value.draft.packs.single().bmsType)
        assertEquals("AN:01", c.state.value.draft.packs.single().address)
        assertEquals(listOf(null, 21), c.state.value.draft.controllers.map { it.canId })
    }

    @Test
    fun `the wizard can remove its last mistaken source and return to an empty draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component()
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        val controllers =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onAddScannedDevice(
            device("WRONG:01", "Wrong", controllerType = ControllerType.KELLY),
            ScannedAdd.CONTROLLER
        )
        val key = c.state.value.draft.controllers.single().key

        controllers.onRemoveController(key)

        assertEquals(0, c.state.value.draft.sourceCount)

        controllers.onAddScannedDevice(
            device("WRONG:02", "Wrong pack", bmsType = BmsType.JK_BMS),
            ScannedAdd.BATTERY
        )
        val packKey = c.state.value.draft.packs.single().key
        controllers.onRemovePack(packKey)

        assertEquals(0, c.state.value.draft.sourceCount)
    }

    @Test
    fun `scooter is only a hint and battery stage still accepts a second battery`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component()
        val what = (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding).component
        what.onArchetypeSelected(VehicleArchetype.SCOOTER)
        what.onNext()
        (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component.onNoController()
        val battery = (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component

        battery.onAddScannedDevice(
            device("AN:01", "Front", bmsType = BmsType.ANT_BMS),
            ScannedAdd.BATTERY
        )
        battery.onAddScannedDevice(
            device("AN:02", "Rear", bmsType = BmsType.ANT_BMS),
            ScannedAdd.BATTERY
        )

        assertEquals(VehicleArchetype.SCOOTER, c.state.value.archetype)
        assertEquals(listOf("AN:01", "AN:02"), c.state.value.draft.packs.map { it.address })
    }

    @Test
    fun `battery wiring is asked only after the draft has a second pack`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component()
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        val controllers =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onAddScannedDevice(
            device("VE:01", "VESC", controllerType = ControllerType.VESC),
            ScannedAdd.CONTROLLER
        )
        controllers.onNext()
        val battery =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component

        assertFalse(c.state.value.showTopologyChoice, "a controller is not a battery pack")
        battery.onUseSeparateBms(device("AN:01", "Main", bmsType = BmsType.ANT_BMS))
        assertFalse(c.state.value.showTopologyChoice, "one pack has no wiring choice")

        battery.onUseSeparateBms(device("AN:02", "Second", bmsType = BmsType.ANT_BMS))
        assertTrue(c.state.value.showTopologyChoice, "two packs must ask how they are wired")
    }

    @Test
    fun `two 72 V packs chosen as series leave the wizard as a 144 V vehicle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = mutableListOf<Vehicle>()
        val c = component(saved = saved)
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        val controllers =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onNoController()
        val battery =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        battery.onUseSeparateBms(device("AN:01", "Upper", bmsType = BmsType.ANT_BMS))
        battery.onUseSeparateBms(device("AN:02", "Lower", bmsType = BmsType.ANT_BMS))
        battery.onTopologyChanged(PackTopology.SERIES)
        battery.onNext()
        val review = (c.stack.value.active.instance as SetupWizardComponent.Child.Review).component
        review.onNameChanged("Series bike")
        review.onSave()
        advanceUntilIdle()

        val vehicle = saved.single()
        val livePacks = vehicle.packs.map { pack ->
            PackState(
                pack = pack,
                data = BmsData(voltage = 72f, isConnected = true),
                isOnline = true
            )
        }
        assertEquals(
            144f,
            PackAggregator.aggregate(livePacks, vehicle.topology).voltage,
            absoluteTolerance = 0.001f
        )
    }

    @Test
    fun `a separate BMS exit creates a second BLE link`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component()
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        val controllers =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onAddScannedDevice(
            device("VE:01", "VESC", controllerType = ControllerType.VESC),
            ScannedAdd.CONTROLLER
        )
        controllers.onNext()

        val battery =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        battery.onUseSeparateBms(device("AN:02", "ANT", bmsType = BmsType.ANT_BMS))

        assertEquals(listOf("VE:01", "AN:02"), c.state.value.draft.linkAddresses)
        assertEquals(listOf("VE:01"), c.state.value.draft.controllers.map { it.address })
        assertEquals(listOf("AN:02"), c.state.value.draft.packs.map { it.address })
    }

    @Test
    fun `controller-derived exit keeps one link and persists the derived relationship`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val added = VehicleDraft().addController(ControllerType.VESC, "VE:01", "VESC")
        val controllerKey = added.controllers.single().key
        val explicitlyDisabled = added.updateController(controllerKey) {
            it.copy(derivedBattery = DerivedBatteryChoice.OFF)
        }
        val c = component(initialDraft = explicitlyDisabled)
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        val controllers =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onNext()

        val battery =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        assertTrue(battery.canUseControllerBattery)
        battery.onUseControllerBattery()

        assertIs<SetupWizardComponent.Child.Review>(c.stack.value.active.instance)
        assertEquals(listOf("VE:01"), c.state.value.draft.linkAddresses)
        assertEquals(emptyList(), c.state.value.draft.packs)
        assertTrue(c.state.value.draft.toControllers().single().providesDerivedBattery)
    }

    @Test
    fun `controller-derived exit is unreachable without a controller`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component()
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers)
            .component.onNoController()

        val battery =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        assertFalse(battery.canUseControllerBattery)
        battery.onUseControllerBattery()

        assertIs<SetupWizardComponent.Child.Battery>(c.stack.value.active.instance)
        assertEquals(0, c.state.value.draft.sourceCount)
        assertFalse(c.state.value.advanceBlocked, "an unavailable action must be a no-op")
    }

    @Test
    fun `device-is-both exit creates one address in both roles`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wheel = device(
            address = "BG:01",
            name = "Begode",
            bmsType = BmsType.BEGODE,
            controllerType = ControllerType.BEGODE
        )
        val c = component()
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers)
            .component.onNoController()

        val battery =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        battery.onUseDeviceAsBoth(wheel)

        assertEquals(listOf("BG:01"), c.state.value.draft.linkAddresses)
        assertEquals(listOf("BG:01"), c.state.value.draft.controllers.map { it.address })
        assertEquals(listOf("BG:01"), c.state.value.draft.packs.map { it.address })
    }

    @Test
    fun `device-is-both exit fills the missing role when its controller was already chosen`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val wheel = device(
            address = "BG:01",
            name = "Begode",
            bmsType = BmsType.BEGODE,
            controllerType = ControllerType.BEGODE
        )
        val c = component()
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        val controllers =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onAddScannedDevice(wheel, ScannedAdd.CONTROLLER)
        controllers.onNext()

        (c.stack.value.active.instance as SetupWizardComponent.Child.Battery)
            .component.onUseDeviceAsBoth(wheel)

        assertEquals(listOf("BG:01"), c.state.value.draft.linkAddresses)
        assertEquals(1, c.state.value.draft.controllers.size)
        assertEquals(1, c.state.value.draft.packs.size)
    }

    @Test
    fun `controller stage starts the shared scan and no vehicle is written before Save`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = mutableListOf<Vehicle>()
        val controller = device("VE:01", "VESC BLE UART", controllerType = ControllerType.VESC)
        val battery = device("AN:02", "ANT", bmsType = BmsType.ANT_BMS)
        val c = component(saved = saved, scan = flowOf(controller, battery))

        val what = (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding).component
        what.onArchetypeSelected(VehicleArchetype.BICYCLE)
        what.onNext()
        advanceUntilIdle()
        assertEquals(listOf(controller, battery), c.state.value.scannedDevices)
        assertEquals(emptyList(), saved)

        val controllers = (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onAddScannedDevice(c.state.value.scannedDevices[0], ScannedAdd.CONTROLLER)
        controllers.onNext()
        val batteries = (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        batteries.onChooseExit(BatteryExit.OWN_BMS)
        advanceUntilIdle()
        batteries.onAddScannedDevice(c.state.value.scannedDevices[1], ScannedAdd.BATTERY)
        batteries.onNext()
        assertEquals(emptyList(), saved)

        val review = (c.stack.value.active.instance as SetupWizardComponent.Child.Review).component
        review.onNameChanged("Bike")
        review.onSave()
        advanceUntilIdle()

        assertEquals(1, saved.size)
        assertEquals(listOf("VE:01"), saved.single().controllers.map { it.address })
        assertEquals(listOf("AN:02"), saved.single().packs.map { it.bmsAddress })
        assertIs<SetupWizardComponent.Child.Done>(c.stack.value.active.instance)
        assertFalse(c.hasUnsavedDraft, "Save establishes the clean baseline before root navigation")
    }

    @Test
    fun `a suspended Save freezes navigation and editing until Done owns the saved draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val saved = mutableListOf<Vehicle>()
        val c = component(
            saved = saved,
            persist = { vehicle ->
                saveStarted.complete(Unit)
                releaseSave.await()
                saved += vehicle
            }
        )
        (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        val controllers =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
        controllers.onAddScannedDevice(
            device("VE:01", "VESC", controllerType = ControllerType.VESC),
            ScannedAdd.CONTROLLER
        )
        controllers.onNext()
        (c.stack.value.active.instance as SetupWizardComponent.Child.Battery)
            .component.onNoBattery()
        val review =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Review).component
        review.onNameChanged("Bike A")

        review.onSave()
        runCurrent()
        assertTrue(saveStarted.isCompleted, "the fake persistence boundary must be suspended")
        assertTrue(c.state.value.saving)
        assertFalse(c.state.value.navigationEnabled)

        review.onBack()
        review.onNameChanged("Bike B")
        assertIs<SetupWizardComponent.Child.Review>(c.stack.value.active.instance)
        assertEquals("Bike A", c.state.value.name)

        releaseSave.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("Bike A"), saved.map { it.name })
        assertIs<SetupWizardComponent.Child.Done>(c.stack.value.active.instance)
        assertFalse(c.state.value.isDirty)
    }

    @Test
    fun `the established root guard reveals a dirty wizard and dismissal restores its draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val c = component()
        val what = (c.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding).component
        what.onArchetypeSelected(VehicleArchetype.SCOOTER)
        what.onNext()
        (c.stack.value.active.instance as SetupWizardComponent.Child.Controllers).component
            .onAddScannedDevice(
                device("VE:42", "Unsaved controller", controllerType = ControllerType.VESC),
                ScannedAdd.CONTROLLER
            )
        val exactDraft = c.state.value.draft
        assertEquals("VE:42", exactDraft.controllers.single().address)
        val revealed = mutableListOf<Int>()
        var destroyed = false

        guardComposerDestruction(
            children = listOf(RootComponent.Child.SetupWizard(c), RootComponent.Child.Loading),
            revealComposerAt = { revealed += it },
            destroyStack = { destroyed = true }
        )

        assertEquals(listOf(0), revealed)
        assertTrue(c.state.value.discardPrompt)
        assertFalse(destroyed)
        c.onDiscardDismissed()
        assertEquals(exactDraft, c.state.value.draft)
        assertFalse(c.state.value.discardPrompt)
    }

    @Test
    fun `leaving setup and returning retains the same wizard and exact draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = DefaultComponentContext(LifecycleRegistry())
        val navigation = StackNavigation<Config>()
        val setup = Config.SetupWizard()
        var wizardCreations = 0
        val stack: Value<ChildStack<Config, Any>> = context.childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = setup,
            handleBackButton = false,
            childFactory = { config, childContext ->
                if (config is Config.SetupWizard) {
                    DefaultSetupWizardComponent(
                        componentContext = childContext,
                        scanAll = { emptyFlow() },
                        saveVehicle = {},
                        onCancelled = { leaveSetupWizard(navigation, Config.Dashboard) },
                        onShowVehicleList = {},
                        onConnected = {}
                    ).also { wizardCreations += 1 }
                } else {
                    config
                }
            }
        )
        val wizard = stack.value.active.instance as DefaultSetupWizardComponent
        (wizard.stack.value.active.instance as SetupWizardComponent.Child.WhatAreWeBuilding)
            .component.onNext()
        (wizard.stack.value.active.instance as SetupWizardComponent.Child.Controllers)
            .component.onAddScannedDevice(
                device("VE:42", "Unsaved controller", controllerType = ControllerType.VESC),
                ScannedAdd.CONTROLLER
            )
        val exactDraft = wizard.state.value.draft

        wizard.onCancel()
        assertTrue(wizard.state.value.discardPrompt)
        wizard.onDiscardConfirmed()

        assertEquals(Config.Dashboard, stack.value.active.configuration)
        assertEquals(listOf(setup, Config.Dashboard), stack.value.items.map { it.configuration })
        navigation.navigate { configurations -> stackAfterGoTo(configurations, setup) }

        val returned = stack.value.active.instance as DefaultSetupWizardComponent
        assertSame(wizard, returned, "the root must relocate the live wizard instead of rebuilding it")
        assertEquals(1, wizardCreations)
        assertEquals(exactDraft, returned.state.value.draft)
    }

    @Test
    fun `all three create entry points route to the setup wizard`() {
        val routes = CreateVehicleEntry.entries.associateWith(::configForCreateVehicle)

        assertEquals(
            mapOf(
                CreateVehicleEntry.RIDE to Config.SetupWizard(prefillFromActiveConnection = true),
                CreateVehicleEntry.DASHBOARD to Config.SetupWizard(prefillFromActiveConnection = true),
                CreateVehicleEntry.SETTINGS to Config.SetupWizard(prefillFromActiveConnection = true)
            ),
            routes
        )
    }
    // ---- Task 5: one decision per step, battery exits first ----

    private val vescScan = device("VE:01", "VESC BLE UART", controllerType = ControllerType.VESC, rssi = -54)
    private val antScan = device("AN:02", "ANT-BLE", bmsType = BmsType.ANT_BMS, rssi = -67)
    private val wheelScan = device(
        "BG:03", "Gotway", bmsType = BmsType.BEGODE, controllerType = ControllerType.BEGODE, rssi = -72
    )
    private val mysteryScan = device("XX:04", "", rssi = -80)

    private fun active(c: DefaultSetupWizardComponent) = c.stack.value.active.instance

    @Test
    fun `aVescScooterWithAnAntBatteryIsBuiltInTheWizard`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = mutableListOf<Vehicle>()
        val skipped = mutableListOf<WizardHandoff>()
        val c = component(saved = saved, skipped = skipped, scan = flowOf(vescScan, antScan))

        val what = (active(c) as SetupWizardComponent.Child.WhatAreWeBuilding).component
        what.onArchetypeSelected(VehicleArchetype.SCOOTER)
        what.onNext()
        advanceUntilIdle()

        val controllers = (active(c) as SetupWizardComponent.Child.Controllers).component
        val vescRow = controllers.scanRows.first { it.device.address == "VE:01" }
        controllers.onAddScannedDevice(vescRow.device, vescRow.additions.single())
        controllers.onNext()

        val battery = (active(c) as SetupWizardComponent.Child.Battery).component
        battery.onChooseExit(BatteryExit.OWN_BMS)
        advanceUntilIdle()
        val antRow = battery.scanRows.single()
        assertEquals("AN:02", antRow.device.address, "the controller itself is not a battery candidate")
        battery.onAddScannedDevice(antRow.device, antRow.additions.single())
        battery.onNext()

        val review = (active(c) as SetupWizardComponent.Child.Review).component
        review.onNameChanged("Scooter")
        review.onSave()
        advanceUntilIdle()

        val vehicle = saved.single()
        assertEquals("Scooter", vehicle.name)
        assertEquals(listOf("VE:01"), vehicle.controllers.map { it.address })
        assertEquals(ControllerType.VESC, vehicle.controllers.single().controllerType)
        assertEquals(listOf("AN:02"), vehicle.packs.map { it.bmsAddress })
        assertEquals(BmsType.ANT_BMS, vehicle.packs.single().bmsType)
        assertEquals(emptyList(), skipped, "the editor was never opened")
    }

    @Test
    fun `theBatteryScanShowsNothingUntilAnExitIsChosen`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var scanStarts = 0
        val c = component(scan = kotlinx.coroutines.flow.flow {
            scanStarts++
            emit(antScan)
        })
        (active(c) as SetupWizardComponent.Child.WhatAreWeBuilding).component.onNext()
        advanceUntilIdle()
        assertEquals(1, scanStarts)

        (active(c) as SetupWizardComponent.Child.Controllers).component.onNoController()
        advanceUntilIdle()
        val battery = (active(c) as SetupWizardComponent.Child.Battery).component

        assertEquals(null, c.state.value.batteryExit)
        assertEquals(emptyList(), battery.scanRows)
        assertFalse(c.state.value.scanning, "no exit chosen: nothing is searching")
        assertEquals(emptyList(), c.state.value.scannedDevices)

        battery.onChooseExit(BatteryExit.OWN_BMS)
        advanceUntilIdle()
        assertEquals(2, scanStarts, "choosing a searching exit starts the search")
        assertEquals(listOf(antScan), battery.scanRows.map { it.device })
    }

    @Test
    fun `ownBmsHidesControllerOnlyDevices`() {
        val rows = listOf(vescScan, antScan, wheelScan, mysteryScan).map {
            SetupWizardComponent.ScanRow(it, ScannedAdd.entries)
        }

        val own = batteryScanRows(rows, BatteryExit.OWN_BMS)
        assertEquals(listOf("AN:02", "BG:03", "XX:04"), own.map { it.device.address })
        assertTrue(own.all { it.additions == listOf(ScannedAdd.BATTERY) })

        val both = batteryScanRows(rows, BatteryExit.DEVICE_IS_BOTH)
        assertEquals(listOf("BG:03", "XX:04"), both.map { it.device.address })
        assertTrue(both.all { it.additions == listOf(ScannedAdd.WHEEL) })

        assertEquals(emptyList(), batteryScanRows(rows, BatteryExit.FROM_CONTROLLER))
    }

    @Test
    fun `fromControllerNeedsNoScan`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var scanStarts = 0
        val c = component(
            initialDraft = VehicleDraft().addController(ControllerType.VESC, "VE:01", "VESC"),
            scan = kotlinx.coroutines.flow.flow {
                scanStarts++
                emit(antScan)
            }
        )
        (active(c) as SetupWizardComponent.Child.WhatAreWeBuilding).component.onNext()
        (active(c) as SetupWizardComponent.Child.Controllers).component.onNext()
        advanceUntilIdle()
        val startsBefore = scanStarts
        val battery = (active(c) as SetupWizardComponent.Child.Battery).component

        battery.onChooseExit(BatteryExit.FROM_CONTROLLER)
        advanceUntilIdle()

        assertEquals(BatteryExit.FROM_CONTROLLER, c.state.value.batteryExit)
        assertTrue(c.state.value.draft.toControllers().single().providesDerivedBattery)
        assertEquals(startsBefore, scanStarts, "the controller's own battery needs no search")
        assertFalse(c.state.value.scanning)
        assertEquals(emptyList(), battery.scanRows)
        assertIs<SetupWizardComponent.Child.Battery>(active(c), "choosing is not advancing")

        // Switching to a searching exit must give the derived pack back: it would duplicate the BMS.
        battery.onChooseExit(BatteryExit.OWN_BMS)
        battery.onAddScannedDevice(antScan, ScannedAdd.BATTERY)
        assertFalse(c.state.value.draft.toControllers().single().providesDerivedBattery)
    }

    @Test
    fun `skipOpensTheEditorHubWithTheSameDraft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = mutableListOf<Vehicle>()
        val skipped = mutableListOf<WizardHandoff>()
        val seed = VehicleDraft().addController(ControllerType.VESC, "VE:01", "VESC")
            .addPack(BmsType.ANT_BMS, "AN:02", "ANT")
        val c = component(saved = saved, skipped = skipped, initialDraft = seed, initialName = "Bike")

        (active(c) as SetupWizardComponent.Child.WhatAreWeBuilding).component.onSkip()

        val handoff = skipped.single()
        assertEquals(seed, handoff.draft, "the editor gets the very same draft")
        assertEquals("Bike", handoff.name)
        assertEquals("generic", handoff.iconKey, "skip means a custom vehicle")
        assertEquals(emptyList(), saved, "nothing is written by leaving the wizard")
        assertIs<SetupWizardComponent.Child.WhatAreWeBuilding>(active(c), "the wizard itself did not walk on")
    }

    @Test
    fun `skip replaces the wizard with the editor instead of stacking them`() {
        val stack = listOf<Config>(Config.Ride, Config.SetupWizard(), Config.Settings)
        assertEquals(
            listOf<Config>(Config.Ride, Config.Settings, Config.VehicleEdit(vehicleId = null)),
            stackAfterWizardSkip(stack)
        )
    }

    @Test
    fun `the review step owns the name and the icon`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val saved = mutableListOf<Vehicle>()
        val c = component(
            saved = saved,
            initialDraft = VehicleDraft().addController(ControllerType.VESC, "VE:01", "VESC")
        )
        (active(c) as SetupWizardComponent.Child.WhatAreWeBuilding).component.onNext()
        (active(c) as SetupWizardComponent.Child.Controllers).component.onNext()
        (active(c) as SetupWizardComponent.Child.Battery).component.onNoBattery()
        val review = (active(c) as SetupWizardComponent.Child.Review).component

        assertFalse(c.state.value.canSave, "no name, no Save")
        review.onNameChanged("Scooter")
        review.onIconChanged("scooter")
        assertTrue(c.state.value.canSave)
        review.onSave()
        advanceUntilIdle()

        assertEquals("scooter", saved.single().iconKey)
        assertEquals("Scooter", saved.single().name)
    }

    @Test
    fun `the controller list is warmest first`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val far = device("FA:01", "Far", controllerType = ControllerType.VESC, rssi = -90)
        val near = device("NE:02", "Near", controllerType = ControllerType.VESC, rssi = -40)
        val mid = device("MI:03", "Mid", controllerType = ControllerType.VESC, rssi = -60)
        val c = component(scan = flowOf(far, near, mid))
        (active(c) as SetupWizardComponent.Child.WhatAreWeBuilding).component.onNext()
        advanceUntilIdle()

        val rows = (active(c) as SetupWizardComponent.Child.Controllers).component.scanRows
        assertEquals(listOf("NE:02", "MI:03", "FA:01"), rows.map { it.device.address })
    }

    @Test
    fun `the CAN block appears only once the link is a live gateway candidate`() {
        val draft = VehicleDraft().addController(ControllerType.VESC, "VE:01", "VESC")
        val quiet = stateOf(draft)
        assertFalse(quiet.showCanBlock, "an ordinary controller is never asked about a bus")
        assertTrue(quiet.copy(canScanTarget = "VE:01").showCanBlock)
        assertTrue(quiet.copy(canScan = CanScanState.Found("VE:01", listOf(21))).showCanBlock)
    }

    private fun stateOf(draft: VehicleDraft): SetupWizardComponent.State {
        val values = SetupWizardComponent.EditableValues(
            VehicleArchetype.CUSTOM, "", "generic", SecondaryGauge.DUTY, PackTopology.PARALLEL,
            draft.persistableValues()
        )
        return SetupWizardComponent.State(
            archetype = VehicleArchetype.CUSTOM,
            name = "",
            iconKey = "generic",
            secondaryGauge = SecondaryGauge.DUTY,
            draft = draft,
            issues = validate(draft),
            savedValues = values
        )
    }
}
