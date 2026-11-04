package ru.sodovaya.volty.presentation.root

import ru.sodovaya.volty.presentation.permissions.missingBluetoothPermissions

/** Demo and offline pages remain accessible; discovery/connect always needs a Bluetooth grant. */
internal fun permissionGatedConfig(destination: Config, missingPermissions: List<String>): Config {
    val needsBluetooth = destination is Config.Loading || destination is Config.Scanning ||
        destination is Config.Picker || destination is Config.AutoConnect || destination is Config.SetupWizard
    return if (needsBluetooth && missingBluetoothPermissions(missingPermissions).isNotEmpty())
        Config.Permissions else destination
}
