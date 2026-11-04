package ru.sodovaya.volty.presentation.permissions

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.model.DemoProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionsGateComponentTest {
    private val scan = "android.permission.BLUETOOTH_SCAN"
    private val connect = "android.permission.BLUETOOTH_CONNECT"
    private val notifications = "android.permission.POST_NOTIFICATIONS"
    private val location = "android.permission.ACCESS_FINE_LOCATION"

    private class Fixture(val required: List<String>, missing: List<String> = required) {
        var missing = missing
        var continued = 0
        val demos = mutableListOf<DemoProfile>()
        var demoResult: Result<Unit> = Result.success(Unit)
        val lifecycle = LifecycleRegistry()
        val component = DefaultPermissionsGateComponent(
            componentContext = DefaultComponentContext(lifecycle),
            requiredPermissions = required,
            missingPermissions = { this.missing },
            onContinueRequested = { continued++ },
            startDemo = { profile -> demos += profile; demoResult },
        )
        fun close() = lifecycle.destroy()
    }

    @Test fun bluetoothRequestContainsOnlyMissingBluetoothPermissions() {
        val f = Fixture(listOf(scan, connect, notifications), listOf(connect, notifications))
        try {
            assertEquals(PermissionsGateComponent.Step.BLUETOOTH, f.component.state.value.step)
            assertEquals(listOf(connect), f.component.onRequestPermissions())
            assertEquals(0, f.continued)
        } finally { f.close() }
    }

    @Test fun partialBluetoothGrantCannotAdvanceToNotificationsOrContinue() {
        val f = Fixture(listOf(scan, connect, notifications))
        try {
            f.component.onRequestPermissions()
            f.missing = listOf(connect, notifications)
            f.component.onPermissionResult(mapOf(scan to true, connect to false))
            assertEquals(PermissionsGateComponent.Step.BLUETOOTH, f.component.state.value.step)
            assertTrue(f.component.state.value.denied)
            assertEquals(listOf(connect), f.component.onRequestPermissions())
            assertEquals(0, f.continued)
        } finally { f.close() }
    }

    @Test fun bluetoothGrantShowsNotificationsWithoutIssuingAnotherRequest() {
        val f = Fixture(listOf(scan, connect, notifications))
        try {
            assertEquals(listOf(scan, connect), f.component.onRequestPermissions())
            f.missing = listOf(notifications)
            f.component.onPermissionResult(mapOf(scan to true, connect to true))
            assertEquals(PermissionsGateComponent.Step.NOTIFICATIONS, f.component.state.value.step)
            assertEquals(0, f.continued)
            assertEquals(listOf(notifications), f.component.onRequestPermissions())
        } finally { f.close() }
    }

    @Test fun notificationDenialContinuesExactlyOnceDespiteLaterRefreshAndCallbacks() {
        val f = Fixture(listOf(scan, connect, notifications), listOf(notifications))
        try {
            assertEquals(listOf(notifications), f.component.onRequestPermissions())
            f.component.onPermissionResult(mapOf(notifications to false))
            f.component.onRefreshPermissions()
            f.component.onPermissionResult(mapOf(notifications to false))
            f.component.onSkipNotifications()
            assertEquals(1, f.continued)
        } finally { f.close() }
    }

    @Test fun notificationsMayBeSkippedButBluetoothMayNot() {
        val f = Fixture(listOf(scan, connect, notifications))
        try {
            f.component.onSkipNotifications()
            assertEquals(0, f.continued)
            f.missing = listOf(notifications)
            f.component.onRefreshPermissions()
            f.component.onSkipNotifications()
            assertEquals(1, f.continued)
        } finally { f.close() }
    }

    @Test fun resumeWhileBluetoothDialogIsReturningWaitsForItsResultBeforeChangingStep() {
        val f = Fixture(listOf(scan, connect, notifications))
        try {
            assertEquals(listOf(scan, connect), f.component.onRequestPermissions())
            f.missing = listOf(notifications)
            f.component.onRefreshPermissions()
            assertEquals(PermissionsGateComponent.Step.BLUETOOTH, f.component.state.value.step)
            f.component.onPermissionResult(mapOf(scan to true, connect to true))
            assertEquals(PermissionsGateComponent.Step.NOTIFICATIONS, f.component.state.value.step)
            assertEquals(listOf(notifications), f.component.onRequestPermissions())
        } finally { f.close() }
    }

    @Test fun permanentDenialOffersSettingsAndReturningRechecksActualGrants() {
        val f = Fixture(listOf(scan, connect))
        try {
            f.component.onRequestPermissions()
            f.component.onPermissionResult(mapOf(scan to false, connect to false), permanentlyDenied = true)
            assertTrue(f.component.state.value.openSettings)
            f.component.onRefreshPermissions()
            assertTrue(f.component.state.value.openSettings)
            assertEquals(0, f.continued)
            f.missing = emptyList()
            f.component.onRefreshPermissions()
            f.component.onRefreshPermissions()
            assertEquals(1, f.continued)
        } finally { f.close() }
    }

    @Test fun legacyLocationIsTheBluetoothStepAndNeedsNoNotificationStep() {
        val f = Fixture(listOf(location))
        try {
            assertTrue(f.component.state.value.usesLegacyLocation)
            assertFalse(f.component.state.value.hasNotificationStep)
            assertEquals(listOf(location), f.component.onRequestPermissions())
            f.missing = emptyList()
            f.component.onPermissionResult(mapOf(location to true))
            assertEquals(1, f.continued)
        } finally { f.close() }
    }

    @Test fun bothDemoProfilesStartWithoutGrantingOrRequestingPermissions() = runTest {
        for (profile in listOf(DemoProfile.SCOOTER, DemoProfile.WHEEL)) {
            val f = Fixture(listOf(scan, connect, notifications))
            try {
                f.component.onShowDemo()
                assertTrue(f.component.state.value.showDemoPicker)
                f.component.onTryDemo(profile)
                assertEquals(listOf(profile), f.demos)
                assertEquals(0, f.continued)
                assertEquals(listOf(scan, connect, notifications), f.missing)
            } finally { f.close() }
        }
    }

    @Test fun failedDemoStaysInChooserAndCanBeRetried() = runTest {
        val f = Fixture(listOf(scan, connect))
        try {
            f.demoResult = Result.failure(IllegalStateException("simulator unavailable"))
            f.component.onShowDemo()
            f.component.onTryDemo(DemoProfile.WHEEL)
            assertTrue(f.component.state.value.demoFailed)
            assertTrue(f.component.state.value.showDemoPicker)
            assertFalse(f.component.state.value.demoStarting)
            assertEquals(0, f.continued)
            f.demoResult = Result.success(Unit)
            f.component.onTryDemo(DemoProfile.WHEEL)
            assertEquals(listOf(DemoProfile.WHEEL, DemoProfile.WHEEL), f.demos)
            assertFalse(f.component.state.value.demoFailed)
        } finally { f.close() }
    }
}
