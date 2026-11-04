package ru.sodovaya.volty.presentation.root

import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionNavigationTest {
    @Test fun notificationDenialDoesNotBlockStartupOrDeviceDiscovery() {
        val missing = listOf("android.permission.POST_NOTIFICATIONS")
        for (destination in listOf(Config.Loading, Config.Scanning, Config.Picker("cold"), Config.AutoConnect("bike"))) {
            assertEquals(destination, permissionGatedConfig(destination, missing))
        }
    }

    @Test fun missingBluetoothGatesStartupAndEveryDiscoveryDestination() {
        for (missing in listOf(listOf("android.permission.BLUETOOTH_CONNECT"), listOf("android.permission.ACCESS_FINE_LOCATION"))) {
            for (destination in listOf(Config.Loading, Config.Scanning, Config.Picker("cold"), Config.Picker("guest"), Config.Picker("add"), Config.AutoConnect("bike"), Config.SetupWizard())) {
                assertEquals(Config.Permissions, permissionGatedConfig(destination, missing))
            }
            assertEquals(Config.Ride, permissionGatedConfig(Config.Ride, missing))
            assertEquals(Config.Settings, permissionGatedConfig(Config.Settings, missing))
        }
    }
}
