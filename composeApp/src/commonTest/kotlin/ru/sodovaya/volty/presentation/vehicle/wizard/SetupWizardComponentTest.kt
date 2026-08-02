package ru.sodovaya.volty.presentation.vehicle.wizard

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.presentation.picker.ScannedAdd
import ru.sodovaya.volty.presentation.root.Config
import ru.sodovaya.volty.presentation.root.CreateVehicleEntry
import ru.sodovaya.volty.presentation.root.RootComponent
import ru.sodovaya.volty.presentation.root.configForCreateVehicle
import ru.sodovaya.volty.presentation.root.guardComposerDestruction
import ru.sodovaya.volty.presentation.vehicle.DerivedBatteryChoice
import ru.sodovaya.volty.presentation.vehicle.VehicleDraft
import ru.sodovaya.volty.presentation.vehicle.addController
import ru.sodovaya.volty.presentation.vehicle.updateController
import ru.sodovaya.volty.presentation.vehicle.validate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class SetupWizardComponentTest {

    private val createdAt = Instant.parse("2026-08-02T10:00:00Z")

    private fun device(
        address: String,
        name: String,
        bmsType: BmsType? = null,
        controllerType: ControllerType? = null
    ) = DiscoveredDevice(
        address = address,
        name = name,
        rssi = -54,
        bmsType = bmsType,
        controllerType = controllerType
    )

    private fun component(
        saved: MutableList<Vehicle> = mutableListOf(),
        scan: Flow<DiscoveredDevice> = emptyFlow(),
        initialDraft: VehicleDraft = VehicleDraft(),
        initialName: String = "",
        persist: suspend (Vehicle) -> Unit = { saved += it },
        cancelled: () -> Unit = {}
    ) = DefaultSetupWizardComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        initialDraft = initialDraft,
        initialName = initialName,
        scanAll = { scan },
        saveVehicle = persist,
        newVehicleId = { "v-new" },
        now = { createdAt },
        onCancelled = cancelled,
        onShowVehicleList = {},
        onConnected = {}
    )

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
        what.onSkip()
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
    fun `both scan stages expose every addition as an actionable device row`() = runTest {
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
        assertEquals(ScannedAdd.entries, controllers.scanRows.single().additions)

        controllers.onNoController()
        val batteries =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        assertEquals(listOf(wheel), batteries.scanRows.map { it.device })
        assertEquals(ScannedAdd.entries, batteries.scanRows.single().additions)

        val row = batteries.scanRows.single()
        batteries.onAddScannedDevice(
            row.device,
            row.additions.single { it == ScannedAdd.WHEEL }
        )
        assertEquals(listOf("BG:01"), c.state.value.draft.controllers.map { it.address })
        assertEquals(listOf("BG:01"), c.state.value.draft.packs.map { it.address })
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
        controllers.onNameChanged("Bike")
        controllers.onAddScannedDevice(c.state.value.scannedDevices[0], ScannedAdd.CONTROLLER)
        controllers.onNext()
        val batteries = (c.stack.value.active.instance as SetupWizardComponent.Child.Battery).component
        batteries.onAddScannedDevice(c.state.value.scannedDevices[1], ScannedAdd.BATTERY)
        batteries.onNext()
        assertEquals(emptyList(), saved)

        (c.stack.value.active.instance as SetupWizardComponent.Child.Review).component.onSave()
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
        controllers.onNameChanged("Bike A")
        controllers.onAddScannedDevice(
            device("VE:01", "VESC", controllerType = ControllerType.VESC),
            ScannedAdd.CONTROLLER
        )
        controllers.onNext()
        (c.stack.value.active.instance as SetupWizardComponent.Child.Battery)
            .component.onNoBattery()
        val review =
            (c.stack.value.active.instance as SetupWizardComponent.Child.Review).component

        review.onSave()
        runCurrent()
        assertTrue(saveStarted.isCompleted, "the fake persistence boundary must be suspended")
        assertTrue(c.state.value.saving)
        assertFalse(c.state.value.navigationEnabled)

        review.onBack()
        controllers.onNameChanged("Bike B")
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
}
