package ru.sodovaya.volty.permissions

expect class PermissionsChecker {
    /** Returns the list of BLE+notification permissions still missing. Empty list = all granted. */
    fun missingPermissions(): List<String>

    /** Monitoring permissions available on this platform; notifications are optional. */
    fun requiredPermissions(): List<String>
}
