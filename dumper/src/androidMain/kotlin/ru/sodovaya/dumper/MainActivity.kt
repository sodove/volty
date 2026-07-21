package ru.sodovaya.dumper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // Pre-31 a BLE scan returns nothing without location permission.
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Threaded into the screen so "permissions needed" is never confused with
    // "connected but the wheel is silent". Checked on start and resume, and
    // updated by the request callback.
    private val permissionsGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result -> permissionsGranted.value = result.values.all { it } }

        permissionsGranted.value = hasAllPermissions()

        setContent {
            MaterialTheme {
                DumperScreen(
                    writer = DumpWriter(applicationContext),
                    permissionsGranted = permissionsGranted.value,
                    onRequestPermissions = { launcher.launch(permissions) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Covers grants made in system settings while the app was backgrounded.
        permissionsGranted.value = hasAllPermissions()
    }

    private fun hasAllPermissions(): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
