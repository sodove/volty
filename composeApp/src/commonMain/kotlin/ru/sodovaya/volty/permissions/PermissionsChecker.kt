package ru.sodovaya.volty.permissions

expect class PermissionsChecker {
    /** Returns the list of BLE+notification permissions still missing. Empty list = all granted. */
    fun missingPermissions(): List<String>

    /** Required permissions for this app (constant per platform). */
    fun requiredPermissions(): List<String>
}
